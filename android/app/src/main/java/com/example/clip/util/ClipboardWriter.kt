package com.example.clip.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

object ClipboardWriter {
    fun write(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("剪藏", text))
    }
}
