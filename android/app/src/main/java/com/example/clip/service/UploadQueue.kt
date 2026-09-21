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

    /** 立即投递（写操作后 / 补 PATCH 后），expedited 减少首传延迟。 */
    fun scheduleNow(app: ClipApp, localId: String) {
        enqueue(app, localId)
    }

    private suspend fun schedule(items: List<QueueItem>, force: Boolean) {
        val ctx = appContext ?: return
        val now = TimeUtil.nowMillis()
        items.forEach { item ->
            val dueAt = item.nextAttemptAt?.let { TimeUtil.parseMillis(it) } ?: 0L
            if (force || dueAt <= now) {
                enqueue(ctx, item.recordLocalId)
            } else {
                val delayMs = dueAt - now
                scope.launch {
                    delay(delayMs)
                    enqueue(ctx, item.recordLocalId)
                }
            }
        }
    }

    private fun enqueue(ctx: Context, localId: String) {
        val request = OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(workDataOf(UploadWorker.KEY_LOCAL_ID to localId))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            "upload_$localId",
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
