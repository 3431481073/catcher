"""App 使用时长：原始事件 → 段（配对）+ 落库 + 未闭合段的暂存。

🔴 **配对全在这里做，手机端不做任何计算** —— 手机只上报 `UsageStatsManager` 吐的
原始事件流，配对 / 去重 / 覆盖 / 续段全在后端这个文件里。

分层（对应 CLAUDE.md §三「核心做成接口，UI 只是壳」）：
  `pair()`        —— 纯函数，无 IO、无 DB，进出都是普通 dict。单测的主力。
  `ingest()`      —— 一次上报的完整处理：配对 → 落库 → 更新未闭合段。一个事务里做完。
  `list_segments()` / `summarize()` —— 读。HTTP 形状在 `routes/usage.py`，这里只出数据。
"""
from __future__ import annotations

import logging
import uuid
from datetime import datetime, timezone

from .db import connect

logger = logging.getLogger(__name__)

# ── 事件常量 ──────────────────────────────────────────────────
# 值取自 Android `UsageEvents.Event`。**这些整数是 Android 硬编码的**，不随版本变
# （API 29 只是把 MOVE_TO_FOREGROUND/BACKGROUND 改名叫 ACTIVITY_RESUMED/PAUSED，值仍是 1/2）。
# 只认这 6 个；别的（如 ACTIVITY_STOPPED=23、CONFIGURATION_CHANGE=5）一律计入 unknown 丢掉。
ACTIVITY_RESUMED = 1
ACTIVITY_PAUSED = 2
SCREEN_INTERACTIVE = 15
SCREEN_NON_INTERACTIVE = 16
KEYGUARD_SHOWN = 17
KEYGUARD_HIDDEN = 18

KNOWN_TYPES = frozenset({
    ACTIVITY_RESUMED, ACTIVITY_PAUSED,
    SCREEN_INTERACTIVE, SCREEN_NON_INTERACTIVE,
    KEYGUARD_SHOWN, KEYGUARD_HIDDEN,
})

# 直接结束当前段的两个：**灭屏 / 锁屏**。
# 亮屏（SCREEN_INTERACTIVE）和解锁（KEYGUARD_HIDDEN）**不结束段** ——
# 熄屏再点亮回到的往往还是原来那个 App，这时候断段会把一段真实使用切成两截。
# 正确的下一个边界是紧接着的那条 ACTIVITY_RESUMED。
_CLOSERS = frozenset({SCREEN_NON_INTERACTIVE, KEYGUARD_SHOWN})

# ── 三条兜底参数（2026-10-11 加，都是被真机数据打出来的）────────
#
# 🔴 **B：同包重复 `RESUMED` 的"这算不算没离开前台"窗口。**
#    原来无条件忽略，理由是"Android 只在 A 真的没离开前台时才这么发" ——
#    **那条理由只在同一批里成立。** 跨批时 `usage_pending` 是张**持久表**：
#    昨天开的段今天还在，今天新来的 `RESUMED(同包)` 被无条件忽略 ⇒ **起点永远不推进**。
#    实测就是这样卡死的：`usage_pending` 里的抖音段起点停在 10-10T03:20:18，
#    **34 小时没动**，等它闭合就是一条 34 小时的怪物段。
RESUMED_REOPEN_WINDOW_MS = 60_000

# 🔴 **C：单段时长上限。** 超过的段**整段丢弃**（不写库），由 `ingest` 计数上报。
#    它是防"上游已经乱了"的最后一道 —— 光靠 B 修好"起点不推进"，闭合那一刻
#    仍会把那个 34 小时的段写进库。上限取 6 小时：实测真实数据里最长的是
#    3.0 小时（快手，10-06），留一倍余量不会误伤。
MAX_SEGMENT_SECONDS = 6 * 60 * 60


