"""Lattice 远程转发接口（手机 → 本后端 → Lattice 本机数据口）。

这一组是**透明通道**：路径、query、body 原样透传，只在外面套剪藏的统一响应
（`{ok, data, error}`）和 X-Token 认证。上游的 `ok` 会被剥掉，`data` 就是上游 body。

转发的机制（发现文件、httpx client、错误映射）全在 `app/lattice.py`，
这里只负责 HTTP 形状。
"""
import asyncio
import json
import logging

import httpx
from fastapi import APIRouter, Depends, Query, WebSocket, WebSocketDisconnect

from .. import lattice, ws_hub
from ..auth import token_ok, verify_token
from ..errors import AppError, ok
from ..schemas import LatticeAnswerRequest, LatticeInputRequest, LatticeWatchRequest

logger = logging.getLogger("lattice.ws")

router = APIRouter(dependencies=[Depends(verify_token)])

# 回车分两次写的间隔。只要让 PTY 上是两次独立的读，不需要很长。
_ENTER_SPLIT_MS = 200


def _clean(**kwargs) -> dict:
    """丢掉 None —— Lattice 的 intParam 把"没给"和"给了个假值"当两回事，
    不能替调用方补默认值（它的默认值在它自己那边）。"""
    return {k: v for k, v in kwargs.items() if v is not None}


# ── 探活 ──────────────────────────────────────────────────────

@router.get("/api/v1/lattice/health")
async def lattice_health():
    """Lattice 数据口的健康检查。返回里带 runId —— 手机据此确认控制的是哪个实例。"""
    return ok(await lattice.forward("GET", "/v1/health"))


# ── 读 ────────────────────────────────────────────────────────

@router.get("/api/v1/lattice/panes")
async def lattice_panes():
    """全部窗格及其状态 + 状态对应的数据。"""
    return ok(await lattice.forward("GET", "/v1/panes"))


@router.get("/api/v1/lattice/panes/{ref}/status")
async def lattice_pane_status(ref: str):
    return ok(await lattice.forward("GET", f"/v1/panes/{lattice.validate_ref(ref)}/status"))


@router.get("/api/v1/lattice/panes/{ref}/text")
async def lattice_pane_text(
    ref: str,
    mode: str | None = Query(None, description="screen / tail / all，默认 screen"),
    lines: int | None = Query(None, description="只对 tail / all 有意义"),
    maxBytes: int | None = Query(None),
):
    """屏幕快照。`truncated` 为真时砍的是**头部**、保尾部。"""
    return ok(
        await lattice.forward(
            "GET",
            f"/v1/panes/{lattice.validate_ref(ref)}/text",
            params=_clean(mode=mode, lines=lines, maxBytes=maxBytes),
        )
    )


@router.get("/api/v1/lattice/panes/{ref}/last-turn")
async def lattice_pane_last_turn(ref: str, maxBytes: int | None = Query(None)):
    """最后一轮汇总文本。取不到时上游回 404，我们翻成 NOT_FOUND。"""
    return ok(
        await lattice.forward(
            "GET",
            f"/v1/panes/{lattice.validate_ref(ref)}/last-turn",
            params=_clean(maxBytes=maxBytes),
        )
    )


# ── 事件流 ────────────────────────────────────────────────────
#
# 这里**曾经有一条 SSE 转发**（`GET /api/v1/lattice/events`）。2026-10-08 删掉了：
# 实测它穿 Cloudflare Tunnel 会在 **10~100 秒之间被不可预测地掐断**（两轮 88.8s / 96.9s），
# 而同一个上游在本地能挂 200 秒以上 —— 也就是说，它在本地测着好好的、一上手机就废。
# 留着只会让下一个人再踩一遍。事件流现在走下面的 WebSocket（两轮都挂满了 299 秒）。
#
# `lattice.open_event_stream()` 保留着 —— WS 端点用它连上游。


# ── 写 ────────────────────────────────────────────────────────

