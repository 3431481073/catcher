package com.example.clip.service

import com.example.clip.ClipApp
import com.example.clip.domain.model.QueueItem
import com.example.clip.util.TimeUtil

/**
 * 自愈/补查：扫未完成记录，重投上传队列 + 重挂转写轮询。幂等（KEEP 去重）。
 * App 启动时和回前台（ON_START）时各扫一次。
 */
object Recovery {

    suspend fun scan(app: ClipApp) {
        val repo = app.repository

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
