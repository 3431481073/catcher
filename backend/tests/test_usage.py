"""App 使用时长配对层的测试。

分两半：
  · `pair()` —— **纯函数**，不碰 DB。这是核心，规则错一条时长就偏。
  · `ingest()` —— 配对 + 落库 + 未闭合段，跑在 `db_env` 的临时库上。

全程不联网、不依赖真实手机数据（事件是手写的，时间戳是任取的固定值）。
"""
import json

import pytest

from app import config, usage
from app.db import connect

from tests.conftest import TEST_TOKEN

AUTH = {"X-Token": TEST_TOKEN}

RESUMED = usage.ACTIVITY_RESUMED
PAUSED = usage.ACTIVITY_PAUSED
SCREEN_ON = usage.SCREEN_INTERACTIVE
SCREEN_OFF = usage.SCREEN_NON_INTERACTIVE
LOCK = usage.KEYGUARD_SHOWN
UNLOCK = usage.KEYGUARD_HIDDEN

# 任意取的一个固定时刻，只是为了好读（2023-11-14T22:13:20Z）
T = 1_700_000_000_000

DEV = "a" * 16


def ev(ms, event_type, pkg=None):
    return {"timestamp_ms": ms, "event_type": event_type, "package_name": pkg}


def _pending_rows():
    with connect() as conn:
        return [dict(r) for r in conn.execute("SELECT * FROM usage_pending").fetchall()]


# ── pair()：纯函数 ────────────────────────────────────────────

def test_pair_switch_app_closes_previous_segment():
    """换 App：RESUMED(B) 到来的那一刻，A 的段就结束（不是等 PAUSED）。"""
    segs, open_seg = usage.pair([
        ev(T, RESUMED, "com.a"),
        ev(T + 5_000, RESUMED, "com.b"),
        ev(T + 9_000, PAUSED, "com.b"),
    ])
    assert [s["package_name"] for s in segs] == ["com.a", "com.b"]
    assert (segs[0]["start_time_ms"], segs[0]["end_time_ms"]) == (T, T + 5_000)
    assert (segs[1]["start_time_ms"], segs[1]["end_time_ms"]) == (T + 5_000, T + 9_000)
    assert open_seg is None


def test_pair_screen_off_closes_segment():
    segs, open_seg = usage.pair([
        ev(T, RESUMED, "com.a"),
        ev(T + 2_000, SCREEN_OFF),
    ])
    assert (segs[0]["start_time_ms"], segs[0]["end_time_ms"]) == (T, T + 2_000)
    assert open_seg is None


def test_pair_lock_closes_segment():
    segs, _ = usage.pair([ev(T, RESUMED, "com.a"), ev(T + 3_000, LOCK)])
    assert (segs[0]["start_time_ms"], segs[0]["end_time_ms"]) == (T, T + 3_000)


def test_pair_wake_and_unlock_do_not_close_segment():
    """亮屏 / 解锁**不结束段** —— 熄屏再点亮回到的往往还是同一个 App。

    如果这里断段了，下面这条会变成两段（3 秒被切成 1 秒 + 2 秒）。
    """
    segs, open_seg = usage.pair([
        ev(T, RESUMED, "com.a"),
        ev(T + 1_000, SCREEN_ON),
        ev(T + 1_100, UNLOCK),
        ev(T + 3_000, PAUSED, "com.a"),
    ])
    assert len(segs) == 1
    assert (segs[0]["start_time_ms"], segs[0]["end_time_ms"]) == (T, T + 3_000)
    assert open_seg is None


def test_pair_repeated_resumed_same_package_keeps_original_start():
    """同包重复 RESUMED → 忽略。重开会把起点推后，段就短了。"""
    segs, _ = usage.pair([
        ev(T, RESUMED, "com.a"),
        ev(T + 1_000, RESUMED, "com.a"),
        ev(T + 5_000, PAUSED, "com.a"),
    ])
    assert len(segs) == 1
    assert segs[0]["start_time_ms"] == T


def test_pair_paused_for_other_package_is_ignored():
    """A 在前台时来了 B 的 PAUSED（乱序/残留）→ 不能把 A 的段关掉。"""
    segs, open_seg = usage.pair([
        ev(T, RESUMED, "com.a"),
        ev(T + 1_000, PAUSED, "com.b"),
        ev(T + 2_000, SCREEN_OFF),
    ])
    assert len(segs) == 1
    assert segs[0]["end_time_ms"] == T + 2_000
    assert open_seg is None


