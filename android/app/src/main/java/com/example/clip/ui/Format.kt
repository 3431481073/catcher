package com.example.clip.ui

import com.example.clip.domain.model.Record
import com.example.clip.domain.model.RecordType

fun RecordType.displayName(): String = when (this) {
    RecordType.VOICE -> "语音"
    RecordType.VIDEO -> "视频"
    RecordType.TEXT -> "文本"
}

/** 记录摘要/首行统一优先级：title → content → url → platform → type。 */
fun Record.displayTitle(): String =
    title?.takeIf { it.isNotBlank() }
        ?: content?.takeIf { it.isNotBlank() }
        ?: url?.takeIf { it.isNotBlank() }
        ?: platform?.takeIf { it.isNotBlank() }
        ?: type.displayName()

/** M0 简单格式化：ISO8601Z → "yyyy-MM-dd HH:mm" */
fun formatTime(iso: String): String = runCatching {
    iso.removeSuffix("Z").replace("T", " ").take(16)
}.getOrDefault(iso)
