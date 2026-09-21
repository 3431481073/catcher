package com.example.clip.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 引导页权限状态 + 跳转封装。状态由 MainActivity.onResume 调 [refresh] 刷新。
 * 悬浮窗是"必过"门槛；通知是"推荐"，不阻塞进入。
 */
object Permissions {

    /** 悬浮窗（SYSTEM_ALERT_WINDOW）：必过。 */
    val overlay = MutableStateFlow(false)

    /** 通知（POST_NOTIFICATIONS）：推荐；API<33 恒 true。 */
    val notification = MutableStateFlow(true)

    fun refresh(context: Context) {
        overlay.value = Settings.canDrawOverlays(context)
        notification.value = isNotificationEnabled(context)
    }

    fun isNotificationEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun overlayIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
}
