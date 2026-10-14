package com.example.clip.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.clip.ClipApp
import com.example.clip.collector.AppLabels
import com.example.clip.collector.DeviceId
import com.example.clip.collector.UsageCollector
import com.example.clip.data.api.ApiService
import com.example.clip.data.api.CreateRecordRequest
import com.example.clip.data.api.NoteDto
import com.example.clip.data.api.PatchRecordRequest
import com.example.clip.data.api.RecordDto
import com.example.clip.data.api.UsageEventPayload
import com.example.clip.data.api.UsageReportRequest
import com.example.clip.data.api.toRecord
import com.example.clip.data.token.TokenGate
import com.example.clip.domain.model.QueueItem
import com.example.clip.domain.model.Record
import com.example.clip.domain.model.RecordType
import com.example.clip.domain.model.SyncStatus
import com.example.clip.domain.repository.RecordRepository
import com.example.clip.ui.overlay.OverlayState
import com.example.clip.util.FileLogger
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
        val subjectId = inputData.getString(KEY_LOCAL_ID) ?: return Result.success()

        // 兜底：暂停超 5 分钟自动恢复（防 Doze 死锁）
        TokenGate.resumeIfExpired()

        // 401 暂停：发探测。token 恢复 → 放行继续真正上传；仍失效/网络错 → retry（不消耗 attempts）
        // 放在分叉**之前** —— 两条路径共用同一个 token 门。
        if (TokenGate.isPaused.value) {
            val probeOk = try {
                api.records(1, 0).ok
            } catch (e: Exception) {
                false
            }
            if (!probeOk) return Result.retry()
        }

        // 🔴 **先读队列、再分叉。** usage 的 subject 在 `records` 表里**没有对应的行**，
        //    所以下面那句 `repo.get(subjectId)` 必须等确认过 kind 之后再走 ——
        //    顺序反了的话 usage 会在第一行就被 `?: return Result.success()` 静默吞掉。
        val queue = repo.getQueue(subjectId) ?: return Result.success()
        if (queue.kind == QueueItem.KIND_USAGE) {
            return doUsageUpload(app, repo, api, queue)
        }

        val record = repo.get(subjectId) ?: return Result.success()

        // 20 次上限（防御；正常由 retry() 触发 FAILED）
        if (queue.attempts >= MAX_ATTEMPTS) {
            repo.update(record.copy(syncStatus = SyncStatus.FAILED, updatedAt = TimeUtil.nowIso()))
            repo.updateQueue(subjectId, queue.attempts, queue.nextAttemptAt, STATUS_FAILED)
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
                repo.updateQueue(subjectId, queue.attempts, null, STATUS_FAILED)
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
        val dto = when {
            // 老语音链路：本地有 OGG → 传后端 /transcribe（电脑上的 faster-whisper）
            record.type == RecordType.VOICE && record.rawAudio != null -> uploadVoice(api, repo, record)
            // 其余一律走 POST /records。**本机 ASR 的 voice 记录走的就是这条** ——
            // 它 rawAudio == null（音频不出手机、不落盘），识别文本已经在 content 里，
            // 所以直接当普通记录发（后端 type=voice 与 text 同形：status=done、无任务）。
            else -> api.createRecord(
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

    // ── App 使用事件上报（kind=usage）──────────────────────────────
    //
    // 与记录上传是**两条路**：这里没有 record、不改 records 表、不闪悬浮球
    // （球反映的是"剪藏记录"的状态，使用时长跟它没关系）。
    // 共用的只有：同一个 `queue` 表、同一个调度循环、同一套退避与 401 门。

    /**
     * 发一批原始事件。
     *
     * 每次最多 [USAGE_BATCH_MAX] 条，**发完还有剩就自己再排一轮** —— 不在一个 worker 里循环发，
     * 免得单次执行卡太久（7 天回溯可能有一万多条）。剩下的由下一次执行接着发。
     */
    private suspend fun doUsageUpload(
        app: ClipApp,
        repo: RecordRepository,
        api: ApiService,
        queue: QueueItem,
    ): Result {
        val subject = queue.recordLocalId
        val dao = app.database.usageEventDao()

        val batch = dao.takePending(USAGE_BATCH_MAX)
        if (batch.isEmpty()) {
            // 没东西可发：队列行不该继续占着（它会在下次采集时按需重建）
            repo.deleteQueue(subject)
            return Result.success()
        }

        val ids = batch.map { it.id }
        // 标 SYNCING 再发。**任何失败路径都要把它们放回 PENDING** ——
        // 否则 `takePending`（只取 PENDING）永远再也捞不到它们。
        // （进程被杀那种情况由 Recovery 的 resetSyncing 兜底。）
        dao.updateStatus(ids, UsageCollector.SYNC_SYNCING)

        return try {
            val resp = api.reportUsage(
                UsageReportRequest(
                    deviceId = DeviceId.get(app),
                    events = batch.map {
                        UsageEventPayload(it.eventType, it.timestampMs, it.packageName)
                    },
                    labels = AppLabels.resolve(app, batch.mapNotNull { it.packageName }.toSet()),
                )
            )
            if (!resp.ok) throw IllegalStateException("usage 上报被拒：${resp.error?.code}")
            val d = resp.data ?: throw IllegalStateException("usage 上报无 data")

            dao.deleteByIds(ids)
            FileLogger.i(
                TAG,
                "usage 上报成功：${ids.size} 条 → accepted=${d.accepted} " +
                    "duplicates=${d.duplicates} unknown=${d.unknown}"
            )
            repo.deleteQueue(subject)

            if (dao.countPending() > 0) {
                repo.insertQueue(usageQueueItem(subject))
                UploadQueue.scheduleNow(app, subject, QueueItem.KIND_USAGE)
            }
            Result.success()
        } catch (e: HttpException) {
            if (e.code() == 401) {
                TokenGate.pause()
                dao.updateStatus(ids, UsageCollector.SYNC_PENDING)
                Result.retry()
            } else if (e.code() in 400..499) {
                // 客户端错误：重试没有意义。标 FAILED 而不是删 —— 它就不再被 takePending 捞出来，
                // 但留在库里能查（`SELECT * FROM usage_events WHERE sync_status='FAILED'`）。
                dao.updateStatus(ids, UsageCollector.SYNC_FAILED)
                // 🔴 队列行**删掉**，不能标 FAILED：标了它就一直在，而
                //    `enqueueUsageIfPending` 是"没有队列行才补一条" —— 行还在就永远不补，
                //    这批事件就永远发不出去了。
                repo.deleteQueue(subject)
                FileLogger.e(TAG, "usage 上报被拒（${e.code()}），${ids.size} 条标 FAILED", e)
                Result.failure()
            } else {
                dao.updateStatus(ids, UsageCollector.SYNC_PENDING)
                retryUsage(repo, subject, queue.attempts)
            }
        } catch (e: Exception) {
            dao.updateStatus(ids, UsageCollector.SYNC_PENDING)
            retryUsage(repo, subject, queue.attempts)
        }
    }

    /** usage 的退避：与记录**同一套**（同样的次数、同样的指数退避），只是不闪球。 */
    private suspend fun retryUsage(repo: RecordRepository, subject: String, attempts: Int): Result {
        val next = attempts + 1
        if (next >= MAX_ATTEMPTS) {
            // 同样：删队列行而不是标 FAILED，理由见 doUsageUpload 里的说明。
            // 事件本身仍是 PENDING，下次采集 / 回前台时会重新排队再试。
            repo.deleteQueue(subject)
            FileLogger.w(TAG, "usage 上报连续失败 $next 次，放弃这一轮（事件留在本地，会重新排队）")
            return Result.failure()
        }
        repo.updateQueue(subject, next, TimeUtil.nowPlusMillis(backoffMillis(next)), STATUS_PENDING)
        return Result.failure()
    }

    private fun usageQueueItem(subject: String) = QueueItem(
        recordLocalId = subject,
        operation = OP_UPLOAD,
        payload = null,
        status = STATUS_PENDING,
        attempts = 0,
        nextAttemptAt = TimeUtil.nowIso(),
        kind = QueueItem.KIND_USAGE,
    )

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
        private const val TAG = "UploadWorker"

        const val KEY_LOCAL_ID = "local_id"
        const val OP_UPLOAD = "UPLOAD"
        const val OP_PATCH = "PATCH"
        const val STATUS_PENDING = "PENDING"
        const val STATUS_FAILED = "FAILED"
        const val MAX_ATTEMPTS = 20

        /**
         * 一次 usage 上报最多带多少条事件。
         *
         * 2000 × 约 90 字节 ≈ **180 KB/请求**。不设上限的话，首次 7 天回溯可能一次攒出
         * 一万多条（约 1 MB 的 body），弱网下这一发会拖很久，失败还要整批重来。
         */
        const val USAGE_BATCH_MAX = 2000
    }
}
