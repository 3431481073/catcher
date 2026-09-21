"""records 接口：列表 / 新建（文本、视频）/ 单个获取 / 更新 note。"""
import uuid

from fastapi import APIRouter, Depends, Query

from .. import events, models
from ..auth import verify_token
from ..errors import AppError, ok
from ..schemas import CreateRecordRequest, PatchRecordRequest

router = APIRouter(dependencies=[Depends(verify_token)])


@router.get("/api/v1/records")
async def list_records(
    limit: int = Query(100, ge=1, le=500),
    offset: int = Query(0, ge=0),
):
    records = models.list_records(limit=limit, offset=offset)
    return ok([models.build_record_dto(r) for r in records])


@router.post("/api/v1/records")
async def create_record(req: CreateRecordRequest):
    if not req.local_id or not req.local_id.strip():
        raise AppError("INVALID_PAYLOAD", "local_id 不能为空", 400)
    if req.type == "text" and not (req.content or "").strip() and not (req.title or "").strip():
        raise AppError("INVALID_PAYLOAD", "文本标题或内容不能为空", 400)
    if req.type == "video" and not (req.platform or "").strip():
        raise AppError("INVALID_PAYLOAD", "视频平台不能为空", 400)

    # local_id 幂等：命中返回真实状态，不重开任务（不变量 3）
    existing = models.get_record_by_local_id(req.local_id)
    if existing:
        return ok(models.build_record_dto(existing))

    record_id = f"rec_{uuid.uuid4().hex}"
    record = models.create_record(
        record_id=record_id,
        local_id=req.local_id,
        type=req.type,
        status="done",  # 文本/视频：record 直接 done，无耗时任务
        title=req.title,
        content=req.content,
        url=req.url,
        platform=req.platform,
        tags=req.tags,
    )
    if req.type == "video":
        # 视频分析本期不做，占位一条 skipped 任务（不变量 9）
        models.create_task(
            task_id=f"task_{uuid.uuid4().hex}",
            record_id=record_id,
            type="video_analyze",
            status="skipped",
        )
    # 事件只发在**真实创建**这条路径上：上面 local_id 幂等的提前 return 天然不发（重试不补发）
    events.emit(record_id, "record_created")
    return ok(models.build_record_dto(record))


@router.get("/api/v1/records/{record_id}")
async def get_record(record_id: str):
    record = models.get_record_by_id(record_id)
    if not record or record["deleted"]:
        raise AppError("NOT_FOUND", "记录不存在", 404)
    return ok(models.build_record_dto(record))


@router.patch("/api/v1/records/{record_id}")
async def patch_record(record_id: str, req: PatchRecordRequest):
    record = models.get_record_by_id(record_id)
    if not record or record["deleted"]:
        raise AppError("NOT_FOUND", "记录不存在", 404)
    if req.append_note is not None:
        # 只在实际追加成功时发事件：PATCH 按 note id 幂等，手机端断网重试会重发同一个
        # append_note，命中去重时不发，否则外部程序会对同一条备注重复加工。
        if models.append_note(
            record_id, req.append_note.id, req.append_note.text, req.append_note.time
        ):
            events.emit(record_id, "note_added")
    return ok(models.build_record_dto(models.get_record_by_id(record_id)))
