"""Lattice 转发层的测试。

**全程不联网** —— 和项目里其它测试一样。假 Lattice 是一个 ASGI app，
用 httpx 的 ASGITransport 挂进去，只在内存里跑。

假 app 还**复刻了 Lattice 的写请求守卫**（必须 application/json、拒收 Origin）：
用上游的真实规矩来反验我们的转发形状对不对，比断言"我们发了什么"更硬 ——
将来谁把 json= 改成 data=，这个测试会红。
"""
import json

import httpx
import pytest

from app import config, lattice
from app.errors import AppError

from tests.conftest import TEST_TOKEN

AUTH = {"X-Token": TEST_TOKEN}

# 假 Lattice 吐的 SSE 帧。**故意带上 `: ping` 注释行** —— 它是保住长连接的关键，
# 我们要求原样透传，谁哪天"顺手只转 event 行"就会被这几条断言逮住。
SSE_CHUNKS = [
    b'event: hello\ndata: {"event":"hello","seq":1,"data":{"rendererReady":true}}\n\n',
    b': ping\n\n',
    b'event: pane.output\ndata: {"event":"pane.output","seq":2,"paneId":"pane-1","data":{"bytes":42}}\n\n',
]


# ── 假 Lattice ────────────────────────────────────────────────

async def _send(send, status: int, obj):
    payload = json.dumps(obj).encode()
    await send({
        "type": "http.response.start",
        "status": status,
        "headers": [(b"content-type", b"application/json")],
    })
    await send({"type": "http.response.body", "body": payload})


def make_fake_lattice(seen: list):
    """返回一个 ASGI app，行为照 Lattice 的 apiServer.ts 裁剪而来。

    `seen` 收集每个请求的 method/path/headers/body —— 断言转发形状用。
    """

    async def app(scope, receive, send):
        assert scope["type"] == "http"
        method, path = scope["method"], scope["path"]
        headers = {k.decode().lower(): v.decode() for k, v in scope["headers"]}

        body = b""
        while True:
            msg = await receive()
            if msg["type"] != "http.request":
                break
            body += msg.get("body", b"")
            if not msg.get("more_body"):
                break
        seen.append({"method": method, "path": path, "headers": headers, "body": body,
                     "query": scope.get("query_string", b"").decode()})

        # ── Lattice 的写请求守卫（原样复刻）──
        if method in ("POST", "DELETE"):
            if "origin" in headers:
                return await _send(send, 403, {"ok": False, "code": "FORBIDDEN_ORIGIN",
                                               "reason": "带 Origin 的请求一律拒绝"})
            if "application/json" not in headers.get("content-type", "").lower():
                return await _send(send, 415, {"ok": False, "code": "BAD_CONTENT_TYPE",
                                               "reason": "写请求必须带 Content-Type: application/json"})

        # ── 上游的错误形状 ──
        # 🔴 必须放在通用路由**之前**：`/nope/last-turn` 也以 `/last-turn` 结尾，
        # 反过来的话会被通用分支先接走（这个坑本文件自己踩过一次）。
        if path.endswith("/nope/last-turn"):
            return await _send(send, 404, {"ok": False, "code": "NO_TRANSCRIPT",
                                           "reason": "最后一轮里没有可复制的文本"})
        if path.endswith("/nope/answer"):
            return await _send(send, 409, {"ok": False, "code": "NO_PENDING",
                                           "reason": "这个窗格现在没有在等外部回答的确认框"})

        # ── 路由 ──
        if path == "/v1/events" and method == "GET":
            await send({
                "type": "http.response.start",
                "status": 200,
                "headers": [(b"content-type", b"text/event-stream; charset=utf-8"),
                            (b"cache-control", b"no-store")],
            })
            for chunk in SSE_CHUNKS:
                await send({"type": "http.response.body", "body": chunk, "more_body": True})
            await send({"type": "http.response.body", "body": b""})
            return

        if path == "/v1/health" and method == "GET":
            return await _send(send, 200, {"ok": True, "app": "lattice",
                                           "runId": "p1-fake", "port": 44770,
                                           "apiVersion": 1, "uptimeMs": 1234})

        if path == "/v1/panes" and method == "GET":
            return await _send(send, 200, {"ok": True, "runId": "p1-fake",
                                           "paneCount": 1, "focusedPaneId": "pane-1",
                                           "panes": [{"id": "pane-1", "ref": "p1-fake/pane-1",
                                                      "title": "catcher", "state": "idle"}]})

        if path.endswith("/status") and method == "GET":
            return await _send(send, 200, {"ok": True, "pane": {
                "id": "pane-1", "ref": "p1-fake/pane-1", "state": "confirm",
                "data": {"state": "confirm", "kind": "question", "canAnswer": True,
                         "pendingId": "ask-1",
                         "options": [{"index": 1, "label": "是", "selected": True},
                                     {"index": 2, "label": "否", "selected": False}]}}})

        if path.endswith("/text") and method == "GET":
            return await _send(send, 200, {"ok": True, "paneId": "pane-1",
                                           "mode": "screen", "text": "hello",
                                           "lines": 1, "bufferLines": 1,
                                           "truncated": False, "altScreen": True})

        if path.endswith("/last-turn") and method == "GET":
            return await _send(send, 200, {"ok": True, "paneId": "pane-1",
                                           "text": "最后一轮", "chars": 4, "blocks": 1,
                                           "toolCount": 0, "busy": False,
                                           "prompt": None, "truncated": False,
                                           "transcriptPath": r"C:\x.jsonl"})

        if path == "/v1/watch" and method == "POST":
            return await _send(send, 200, {"ok": True, "watching": True,
                                           "answerUntil": 1791999999999})

        if path.endswith("/input") and method == "POST":
            return await _send(send, 200, {"ok": True, "paneId": "pane-1", "bytes": 4,
                                           "delivered": True, "duplicate": False,
                                           "outputSeen": False, "outputBytes": 0,
                                           "waitedMs": 0, "quiet": False})

        if path.endswith("/answer") and method == "POST":
            return await _send(send, 200, {"ok": True, "paneId": "pane-1", "id": "ask-1",
                                           "action": "allow", "remainingMs": 80000,
                                           "note": "答案已写入"})

        return await _send(send, 404, {"ok": False, "code": "NOT_FOUND", "reason": "没有这条接口"})

    return app


