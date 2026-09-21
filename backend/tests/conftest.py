"""测试夹具：临时 SQLite + 停用 worker。"""
import pytest

TEST_TOKEN = "test-token-0123456789-abcdefghijklmnopqrstuvwx"


@pytest.fixture()
def db_env(tmp_path, monkeypatch):
    """临时数据库环境，返回 config。"""
    from app import config, db

    monkeypatch.setattr(config, "DB_PATH", str(tmp_path / "test.db"))
    monkeypatch.setattr(config, "DATA_DIR", tmp_path)
    monkeypatch.setattr(config, "AUDIO_DIR", tmp_path / "audio")
    monkeypatch.setattr(config, "WAV_DIR", tmp_path / "wav")
    monkeypatch.setattr(config, "TOKEN", TEST_TOKEN)
    db.init_db()
    return config


@pytest.fixture()
def client(db_env, monkeypatch):
    """TestClient：触发 lifespan 但把 worker 置空，避免测试里跑真实转写。"""
    from fastapi.testclient import TestClient

    from app import worker as worker_mod
    from app.main import app

    async def _noop(*args, **kwargs):
        return None

    monkeypatch.setattr(worker_mod.worker, "start", _noop)
    monkeypatch.setattr(worker_mod.worker, "stop", _noop)

    with TestClient(app) as c:
        yield c
