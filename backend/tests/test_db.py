"""DB 层单元测试。"""
from app import models


def test_create_and_get_record(db_env):
    r = models.create_record("rec_1", "loc_1", "voice", "processing")
    assert r["id"] == "rec_1"
    assert r["local_id"] == "loc_1"
    assert models.get_record_by_id("rec_1")["id"] == "rec_1"
    assert models.get_record_by_local_id("loc_1")["id"] == "rec_1"


def test_claim_and_reset(db_env):
    models.create_record("rec_1", "loc_1", "voice", "processing", raw_audio="/tmp/a.webm")
    models.create_task("task_1", "rec_1", "transcribe")

    t = models.claim_next_pending_task()
    assert t["id"] == "task_1"
    assert models.get_task("task_1")["status"] == "processing"

    # 自愈：processing → pending
    n = models.reset_processing_to_pending()
    assert n == 1
    assert models.get_task("task_1")["status"] == "pending"


def test_claim_excludes_types(db_env):
    """停用语音时 worker 传 exclude_types=('transcribe',)：跳过 transcribe，仍能领别的类型。"""
    models.create_record("rec_1", "loc_1", "voice", "processing", raw_audio="/tmp/a.webm")
    models.create_record("rec_2", "loc_2", "video", "done")
    models.create_task("task_t", "rec_1", "transcribe")          # 更早，但应被排除
    models.create_task("task_v", "rec_2", "video_analyze")

    t = models.claim_next_pending_task(exclude_types=("transcribe",))
    assert t["id"] == "task_v"
    # 被排除的那条必须原样留着（不是被标 skipped、也不是被吞掉）
    assert models.get_task("task_t")["status"] == "pending"

    # 没传 exclude_types 时行为不变，仍按 created_at 取最早
    models.set_task_status("task_v", "done")
    assert models.claim_next_pending_task()["id"] == "task_t"


def test_queue_position(db_env):
    models.create_record("rec_1", "loc_1", "voice", "processing")
    models.create_record("rec_2", "loc_2", "voice", "processing")
    models.create_task("task_1", "rec_1", "transcribe")
    models.create_task("task_2", "rec_2", "transcribe")

    dto1 = models.build_record_dto(models.get_record_by_id("rec_1"))
    dto2 = models.build_record_dto(models.get_record_by_id("rec_2"))
    assert dto1["queue_position"] == 1
    assert dto2["queue_position"] == 2


def test_dto_no_task(db_env):
    models.create_record("rec_1", "loc_1", "text", "done")
    dto = models.build_record_dto(models.get_record_by_id("rec_1"))
    assert dto["task_id"] is None
    assert dto["task_status"] is None
    assert dto["queue_position"] == 0
