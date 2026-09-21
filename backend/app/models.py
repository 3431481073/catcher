"""records / tasks 数据访问。所有函数同步，由调用方决定是否跑在线程里。"""
import json
from datetime import datetime, timezone

from .db import connect


def _now() -> str:
    # 微秒精度，保证同秒内多条记录的 queue_position 顺序正确
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ")


# ---------- records ----------

def create_record(record_id, local_id, type, status, raw_audio=None,
                  title=None, content=None, note=None, url=None, platform=None,
                  tags=None):
    now = _now()
    tags_json = json.dumps(tags, ensure_ascii=False) if tags else None
    with connect() as conn:
        conn.execute(
            "INSERT INTO records (id, local_id, type, title, content, note, url, platform, tags,"
            " status, raw_audio, deleted, created_at, updated_at)"
            " VALUES (?,?,?,?,?,?,?,?,?,?,?,0,?,?)",
            (record_id, local_id, type, title, content, note, url, platform, tags_json,
             status, raw_audio, now, now),
        )
    return get_record_by_id(record_id)


def get_record_by_id(record_id):
    with connect() as conn:
        row = conn.execute("SELECT * FROM records WHERE id = ?", (record_id,)).fetchone()
    return dict(row) if row else None


def get_record_by_local_id(local_id):
    with connect() as conn:
        row = conn.execute(
            "SELECT * FROM records WHERE local_id = ?", (local_id,)
        ).fetchone()
    return dict(row) if row else None


def list_records(limit=100, offset=0):
    with connect() as conn:
        rows = conn.execute(
            "SELECT * FROM records WHERE deleted = 0 ORDER BY created_at DESC LIMIT ? OFFSET ?",
            (limit, offset),
        ).fetchall()
    return [dict(r) for r in rows]


def update_record(record_id, **fields):
    """fields 的键均为内部列名，非用户输入。"""
    fields["updated_at"] = _now()
    keys = ", ".join(f"{k} = ?" for k in fields)
    with connect() as conn:
        conn.execute(f"UPDATE records SET {keys} WHERE id = ?", (*fields.values(), record_id))


def append_note(record_id, note_id, text, time):
    """追加一条备注（按 id 幂等：已存在则跳过）。

    返回是否**真的追加了**：命中去重 → False，真追加 → True。
    上层据此决定要不要发 note_added 事件（重试命中时不能再发，否则外部程序会重复加工）。
    """
    record = get_record_by_id(record_id)
    notes = json.loads(record["note"]) if record["note"] else []
    if any(n.get("id") == note_id for n in notes):
        return False
    notes.append({"id": note_id, "text": text, "time": time})
    update_record(record_id, note=json.dumps(notes, ensure_ascii=False))
    return True


# ---------- tasks ----------

def create_task(task_id, record_id, type, status="pending"):
    now = _now()
    with connect() as conn:
        conn.execute(
            "INSERT INTO tasks (id, record_id, type, status, progress, created_at, updated_at)"
            " VALUES (?,?,?,?,0,?,?)",
            (task_id, record_id, type, status, now, now),
        )


def get_task(task_id):
    with connect() as conn:
        row = conn.execute("SELECT * FROM tasks WHERE id = ?", (task_id,)).fetchone()
    return dict(row) if row else None


def get_latest_task(record_id):
    with connect() as conn:
        row = conn.execute(
            "SELECT * FROM tasks WHERE record_id = ? ORDER BY created_at DESC LIMIT 1",
            (record_id,),
        ).fetchone()
    return dict(row) if row else None


def claim_next_pending_task():
    """原子地把最早一条 pending 任务置为 processing，返回该任务；无则 None。"""
    now = _now()
    with connect() as conn:
        row = conn.execute(
            "UPDATE tasks SET status='processing', updated_at=?"
            " WHERE id = (SELECT id FROM tasks WHERE status='pending'"
            " ORDER BY created_at ASC LIMIT 1)"
            " RETURNING id, record_id, type, created_at",
            (now,),
        ).fetchone()
    return dict(row) if row else None


def set_task_status(task_id, status, **fields):
    """fields 的键均为内部列名。"""
    fields["status"] = status
    fields["updated_at"] = _now()
    keys = ", ".join(f"{k} = ?" for k in fields)
    with connect() as conn:
        conn.execute(f"UPDATE tasks SET {keys} WHERE id = ?", (*fields.values(), task_id))


def reset_processing_to_pending():
    """自愈：进程重启后把遗留 processing 重置为 pending。返回重置条数。"""
    now = _now()
    with connect() as conn:
        cur = conn.execute(
            "UPDATE tasks SET status='pending', updated_at=? WHERE status='processing'",
            (now,),
        )
    return cur.rowcount


def count_pending_before(created_at):
    """pending 且 created_at <= 给定值的任务数（用于 1-based queue_position）。"""
    with connect() as conn:
        row = conn.execute(
            "SELECT COUNT(*) AS c FROM tasks WHERE status='pending' AND created_at <= ?",
            (created_at,),
        ).fetchone()
    return row["c"]


# ---------- DTO ----------

def build_record_dto(record):
    """由 record 行 + 最新 task 行组装 RecordDTO（dict）。"""
    task = get_latest_task(record["id"])
    task_id = task["id"] if task else None
    task_status = task["status"] if task else None

    queue_position = 0
    if task and task["status"] == "pending":
        queue_position = count_pending_before(task["created_at"])

    return {
        "record_id": record["id"],
        "local_id": record["local_id"],
        "type": record["type"],
        "title": record["title"],
        "content": record["content"],
        "url": record["url"],
        "platform": record["platform"],
        "tags": json.loads(record["tags"]) if record["tags"] else [],
        "note": json.loads(record["note"]) if record["note"] else [],
        "status": record["status"],
        "error": record["error"],
        "task_id": task_id,
        "task_status": task_status,
        "queue_position": queue_position,
        "created_at": record["created_at"],
        "updated_at": record["updated_at"],
    }
