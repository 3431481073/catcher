package com.example.clip.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.clip.ClipApp
import com.example.clip.domain.model.QueueItem
import com.example.clip.util.TimeUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 上传队列调度器：观察 queue 表 PENDING 任务，到期的投递 UploadWorker（expedited + 网络约束）。
 * 网络恢复时立即重投（不等退避到期）。进程重启后由 [Recovery] 重新触发。
 */
object UploadQueue {

    /**
     * App 使用事件上报的**固定主体 id**。
     *
     * 全部事件共用**一个**队列行（不是"一批一行"）：一批一行就要在 `payload` 里存 id 列表、
     * 要处理"并发两批"、还要处理"队列行比事件多"。固定一行 + 每次取一批（有剩就再排一轮）
     * 状态少得多，而且 `ExistingWorkPolicy.KEEP` 天然防重入。
     */
    const val USAGE_SUBJECT = "usage"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var appContext: Context? = null

    fun start(app: ClipApp) {
        appContext = app.applicationContext
        scope.launch {
            app.repository.observePendingQueue().collect { items ->
                schedule(items, force = false)
            }
        }
        registerNetworkCallback(app)
    }

    /**
     * App 使用事件：**有 pending 就确保队列行存在并投递**，没有就什么都不做。幂等。
     *
     * 队列行只在缺失时才插 —— 已经有了就别动，否则 `REPLACE` 会把 `attempts` 清零，
     * 正在退避的那一条会被无限重置。
     */
    suspend fun enqueueUsageIfPending(app: ClipApp) {
        val dao = app.database.usageEventDao()
        if (dao.countPending() == 0) return
        val repo = app.repository
        if (repo.getQueue(USAGE_SUBJECT) == null) {
            repo.insertQueue(
                QueueItem(
                    recordLocalId = USAGE_SUBJECT,
                    operation = UploadWorker.OP_UPLOAD,
                    payload = null,
                    status = UploadWorker.STATUS_PENDING,
                    attempts = 0,
                    nextAttemptAt = TimeUtil.nowIso(),
                    kind = QueueItem.KIND_USAGE,
                )
            )
        }
        scheduleNow(app, USAGE_SUBJECT, QueueItem.KIND_USAGE)
    }

    /** 立即投递（写操作后 / 补 PATCH 后），expedited 减少首传延迟。 */
    fun scheduleNow(app: ClipApp, localId: String, kind: String = QueueItem.KIND_RECORD) {
        enqueue(app, localId, kind)
    }

    private suspend fun schedule(items: List<QueueItem>, force: Boolean) {
        val ctx = appContext ?: return
        val now = TimeUtil.nowMillis()
        items.forEach { item ->
            val dueAt = item.nextAttemptAt?.let { TimeUtil.parseMillis(it) } ?: 0L
            if (force || dueAt <= now) {
                enqueue(ctx, item.recordLocalId, item.kind)
            } else {
                val delayMs = dueAt - now
                scope.launch {
                    delay(delayMs)
                    enqueue(ctx, item.recordLocalId, item.kind)
                }
            }
        }
    }

    /**
     * 唯一工作名带上 kind。
     *
     * 记录的 id 是 `loc_...`、usage 的是固定串 `usage`，本来也不会撞；带上 kind 是为了
     * **在 WorkManager 的列表里一眼看出这条是谁的**，而且将来若有别的 kind 用了同一个 id，
     * 名字不同能避免互相 KEEP 掉。
     */
    private fun enqueue(ctx: Context, localId: String, kind: String) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(workDataOf(UploadWorker.KEY_LOCAL_ID to localId))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "upload_${kind}_$localId",
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    private fun registerNetworkCallback(app: ClipApp) {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch {
                    val pending = app.repository.getPendingQueue()
                    // 网络恢复 → 重置退避时间，立即重投
                    pending.forEach { item ->
                        app.repository.updateQueue(
                            item.recordLocalId, item.attempts, TimeUtil.nowIso(), "PENDING"
                        )
                    }
                    schedule(pending, force = true)
                }
            }
        })
    }
}