class BoomTransport(httpx.AsyncBaseTransport):
    """连不上：模拟 Lattice 没在跑（重试一次后仍失败）。"""

    def __init__(self):
        self.calls = 0

    async def handle_async_request(self, request):
        self.calls += 1
        raise httpx.ConnectError("connection refused", request=request)


# ── 夹具 ──────────────────────────────────────────────────────

@pytest.fixture()
def lattice_env(tmp_path, monkeypatch):
    """发现文件指到 tmp，避免碰到机器上真在跑的那个 Lattice。"""
    api_dir = tmp_path / "api"
    api_dir.mkdir()
    current = api_dir / "current.json"
    current.write_text(json.dumps({
        "app": "lattice", "product": "Lattice", "apiVersion": 1,
        "runId": "p1-fake", "pid": 1234, "port": 44770,
        "baseUrl": "http://127.0.0.1:44770", "startedAt": "2026-10-04T00:00:00.000Z",
        "file": str(current),
    }), encoding="utf-8")
    monkeypatch.setattr(config, "LATTICE_DISCOVERY_FILE", current)
    return current


@pytest.fixture()
def seen():
    return []


@pytest.fixture()
def fake_lattice(lattice_env, monkeypatch, seen):
    transport = httpx.ASGITransport(app=make_fake_lattice(seen))
    monkeypatch.setattr(
        lattice, "get_client",
        lambda: httpx.AsyncClient(transport=transport, trust_env=False),
    )
    return seen


@pytest.fixture()
def no_lattice(monkeypatch):
    monkeypatch.setattr(
        lattice, "get_client",
        lambda: httpx.AsyncClient(transport=BoomTransport(), trust_env=False),
    )


# ── 认证 ──────────────────────────────────────────────────────

def test_requires_token(client, fake_lattice):
    assert client.get("/api/v1/lattice/health").status_code == 401