def test_pair_paused_without_open_segment_is_ignored():
    segs, open_seg = usage.pair([ev(T, PAUSED, "com.a")])
    assert segs == [] and open_seg is None


def test_pair_resumed_without_package_is_skipped():
    """没有包名的 ACTIVITY 事件配不出段（会静默算成 0 秒），直接跳过。"""
    segs, open_seg = usage.pair([ev(T, RESUMED, None), ev(T + 1_000, PAUSED, None)])
    assert segs == [] and open_seg is None


def test_pair_is_order_independent():
    """上报顺序不该影响结果 —— 手机分天拉、跨批拼，顺序本来就不保证。"""
    events = [
        ev(T, RESUMED, "com.a"),
        ev(T + 5_000, RESUMED, "com.b"),
        ev(T + 9_000, PAUSED, "com.b"),
    ]
    forward, _ = usage.pair(events)
    backward, _ = usage.pair(list(reversed(events)))
    assert forward == backward


def test_pair_continues_open_segment_from_previous_batch():
    """跨批续段：上一批留下 open=com.a@T，这一批的 PAUSED 把它闭合成 6 秒。"""
    segs, open_seg = usage.pair(
        [ev(T + 6_000, PAUSED, "com.a")],
        open_segment={"package_name": "com.a", "start_time_ms": T},
    )
    assert len(segs) == 1
    assert (segs[0]["start_time_ms"], segs[0]["end_time_ms"]) == (T, T + 6_000)
    assert open_seg is None


def test_pair_carries_open_segment_when_never_closed():
    """整批都没闭合 → 原样交出去，等下一批。"""
    segs, open_seg = usage.pair([ev(T, RESUMED, "com.a"), ev(T + 1_000, SCREEN_ON)])
    assert segs == []
    assert open_seg == {"package_name": "com.a", "start_time_ms": T}


def test_pair_tolerates_start_after_end():
    """跨批乱序时起点可能晚于这条事件 —— 时间跨度不能变成负的。"""
    segs, _ = usage.pair(
        [ev(T, PAUSED, "com.a")],
        open_segment={"package_name": "com.a", "start_time_ms": T + 5_000},
    )
    assert segs[0]["end_time_ms"] >= segs[0]["start_time_ms"]


# ── ingest()：配对 + 落库 ─────────────────────────────────────

def test_pair_repeated_resumed_after_window_reopens(db_env):
    """同包 RESUMED 隔得久了 = **重新打开**，不是"没离开前台"。

    🔴 这是 v60 那次真机数据的直接产物：`usage_pending` 里一个抖音段的起点被卡在
    `10-10T03:20:18` **34 小时没动**，就是因为老规则无条件忽略同包 RESUMED。
    """
    # 1 秒 = 窗口内 → 忽略，保住起点（老行为，不能退化）
    segs, _ = usage.pair([
        ev(T, RESUMED, "com.a"),
        ev(T + 1_000, RESUMED, "com.a"),
        ev(T + 5_000, PAUSED, "com.a"),
    ])
    assert len(segs) == 1 and segs[0]["start_time_ms"] == T

    # 61 秒 > 窗口 → 关旧的、开新的，**起点必须推进**
    segs, open_seg = usage.pair([
        ev(T, RESUMED, "com.a"),
        ev(T + 61_000, RESUMED, "com.a"),
        ev(T + 70_000, PAUSED, "com.a"),
    ])
    assert [(s["start_time_ms"], s["end_time_ms"]) for s in segs] == [
        (T, T + 61_000), (T + 61_000, T + 70_000)
    ]
    assert open_seg is None


def test_pair_cross_batch_same_package_resumed_advances(db_env):
    """跨批的同包 RESUMED 也要推进起点 —— 那个 34 小时就是这么攒出来的。"""
    stale = {"package_name": "com.a", "start_time_ms": T}
    segs, open_seg = usage.pair([ev(T + 3600_000, RESUMED, "com.a")], open_segment=stale)
    assert len(segs) == 1
    assert segs[0]["end_time_ms"] == T + 3600_000       # 旧段被关掉
    assert open_seg == {"package_name": "com.a", "start_time_ms": T + 3600_000}  # 起点推进了