def _iso(ms: int) -> str:
    """epoch 毫秒 → ISO8601 UTC（微秒 + Z）。与 `models._now()` 同格式，可直接字符串比较。"""
    return datetime.fromtimestamp(ms / 1000, timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ")


# ── 纯函数：配对 ──────────────────────────────────────────────

def pair(events, open_segment=None):
    """把原始事件流配对成使用段。

    :param events: `[{timestamp_ms, event_type, package_name}]`，**顺序任意**（进来先按时间排）
    :param open_segment: 上次上报留下来、**还没闭合**的段 `{package_name, start_time_ms}`；没有就 None
    :return: `(segments, new_open)`
        - `segments`: `[{package_name, start_time_ms, end_time_ms}]`（时间跨度不为负）
        - `new_open`:  本次跑完仍未闭合的段，或 None

    规则（错一条都会让时长偏差很多）：
      · `ACTIVITY_RESUMED(pkg)` → 关掉当前段（end=这条的时间），开新段 `(pkg, 这条的时间)`
      · `ACTIVITY_PAUSED(pkg)`  → **仅当**当前段的包名就是 pkg 才关；包名不符或本来就没开段 → 忽略
      · `SCREEN_NON_INTERACTIVE` / `KEYGUARD_SHOWN` → 关掉当前段
      · `SCREEN_INTERACTIVE` / `KEYGUARD_HIDDEN`    → 什么也不做（见 `_CLOSERS` 上面的说明）
      · 其它类型的 `event_type` → 忽略（由调用方计入 unknown）

    两个规格没写、由这里定死的边界（都属于「不这么做会算错」的那类）：
      · **同一个包名重复 RESUMED**（`open=(A,1000)` 又来一条 `RESUMED(A,2000)`）→ **忽略**。
        Android 只有在 A 真的没有离开前台时才会这么发；忽略才能保住段的起点，
        重开会让起点被推后 1 秒。
      · **包名为空的 ACTIVITY_***：跳过。屏幕/锁屏事件本来就没包名，
        而没包名的 ACTIVITY 事件配不出段（会静默算成 0 秒的段）。
    """
    ordered = sorted(events, key=lambda e: int(e["timestamp_ms"]))

    segments: list[dict] = []
    seg = None
    if open_segment:
        seg = (open_segment["package_name"], int(open_segment["start_time_ms"]))

    def close(at_ms: int) -> None:
        nonlocal seg
        if seg is None:
            return
        start = seg[1]
        end = max(at_ms, start)   # 时间跨度不为负：跨批时起点可能晚于这条事件（乱序上报）
        segments.append({"package_name": seg[0], "start_time_ms": start, "end_time_ms": end})
        seg = None

    for e in ordered:
        t = int(e["timestamp_ms"])
        et = int(e["event_type"])
        pkg = e.get("package_name")

        if et == ACTIVITY_RESUMED:
            if not pkg:
                continue
            if seg is not None:
                if seg[0] == pkg and (t - seg[1]) < RESUMED_REOPEN_WINDOW_MS:
                    # 同包、且**距开段不到 60 秒** ⇒ 真的是"没离开前台"，忽略以保住起点
                    continue
                # 同包但隔了 ≥60 秒 = **重新打开**；异包 = 切走。两种都要先关旧的
                # （见 RESUMED_REOPEN_WINDOW_MS 上面那段：旧写法会卡死起点）
                close(t)
            seg = (pkg, t)
        elif et == ACTIVITY_PAUSED:
            if pkg and seg is not None and seg[0] == pkg:
                close(t)
        elif et in _CLOSERS:
            close(t)
        # SCREEN_INTERACTIVE / KEYGUARD_HIDDEN → 不结束段；其它类型 → 忽略

    new_open = {"package_name": seg[0], "start_time_ms": seg[1]} if seg else None
    return segments, new_open


# ── 一次上报的完整处理 ────────────────────────────────────────

def ingest(device_id: str, events, labels=None) -> dict:
    """配对 + 落库 + 更新未闭合段。**一个事务里做完**。

    :param labels: `{包名: 可读名}`，手机端解析 App 名后一起带上。
                   写进 `usage_records.app_label`；覆盖时用 COALESCE，空值不会把已有的名字抹掉。
    :return: `{"accepted": 落库的段数, "duplicates": 命中唯一键被覆盖的段数,
              "unknown": 认不出的原始事件数, "oversized_skipped": 超时长上限被丢弃的段数,
              "pending_stale_rejected": 想把 `usage_pending` 拖回去、被挡下的批次数}`
              —— 后两个是 2026-10-11 加的：它们是"上游数据乱了"的唯一可见信号。
    """
    labels = labels or {}
    unknown = sum(1 for e in events if int(e["event_type"]) not in KNOWN_TYPES)

    with connect() as conn:
        open_row = conn.execute(
            "SELECT package_name, start_time FROM usage_pending WHERE device_id = ?",
            (device_id,),
        ).fetchone()
        open_segment = None
        if open_row:
            open_segment = {
                "package_name": open_row["package_name"],
                "start_time_ms": _parse_iso_ms(open_row["start_time"]),
            }

        segments, new_open = pair(events, open_segment)

        accepted = duplicates = oversized = stale_rejected = 0
        now_iso = _iso(_now_ms())
        for s in segments:
            start_iso = _iso(s["start_time_ms"])
            end_iso = _iso(s["end_time_ms"])
            duration = max(0, (s["end_time_ms"] - s["start_time_ms"]) // 1000)

            # 🔴 **C：超过上限的段整段丢弃。** 放在最前面 —— 连 `duplicates` 都不数它，
            #    因为这一条根本不打算入库。`accepted` 同样不加：它没被接受。
            #    这是最后一道闸：上游数据一旦被污染（比如那个 34 小时的起点），
            #    修好起点之后收敛的那一刻仍会想写一个怪物段出来。
            if duration > MAX_SEGMENT_SECONDS:
                oversized += 1
                logger.warning(
                    "usage 段超上限被丢弃：%s %s → %s（%d 秒 > %d 秒），device=%s",
                    s["package_name"], start_iso, end_iso, duration,
                    MAX_SEGMENT_SECONDS, device_id,
                )
                continue

            accepted += 1

            # 先查一次是否存在 —— 只用它来**数 duplicates**（`ON CONFLICT DO UPDATE`
            # 的 rowcount 无论插入还是更新都是 1，分不出来）。真正的写还是走 upsert。
            exists = conn.execute(
                "SELECT 1 FROM usage_records"
                " WHERE device_id = ? AND package_name = ? AND start_time = ?",
                (device_id, s["package_name"], start_iso),
            ).fetchone()
            if exists:
                duplicates += 1

            # 🔴 覆盖语义：**新的 end_time/duration 无条件盖掉旧的**。
            # 这是为「上次扫描被截断（窗口边界把段切了）→ 这次拿到完整的」准备的：
            # 两次的 start_time 相同（第一次留下 open 段、第二次从它续上）⇒ 命中唯一键。
            # app_label 用 COALESCE：覆盖时若本批没带名字，保留库里已有的那个。
            conn.execute(
                "INSERT INTO usage_records"
                " (id, device_id, package_name, app_label, start_time, end_time,"
                "  duration_seconds, created_at, updated_at)"
                " VALUES (?,?,?,?,?,?,?,?,?)"
                " ON CONFLICT(device_id, package_name, start_time) DO UPDATE SET"
                "  end_time = excluded.end_time,"
                "  duration_seconds = excluded.duration_seconds,"
                "  app_label = COALESCE(excluded.app_label, usage_records.app_label),"
                "  updated_at = excluded.updated_at",
                (
                    "usage_" + uuid.uuid4().hex, device_id, s["package_name"],
                    labels.get(s["package_name"]), start_iso, end_iso, duration,
                    now_iso, now_iso,
                ),
            )

        # ── 未闭合段：**只许向前推进**（2026-10-11 加，就是 A）──────────────
        #
        # 🔴 为什么必须有这一条：`usage_pending` 是**每设备一行**的全局游标，而"这批算出来
        #    的开段起点"**不一定比库里那条新** —— 手机侧重发旧事件时（实测：整个 7 天被
        #    重新上传了一遍，6372/6773 = 94% 的行 `updated_at != created_at`），
        #    批次顺序**不再单调递增**（`takePending` 按时间升序取，但重插的旧事件会插到队首），
        #    于是一批纯旧数据就能把游标**拖回**几个小时甚至几天前。
        #    实测就是这么被拖到 `10-10T03:20:18` 然后卡住 34 小时的。
        #
        # ⇒ 旧批次的结果**照样写 `usage_records`**（幂等覆盖，不会重复计数），
        #   但**没有资格拖回这个游标**。游标只认向前。
        if new_open:
            new_ms = new_open["start_time_ms"]
            old_ms = open_segment["start_time_ms"] if open_segment else None
            if old_ms is not None and new_ms < old_ms:
                # 这批算出来的开段比库里那条**更旧** ⇒ 是重发的旧数据，不许拖回游标。
                # 只把 `updated_at` 推一下（留个"有批次来过"的痕），起点/包名一律不动。
                conn.execute(
                    "UPDATE usage_pending SET updated_at = ? WHERE device_id = ?",
                    (_iso(_now_ms()), device_id),
                )
                stale_rejected += 1
            else:
                conn.execute(
                    "INSERT INTO usage_pending (device_id, package_name, start_time, updated_at)"
                    " VALUES (?,?,?,?)"
                    " ON CONFLICT(device_id) DO UPDATE SET"
                    "  package_name = excluded.package_name,"
                    "  start_time = excluded.start_time,"
                    "  updated_at = excluded.updated_at",
                    (device_id, new_open["package_name"], _iso(new_ms), _iso(_now_ms())),
                )
        else:
            conn.execute("DELETE FROM usage_pending WHERE device_id = ?", (device_id,))

    return {
        "accepted": accepted,
        "duplicates": duplicates,
        "unknown": unknown,
        # 🔴 这两个是本轮加的**可观测字段** —— 没有它们，"上游又乱了"和"一切正常"
        #    在响应里长得一模一样（本项目最忌讳的那种静默）。
        "oversized_skipped": oversized,    # 超 6 小时被丢弃的段数
        "pending_stale_rejected": stale_rejected,  # 想把游标拖回去、被挡下的批次数
    }


# ── 读 ────────────────────────────────────────────────────────

def list_segments(start_utc=None, end_utc=None, package_name=None,
                  device_id=None, limit=5000) -> list[dict]:
    """按时间区间（`start_time ∈ [start_utc, end_utc)`）列段。时间是 ISO8601 UTC 字符串。

    ISO 串是定宽的，所以字符串比较**等价于**时间比较，不需要解析。
    """
    where, params = [], []
    if start_utc:
        where.append("start_time >= ?")
        params.append(start_utc)
    if end_utc:
        where.append("start_time < ?")
        params.append(end_utc)
    if package_name:
        where.append("package_name = ?")
        params.append(package_name)
    if device_id:
        where.append("device_id = ?")
        params.append(device_id)
    sql = "SELECT * FROM usage_records"
    if where:
        sql += " WHERE " + " AND ".join(where)
    sql += " ORDER BY start_time ASC LIMIT ?"
    params.append(limit)

    with connect() as conn:
        rows = conn.execute(sql, params).fetchall()
    return [_to_dto(r) for r in rows]


def summarize(start_utc=None, end_utc=None, device_id=None) -> list[dict]:
    """按包名汇总总时长（降序）。字段名与 `list_segments` 的 `records` 不同，见 `routes/usage.py`。"""
    where, params = [], []
    if start_utc:
        where.append("start_time >= ?")
        params.append(start_utc)
    if end_utc:
        where.append("start_time < ?")
        params.append(end_utc)
    if device_id:
        where.append("device_id = ?")
        params.append(device_id)
    sql = (
        "SELECT package_name, MAX(app_label) AS app_label,"
        " SUM(duration_seconds) AS total_seconds, COUNT(*) AS segments,"
        " MIN(start_time) AS first_start, MAX(end_time) AS last_end"
        " FROM usage_records"
    )
    if where:
        sql += " WHERE " + " AND ".join(where)
    sql += " GROUP BY package_name ORDER BY total_seconds DESC"

    with connect() as conn:
        rows = conn.execute(sql, params).fetchall()
    return [dict(r) for r in rows]


def _to_dto(row) -> dict:
    """一行 → 给外部消费的 DTO。

    🔴 **2026-10-12 补了 `created_at` / `updated_at`。** 原来只给 7 个键，
    于是"接口返回的是不是全部字段"这个问题**两个答案**（库里 9 列、接口 7 个），
    写文档时当场撞上（`docs/usage-fields.md` 里那条标注就是它）。
    **接口和表保持一致** —— 消费方不必为了两个字段再直读库。

    ⚠️ **是加键，不是改键** —— 老消费者不会因为多两个键而出错。
    """
    return {
        "id": row["id"],
        "device_id": row["device_id"],
        "package_name": row["package_name"],
        "app_label": row["app_label"],
        "start_time": row["start_time"],
        "end_time": row["end_time"],
        "duration_seconds": row["duration_seconds"],
        # 落库时刻 / 最后一次改写时刻。**都不是使用时间。**
        # `updated_at` 会因重复上报的原地覆盖而变（同一段重发时），
        # 所以别再拿它当"要不要重新拉"的判据 —— 那要看 start_time/end_time。
        "created_at": row["created_at"],
        "updated_at": row["updated_at"],
    }


# ── 小工具 ────────────────────────────────────────────────────

def _now_ms() -> int:
    return int(datetime.now(timezone.utc).timestamp() * 1000)


def _parse_iso_ms(iso: str) -> int:
    """ISO8601（带 Z）→ epoch 毫秒。只用于读回我们自己写的 `usage_pending.start_time`。"""
    return int(datetime.strptime(iso, "%Y-%m-%dT%H:%M:%S.%fZ")
               .replace(tzinfo=timezone.utc).timestamp() * 1000)
