"""GET /tasks/{id}：返回完整 RecordDTO。"""
from fastapi import APIRouter, Depends

from .. import models
from ..auth import verify_token
from ..errors import AppError, ok

router = APIRouter(dependencies=[Depends(verify_token)])


@router.get("/api/v1/tasks/{task_id}")
async def get_task(task_id: str):
    task = models.get_task(task_id)
    if not task:
        raise AppError("NOT_FOUND", "任务不存在", 404)
    record = models.get_record_by_id(task["record_id"])
    if not record:
        raise AppError("NOT_FOUND", "记录不存在", 404)
    return ok(models.build_record_dto(record))
