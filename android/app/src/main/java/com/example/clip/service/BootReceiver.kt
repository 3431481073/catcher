package com.example.clip.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

/** 开机自启：收 BOOT_COMPLETED，有悬浮窗权限才起球（无权限白起）。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Settings.canDrawOverlays(context)) {
            runCatching { FloatingBallService.start(context) }
        }
    }
}
