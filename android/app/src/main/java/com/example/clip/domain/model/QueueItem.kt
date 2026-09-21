package com.example.clip.domain.model

/** 上传队列项（domain 层）。一个 record 同一时刻只有一项（UPLOAD 或 PATCH）。 */
data class QueueItem(
    val recordLocalId: String,
    val operation: String,      // UPLOAD / PATCH
    val payload: String?,       // PATCH 时为 note 内容
    val status: String,         // PENDING / FAILED
    val attempts: Int,
    val nextAttemptAt: String?  // 下次重试时间（ISO）；到期即投递
)
