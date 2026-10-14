"""已连接的手机 WS 客户端登记表 —— 让**后端自己的**事件也能推给手机。

为什么需要：`/api/v1/lattice/ws` 那条连接原本只有一个数据源 —— Lattice 的 SSE，
消息全在 `_pump_sse_to_ws` 里从上游转出来。于是后端自己**没有任何办法**主动推一条
东西给手机（比如"现在去采集一次使用时长"）。这个模块补上那一半：一个进程内的集合，
谁 accept 了就登记、断了就注销，后端任意处 `broadcast()` 就能发出去。

**共用同一条连接**（不新开 WS）：手机端解出来的还是同一个 `LatticeEventDto`
信封，只是多了一种 `event` 取值 —— 这就是"复用现有 WS 通道，加事件类型"。
代价是它跟那条连接的寿命绑在一起（客户端只在「远程」Tab 打开时连着），
`trigger` 的返回里带 `delivered`，**0 就是"手机现在没连着"**，不静默。
"""
import json
import logging

from fastapi import WebSocket

logger = logging.getLogger("ws_hub")

# 🔴 只有一个事件循环在跑（uvicorn 单进程），所以这个 set 不需要锁。
#    全体操作都在协程里做，天然串行。
_clients: set[WebSocket] = set()


def register(websocket: WebSocket) -> None:
    _clients.add(websocket)


def unregister(websocket: WebSocket) -> None:
    """注销。**必须放在 finally 里** —— 漏了它，重连几次之后集合里全是死连接，
    broadcast 每次都对着空气发。"""
    _clients.discard(websocket)


def client_count() -> int:
    return len(_clients)


async def broadcast(event: str, **fields) -> int:
    """推给所有连着的手机，返回**真正发出去的条数**。

    发失败的连接当场注销：`send_text` 抛异常基本等同于"这条已经断了"，
    留着它只会让下一次 broadcast 再失败一遍。
    """
    if not _clients:
        return 0

    text = json.dumps({"event": event, **fields}, ensure_ascii=False)
    sent, dead = 0, []
    for ws in list(_clients):
        try:
            await ws.send_text(text)
            sent += 1
        except Exception as e:
            dead.append(ws)
            logger.warning("WS 推送失败，注销该连接：%s", e)

    for ws in dead:
        _clients.discard(ws)
    return sent
