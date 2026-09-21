package com.example.clip.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.clip.ClipApp
import com.example.clip.data.api.toRecord
import com.example.clip.domain.model.Record
import com.example.clip.domain.model.TaskStatus
import com.example.clip.recorder.RecordingController
import com.example.clip.recorder.VoiceDestination
import com.example.clip.ui.overlay.OverlayState
import com.example.clip.util.ClipboardWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 转写轮询前台服务（foregroundServiceType=dataSync）。只做 voice 轮询，上传由 UploadWorker 负责。
 * 轮询失败不再标 failed：保留 processing，等自愈/前台补查重新挂。
 */
class SyncService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 首行 startForeground
        startForeground(NOTIF_ID, buildSyncNotification("转写中…"))
        val localId = intent?.getStringExtra(EXTRA_LOCAL_ID)
        if (localId == null) {
            finish()
            return START_NOT_STICKY
        }
        scope.launch { poll(localId) }
        return START_NOT_STICKY
    }

    private suspend fun poll(localId: String) {
        val app = application as ClipApp
        val repo = app.repository
        val api = app.api

        var record = repo.get(localId) ?: run { finish(); return }
        val taskId = record.taskId ?: run { finish(); return }

        while (true) {
            val resp = try {
                api.task(taskId)
            } catch (e: Exception) {
                // 轮询失败（网络/超时）：保留 processing，等自愈/补查重挂
                finish(); return
            }
            val dto = resp.data ?: run { finish(); return }
            record = dto.toRecord(record, synced = true)
            repo.update(record)
            when (record.taskStatus) {
                TaskStatus.DONE -> { onDone(app, record); OverlayState.onVoiceDone(); finish(); return }
                TaskStatus.FAILED, TaskStatus.SKIPPED -> { OverlayState.onVoiceFailed(); finish(); return }
                else -> delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun onDone(app: ClipApp, record: Record) {
        val content = record.content
        if (content.isNullOrBlank()) return
        // BODY/NOTE：转写结果由 overlay 面板 observe 后填入输入框，不写剪贴板/通知
        if (RecordingController.recordingDestination.value != VoiceDestination.CLIPBOARD) return
        if (isForeground()) {
            ClipboardWriter.write(app, content)
        } else {
            NotificationHelper.postDone(app, record.localId, content)
        }
    }

    private fun isForeground(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    private fun finish() {
        mainHandler.post {
            if (Build.VERSION.SDK_INT >= 33) stopForeground(STOP_FOREGROUND_REMOVE) else stopForeground(true)
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildSyncNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "同步", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("剪藏")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
    }

    companion object {
        private const val EXTRA_LOCAL_ID = "extra_local_id"
        private const val NOTIF_ID = 1002
        private const val CHANNEL_ID = "sync"
        private const val POLL_INTERVAL_MS = 2000L

        fun startPolling(context: Context, localId: String) {
            context.startForegroundService(
                Intent(context, SyncService::class.java).putExtra(EXTRA_LOCAL_ID, localId)
            )
        }
    }
}
