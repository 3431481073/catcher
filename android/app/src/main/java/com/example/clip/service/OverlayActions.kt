package com.example.clip.service

import com.example.clip.ClipApp
import com.example.clip.domain.model.NoteEntry
import com.example.clip.domain.model.QueueItem
import com.example.clip.domain.model.Record
import com.example.clip.domain.model.RecordStatus
import com.example.clip.domain.model.RecordType
import com.example.clip.domain.model.SyncStatus
import com.example.clip.util.ClipboardWriter
import com.example.clip.util.Ids
import com.example.clip.util.TimeUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    /**
     * 本机 ASR 识别完成 → 三件事一起做，**不问用户**（识别为空则整条不发，由调用方先判）。
     *
     * 1. 落本地 Room（type=VOICE，直接 done —— 识别已经在手机上做完了，没有后端任务）
     * 2. 排上传队列 → 由 [UploadWorker] 发 `POST /records`（type=voice, content=文本）
     * 3. 写系统剪贴板
     *
     * 与 [submitText] 同构，两点不同：type 是 VOICE；`rawAudio` 为 null ——
     * **音频不出手机、也不落盘**，UploadWorker 会据此走 createRecord 而不是 /transcribe。
     */
    fun submitLocalVoice(app: ClipApp, text: String) {
        val content = text.trim().takeIf { it.isNotBlank() } ?: return
        scope.launch {
            val now = TimeUtil.nowIso()
            val localId = Ids.newLocalId()
            val record = Record(
                localId = localId, serverId = null, type = RecordType.VOICE,
                title = null, content = content, url = null, platform = null,
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
            // 剪贴板放最后：它是"用户马上要用的那个"，越靠近用户按停止越新鲜
            withContext(Dispatchers.Main) { ClipboardWriter.write(app, content) }
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