@router.post("/api/v1/lattice/panes/{ref}/input")
async def lattice_pane_input(ref: str, req: LatticeInputRequest):
    """往窗格写字/按键。走 PTY，等同于在电脑上敲键盘。

    ⚠️ **这是本组接口里唯一"有脑子"的一条，不是纯透传。**

    原因：Lattice 收到 `enter:true` 时会把文字和 `\\r` 拼成**同一个字符串**、
    **一次** writeSession 写进 PTY（它的 apiServer.ts:1381-1385）。实测在真机上
    这样发出去的文字会停在 Claude Code 的输入框里**不提交** —— 推测是它的 TUI
    把"整块到达"当成粘贴，里面的 `\\r` 就变成了字面换行。

    所以这里拆成**两次独立的 Lattice 调用**，中间隔 `_ENTER_SPLIT_MS`：
    第一次发文字，第二次单独发一个回车键。这样 PTY 上是两次写，不是一次。

    为什么放在后端而不是手机端：手机端拆要两次穿 Cloudflare Tunnel（慢一倍），
    而且中途失败会把一句没提交的文字留在电脑输入框里。后端拆只花一次隧道往返，
    第二次是本地 loopback。
    """
    pane = lattice.validate_ref(ref)
    body = req.model_dump(by_alias=True, exclude_none=True)
    has_payload = bool(body.get("text")) or bool(body.get("keys"))

    if body.get("enter"):
        body.pop("enter")
        if not has_payload:
            # 只给了 enter —— 等价于单发一个回车
            return ok(await lattice.forward(
                "POST", f"/v1/panes/{pane}/input", json_body={"keys": ["enter"]}
            ))
        first = await lattice.forward("POST", f"/v1/panes/{pane}/input", json_body=body)
        await asyncio.sleep(_ENTER_SPLIT_MS / 1000)
        second = await lattice.forward(
            "POST", f"/v1/panes/{pane}/input", json_body={"keys": ["enter"]}
        )
        merged = dict(first)
        merged["bytes"] = int(first.get("bytes") or 0) + int(second.get("bytes") or 0)
        merged["enterSplit"] = True
        return ok(merged)

    return ok(await lattice.forward("POST", f"/v1/panes/{pane}/input", json_body=body))


@router.post("/api/v1/lattice/panes/{ref}/answer")
async def lattice_pane_answer(ref: str, req: LatticeAnswerRequest):
    """把确认框的答案送回去 —— 等于替用户在电脑上按了那一项。"""
    return ok(
        await lattice.forward(
            "POST",
            f"/v1/panes/{lattice.validate_ref(ref)}/answer",
            json_body=req.model_dump(by_alias=True, exclude_none=True),
        )
    )


@router.post("/api/v1/lattice/watch")
async def lattice_watch(req: LatticeWatchRequest):
    """声明"我要应答"。

    **不做这一步，Lattice 的钩子不会挂住等答案**（fail-open）—— 确认框会直接
    弹在电脑上。只读的看板不该拖慢电脑前的人，所以这个意愿必须显式声明。
    """
    return ok(
        await lattice.forward(
            "POST",
            "/v1/watch",
            json_body=req.model_dump(by_alias=True, exclude_none=True),
        )
    )


# ── WebSocket 事件流 ──────────────────────────────────────────
#
# 为什么要有它（而不是只用上面那条 SSE）：实测穿 Cloudflare Tunnel 的 SSE 会在
# 10~100 秒之间被不可预测地掐断，**而同一条流在本地能挂 200 秒以上**。
# WS 走的是另一套升级/帧机制，未必受同一个限制。
#
# 🔴 `ws_router` **故意不挂 `dependencies=[Depends(verify_token)]`**：
#    `verify_token` 抛的是 `AppError`，而 FastAPI 的 exception handler
#    **对 WebSocket 不生效** —— 挂在 router 上，鉴权失败只会在握手阶段变成
#    一条看不懂的断裂。所以这里手动判、手动关。

ws_router = APIRouter()

_WS_BACKOFF_MIN = 1.0
_WS_BACKOFF_MAX = 10.0