def test_other_routes_not_affected(client):
    """回归：加了转发层之后，剪藏自己的 health 仍然免 token、仍然直返。"""
    r = client.get("/api/v1/health")
    assert r.status_code == 200
    assert r.json()["data"] == {"status": "ok"}


# ── 上游不可达 ────────────────────────────────────────────────

def test_missing_discovery_is_unavailable(client, tmp_path, monkeypatch):
    missing = tmp_path / "nope" / "current.json"
    monkeypatch.setattr(config, "LATTICE_DISCOVERY_FILE", missing)
    r = client.get("/api/v1/lattice/health", headers=AUTH)
    assert r.status_code == 503
    assert r.json()["error"]["code"] == "LATTICE_UNAVAILABLE"
    assert "没在运行" in r.json()["error"]["message"]


def test_connect_failure_is_unavailable(client, lattice_env, no_lattice):
    r = client.get("/api/v1/lattice/health", headers=AUTH)
    assert r.status_code == 503
    assert r.json()["error"]["code"] == "LATTICE_UNAVAILABLE"


# ── 转发形状 ──────────────────────────────────────────────────

def test_health_passthrough(client, fake_lattice):
    r = client.get("/api/v1/lattice/health", headers=AUTH)
    assert r.status_code == 200
    body = r.json()
    assert body["ok"] is True and body["error"] is None
    # 上游的 ok 被剥掉，其余原样
    assert body["data"]["runId"] == "p1-fake"
    assert "ok" not in body["data"]


def test_panes_passthrough(client, fake_lattice):
    r = client.get("/api/v1/lattice/panes", headers=AUTH)
    assert r.status_code == 200
    assert r.json()["data"]["panes"][0]["title"] == "catcher"


def test_status_passthrough(client, fake_lattice):
    r = client.get("/api/v1/lattice/panes/pane-1/status", headers=AUTH)
    assert r.status_code == 200
    data = r.json()["data"]["pane"]["data"]
    assert data["kind"] == "question"
    assert data["canAnswer"] is True
    assert data["pendingId"] == "ask-1"
    assert [o["label"] for o in data["options"]] == ["是", "否"]


def test_text_query_reaches_upstream(client, fake_lattice):
    client.get("/api/v1/lattice/panes/pane-1/text",
               headers=AUTH, params={"mode": "tail", "lines": 200, "maxBytes": 4096})
    q = fake_lattice[-1]
    assert q["path"] == "/v1/panes/pane-1/text"
    sent = dict(kv.split("=", 1) for kv in q["query"].split("&") if kv)
    assert sent == {"mode": "tail", "lines": "200", "maxBytes": "4096"}


def test_text_query_omits_absent_params(client, fake_lattice):
    """没给的参数**不能**替调用方补默认值 —— Lattice 的默认值在它自己那边，
    传个 0 进去会被它的 clamp 拉成最小值（它自己的注释里记过这个坑）。"""
    client.get("/api/v1/lattice/panes/pane-1/text", headers=AUTH)
    assert fake_lattice[-1]["query"] == ""


# ── 错误映射 ──────────────────────────────────────────────────

def test_no_transcript_maps_to_404(client, fake_lattice):
    r = client.get("/api/v1/lattice/panes/nope/last-turn", headers=AUTH)
    assert r.status_code == 404
    assert r.json()["error"]["code"] == "NOT_FOUND"
    # 上游原生 code 保留在 message 里
    assert "[NO_TRANSCRIPT]" in r.json()["error"]["message"]


def test_no_pending_maps_to_409_conflict(client, fake_lattice):
    r = client.post("/api/v1/lattice/panes/nope/answer", headers=AUTH, json={"action": "allow"})
    assert r.status_code == 409
    assert r.json()["error"]["code"] == "CONFLICT"
    assert "[NO_PENDING]" in r.json()["error"]["message"]


