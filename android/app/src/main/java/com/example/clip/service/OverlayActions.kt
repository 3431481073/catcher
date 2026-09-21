package com.example.clip.service

import com.example.clip.ClipApp
import com.example.clip.domain.model.NoteEntry
import com.example.clip.domain.model.QueueItem
import com.example.clip.domain.model.Record
import com.example.clip.domain.model.RecordStatus
import com.example.clip.domain.model.RecordType
import com.example.clip.domain.model.SyncStatus
import com.example.clip.util.Ids
import com.example.clip.util.TimeUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * overlay 面板的提交动作：落 Room + 上传队列 + 调度，与 MainViewModel 写路径一致。
 * note 不随 POST 上传（后端 createRecord 无 note 字段），靠 UploadWorker「上传成功后补 PATCH」既有逻辑。
 */
object OverlayActions {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun submitText(app: ClipApp, title: String?, body: String, tags: List<String> = emptyList()) {
        val content = body.trim().takeIf { it.isNotBlank() }
        val trimmedTitle = title?.trim()?.takeIf { it.isNotBlank() }
        if (content == null && trimmedTitle == null) return
        scope.launch {
            val now = TimeUtil.nowIso()
            val localId = Ids.newLocalId()
            val record = Record(
                localId = localId, serverId = null, type = RecordType.TEXT,
                title = trimmedTitle,
                content = content, url = null, platform = null,
                tags = tags,
                status = RecordStatus.DONE, error = null,
                taskId = null, taskStatus = null, queuePosition = 0,
                syncStatus = SyncStatus.SYNCING, rawAudio = null,
                createdAt = now, updatedAt = now
            )
            app.repository.insert(record)
            app.repository.insertQueue(
                QueueItem(recordLocalId = localId, operation = "UPLOAD", payload = null, status = "PENDING", attempts = 0, nextAttemptAt = now)
            )
            UploadQueue.scheduleNow(app, localId)
        }
    }

    fun submitVideo(app: ClipApp, title: String?, url: String?, platform: String?, note: String?, tags: List<String> = emptyList()) {
        val p = platform?.trim()
        if (p.isNullOrBlank()) return
        scope.launch {
            val now = TimeUtil.nowIso()
            val localId = Ids.newLocalId()
            val record = Record(
                localId = localId, serverId = null, type = RecordType.VIDEO,
                title = title?.trim()?.takeIf { it.isNotBlank() },
                content = null,
                notes = note?.trim()?.takeIf { it.isNotBlank() }?.let { listOf(NoteEntry(Ids.newNoteId(), it, now)) } ?: emptyList(),
                url = url?.trim()?.takeIf { it.isNotBlank() },
                platform = p,
                tags = tags,
                status = RecordStatus.DONE, error = null,
                taskId = null, taskStatus = null, queuePosition = 0,
                syncStatus = SyncStatus.SYNCING, rawAudio = null,
                createdAt = now, updatedAt = now
            )
            app.repository.insert(record)
            app.repository.insertQueue(
                QueueItem(recordLocalId = localId, operation = "UPLOAD", payload = null, status = "PENDING", attempts = 0, nextAttemptAt = now)
            )
            UploadQueue.scheduleNow(app, localId)
        }
    }
}
