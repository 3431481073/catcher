package com.example.clip.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.example.clip.util.FileLogger

/**
 * 常驻前台服务：承载全局悬浮球。
 * foregroundServiceType 用 dataSync（与 SyncService 一致）——specialUse 在国产 ROM 支持不全。
 * START_NOT_STICKY：被杀不自动重启（不做极致保活）；onTaskRemoved 只记日志不重启。
 */
class FloatingBallService : Service() {

    private var window: FloatingBallWindow? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        FileLogger.i(TAG, "onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        FileLogger.i(
            TAG,
            "onStartCommand overlay=${Settings.canDrawOverlays(this)} notif=${NotificationManagerCompat.from(this).areNotificationsEnabled()}"
        )
        try {
            startForeground(NOTIF_ID, buildNotification())
            FileLogger.i(TAG, "startForeground ok")
        } catch (e: Exception) {
            FileLogger.e(TAG, "startForeground 失败", e)
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            if (window == null) window = FloatingBallWindow(this)
            window?.show()
            FileLogger.i(TAG, "悬浮球窗口已挂载")
        } catch (e: Exception) {
            FileLogger.e(TAG, "悬浮球窗口挂载失败", e)
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        FileLogger.i(TAG, "onTaskRemoved（不重启）")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        FileLogger.i(TAG, "onDestroy")
        isRunning = false
        window?.remove()
        window = null
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "悬浮球", NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) } // LOW 默认无声音无震动
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("剪藏")
            .setContentText("悬浮球运行中")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "FloatingBallService"
        private const val CHANNEL_ID = "floating_ball"
        private const val NOTIF_ID = 1003

        @Volatile private var isRunning = false

        fun start(context: Context) {
            if (isRunning) {
                FileLogger.i(TAG, "start 被忽略：已在运行")
                return
            }
            context.startForegroundService(
                Intent(context, FloatingBallService::class.java)
            )
        }
    }
}
