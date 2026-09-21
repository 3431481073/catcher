"""DTO 定义（文档性）。实际返回 dict，见 models.build_record_dto。"""
from typing import Literal, Optional

from pydantic import BaseModel


class NoteDTO(BaseModel):
    id: str
    text: str
    time: str


class RecordDTO(BaseModel):
    record_id: str
    local_id: str
    type: str                    # voice / video / text
    title: Optional[str] = None
    content: Optional[str] = None
    note: list[NoteDTO] = []     # 备注数组（追加式，带时间戳）
    url: Optional[str] = None
    platform: Optional[str] = None
    tags: list[str] = []         # 标签名字符串列表
    status: str                  # pending / processing / done / failed
    error: Optional[str] = None
    task_id: Optional[str] = None
    task_status: Optional[str] = None  # pending/processing/done/failed/skipped/null
    queue_position: int = 0
    created_at: str
    updated_at: str


class CreateRecordRequest(BaseModel):
    local_id: str
    type: Literal["text", "video"]
    content: Optional[str] = None
    title: Optional[str] = None
    url: Optional[str] = None
    platform: Optional[str] = None
    tags: list[str] = []


class PatchRecordRequest(BaseModel):
    append_note: Optional[NoteDTO] = None   # 追加一条备注（按 id 幂等）
