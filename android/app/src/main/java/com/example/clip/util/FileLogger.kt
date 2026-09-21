package com.example.clip.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 关键路径日志：同时打 logcat 和落盘到 getExternalFilesDir(null)/crash.log。
 * 落盘是为了用户连不上 adb 时也能把崩溃点发回来。init 在 ClipApp.onCreate 调一次。
 */
object FileLogger {

    @Volatile private var file: File? = null

    fun init(context: Context) {
        if (file != null) return
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        file = File(dir, "crash.log")
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        write(tag, "INFO", msg, null)
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        Log.e(tag, msg, t)
        write(tag, "ERROR", msg, t)
    }

    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
        write(tag, "WARN", msg, null)
    }

    private fun write(tag: String, level: String, msg: String, t: Throwable?) {
        val f = file ?: return
        runCatching {
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val sb = StringBuilder()
            sb.append(ts).append(" [").append(level).append("] ").append(tag).append(": ").append(msg)
            if (t != null) {
                appendThrowable(sb, t, 0)
            }
            sb.append('\n')
            f.appendText(sb.toString())
        }
    }

    /** 完整堆栈（不截断）+ 递归 cause 链。 */
    private fun appendThrowable(sb: StringBuilder, t: Throwable, depth: Int) {
        val indent = "  ".repeat(depth)
        sb.append('\n').append(indent).append(t.toString())
        t.stackTrace.forEach { sb.append("\n  ").append(indent).append("at ").append(it) }
        t.cause?.takeIf { it !== t }?.let { appendThrowable(sb, it, depth + 1) }
    }
}
