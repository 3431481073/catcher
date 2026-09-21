"""POST /transcribe：收 audio + local_id，立刻返回，幂等。"""
import uuid

from fastapi import APIRouter, Depends, File, Form, UploadFile

from .. import audio as audio_mod
from .. import config, events, models
from ..auth import verify_token
from ..errors import AppError, ok

router = APIRouter(dependencies=[Depends(verify_token)])


@router.post("/api/v1/transcribe")
async def transcribe(
    audio: UploadFile = File(...),
    local_id: str = Form(...),
):
    if not local_id or not local_id.strip():
        raise AppError("INVALID_PAYLOAD", "local_id 不能为空", 400)

    # 幂等：命中返回真实状态，不重开任务
    existing = models.get_record_by_local_id(local_id)
    if existing:
        return ok(models.build_record_dto(existing))

    content = await audio.read()
    if not content:
        raise AppError("INVALID_PAYLOAD", "音频为空", 400)
    if len(content) > config.MAX_AUDIO_BYTES:
        raise AppError("INVALID_PAYLOAD", "音频超过 50MB 上限", 400)

    record_id = f"rec_{uuid.uuid4().hex}"
    raw_path = audio_mod.save_audio(record_id, audio.filename or "audio.bin", content)

    record = models.create_record(
        record_id=record_id,
        local_id=local_id,
        type="voice",
        status="processing",  # 语音刚提交：record=processing
        raw_audio=raw_path,
    )
    models.create_task(
        task_id=f"task_{uuid.uuid4().hex}",
        record_id=record_id,
        type="transcribe",
        status="pending",
    )

    # 语音是本项目最主要的入口，和文本/视频一样发 record_created
    # （上面 local_id 幂等的提前 return 不发，重试不补发）
    events.emit(record_id, "record_created")

    # 立刻返回完整 RecordDTO
    return ok(models.build_record_dto(record))