def test_ingest_skips_oversized_segment(db_env):
    """超过 6 小时的段**整段丢弃**，只计数 —— 它是防"上游已污染"的最后一道闸。"""
    seven_hours = 7 * 60 * 60 * 1000
    r = usage.ingest(DEV, [
        ev(T, RESUMED, "com.a"),
        ev(T + seven_hours, PAUSED, "com.a"),
    ])
    assert r["oversized_skipped"] == 1
    assert r["accepted"] == 0
    assert usage.list_segments() == []                  # 🔴 一条都没写进去


def test_pending_never_moves_backward(db_env):
    """A：重发的旧批次**没有资格**把 `usage_pending` 拖回去。"""
    usage.ingest(DEV, [ev(T, RESUMED, "com.a")])        # 开段 = (a, T)
    assert _pending_rows()[0]["start_time"] == usage._iso(T)

    # 一批**纯旧数据**（起点比库里那条早一小时）—— 实测就是它把游标拖到 34 小时前的
    r = usage.ingest(DEV, [ev(T - 3600_000, RESUMED, "com.b")])
    assert r["pending_stale_rejected"] == 1
    rows = _pending_rows()
    assert len(rows) == 1
    assert rows[0]["package_name"] == "com.a"           # 包名没被改
    assert rows[0]["start_time"] == usage._iso(T)       # 🔴 起点没被拖回去

    # 而**更新的**批次照常推进
    r = usage.ingest(DEV, [ev(T + 7200_000, RESUMED, "com.b")])
    assert r["pending_stale_rejected"] == 0
    assert _pending_rows()[0]["start_time"] == usage._iso(T + 7200_000)


def test_ingest_writes_segments(db_env):
    r = usage.ingest(DEV, [
        ev(T, RESUMED, "com.a"),
        ev(T + 5_000, RESUMED, "com.b"),
        ev(T + 9_000, PAUSED, "com.b"),
    ])
    # 2026-10-11 起返回值多了两个可观测字段（oversized_skipped / pending_stale_rejected），
    # 所以**不再用整字典相等** —— 那样每加一个统计字段都要改一次断言。
    assert (r["accepted"], r["duplicates"], r["unknown"]) == (2, 0, 0)
    assert r["oversized_skipped"] == 0
    assert r["pending_stale_rejected"] == 0

    rows = usage.list_segments()
    assert [x["package_name"] for x in rows] == ["com.a", "com.b"]
    assert [x["duration_seconds"] for x in rows] == [5, 4]
    assert all(x["id"].startswith("usage_") for x in rows)


def test_ingest_same_batch_twice_is_idempotent(db_env):
    """重复上报：第二次全部命中唯一键 → 段数不变，duplicates 等于段数。"""
    events = [ev(T, RESUMED, "com.a"), ev(T + 5_000, RESUMED, "com.b"), ev(T + 9_000, PAUSED, "com.b")]

    first = usage.ingest(DEV, events)
    second = usage.ingest(DEV, events)

    assert first["accepted"] == 2 and first["duplicates"] == 0
    assert second["accepted"] == 2 and second["duplicates"] == 2
    assert len(usage.list_segments()) == 2          # 🔴 没有变多


def test_ingest_overwrites_truncated_segment_with_complete_one(db_env):
    """上次扫描被窗口切短（2 秒）→ 这次拿到完整的（9 秒）→ 同一段被覆盖，不是新增一条。"""
    usage.ingest(DEV, [ev(T, RESUMED, "com.a"), ev(T + 2_000, SCREEN_OFF)])
    assert usage.list_segments()[0]["duration_seconds"] == 2

    r = usage.ingest(DEV, [ev(T, RESUMED, "com.a"), ev(T + 9_000, PAUSED, "com.a")])
    rows = usage.list_segments()
    assert r["duplicates"] == 1
    assert len(rows) == 1                            # 🔴 覆盖，不是新增
    assert rows[0]["duration_seconds"] == 9


def test_ingest_open_segment_survives_to_next_batch(db_env):
    """App 被杀的断档段：上一批只开到一半，下一批接着闭它。"""
    first = usage.ingest(DEV, [ev(T, RESUMED, "com.a")])
    assert first["accepted"] == 0
    assert len(_pending_rows()) == 1                 # 未闭合段落到表里了

    second = usage.ingest(DEV, [ev(T + 6_000, PAUSED, "com.a")])
    rows = usage.list_segments()
    assert second["accepted"] == 1
    assert rows[0]["duration_seconds"] == 6
    assert _pending_rows() == []                     # 闭合 → 清掉