def test_bad_ref_rejected(client, fake_lattice):
    """`@` 不在 [A-Za-z0-9._-] 里 → validate_ref 拦下。

    注意**带斜杠的 ref 走不到这里**：Starlette 用解码后的 path 匹配路由，
    `%2F` 会变成 `/`，于是 `/panes/a/b/status` 根本不匹配这条路由，直接 404。
    两条路都是拒绝，只是错误码不同（这里断言的是能走到校验的那一种）。
    """
    r = client.get("/api/v1/lattice/panes/pane@1/status", headers=AUTH)
    assert r.status_code == 400
    assert r.json()["error"]["code"] == "INVALID_PAYLOAD"


# ── 写请求的形状（用上游的守卫反验）────────────────────────────

def test_watch_sends_json_without_origin(client, fake_lattice):
    r = client.post("/api/v1/lattice/watch", headers=AUTH,
                    json={"answer": True, "seconds": 3600})
    assert r.status_code == 200, r.text  # 假 app 会因带 Origin 或缺 content-type 回 403/415
    q = fake_lattice[-1]
    assert q["method"] == "POST"
    assert "origin" not in q["headers"]
    assert q["headers"]["content-type"].startswith("application/json")
    assert json.loads(q["body"]) == {"answer": True, "seconds": 3600}


def test_input_alias_roundtrip(client, fake_lattice):
    """snake_case 进来的 client_nonce 必须以 camelCase 出去。

    （不带 enter：带 enter 会被拆成两次写，那条路径见下面两个用例。）
    """
    r = client.post("/api/v1/lattice/panes/pane-1/input", headers=AUTH,
                    json={"text": "echo hi", "client_nonce": "n-1"})
    assert r.status_code == 200, r.text
    sent = json.loads(fake_lattice[-1]["body"])
    assert sent == {"text": "echo hi", "clientNonce": "n-1"}


def _inputs(seen):
    return [q for q in seen if q["path"].endswith("/input")]


def test_enter_is_split_into_two_writes(client, fake_lattice):
    """`enter:true` 必须拆成【两次独立的 Lattice 调用】。

    一次写会让 Claude Code 把整块当粘贴、`\\r` 变字面换行 —— 真机上表现为
    "文字进了输入框但没提交"。这条用例钉的就是"必须两次"。
    """
    r = client.post("/api/v1/lattice/panes/pane-1/input", headers=AUTH,
                    json={"text": "echo hi", "enter": True, "client_nonce": "n-1"})
    assert r.status_code == 200, r.text
    sent = _inputs(fake_lattice)
    assert len(sent) == 2, "回车必须拆成两次写"
    assert json.loads(sent[0]["body"]) == {"text": "echo hi", "clientNonce": "n-1"}
    assert json.loads(sent[1]["body"]) == {"keys": ["enter"]}
    assert r.json()["data"]["enterSplit"] is True


def test_enter_only_becomes_a_single_key(client, fake_lattice):
    """只给 enter（没文字）→ 就发一个回车，别去发一个空 body（上游会 400）。"""
    r = client.post("/api/v1/lattice/panes/pane-1/input", headers=AUTH,
                    json={"enter": True})
    assert r.status_code == 200, r.text
    sent = _inputs(fake_lattice)
    assert len(sent) == 1
    assert json.loads(sent[0]["body"]) == {"keys": ["enter"]}


def test_no_enter_is_pure_passthrough(client, fake_lattice):
    """不带 enter 时仍然是纯透传（一次调用，body 原样）。"""
    client.post("/api/v1/lattice/panes/pane-1/input", headers=AUTH,
                json={"text": "x", "keys": ["ctrl+c"]})
    sent = _inputs(fake_lattice)
    assert len(sent) == 1
    assert json.loads(sent[0]["body"]) == {"text": "x", "keys": ["ctrl+c"]}


def test_answer_passthrough(client, fake_lattice):
    r = client.post("/api/v1/lattice/panes/pane-1/answer", headers=AUTH,
                    json={"action": "answer", "id": "ask-1", "answers": {"Q": "是"}})
    assert r.status_code == 200, r.text
    assert json.loads(fake_lattice[-1]["body"]) == {
        "action": "answer", "id": "ask-1", "answers": {"Q": "是"}}


