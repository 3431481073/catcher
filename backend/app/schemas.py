"""DTO 定义（文档性）。实际返回 dict，见 models.build_record_dto。"""
from typing import Literal, Optional

from pydantic import BaseModel, ConfigDict, Field


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
    # voice = 手机本地 ASR 识别出来的语音记录（音频不出手机，只发文本）。
    # 它落进 records.py 的通用路径（status=done、无任务），与 text 同形；
    # content 非空由前端保证（识别为空就不建记录），后端不额外校验。
    type: Literal["text", "voice", "video"]
    content: Optional[str] = None
    title: Optional[str] = None
    url: Optional[str] = None
    platform: Optional[str] = None
    tags: list[str] = []


class PatchRecordRequest(BaseModel):
    append_note: Optional[NoteDTO] = None   # 追加一条备注（按 id 幂等）


# ── App 使用时长上报 ──────────────────────────────────────────
# 形状对应 android `UsageStatsManager.queryEvents` 吐的原始事件流。手机端**不做任何计算**，
# 配对/去重/覆盖全在后端 app/usage.py 里 —— 所以这里的字段就是事件的字段，一个不多。

class UsageEventItem(BaseModel):
    event_type: int                        # UsageEvents.Event 的常量值（见 usage.py 顶部）
    timestamp_ms: int                      # epoch 毫秒（UTC）
    package_name: Optional[str] = None     # 屏幕/锁屏事件没有包名


class UsageReportRequest(BaseModel):
    device_id: str                         # Android ID 的 SHA256 前 16 位
    events: list[UsageEventItem] = Field(max_length=20000)
    # {包名: 可读名}。手机端解析 App 名后一起带上（Android 11+ 需要 QUERY_ALL_PACKAGES）。
    # 每次把本批出现的包名都带上，不做"只传首次"的状态 —— 幂等，且省掉手机端一份映射表。
    labels: dict[str, str] = {}


# ── Lattice 远程转发的请求体（原样转发给 Lattice，形状见它的 dataApi.ts）───
# 字段名是 Lattice 的 camelCase：用 alias + populate_by_name，这样
# model_dump(by_alias=True) 出去的就是上游认的形状，不必在路由里手拼 dict。

class LatticeInputRequest(BaseModel):
    """往窗格写。text 与 keys 至少给一个（Lattice 会自己校验）。"""
    model_config = ConfigDict(populate_by_name=True)

    text: Optional[str] = None
    keys: Optional[list[str]] = None
    enter: Optional[bool] = None
    # 幂等去重：同一个 nonce 在 60 秒内重复到达只会执行一次（弱网重发用）
    client_nonce: Optional[str] = Field(default=None, alias="clientNonce")
    await_output_ms: Optional[int] = Field(default=None, alias="awaitOutputMs")


class LatticeAnswerRequest(BaseModel):
    """确认框的答案。语义化：answer（选择题）/ allow / deny。"""
    id: Optional[str] = None
    action: Optional[Literal["answer", "allow", "deny"]] = None
    answers: Optional[dict[str, str]] = None   # action=answer 时必填
    message: Optional[str] = None              # deny 的理由


class LatticeWatchRequest(BaseModel):
    """声明"我要应答"。不声明的话确认框直接弹在电脑上（fail-open）。"""
    answer: Optional[bool] = None
    seconds: Optional[int] = None              # Lattice 侧 clamp 到 5..3600，默认 60
