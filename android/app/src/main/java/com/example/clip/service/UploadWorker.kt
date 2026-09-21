package com.example.clip.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.clip.ClipApp
import com.example.clip.data.api.ApiService
import com.example.clip.data.api.CreateRecordRequest
import com.example.clip.data.api.NoteDto
import com.example.clip.data.api.PatchRecordRequest
import com.example.clip.data.api.RecordDto
import com.example.clip.data.api.toRecord
import com.example.clip.data.token.TokenGate
import com.example.clip.domain.model.QueueItem
import com.example.clip.domain.model.Record
import com.example.clip.domain.model.RecordType
import com.example.clip.domain.model.SyncStatus
import com.example.clip.domain.repository.RecordRepository
import com.example.clip.ui.overlay.OverlayState
import com.example.clip.util.TimeUtil
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import java.io.File

/**
 * 单次上传尝试（WorkManager）。UPLOAD（voice transcribe / text·video createRecord）或 PATCH（note）。
 * 失败走退避计数（1s→2s→…→60s，20 次到顶 FAILED），由 [UploadQueue] 负责重新调度。
 */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as ClipApp
        val repo = app.repository
        val api = app.api
        val localId = inputData.getString(KEY_LOCAL_ID) ?: return Result.success()

        // 兜底：暂停超 5 分钟自动恢复（防 Doze 死锁）
        TokenGate.resumeIfExpired()

        val record = repo.get(localId) ?: return Result.success()
        val queue = repo.getQueue(localId) ?: return Result.success()

        // 401 暂停：发探测。token 恢复 → 放行继续真正上传；仍失效/网络错 → retry（不消耗 attempts）
        if (TokenGate.isPaused.value) {
            val probeOk = try {
                api.records(1, 0).ok
            } catch (e: Exception) {
                false
            }
            if (!probeOk) return Result.retry()
        }

        // 20 次上限（防御；正常由 retry() 触发 FAILED）
        if (queue.attempts >= MAX_ATTEMPTS) {
            repo.update(record.copy(syncStatus = SyncStatus.FAILED, updatedAt = TimeUtil.nowIso()))
            repo.updateQueue(localId, queue.attempts, queue.nextAttemptAt, STATUS_FAILED)
            if (record.type == RecordType.VOICE) OverlayState.onVoiceFailed()
            else OverlayState.onSubmitFailed()
            return Result.failure()
        }

        return try {
            when (queue.operation) {
                OP_UPLOAD -> doUpload(app, repo, api, record)
                OP_PATCH -> doPatch(repo, api, record)
                else -> Result.success()
            }
        } catch (e: HttpException) {
            if (e.code() == 401) {
                TokenGate.pause()
                Result.retry()
            } else if (e.code() in 400..499) {
                // 客户端错误（如 400 INVALID_PAYLOAD）：永久失败，不重试，球及时回状态
                repo.update(record.copy(syncStatus = SyncStatus.FAILED, updatedAt = TimeUtil.nowIso()))
                repo.updateQueue(localId, queue.attempts, null, STATUS_FAILED)
                if (record.type == RecordType.VOICE) OverlayState.onVoiceFailed()
                else OverlayState.onSubmitFailed()
                Result.failure()
            } else {
                retry(repo, record, queue.attempts)
            }
        } catch (e: Exception) {
            retry(repo, record, queue.attempts)
        }
    }

    private suspend fun doUpload(app: ClipApp, repo: RecordRepository, api: ApiService, record: Record): Result {
        val dto = when (record.type) {
            RecordType.VOICE -> uploadVoice(api, repo, record)
            RecordType.TEXT, RecordType.VIDEO -> api.createRecord(
                CreateRecordRequest(
                    localId = record.localId,
                    type = record.type.name.lowercase(),
                    content = record.content,
                    title = record.title,
                    url = record.url,
                    platform = record.platform,
                    tags = record.tags
                )
            ).data ?: throw IllegalStateException("createRecord 无 data")
        }

        val updated = dto.toRecord(record, synced = true)
        repo.update(updated)
        repo.deleteQueue(record.localId)

        // 本地有备注 → 上传成功后补 PATCH 追加（覆盖"上传前补备注"的场景）
        if (record.notes.isNotEmpty()) {
            repo.insertQueue(
                QueueItem(
                    recordLocalId = record.localId,
                    operation = OP_PATCH,
                    payload = null,
                    status = STATUS_PENDING,
                    attempts = 0,
                    nextAttemptAt = TimeUtil.nowIso()
                )
            )
            UploadQueue.scheduleNow(app, record.localId)
        }

        // voice 上传成功 → 起转写轮询；文本/视频上传成功 → 闪 done（准确状态：后端确认成功才闪）
        if (updated.type == RecordType.VOICE && updated.taskId != null) {
            SyncService.startPolling(app, record.localId)
        } else if (updated.type == RecordType.TEXT || updated.type == RecordType.VIDEO) {
            OverlayState.onSubmitDone(updated.type)
        }
        return Result.success()
    }

    private suspend fun doPatch(repo: RecordRepository, api: ApiService, record: Record): Result {
        val serverId = record.serverId
        if (serverId == null) {
            // 还没 serverId（UPLOAD 未完成）：备注已存本地，等 UPLOAD 成功后再补
            repo.deleteQueue(record.localId)
            return Result.success()
        }
        // 逐条 appendNote（后端按 id 幂等去重，重试安全）
        record.notes.forEach { note ->
            api.patchRecord(serverId, PatchRecordRequest(appendNote = NoteDto(note.id, note.text, note.time)))
        }
        repo.deleteQueue(record.localId)
        return Result.success()
    }

    private suspend fun uploadVoice(api: ApiService, repo: RecordRepository, record: Record): RecordDto {
        val audioPath = repo.getAudioPath(record.localId)
            ?: throw IllegalStateException("音频文件缺失")
        val file = File(audioPath)
        val part = MultipartBody.Part.createFormData(
            "audio", file.name, file.asRequestBody("audio/ogg".toMediaTypeOrNull())
        )
        val localIdBody = record.localId.toRequestBody("text/plain".toMediaTypeOrNull())
        return api.transcribe(part, localIdBody).data ?: throw IllegalStateException("transcribe 无 data")
    }

    private suspend fun retry(repo: RecordRepository, record: Record, attempts: Int): Result {
        val next = attempts + 1
        if (next >= MAX_ATTEMPTS) {
            repo.update(record.copy(syncStatus = SyncStatus.FAILED, updatedAt = TimeUtil.nowIso()))
            repo.updateQueue(record.localId, next, null, STATUS_FAILED)
            if (record.type == RecordType.VOICE) OverlayState.onVoiceFailed()
            else OverlayState.onSubmitFailed()
            return Result.failure()
        }
        val backoff = backoffMillis(next)
        repo.updateQueue(record.localId, next, TimeUtil.nowPlusMillis(backoff), STATUS_PENDING)
        return Result.failure()  // UploadQueue 观察 PENDING 后重新调度
    }

    private fun backoffMillis(attempts: Int): Long {
        val exp = 1000L * (1L shl (attempts - 1).coerceIn(0, 30))
        return exp.coerceAtMost(60_000L)
    }

    companion object {
        const val KEY_LOCAL_ID = "local_id"
        const val OP_UPLOAD = "UPLOAD"
        const val OP_PATCH = "PATCH"
        const val STATUS_PENDING = "PENDING"
        const val STATUS_FAILED = "FAILED"
        const val MAX_ATTEMPTS = 20
    }
}
