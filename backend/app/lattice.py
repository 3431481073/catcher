"""转发到 Lattice 本机数据口。

手机 → Cloudflare Tunnel → 本后端 → 127.0.0.1:<Lattice 端口> → Lattice

Lattice 那边**一行不改** —— 它自带的数据口本来就是为"外部软件读写"设计的。
这一层只做四件事：找端口（发现文件）、转发、把上游错误翻成剪藏的错误码、
以及一条"Lattice 刚重启就重试一次"的兜底。

## 为什么每请求重读发现文件

Lattice 的端口是**随机的**（44770 起 12 次尝试），每次启动都可能不同 ——
写死端口或者进程内长缓存只会制造"指着死端口"的 bug。文件 < 500B，
而 Lattice 一 listen 就会覆盖它。重读的开销在轮询量级下可以忽略。

## 为什么 client 要 trust_env=False

Windows 的系统代理可以是环境变量（HTTP_PROXY 等），httpx 默认会读。
那样 `http://127.0.0.1:<port>` 也会被丢给代理 —— 表现是莫名其妙的 502。
回环流量永远不该走代理。
"""
import asyncio
import json
import re
from contextlib import asynccontextmanager

import httpx

from . import config
from .errors import AppError

# ref 只在 pane-N 这一层校验；`runId/pane-N` 那种带斜杠的写法**故意不支持**：
# 后端总是转发给发现文件里那**一个**实例，拼 runId 只会在 Lattice 重启换 runId 后
# 静默指向新实例的同名窗格。裸 ref 的语义就是"当前实例的 pane-1"，天然正确。
_REF_RE = re.compile(r"^[A-Za-z0-9._-]{1,64}$")

_client: httpx.AsyncClient | None = None


def read_discovery() -> str:
    """读 Lattice 的发现文件，返回 baseUrl（形如 http://127.0.0.1:44771）。

    读不到一律抛 LATTICE_UNAVAILABLE —— 对手机来说"Lattice 没开"和
    "发现文件被删了"是同一件事：去电脑上开一下。
    """
    path = config.LATTICE_DISCOVERY_FILE
    try:
        raw = path.read_text(encoding="utf-8")
    except FileNotFoundError as e:
        raise AppError(
            "LATTICE_UNAVAILABLE",
            "电脑上的 Lattice 没在运行（读不到数据口发现文件）",
            503,
        ) from e
    except OSError as e:
        raise AppError("LATTICE_UNAVAILABLE", f"读不到 Lattice 发现文件：{e}", 503) from e

    try:
        info = json.loads(raw)
    except ValueError as e:
        raise AppError("LATTICE_UNAVAILABLE", "Lattice 发现文件不是合法 JSON", 503) from e

    base = info.get("baseUrl") if isinstance(info, dict) else None
    # 🔴 只认回环。发现文件是用户可写的普通文件，而 Lattice 的 Host 守卫只管它自己那侧；
    # 这里再夹一道，保证我们**永远**不会拿着这个文件里的地址去请求别的机器。
    if not isinstance(base, str) or not base.startswith("http://127.0.0.1:"):
        raise AppError("LATTICE_UNAVAILABLE", "Lattice 发现文件里没有可用的 baseUrl", 503)
    return base.rstrip("/")


def get_client() -> httpx.AsyncClient:
    """懒加载单例。创建时不建立连接，所以 Lattice 不在也不影响启动。"""
    global _client
    if _client is None:
        _client = httpx.AsyncClient(
            timeout=httpx.Timeout(
                connect=config.LATTICE_CONNECT_TIMEOUT,
                read=config.LATTICE_READ_TIMEOUT,
                write=config.LATTICE_WRITE_TIMEOUT,
                pool=config.LATTICE_CONNECT_TIMEOUT,
            ),
            trust_env=False,         # 回环绝不走系统代理
            follow_redirects=False,  # Lattice 不发重定向；跟了会绕过它的 Host 守卫
        )
    return _client


async def aclose() -> None:
    global _client
    if _client is not None:
        await _client.aclose()
        _client = None


def validate_ref(ref: str) -> str:
    if not _REF_RE.match(ref):
        raise AppError(
            "INVALID_PAYLOAD",
            f"窗格 ref 形状不对：{ref}（只接受 pane-N 这种，不含斜杠）",
            400,
        )
    return ref


def _raise_from_lattice(resp: httpx.Response) -> None:
    """把 Lattice 的错误翻成剪藏的错误码。

    上游的原生 code 保留在 message 里（形如 `[NO_PENDING] ...`）——
    它**不进**剪藏的契约表，但排查和手机端展示都用得上。
    """
    try:
        err = resp.json()
        code = str(err.get("code") or "")
        reason = str(err.get("reason") or "")
    except Exception:
        code, reason = "", resp.text[:200]

    detail = f"[{code}] {reason}" if code else (reason or f"Lattice 返回 {resp.status_code}")
    status = resp.status_code

    if status == 404:
        raise AppError("NOT_FOUND", detail, 404)
    if status in (400, 413, 415, 422):
        raise AppError("INVALID_PAYLOAD", detail, 400)
    if status == 409:
        # 确认框已过期 / 答案过期 / shell 已死 —— 语义是"刷新重试"，不是"你写错了"
        raise AppError("CONFLICT", detail, 409)
    if status == 403:
        # FORBIDDEN_ORIGIN / bad-host：我们自己的 bug，绝不该出现
        raise AppError("SERVER_ERROR", detail, 500)
    raise AppError("LATTICE_UNAVAILABLE", detail, 503)


