package com.example.clip.service

import com.example.clip.ClipApp
import com.example.clip.domain.model.QueueItem
import com.example.clip.util.FileLogger
import com.example.clip.util.TimeUtil

/**
 * 自愈/补查：扫未完成记录，重投上传队列 + 重挂转写轮询。幂等（KEEP 去重）。
 * App 启动时和回前台（ON_START）时各扫一次。
 */
object Recovery {

    private const val TAG = "Recovery"

    suspend fun scan(app: ClipApp) {
        val repo = app.repository

        // 0. App 使用事件的自愈 —— 两件事，都是"进程在错误的时刻死了"留下的：
        val dao = app.database.usageEventDao()
        //   ① 标了 SYNCING 还没收到响应就被杀 → 那些行 `takePending` 永远捞不到（只取 PENDING），
        //      会**永久卡住**。放回 PENDING，重发是安全的（后端按唯一键去重）。
        val revived = dao.resetSyncing()
        if (revived > 0) FileLogger.i(TAG, "usage 自愈：$revived 条 SYNCING 复位为 PENDING")
        //   ② 有事件待发却没有队列行（放弃重试时会把队列行删掉）→ 补一条并投递。
        UploadQueue.enqueueUsageIfPending(app)

        // 1. 转写中（上传完成、有 task_id）→ 重挂轮询
        repo.getProcessingWithTask().forEach { record ->
            SyncService.startPolling(app, record.localId)
        }

        // 2. 未同步 → 确保 queue 项存在并投递
        repo.getUnsynced().forEach { record ->
            if (repo.getQueue(record.localId) == null) {
                repo.insertQueue(
                    QueueItem(
                        recordLocalId = record.localId,
                        operation = "UPLOAD",
                        payload = null,
                        status = "PENDING",
                        attempts = 0,
                        nextAttemptAt = TimeUtil.nowIso()
                    )
                )
            }
            UploadQueue.scheduleNow(app, record.localId)
        }
    }
}
