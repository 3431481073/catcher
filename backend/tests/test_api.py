"""API 层测试（转写走真实建记录，但不触发 worker）。"""
from tests.conftest import TEST_TOKEN


def _auth():
    return {"X-Token": TEST_TOKEN}


def _post_transcribe(client, local_id):
    return client.post(
        "/api/v1/transcribe",
        files={"audio": ("test.webm", b"fake-audio-bytes", "audio/webm")},
        data={"local_id": local_id},
        headers=_auth(),
    )


def test_health_no_token(client):
    r = client.get("/api/v1/health")
    assert r.status_code == 200
    assert r.json() == {"ok": True, "data": {"status": "ok"}, "error": None}


def test_requires_token(client):
    r = client.get("/api/v1/records")
    assert r.status_code == 401
    assert r.json()["error"]["code"] == "UNAUTHORIZED"


def test_transcribe_returns_dto(client):
    r = _post_transcribe(client, "loc_1")
    assert r.status_code == 200
    body = r.json()
    assert body["ok"] is True
    dto = body["data"]
    assert dto["local_id"] == "loc_1"
    assert dto["type"] == "voice"
    assert dto["status"] == "processing"
    assert dto["task_status"] == "pending"
    assert dto["record_id"].startswith("rec_")
    assert dto["task_id"].startswith("task_")
    assert dto["queue_position"] >= 1


def test_transcribe_idempotent(client):
    r1 = _post_transcribe(client, "loc_idem")
    r2 = _post_transcribe(client, "loc_idem")
    assert r1.json()["data"]["record_id"] == r2.json()["data"]["record_id"]


def test_transcribe_missing_local_id(client):
    r = client.post(
        "/api/v1/transcribe",
        files={"audio": ("a.webm", b"x", "audio/webm")},
        data={"local_id": ""},
        headers=_auth(),
    )
    assert r.status_code == 400
    assert r.json()["error"]["code"] == "INVALID_PAYLOAD"


def test_transcribe_disabled(client, db_env, monkeypatch):
    """CLIP_VOICE_ENABLED=0 → 503 FEATURE_DISABLED，且一行都不落库。"""
    from app import config, db

    monkeypatch.setattr(config, "VOICE_ENABLED", False)

    r = _post_transcribe(client, "loc_disabled")
    assert r.status_code == 503
    assert r.json()["ok"] is False
    assert r.json()["error"]["code"] == "FEATURE_DISABLED"

    # 状态码只证明「拒绝了」，下面才证明「真的没落库」（含 events 旁路与音频文件）
    with db.connect() as conn:
        assert conn.execute("SELECT COUNT(*) FROM records").fetchone()[0] == 0
        assert conn.execute("SELECT COUNT(*) FROM tasks").fetchone()[0] == 0
        assert conn.execute("SELECT COUNT(*) FROM events").fetchone()[0] == 0
    audio_files = list(db_env.AUDIO_DIR.iterdir()) if db_env.AUDIO_DIR.exists() else []
    assert audio_files == []


def test_records_list(client):
    _post_transcribe(client, "loc_list")
    r = client.get("/api/v1/records", headers=_auth())
    assert r.status_code == 200
    data = r.json()["data"]
    assert isinstance(data, list)
    assert any(d["local_id"] == "loc_list" for d in data)


def test_task_returns_dto(client):
    dto = _post_transcribe(client, "loc_task").json()["data"]
    r = client.get(f"/api/v1/tasks/{dto['task_id']}", headers=_auth())
    assert r.status_code == 200
    assert r.json()["data"]["task_id"] == dto["task_id"]
    assert r.json()["data"]["local_id"] == "loc_task"


def test_task_not_found(client):
    r = client.get("/api/v1/tasks/nonexistent", headers=_auth())
    assert r.status_code == 404
    assert r.json()["error"]["code"] == "NOT_FOUND"


# ---------- M3: POST /records ----------

def _post_record(client, **body):
    return client.post("/api/v1/records", json=body, headers=_auth())


def test_create_text_record(client):
    r = _post_record(client, local_id="loc_text", type="text", content="hello")
    assert r.status_code == 200
    dto = r.json()["data"]
    assert dto["type"] == "text"
    assert dto["status"] == "done"
    assert dto["task_id"] is None
    assert dto["task_status"] is None
    assert dto["content"] == "hello"


def test_create_video_record(client):
    r = _post_record(
        client, local_id="loc_video", type="video",
        url="https://www.bilibili.com/video/av1", platform="bilibili",
    )
    assert r.status_code == 200
    dto = r.json()["data"]
    assert dto["type"] == "video"
    assert dto["status"] == "done"
    assert dto["task_status"] == "skipped"
    assert dto["task_id"].startswith("task_")
    assert dto["url"].startswith("https://")


def test_create_record_idempotent(client):
    r1 = _post_record(client, local_id="loc_idem2", type="text", content="a")
    r2 = _post_record(client, local_id="loc_idem2", type="text", content="a")
    assert r1.json()["data"]["record_id"] == r2.json()["data"]["record_id"]