async def forward(
    method: str,
    path: str,
    *,
    json_body: dict | None = None,
    params: dict | None = None,
) -> dict:
    """转发一个请求，返回上游 body 去掉 `ok` 之后的内容。

    `json=` 是必须的：Lattice 的写请求守卫要求 `Content-Type: application/json`，
    而 httpx 用 `json=` 时不会带 `Origin` 头 —— 它拒收带 Origin 的请求。
    """
    last_exc: Exception | None = None
    for attempt in (0, 1):
        base = read_discovery()  # 每拍重读：Lattice 重启会换端口
        try:
            resp = await get_client().request(
                method, base + path, json=json_body, params=params
            )
        except (
            httpx.ConnectError,
            httpx.ConnectTimeout,
            httpx.ReadTimeout,
            httpx.RemoteProtocolError,
        ) as e:
            last_exc = e
            if attempt == 0:
                # 可能是"读到的是刚关掉的那个端口"——重读发现文件再试一次
                await asyncio.sleep(0.15)
                continue
            raise AppError(
                "LATTICE_UNAVAILABLE", "连不上 Lattice 数据口（可能刚重启或已退出）", 503
            ) from e
        except httpx.HTTPError as e:
            raise AppError("LATTICE_UNAVAILABLE", f"访问 Lattice 数据口失败：{e}", 503) from e

        if resp.status_code >= 400:
            _raise_from_lattice(resp)

        try:
            body = resp.json()
        except ValueError as e:
            raise AppError(
                "LATTICE_UNAVAILABLE", "Lattice 数据口返回的不是 JSON", 503
            ) from e

        if isinstance(body, dict):
            body.pop("ok", None)
            return body
        return {"data": body}

    # 循环里要么 return 要么 raise，走不到这里；留着是为了让返回类型明确
    raise AppError("LATTICE_UNAVAILABLE", f"连不上 Lattice 数据口：{last_exc}", 503)


# ── SSE 事件流 ────────────────────────────────────────────────

def new_stream_client() -> httpx.AsyncClient:
    """SSE 专用的 httpx client。

    **单独抽成函数是为了测试能替换掉它** —— `get_client()` 那个单例被
    `forward()` 用着，两者的超时策略相反（一个 10 秒读超时，一个不设），
    不能共用同一个 patch 点。
    """
    return httpx.AsyncClient(
        timeout=httpx.Timeout(
            connect=config.LATTICE_CONNECT_TIMEOUT,
            read=None,          # ← 长连接不设读超时，见 open_event_stream 的说明
            write=config.LATTICE_WRITE_TIMEOUT,
            pool=config.LATTICE_CONNECT_TIMEOUT,
        ),
        trust_env=False,         # 回环绝不走系统代理（同 forward）
        follow_redirects=False,  # 跟了会绕过 Lattice 的 Host 守卫
    )


@asynccontextmanager
async def open_event_stream():
    """打开到 Lattice `GET /v1/events` 的长连接，yield 一个 httpx Response 供迭代。

    🔴 **为什么不能复用 `get_client()`**：那个 client 带 `read=10` 秒
    （`config.LATTICE_READ_TIMEOUT`）—— 拿去开 SSE，**10 秒后会被自己的读超时掐死**，
    而 Lattice 的心跳是 20 秒一次，等不到。所以这里必须 `read=None`：
    长连接的活性由 Lattice 的 `: ping` 保证，兜底在手机侧（客户端读超时 → 重连）。

    **上游的 4xx/5xx 在 `__aenter__` 时就抛**，所以调用方能在**还没开始响应**的时候
    就把它翻成剪藏的错误码。这一步很要紧：一旦 StreamingResponse 开始吐字节，
    HTTP 状态码就已经发出去了，那时再发现上游是 503 就只能当成"流意外结束"。

    Lattice 的 `MAX_SSE_CLIENTS = 8`，超出会回 503 —— 走到上面那条路径。
    """
    base = read_discovery()
    # 每条流一个 client：它的生命周期就是这一条连接，随生成器退出一起关，
    # 不适合做成全局单例（`get_client()` 那个已经是给普通请求用的了）。
    client = new_stream_client()
    try:
        async with client.stream("GET", base + "/v1/events") as resp:
            if resp.status_code >= 400:
                await resp.aread()  # 错误体要读出来才解析得到 code/reason
                _raise_from_lattice(resp)  # 抛 AppError，直接穿出去
            yield resp
    # 🔴 连接阶段的失败必须翻成剪藏的错误码 —— 和 `forward()` 一样。
    #    漏了这一步的代价实测过：Lattice 渲染层忙的时候，回环连接会超过
    #    `LATTICE_CONNECT_TIMEOUT`（2 秒），`httpx.ConnectTimeout` 直接冒成
    #    **HTTP 500**，而不是一个说得清的 503。（诊断路由第一次就是这么挂的。）
    except (httpx.ConnectError, httpx.ConnectTimeout, httpx.RemoteProtocolError) as e:
        raise AppError(
            "LATTICE_UNAVAILABLE", "连不上 Lattice 数据口（可能刚重启或已退出）", 503
        ) from e
    except httpx.HTTPError as e:
        raise AppError("LATTICE_UNAVAILABLE", f"访问 Lattice 数据口失败：{e}", 503) from e
    finally:
        await client.aclose()