@ws_router.websocket("/api/v1/lattice/ws")
async def lattice_ws(websocket: WebSocket):
    """把 Lattice 的事件流推给手机，**中间上游断了自己重连，不关这条 WS**。

    设计的核心是"把抖动挪到 loopback 那条腿上"：
      手机 ←WS（尽量不动）← 后端 ←SSE（本地，断了秒级重连）← Lattice
    上游每次重连成功，Lattice 都会推一个 `hello`（含全量快照），原样转发给手机
    ⇒ 客户端自动重同步，不需要额外协议。
    """
    if not token_ok(websocket.headers.get("x-token")):
        # 没 accept 就 close → Starlette 在握手阶段直接回 403（干净，不是半截连接）
        await websocket.close(code=1008)
        return

    await websocket.accept()
    # 登记进 ws_hub：这条连接从此**不只**转发 Lattice 的事件，后端自己也能往上推
    # （用法见 routes/usage.py 的 /usage/trigger）。下面整个循环一行没动。
    ws_hub.register(websocket)
    backoff = _WS_BACKOFF_MIN
    # 🔴 必须**并发**盯着客户端断开。只靠"send 失败"会发现不了：
    #    上游重连期间我们可能几十秒不发任何东西，那段时间手机走了我们不知道，
    #    于是这个处理器会一直循环重连 Lattice —— 每个被遗弃的连接漏一个任务。
    #    （实测就是它把测试套件挂死的。）
    watcher = asyncio.create_task(_wait_client_gone(websocket))
    try:
        while not watcher.done():
            detail = None
            try:
                async with lattice.open_event_stream() as resp:
                    backoff = _WS_BACKOFF_MIN  # 连上就复位
                    await _pump_sse_to_ws(resp, websocket)
                    # ⚠️ 上游**正常结束**也要走退避那条路。不区分的话，上游一收尾
                    #    while 立刻重连 —— 变成打 Lattice 的热循环。
                    detail = "上游事件流结束"
            except (AppError, httpx.HTTPError, OSError) as e:
                detail = getattr(e, "message", None) or str(e)

            if watcher.done():
                break

            # 走到这里 = 上游没了（异常结束或正常收尾）。**WS 不关** —— 手机那条腿要稳。
            # 但也别让它静默：发一条消息出去。否则"Lattice 没开"和"连着但没动静"
            # 在手机上长得一模一样（本项目的经典静默失效）。
            logger.warning("WS 上游中断，%.1fs 后重连：%s", backoff, detail)
            try:
                await websocket.send_text(
                    json.dumps({"event": "upstream-error", "message": detail},
                               ensure_ascii=False)
                )
            except Exception:
                break  # 发不出去 = 手机已经走了
            await asyncio.sleep(backoff)
            backoff = min(backoff * 2, _WS_BACKOFF_MAX)
    except WebSocketDisconnect:
        pass
    finally:
        ws_hub.unregister(websocket)
        watcher.cancel()
        try:
            await watcher
        except (asyncio.CancelledError, Exception):
            pass


async def _wait_client_gone(websocket: WebSocket) -> None:
    """只等客户端断开，**内容一概不看**。

    客户端本来就不需要发任何东西，所以 `receive()` 拿到的要么是忽略的消息、
    要么是断开。断开时它抛 `WebSocketDisconnect` / `RuntimeError`，任务就此结束 ——
    主循环用 `watcher.done()` 立刻知道该收摊了。
    """
    try:
        while True:
            await websocket.receive()
    except (WebSocketDisconnect, RuntimeError):
        return


async def _pump_sse_to_ws(resp: httpx.Response, websocket: WebSocket) -> None:
    """上游 SSE 的一行行 → WS 文本消息。

    **不做成"转发原始 SSE 文本"**：客户端既然用了 WS，就不该再解一遍 SSE 帧。
    一个事件一条消息，内容就是 Lattice 的 JSON 信封原文。
    """
    data_lines: list[str] = []
    async for line in resp.aiter_lines():
        if line.startswith(":"):
            # Lattice 的心跳注释行 → 转成一条**可观测的**文本消息。
            # WS 自己有 ping/pong 帧，但那条在客户端库内部就被消费掉了、应用层看不见；
            # 转发成文本，客户端才拿得到它来判活。
            await websocket.send_text('{"event":"ping"}')
        elif line == "":
            if data_lines:
                await websocket.send_text("\n".join(data_lines))
                data_lines.clear()
        elif line.startswith("data:"):
            data_lines.append(line[5:].lstrip())
