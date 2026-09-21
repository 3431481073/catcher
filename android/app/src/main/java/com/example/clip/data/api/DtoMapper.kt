package com.example.clip.data.api

import com.example.clip.domain.model.NoteEntry
import com.example.clip.domain.model.Record
import com.example.clip.domain.model.RecordStatus
import com.example.clip.domain.model.RecordType
import com.example.clip.domain.model.SyncStatus
import com.example.clip.domain.model.TaskStatus

/**
 * 后端 DTO(小写) → domain(大写枚举)。解析失败兜底用旧值，防后端返回未知值崩溃。
 * 集中处理跨端枚举大小写映射，不再散落各处手写 valueOf（见 PROGRESS.md 教训）。
 *
 * 合并规则（统一）：数据字段（title/content/note/url/platform）DTO 为 null 时保留本地值。
 * 典型场景：视频 note 不在 POST /records 里（上传后 PATCH 补），POST 响应 note=null，
 * 若直接覆盖会把本地 note 抹掉 → 详情不显示、后续 PATCH 也读到 null。
 */
fun RecordDto.toRecord(previous: Record, synced: Boolean): Record = previous.copy(
    serverId = recordId,
    type = runCatching { RecordType.valueOf(type.uppercase()) }.getOrDefault(previous.type),
    title = title ?: previous.title,
    content = content ?: previous.content,
    notes = note?.map { NoteEntry(it.id, it.text, it.time) } ?: previous.notes,
    url = url ?: previous.url,
    platform = platform ?: previous.platform,
    tags = tags ?: previous.tags,
    status = runCatching { RecordStatus.valueOf(status.uppercase()) }.getOrDefault(previous.status),
    error = error,
    taskId = taskId,
    taskStatus = taskStatus?.uppercase()?.let { runCatching { TaskStatus.valueOf(it) }.getOrNull() },
    queuePosition = queuePosition,
    syncStatus = if (synced) SyncStatus.SYNCED else previous.syncStatus,
    updatedAt = updatedAt
)
