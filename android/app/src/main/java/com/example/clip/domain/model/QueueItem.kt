package com.example.clip.domain.model

/** 上传队列项（domain 层）。一个记录同一时刻只有一项（UPLOAD 或 PATCH）。 */
data class QueueItem(
    /** 队列的"主体"：记录的 local_id，或 usage 那个固定主体 id（[KIND_USAGE] 见 `UploadQueue.USAGE_SUBJECT`）。 */
    val recordLocalId: String,
    val operation: String,      // UPLOAD / PATCH
    val payload: String?,       // PATCH 时为 note 内容
    val status: String,         // PENDING / FAILED
    val attempts: Int,
    val nextAttemptAt: String?, // 下次重试时间（ISO）；到期即投递
    /**
     * 这条队列项是"记录"还是"App 使用事件"。两种东西共用同一张 `queue` 表和同一个
     * 调度循环（[com.example.clip.service.UploadQueue]），**由 `UploadWorker` 在顶部靠它分叉**。
     *
     * 默认 `record` 是为了不动那一堆 `QueueItem(...)` 的现有调用点。
     */
    val kind: String = KIND_RECORD,
) {
    companion object {
        const val KIND_RECORD = "record"
        const val KIND_USAGE = "usage"
    }
}