def test_invalid_action_rejected(client, fake_lattice):
    r = client.post("/api/v1/lattice/panes/pane-1/answer", headers=AUTH,
                    json={"action": "maybe"})
    assert r.status_code == 400
    assert r.json()["error"]["code"] == "INVALID_PAYLOAD"


# ── 直连（不经 HTTP 层）───────────────────────────────────────

def test_forward_retries_once_on_connect_error(lattice_env, monkeypatch):
    """连接失败要重试一次（覆盖"读到的是刚关掉的那个端口"的竞态）。"""
    import asyncio

    boom = BoomTransport()
    monkeypatch.setattr(
        lattice, "get_client",
        lambda: httpx.AsyncClient(transport=boom, trust_env=False),
    )
    with pytest.raises(AppError) as ei:
        asyncio.run(lattice.forward("GET", "/v1/health"))
    assert ei.value.code == "LATTICE_UNAVAILABLE"
    assert boom.calls == 2  # 重试了一次


# ── 事件流的上游连接 ──────────────────────────────────────────
#
# `forward()` 走 `get_client()`，事件流走 `new_stream_client()` —— 两个 patch 点分开，
# 因为它们的超时策略相反（一个 10 秒读超时，一个不设）。上面那些夹具碰不到这条路径。
#
# 对外那条 SSE 路由（`/api/v1/lattice/events`）2026-10-08 删了（原因见 routes/lattice.py），
# 但 `open_event_stream()` 还活着 —— **WebSocket 端点用它连上游**，所以这些用例跟着留。

@pytest.fixture()
def fake_lattice_stream(lattice_env, monkeypatch, seen):
    transport = httpx.ASGITransport(app=make_fake_lattice(seen))
    monkeypatch.setattr(
        lattice, "new_stream_client",
        lambda: httpx.AsyncClient(transport=transport, trust_env=False, follow_redirects=False),
    )
    return seen


def test_open_event_stream_unreachable_is_app_error(lattice_env, monkeypatch):
    """连不上 Lattice 时 `open_event_stream()` 要抛 **AppError(503)**，不是裸的 httpx 异常。

    这条是补回来的：它最初漏了错误映射，实测在一次回环 `httpx.ConnectTimeout` 上
    直接冒成了 HTTP 500（当时还是经由诊断路由暴露出来的）。
    `forward()` 一直有这层映射，流式这条路当时漏了。
    """
    import asyncio

    boom = BoomTransport()
    monkeypatch.setattr(
        lattice, "new_stream_client",
        lambda: httpx.AsyncClient(transport=boom, trust_env=False, follow_redirects=False),
    )

    async def go():
        async with lattice.open_event_stream():
            pass

    with pytest.raises(AppError) as ei:
        asyncio.run(go())
    assert ei.value.code == "LATTICE_UNAVAILABLE"


# ── WebSocket ─────────────────────────────────────────────────

def test_ws_rejects_bad_token(client):
    """错误 token 必须**在握手阶段就被拒**，不能进去半截。

    `verify_token` 抛的 `AppError` 对 WS 不生效，所以这里走的是
    "未 accept 就 close(1008)" —— 表现是握手直接失败。
    """
    with pytest.raises(Exception):
        with client.websocket_connect("/api/v1/lattice/ws",
                                      headers={"X-Token": "wrong-token"}) as ws:
            ws.receive_text()


def test_ws_forwards_sse_events_as_text(client, fake_lattice_stream):
    """上游的 SSE 事件要变成一条条 WS 文本消息（客户端不该再解一遍 SSE 帧）。

    假上游吐完 3 个块就正常收尾 ⇒ 之后必定跟一条 `upstream-error`
    （上游结束也要退避重连，见 ws 处理器里的注释）。
    """
    with client.websocket_connect("/api/v1/lattice/ws", headers=AUTH) as ws:
        got = [json.loads(ws.receive_text()) for _ in range(3)]

    assert [m["event"] for m in got] == ["hello", "ping", "pane.output"]
    # 事件体就是 Lattice 的信封原文，没被重新包一层
    assert got[0]["seq"] == 1
    assert got[2]["paneId"] == "pane-1"
    assert got[2]["data"] == {"bytes": 42}