def test_ingest_labels_are_stored_and_not_wiped_by_later_batches(db_env):
    """app_label：本批带了就写，没带不能把库里已有的名字抹掉。"""
    events = [ev(T, RESUMED, "com.a"), ev(T + 3_000, SCREEN_OFF)]
    usage.ingest(DEV, events, labels={"com.a": "微信"})
    assert usage.list_segments()[0]["app_label"] == "微信"

    usage.ingest(DEV, events)                        # 这次不带 labels
    assert usage.list_segments()[0]["app_label"] == "微信"


def test_ingest_counts_unknown_event_types(db_env):
    r = usage.ingest(DEV, [
        ev(T, 23, "com.a"),                          # ACTIVITY_STOPPED，我们不管
        ev(T + 1_000, 99, "com.b"),
        ev(T + 2_000, RESUMED, "com.a"),
    ])
    assert r["unknown"] == 2
    assert len(usage.list_segments()) == 0           # 只开段没闭合，没有段落库


def test_ingest_segments_are_isolated_per_device(db_env):
    """同一段被两台设备报到 → 两台各一条（唯一键含 device_id）。"""
    events = [ev(T, RESUMED, "com.a"), ev(T + 3_000, SCREEN_OFF)]
    usage.ingest("a" * 16, events)
    r = usage.ingest("b" * 16, events)
    assert r["duplicates"] == 0
    assert len(usage.list_segments()) == 2


# ── 命名回归：事件常量必须与 Android 对齐 ────────────────────

def test_event_constants_match_android():
    """这几个整数是 Android `UsageEvents.Event` 定的，写错一个就静默算错时长。

    值来自 Android 官方文档；**不是我们算出来的**，所以这里写死做回归。
    """
    assert (usage.ACTIVITY_RESUMED, usage.ACTIVITY_PAUSED) == (1, 2)
    assert (usage.SCREEN_INTERACTIVE, usage.SCREEN_NON_INTERACTIVE) == (15, 16)
    assert (usage.KEYGUARD_SHOWN, usage.KEYGUARD_HIDDEN) == (17, 18)


# ── /usage/trigger → ws_hub（共用那条 Lattice WS）────────────────

@pytest.fixture()
def no_lattice(tmp_path, monkeypatch):
    """发现文件指到一个不存在的地方。

    这样 `open_event_stream()` 会**立刻**抛 AppError(503)，不走网络 ——
    测试既碰不到机器上真在跑的 Lattice，也不会卡在超时上。
    WS 端点照常 accept + 登记，只是上游那条腿一直是断的。
    """
    monkeypatch.setattr(config, "LATTICE_DISCOVERY_FILE", tmp_path / "nope" / "current.json")


def test_trigger_reaches_a_connected_ws(client, no_lattice):
    """`/usage/trigger` 要能推 `trigger_usage` 到那条共用的 WS 上。"""
    with client.websocket_connect("/api/v1/lattice/ws", headers=AUTH) as ws:
        # 先收一条：上游连不上 → 处理器必定发一条 upstream-error。
        # 🔴 这也顺便解决了**竞态** —— 收到它才说明服务端已经走过 `accept()` 和
        #    `ws_hub.register()`，否则下面的 POST 可能抢在登记之前打出去。
        assert json.loads(ws.receive_text())["event"] == "upstream-error"

        r = client.post("/api/v1/usage/trigger", headers=AUTH)
        assert r.json()["data"]["delivered"] == 1

        assert json.loads(ws.receive_text())["event"] == "trigger_usage"

    # 断开 → finally 里 unregister → 再推就没人接了
    assert client.post("/api/v1/usage/trigger", headers=AUTH).json()["data"]["delivered"] == 0


def test_trigger_with_no_client_reports_zero(client, no_lattice):
    """没有手机连着时 delivered=0 —— 不是错误，但也不能假装推成功了。"""
    r = client.post("/api/v1/usage/trigger", headers=AUTH)
    assert r.status_code == 200
    assert r.json()["data"]["delivered"] == 0

