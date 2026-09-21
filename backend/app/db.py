"""SQLite 连接 + 建表（WAL）。schema 严格按 CLAUDE.md §五，一字不改。"""
import json
import sqlite3
import uuid
from contextlib import contextmanager

from . import config

SCHEMA = """
CREATE TABLE IF NOT EXISTS records (
  id          TEXT PRIMARY KEY,
  local_id    TEXT UNIQUE NOT NULL,
  type        TEXT NOT NULL,           -- voice / video / text
  title       TEXT,
  content     TEXT,
  note        TEXT,
  url         TEXT,
  platform    TEXT,
  tags        TEXT,                    -- JSON 数组，如 ["AIGC","AI做视频"]
  status      TEXT NOT NULL DEFAULT 'pending',
  error       TEXT,
  raw_audio   TEXT,
  wav_audio   TEXT,
  analysis    TEXT,
  deleted     INTEGER NOT NULL DEFAULT 0,
  created_at  TEXT NOT NULL,
  updated_at  TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_records_created ON records(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_records_status ON records(status);

CREATE TABLE IF NOT EXISTS tasks (
  id          TEXT PRIMARY KEY,
  record_id   TEXT NOT NULL,
  type        TEXT NOT NULL,           -- transcribe / video_analyze
  status      TEXT NOT NULL DEFAULT 'pending',
  progress    INTEGER NOT NULL DEFAULT 0,
  result      TEXT,
  error       TEXT,
  created_at  TEXT NOT NULL,
  updated_at  TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_tasks_status ON tasks(status);
CREATE INDEX IF NOT EXISTS idx_tasks_record ON tasks(record_id);
"""


def init_db() -> None:
    """建目录 + 建表 + 设 WAL（持久化到数据库文件）。"""
    config.DATA_DIR.mkdir(parents=True, exist_ok=True)
    config.AUDIO_DIR.mkdir(parents=True, exist_ok=True)
    config.WAV_DIR.mkdir(parents=True, exist_ok=True)
    with connect() as conn:
        conn.execute("PRAGMA journal_mode=WAL")
        conn.executescript(SCHEMA)
        _migrate(conn)


def _migrate(conn) -> None:
    """增量迁移：老库补列（CREATE TABLE IF NOT EXISTS 不会给已存在的表加列）。"""
    cols = {row["name"] for row in conn.execute("PRAGMA table_info(records)")}
    if "tags" not in cols:
        conn.execute("ALTER TABLE records ADD COLUMN tags TEXT")
    _migrate_notes(conn)


def _migrate_notes(conn) -> None:
    """note 单条字符串 → JSON 数组 [{id,text,time}]；空/null 不动（不生成空 NoteEntry）。"""
    rows = conn.execute(
        "SELECT id, note, updated_at FROM records WHERE note IS NOT NULL AND note != ''"
    ).fetchall()
    for row in rows:
        note = row["note"]
        try:
            if isinstance(json.loads(note), list):
                continue  # 已是数组，跳过
        except Exception:
            pass
        entry = {"id": "note_" + uuid.uuid4().hex, "text": note, "time": row["updated_at"]}
        conn.execute(
            "UPDATE records SET note = ? WHERE id = ?",
            (json.dumps([entry], ensure_ascii=False), row["id"]),
        )


@contextmanager
def connect():
    """每次操作一个独立连接（WAL 下并发安全），成功提交、退出关闭。"""
    conn = sqlite3.connect(str(config.DB_PATH), timeout=5)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA busy_timeout=5000")
    conn.execute("PRAGMA synchronous=NORMAL")
    try:
        yield conn
        conn.commit()
    finally:
        conn.close()
