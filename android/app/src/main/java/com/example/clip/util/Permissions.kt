package com.example.clip.util

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 引导页权限状态 + 跳转封装。状态由 MainActivity.onResume 调 [refresh] 刷新。
 *
 * 四档强度：
 *  - **悬浮窗**：必过门槛，没有它这个 App 就没形态。
 *  - **通知**：推荐，不阻塞（API<33 恒 true）。
 *  - **使用情况访问**：推荐，**不阻塞** —— 缺了只是少采集一类数据，剪藏本身照常。
 *    它是**特殊权限**，不在普通权限列表里，`requestPermissions` 对它无效，只能跳系统设置。
 *  - **电池优化白名单**（2026-10-12 加）：推荐，**不阻塞** —— 它不影响"能不能采"，
 *    只影响"多久采一次"。见 [batteryExempt] 的说明。
 */
object Permissions {

    /** 悬浮窗（SYSTEM_ALERT_WINDOW）：必过。 */
    val overlay = MutableStateFlow(false)

    /** 通知（POST_NOTIFICATIONS）：推荐；API<33 恒 true。 */
    val notification = MutableStateFlow(true)

    /** 使用情况访问（PACKAGE_USAGE_STATS）：推荐，不阻塞。 */
    val usage = MutableStateFlow(false)

    /**
     * 电池优化白名单（Doze 豁免）：推荐，不阻塞。
     *
     * 🔴 **它管的是"节奏"，不是"能不能"**：不进白名单时系统的 Doze / 后台冻结会把
     * 定时采集**推迟**（实测上传间隔最长 26 分钟，而注册的是 15 分钟）。
     * **采集不会停**，只是频率不稳。
     *
     * ⚠️ 判据是 `PowerManager.isIgnoringBatteryOptimizations` —— **返回 true 表示"已豁免"**
     *    （注意这个方法的名字是反的：它是"忽略电池优化"= 我们要的那个状态）。
     */
    val batteryExempt = MutableStateFlow(false)

    fun refresh(context: Context) {
        overlay.value = Settings.canDrawOverlays(context)
        notification.value = isNotificationEnabled(context)
        usage.value = isUsageAccessGranted(context)
        batteryExempt.value = isBatteryExempt(context)
    }

    /**
     * 在不在电池优化白名单里。
     * ⚠️ `isIgnoringBatteryOptimizations` 这个名字是**反的** ——
     * 返回 `true` = **系统忽略了对本 App 的电池优化** = 我们要的那个状态。
     */
    fun isBatteryExempt(context: Context): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.isIgnoringBatteryOptimizations(context.packageName) ?: false

    fun isNotificationEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun overlayIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )

    /**
     * 使用情况访问有没有开。**只能走 AppOps，没有普通权限 API** ——
     * `checkPermission(PACKAGE_USAGE_STATS)` 永远返回 DENIED（它是 AppOps 权限）。
     *
     * 用 `unsafeCheckOpNoThrow`（API 29+）而不是老的 `checkOpNoThrow`：后者在
     * 未授权时会抛 SecurityException。
     */
    fun isUsageAccessGranted(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        return appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }

    fun usageIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    /**
     * 跳「电池优化」白名单。
     *
     * 🔴 用 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（**带 package**）——
     *    它会**直接对本 App 弹一个"是否允许后台运行"的框**，比让用户在列表里翻快得多。
     *    ⚠️ 代价：它要求 manifest 里声明 **`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`** 权限，
     *    而 Google Play 对上架应用限制这个权限。**本项目不上架**，所以可用。
     *
     * 🔴 **必须兜底**：部分 ROM（含部分 ColorOS）直接忽略这个 action 或不弹框 ——
     *    那样用户点了「去设置」会**什么都不发生**。退到
     *    `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`（**不需要那个权限**的列表页），
     *    让他自己去列表里找「剪藏」。再不行退到应用详情页。
     */
    fun openBatterySettings(context: Context) {
        try {
            context.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}")
                )
            )
        } catch (e: Exception) {
            FileLogger.e("Permissions", "直接申请电池优化豁免失败，退到白名单列表页", e)
            try {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                FileLogger.e("Permissions", "电池优化列表页也打不开，退到应用详情页", e2)
                runCatching {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                }
            }
        }
    }

    /**
     * 跳到「使用情况访问」。
     *
     * 🔴 **必须兜底**：`ACTION_USAGE_ACCESS_SETTINGS` 不是所有 ROM 都实现，
     * 打不开就会抛 ActivityNotFoundException（典型的静默失效 —— 用户点了「去开启」，
     * 什么都没发生，还以为是自己点歪了）。退到本应用的系统详情页，至少让他有路可走。
     */
    fun openUsageSettings(context: Context) {
        try {
            context.startActivity(usageIntent())
        } catch (e: Exception) {
            FileLogger.e("Permissions", "打不开「使用情况访问」页面，退到应用详情页", e)
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}")
                    )
                )
            }
        }
    }
}
