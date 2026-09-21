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
    dto = _post_record(
        client, local_id="loc_patch", type="video", url="https://x.com/v/1"
    ).json()["data"]
    r = client.patch(
        f"/api/v1/records/{dto['record_id']}", json={"note": "我的备注"}, headers=_auth()
    )
    assert r.status_code == 200
    assert r.json()["data"]["note"] == "我的备注"


def test_patch_not_found(client):
    r = client.patch("/api/v1/records/nonexistent", json={"note": "x"}, headers=_auth())
    assert r.status_code == 404
    assert r.json()["error"]["code"] == "NOT_FOUND"
