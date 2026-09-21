package com.example.clip.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.clip.MainActivity

/**
 * 转写完成通知（后台场景）。点开拉起 MainActivity（前台），由它读 Room 写剪贴板。
 * 不用 BroadcastReceiver：Android 10+ 后台组件写剪贴板受限，Activity 前台才稳。
 */
object NotificationHelper {

    private const val CHANNEL_ID = "done"
    private const val CHANNEL_NAME = "剪藏完成"

    fun postDone(context: Context, localId: String, content: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
            )
        }

        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_CLIPBOARD_LOCAL_ID, localId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            context,
            localId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("转写完成")
            .setContentText(content)
            .setStyle(Notification.BigTextStyle().bigText(content))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()

        nm.notify(localId.hashCode(), notification)
    }
}
