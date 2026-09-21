package com.example.clip.util

import android.content.ClipboardManager
import android.content.Context

/**
 * 剪贴板缓存：overlay 窗口读不到系统剪贴板（Android 10+ 限制），
 * 在 MainActivity.onResume（前台有焦点）+ OnPrimaryClipChangedListener 时读剪贴板存内存。
 * paste 时先直接读，失败/空再从缓存读，都没有则静默返回。
 */
object ClipboardCache {

    @Volatile private var cachedText: String? = null
    @Volatile private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        refresh(cm)
        cm.addPrimaryClipChangedListener { refresh(cm) }
    }

    private fun refresh(cm: ClipboardManager) {
        try {
            cachedText = cm.getPrimaryClip()?.getItemAt(0)?.text?.toString()
        } catch (_: Exception) {
            // 静默：无焦点时读剪贴板可能抛 SecurityException
        }
    }

    fun cachedText(): String? = cachedText
}