def test_create_record_invalid_type(client):
    r = _post_record(client, local_id="loc_bad", type="foo", content="x")
    assert r.status_code == 400
    assert r.json()["error"]["code"] == "INVALID_PAYLOAD"


def test_create_text_missing_content(client):
    r = _post_record(client, local_id="loc_empty", type="text")
    assert r.status_code == 400
    assert r.json()["error"]["code"] == "INVALID_PAYLOAD"


def test_create_video_missing_url(client):
    r = _post_record(client, local_id="loc_nourl", type="video")
    assert r.status_code == 400
    assert r.json()["error"]["code"] == "INVALID_PAYLOAD"


# ---------- M3: GET /records/{id} ----------

def test_get_record_by_id(client):
    dto = _post_record(client, local_id="loc_get", type="text", content="hi").json()["data"]
    r = client.get(f"/api/v1/records/{dto['record_id']}", headers=_auth())
    assert r.status_code == 200
    assert r.json()["data"]["local_id"] == "loc_get"


def test_get_record_not_found(client):
    r = client.get("/api/v1/records/nonexistent", headers=_auth())
    assert r.status_code == 404
    assert r.json()["error"]["code"] == "NOT_FOUND"


# ---------- M3: PATCH /records/{id} ----------

def test_patch_note(client):
    # 对齐现契约：video 现在 platform 必填；PATCH 收的是 append_note 对象（不是旧的 note 字符串）
    dto = _post_record(
        client, local_id="loc_patch", type="video", url="https://x.com/v/1", platform="bilibili"
    ).json()["data"]
    r = client.patch(
        f"/api/v1/records/{dto['record_id']}",
        json={"append_note": {"id": "note_p1", "text": "我的备注", "time": "2026-09-21T00:00:00.000000Z"}},
        headers=_auth(),
    )
    assert r.status_code == 200
    assert r.json()["data"]["note"][0]["text"] == "我的备注"


def test_patch_not_found(client):
    r = client.patch("/api/v1/records/nonexistent", json={"note": "x"}, headers=_auth())
    assert r.status_code == 404
    assert r.json()["error"]["code"] == "NOT_FOUND"


# ---------- events（外部处理程序的信号表）----------
# 外部程序直接读库，所以这里也用 db.connect() 直读，不经过 API（API 里没有 events 接口，是有意的）

def _events():
    from app import db
    with db.connect() as conn:
        return [dict(r) for r in conn.execute("SELECT * FROM events ORDER BY id")]


def _append_note(client, record_id, note_id):
    return client.patch(
        f"/api/v1/records/{record_id}",
        json={"append_note": {"id": note_id, "text": "备注", "time": "2026-09-21T00:00:00.000000Z"}},
        headers=_auth(),
    )


def test_event_record_created_text(client):
    dto = _post_record(client, local_id="loc_ev_text", type="text", content="x").json()["data"]
    rows = _events()
    assert len(rows) == 1
    assert rows[0]["event_type"] == "record_created"
    assert rows[0]["record_id"] == dto["record_id"]
    assert rows[0]["created_at"].endswith("Z")      # ISO8601 UTC，与 records.created_at 同格式
    assert rows[0]["id"] >= 1                        # 自增游标


def test_event_record_created_voice(client):
    # 语音是本项目主入口，也要发（决策 A）
    dto = _post_transcribe(client, "loc_ev_voice").json()["data"]
    rows = _events()
    assert len(rows) == 1
    assert rows[0]["event_type"] == "record_created"
    assert rows[0]["record_id"] == dto["record_id"]


def test_event_not_duplicated_on_idempotent_post(client):
    d1 = _post_record(client, local_id="loc_ev_retry", type="text", content="x").json()["data"]
    d2 = _post_record(client, local_id="loc_ev_retry", type="text", content="x").json()["data"]
    assert d1["record_id"] == d2["record_id"]
    assert len(_events()) == 1                       # local_id 重试不补发


def test_event_note_added(client):
    dto = _post_record(client, local_id="loc_ev_note", type="text", content="x").json()["data"]
    assert _append_note(client, dto["record_id"], "note_a").status_code == 200
    assert [e["event_type"] for e in _events()] == ["record_created", "note_added"]


def test_event_note_not_duplicated_on_idempotent_patch(client):
    dto = _post_record(client, local_id="loc_ev_note2", type="text", content="x").json()["data"]
    _append_note(client, dto["record_id"], "note_b")
    _append_note(client, dto["record_id"], "note_b")  # 同一 note id 重试
    assert [e["event_type"] for e in _events()] == ["record_created", "note_added"]
    assert len(_events()) == 2


def test_event_failure_does_not_break_main_business(client, monkeypatch):
    # "插不进就算了"：events 写失败，主业务必须照常成功（证伪标准）
    from app import events as events_mod

    def _boom():
        raise RuntimeError("db is gone")

    monkeypatch.setattr(events_mod, "connect", _boom)
    r = _post_record(client, local_id="loc_ev_fail", type="text", content="x")
    assert r.status_code == 200                      # 主业务不受影响
    assert _events() == []                           # 事件没写进去，但也不抛
