"""App 使用时长接口：上报 / 查询 / 汇总 / 触发采集。

这一层**只负责 HTTP 形状**：配对、去重、跨批全在 `app/usage.py`（纯逻辑，可单测）。
和 `routes/lattice.py` 一样，router 上挂 `verify_token`（不是 WS，异常处理器正常工作）。
"""
from datetime import datetime, timedelta, timezone
import logging

from fastapi import APIRouter, Depends, Query

from .. import usage, ws_hub
from ..auth import verify_token
from ..errors import AppError, ok
from ..schemas import UsageReportRequest

logger = logging.getLogger("usage")

router = APIRouter(dependencies=[Depends(verify_token)])


# ── 时间参数 ──────────────────────────────────────────────────
# 🔴 一个必须说清楚的口径：库里存的一律是 **UTC**（ISO8601 + Z，与 records 同格式），
#    但「哪一天」是**用户视角的本地一天**。`date=2026-10-11` 指的是
#    **服务器本地时区**的 10-11 的 00:00 到 24:00，换算成 UTC 再去查。
#    这台服务器就是用户自己的 PC（UTC+8），与手机同一时区，所以两者一致。
#    真要显式控制，再给 `from`/`to` 传带时区的 ISO8601 —— 那才是无歧义的写法。

def _local_tz():
    # 取当前时刻的本地偏移。中国没有夏令时，固定偏移够用；
    # 万一在有时区的机器上跑，"某一天"的边界会随 DST 差一小时 —— 可接受。
    return datetime.now().astimezone().tzinfo


def _fmt_utc(dt: datetime) -> str:
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ")


def _norm_ts(value: str) -> str:
    """`YYYY-MM-DD` 或 ISO8601（带不带时区都行）→ 库里那种 ISO8601 UTC 串。

    不带时区的按**服务器本地时区**解释。ISO 串定宽，所以之后可以当字符串直接比较。
    """
    raw = value.strip()
    if len(raw) == 10 and raw[4] == "-" and raw[7] == "-":
        d = datetime.strptime(raw, "%Y-%m-%d").date()
        return _fmt_utc(datetime(d.year, d.month, d.day, tzinfo=_local_tz()))
    dt = datetime.fromisoformat(raw.replace("Z", "+00:00"))
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=_local_tz())
    return _fmt_utc(dt)


def _is_date_only(s: str) -> bool:
    s = s.strip()
    return len(s) == 10 and s[4] == "-" and s[7] == "-"


def _day_start_local(date_str: str) -> datetime:
    d = datetime.strptime(date_str.strip(), "%Y-%m-%d").date()
    return datetime(d.year, d.month, d.day, tzinfo=_local_tz())


def _resolve_window(date, from_, to):
    """把 `date` / `from` / `to` 解成一个 `[start, end)` UTC 区间。都没给就是全时段。

    🔴 `to` 只给到日期（`2026-10-12`）时，意思是**那一天整天都算**，
       所以要把边界推到**本地**次日 00:00 —— 不是 UTC 次日 00:00。
       （UTC+8 下这两者差 8 小时，写成 UTC 会把当天 16:00 之后的段全漏掉。）
    """
    if from_ or to:
        start = _norm_ts(from_) if from_ else None
        if not to:
            end = None
        elif _is_date_only(to):
            end = _fmt_utc(_day_start_local(to) + timedelta(days=1))
        else:
            end = _norm_ts(to)
        return start, end
    if date:
        start_dt = _day_start_local(date)
        return _fmt_utc(start_dt), _fmt_utc(start_dt + timedelta(days=1))
    return None, None


# ── 上报 ──────────────────────────────────────────────────────

@router.post("/api/v1/usage")
async def report_usage(req: UsageReportRequest):
    """接收原始事件流 → 配对成段 → 去重写入。

    幂等：同一批重发不会多出段（唯一键 `(device_id, package_name, start_time)`），
    重复的那部分在返回里报成 `duplicates`。

    `usage.ingest` 是同步的（sqlite）—— 和 records 那组一样直接调，不进线程：
    一批最多 2000 条事件、配对是纯内存循环，本地 sqlite 写几条，量级是毫秒。
    """
    if not req.device_id.strip():
        raise AppError("INVALID_PAYLOAD", "device_id 不能为空", 400)
    result = usage.ingest(
        req.device_id.strip(),
        [e.model_dump() for e in req.events],
        req.labels,
    )
    # 🔴 **必须落日志**（2026-10-11 加）。理由：这些数**只在响应体里**，
    #    而手机上没人看响应 —— 于是"去重生效"和"去重根本没跑"在后端**长得一模一样**。
    #    验收第 5 条（重复上报去重）要看的正是 `duplicates` 的实际值，
    #    没有这一行就只能靠 curl 打一次，而真实流量不会经过 curl。
    #    `oversized` / `stale` 两个新计数同理 —— 它们是"上游数据乱了"的唯一可见信号。
    logger.info(
        "usage 上报：accepted=%d duplicates=%d unknown=%d oversized=%d stale=%d",
        result["accepted"], result["duplicates"], result["unknown"],
        result["oversized_skipped"], result["pending_stale_rejected"],
    )
    return ok(result)


# ── 查 / 汇总 ─────────────────────────────────────────────────
# 路由顺序无所谓：`/summary` 是静态段、没有 `/usage/{id}` 这种通配路由，不会互相吞。

@router.get("/api/v1/usage/summary")
async def usage_summary(
    date: str | None = Query(None, description="YYYY-MM-DD，服务器本地时区的一天"),
    package: str | None = Query(None, description="总结时忽略（汇总本来就是按包名分组的）"),
    from_: str | None = Query(None, alias="from"),
    to: str | None = Query(None),
    device_id: str | None = Query(None),
):
    """按 App 汇总总时长，降序。"""
    start, end = _resolve_window(date, from_, to)
    rows = usage.summarize(start, end, device_id)
    return ok({
        "records": rows,
        "total_seconds": sum(r["total_seconds"] or 0 for r in rows),
        "from": start,
        "to": end,
    })


@router.get("/api/v1/usage")
async def list_usage(
    date: str | None = Query(None, description="YYYY-MM-DD，服务器本地时区的一天"),
    package: str | None = Query(None, description="只看某个包名"),
    from_: str | None = Query(None, alias="from", description="ISO8601，带时区则无歧义"),
    to: str | None = Query(None, description="ISO8601"),
    device_id: str | None = Query(None),
    limit: int = Query(5000, ge=1, le=20000),
):
    """按时间区间列段。`date` 与 `from`/`to` 同时给时，**以 `from`/`to` 为准**。"""
    try:
        start, end = _resolve_window(date, from_, to)
    except ValueError:
        raise AppError("INVALID_PAYLOAD", "date 应为 YYYY-MM-DD，from/to 应为 ISO8601", 400)
    records = usage.list_segments(start, end, package, device_id, limit)
    return ok({"records": records, "from": start, "to": end})


# ── 触发手机采集 ──────────────────────────────────────────────

@router.post("/api/v1/usage/trigger")
async def trigger_usage():
    """让手机**立刻**采集一次。

    🔴 走的是那条共用的 WS（见 `ws_hub`），所以它**只在手机开着「远程」Tab 时有效**。
    `delivered` 就是"推到了几条连接上"——**0 表示手机现在没连着**，
    不是错误，但也别把它当成"手机已经在采了"。周期采集（15 分钟）不依赖这条路径。
    """
    delivered = await ws_hub.broadcast("trigger_usage")
    return ok({"delivered": delivered})
