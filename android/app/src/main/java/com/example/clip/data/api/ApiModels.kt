package com.example.clip.data.api

import com.google.gson.annotations.SerializedName

/** 统一响应 { ok, data, error } */
data class ApiResponse<T>(
    val ok: Boolean,
    val data: T?,
    val error: ApiError?
)

data class ApiError(
    val code: String?,
    val message: String?
)

data class HealthDto(
    val status: String?
)

/** 后端备注条目（蛇形字段用 @SerializedName 映射）。 */
data class NoteDto(
    val id: String,
    val text: String,
    val time: String
)

/** 后端 RecordDTO（字段蛇形，用 @SerializedName 映射） */
data class RecordDto(
    @SerializedName("record_id") val recordId: String,
    @SerializedName("local_id") val localId: String,
    val type: String,             // voice / video / text（小写）
    val title: String?,
    val content: String?,
    val note: List<NoteDto>? = null,
    val url: String?,
    val platform: String?,
    val tags: List<String>? = null,
    val status: String,           // pending / processing / done / failed（小写）
    val error: String?,
    @SerializedName("task_id") val taskId: String?,
    @SerializedName("task_status") val taskStatus: String?,  // pending/processing/done/failed/skipped/null
    @SerializedName("queue_position") val queuePosition: Int,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("updated_at") val updatedAt: String
)

/** POST /records 请求体 */
data class CreateRecordRequest(
    @SerializedName("local_id") val localId: String,
    val type: String,             // "text" / "video"（小写）
    val content: String? = null,
    val title: String? = null,
    val url: String? = null,
    val platform: String? = null,
    val tags: List<String> = emptyList()
)

/** PATCH /records/{id} 请求体：追加一条备注（按 id 幂等）。 */
data class PatchRecordRequest(
    @SerializedName("append_note") val appendNote: NoteDto? = null
)
