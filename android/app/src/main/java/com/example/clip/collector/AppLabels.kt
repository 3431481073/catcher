package com.example.clip.collector

import android.content.Context

/**
 * 包名 → 可读的 App 名（"com.tencent.mm" → "微信"）。
 *
 * **只有手机做得了这件事** —— 后端拿到的只有包名字符串。所以名字随事件一起上报，
 * 后端 `usage_records.app_label` 存下来。
 *
 * 查不到的**不放进 map**（而不是塞个空串）：后端看到"没有这一项"就当 null，
 * 下次上报带上真名时 `COALESCE` 会把它补上；塞空串反而会把已有的名字覆盖掉。
 */
object AppLabels {

    fun resolve(context: Context, packages: Collection<String>): Map<String, String> {
        if (packages.isEmpty()) return emptyMap()
        val pm = context.packageManager
        val out = HashMap<String, String>(packages.size)
        packages.forEach { pkg ->
            // Android 11+ 没声明 QUERY_ALL_PACKAGES 时这里会抛 NameNotFoundException ——
            // 那一条跳过就行，包名本身仍然在（它来自使用事件，不依赖 PackageManager）。
            val label = runCatching {
                pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
            }.getOrNull()
            if (!label.isNullOrBlank()) out[pkg] = label
        }
        return out
    }
}
