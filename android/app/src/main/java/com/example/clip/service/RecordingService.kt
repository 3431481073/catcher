package com.example.clip.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.example.clip.ClipApp
import com.example.clip.domain.model.QueueItem
import com.example.clip.domain.model.Record
import com.example.clip.domain.model.RecordStatus
import com.example.clip.domain.model.RecordType
import com.example.clip.domain.model.SyncStatus
import com.example.clip.recorder.RecordingController
import com.example.clip.util.Ids
import com.example.clip.util.TimeUtil
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.Executors

/**
 * 录音前台服务（foregroundServiceType=microphone）。
 * MediaRecorder 操作全部串行在一个单线程 executor 上；每 100ms 采样 maxAmplitude 发布到 RecordingController。
 * 结束时落 Room（records + blobs），再起 SyncService 上传。
 */
class RecordingService : Service() {

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var recorder: MediaRecorder? = null
    @Volatile private var outputFile: File? = null
    @Volatile private var localId: String? = null

    /** 振幅采样（主线程 100ms 循环），读 recorder.maxAmplitude。 */
    private val amplitudeSampler = object : Runnable {
        override fun run() {
            val r = recorder
            if (r != null) {
                val max = runCatching { r.maxAmplitude }.getOrDefault(0)
                RecordingController.updateAmplitude((max / 32767f).coerceIn(0f, 1f))
                mainHandler.postDelayed(this, 100L)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // 首行 startForeground（Android 12+ 5 秒限制）
                startForeground(NOTIF_ID, buildRecordingNotification())
                executor.execute { doStartRecording() }
            }
            ACTION_STOP -> executor.execute { doStopRecording() }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun doStartRecording() {
        val dir = File(filesDir, "audio").apply { mkdirs() }
        val id = Ids.newLocalId()
        localId = id
        val out = File(dir, "$id.ogg")
        outputFile = out
        try {
            recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.OGG)
                setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
                setAudioSamplingRate(16000)
                setAudioChannels(1)
                setAudioEncodingBitRate(32_000)
                setOutputFile(out.absolutePath)
                prepare()
                start()
            }
            // 通知 overlay 新语音记录的 localId + 开始振幅采样
            RecordingController.onVoiceRecordCreated(id)
            mainHandler.post(amplitudeSampler)
        } catch (e: Exception) {
            recorder?.runCatching { release() }
            recorder = null
            localId = null
            outputFile?.delete()
            outputFile = null
            mainHandler.removeCallbacks(amplitudeSampler)
            RecordingController.updateAmplitude(0f)
            finish()
        }
    }

    private fun doStopRecording() {
        mainHandler.removeCallbacks(amplitudeSampler)
        RecordingController.updateAmplitude(0f)
        val rec = recorder
        val file = outputFile
        val id = localId
        recorder = null
        outputFile = null
        localId = null

        if (rec == null || file == null || id == null) {
            finish()
            return
        }

        runCatching { rec.stop() }
        rec.release()

        try {
            runBlocking {
                val app = application as ClipApp
                val now = TimeUtil.nowIso()
                val record = Record(
                    localId = id,
                    serverId = null,
                    type = RecordType.VOICE,
                    title = null, content = null, url = null, platform = null,
                    status = RecordStatus.PROCESSING,
                    error = null,
                    taskId = null,
                    taskStatus = null,
                    queuePosition = 0,
                    syncStatus = SyncStatus.SYNCING,
                    rawAudio = file.absolutePath,
                    createdAt = now,
                    updatedAt = now
                )
                app.repository.insert(record)
                app.repository.insertQueue(
                    QueueItem(
                        recordLocalId = id,
                        operation = "UPLOAD",
                        payload = null,
                        status = "PENDING",
                        attempts = 0,
                        nextAttemptAt = now
                    )
                )
                UploadQueue.scheduleNow(app, id)
            }
        } finally {
            finish()
        }
    }

    private fun finish() {
        mainHandler.post {
            if (Build.VERSION.SDK_INT >= 33) stopForeground(STOP_FOREGROUND_REMOVE) else stopForeground(true)
            stopSelf()
        }
    }

    override fun onDestroy() {
        executor.shutdown()
        mainHandler.removeCallbacks(amplitudeSampler)
        recorder?.runCatching { stop(); release() }
        recorder = null
        super.onDestroy()
    }

    private fun buildRecordingNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "录音", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("正在录音")
            .setContentText("说完了点击悬浮球结束")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
    }

    companion object {
        private const val ACTION_START = "com.example.clip.recording.START"
        private const val ACTION_STOP = "com.example.clip.recording.STOP"
        private const val NOTIF_ID = 1001
        private const val CHANNEL_ID = "recording"

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, RecordingService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startForegroundService(
                Intent(context, RecordingService::class.java).setAction(ACTION_STOP)
            )
        }
    }
}
