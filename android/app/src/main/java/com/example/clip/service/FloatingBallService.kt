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
import com.example.clip.ClipApp
import com.example.clip.collector.UsageCollector
import com.example.clip.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 常驻前台服务：承载全局悬浮球。
 * foregroundServiceType 用 dataSync（与 SyncService 一致）——specialUse 在国产 ROM 支持不全。
 * START_NOT_STICKY：被杀不自动重启（不做极致保活）；onTaskRemoved 只记日志不重启。
 */
class FloatingBallService : Service() {

    private var window: FloatingBallWindow? = null

    /** 定时采集的协程。**随服务一起生、一起死** —— `onDestroy` 里 cancel。 */
    private var collectJob: Job? = null

    /** 服务自己的作用域（不挂 `viewModelScope` / `appScope` —— 那些活在服务之外）。 */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
        startUsageTicker()
        return START_NOT_STICKY
    }

    /**
     * **App 使用时长的定时增量采集**（2026-10-12 加，方案 A）。
     *
     * 背景：`UsageCollectWorker` 的 15 分钟周期任务**被系统压制** —— WorkManager 的周期
     * 只是"不早于 15 分钟"，Doze / 国产 ROM 的后台冻结会把它推到几十分钟甚至更久
     * （实测上传间隔最长 **26 分钟**）。
     *
     * 🔴 **为什么放在这个服务里**：它是**常驻前台服务** ——
     *    · **进程不会被回收**（这正是 v62 那轮把采集挪走的原因，现在这个前提回来了）
     *    · **foreground 优先级**，系统不会冻结它
     *    ⇒ **推迟是有界的**，不像 WorkManager 那样可能被推到几小时。
     *
     * ⚠️ **别过度承诺**：前台服务**不自动持 wakelock**，所以设备睡着时 `delay` 一样会被
     *    **CPU 休眠**推迟。**这是对的，不是缺陷** —— 采集窗口是「上次扫到 −5 分钟 到 now」，
     *    **晚跑只是多拉一段，不会漏**。为此去申请 `PARTIAL_WAKE_LOCK` 让 CPU 不睡，
     *    是"为了统计使用时长而持续耗电"，不划算。
     *
     * 🔴 **只做增量，不做回溯**：回溯是**有状态的长任务**（7 天、按天切分、游标续传），
     *    `UsageCollectWorker` 的"被杀也能被系统重新调度"更适合它。这里碰它是重复劳动。
     *
     * 🔴 **单拍失败不退出循环**：`runCatching` 包住每一拍 ——
     *    没有权限、系统抽风、没网，都只该让**这一拍**没结果，不该把整个定时器打死。
     */
    private fun startUsageTicker() {
        if (collectJob?.isActive == true) return          // onStartCommand 可能来多次
        FileLogger.i(TAG, "启动定时采集：每 ${USAGE_TICK_MS / 60_000} 分钟一拍")
        collectJob = serviceScope.launch {
            while (isActive) {
                delay(USAGE_TICK_MS)
                runCatching {
                    val app = applicationContext as? ClipApp ?: return@runCatching
                    UsageCollector.collectIncremental(app, app.database.usageEventDao())
                    UploadQueue.enqueueUsageIfPending(app)
                }.onFailure {
                    FileLogger.e(TAG, "定时采集失败（不影响下一拍）", it)
                }
            }
        }
    }

    /**
     * 用户从最近任务里划掉了这个 App —— 系统随时可能杀进程。
     *
     * 这里的**唯一**目标是：把最后这一段时间的使用事件落盘，别让它随进程一起消失。
     * 这是"尽力而为"：系统给多少时间不确定，所以卡一个 [EXIT_COLLECT_TIMEOUT_MS] 上限，
     * **超时就放弃**，绝不为了数据把主线程拖到 ANR。
     *
     * 🔴 **这里不发起网络上报。** 杀掉进程时请求大概率发不完，而且 WorkManager 的任务
     * 也不是在这里同步执行的 —— 只把队列行备好并 `enqueue` 一次，系统愿不愿意调度是它的事，
     * 不愿意就下次启动补（`Recovery` 会重新排队）。
     *
     * `runBlocking` 在这里是合适的：这是进程最后的机会，**没有任何后续时机**可以接住它，
     * 而且下面几个调用（DataStore / Room / WorkManager）都在各自的 IO 线程上跑，
     * 不会和被阻塞的主线程互相等。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        FileLogger.i(TAG, "onTaskRemoved（不重启）")
        val app = applicationContext as? ClipApp
        if (app != null) {
            val done = runCatching {
                runBlocking {
                    withTimeoutOrNull(EXIT_COLLECT_TIMEOUT_MS) {
                        UsageCollector.collectIncremental(app, app.database.usageEventDao())
                        UploadQueue.enqueueUsageIfPending(app)
                        true
                    }
                }
            }.getOrNull()
            if (done != true) FileLogger.w(TAG, "退出前采集未跑完（超时/失败），下次启动会补上")
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        FileLogger.i(TAG, "onDestroy")
        isRunning = false
        // 🔴 定时采集随服务一起收摊。**服务被关 = 定时器该停** ——
        //    "进程活着才有定时器"，这正是本方案的前提。
        //    停掉之后由 `UsageCollectWorker` 的周期任务兜底。
        collectJob = null
        serviceScope.cancel()
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

        /** 退出前采集的时间上限。够跑完一次 `queryEvents` + 一小批插入，又短到不会 ANR。 */
        private const val EXIT_COLLECT_TIMEOUT_MS = 3_000L

        /**
         * 定时增量采集的间隔。
         *
         * **和 `UsageCollectWorker.schedulePeriodic` 的 15 分钟对齐** —— 两者是同一个
         * 节奏的两个来源：**这个（前台服务内）是主力，那个（WorkManager）是兜底**。
         * 服务被关掉时只剩后者，而后者会被系统压制。
         */
        private const val USAGE_TICK_MS = 15 * 60 * 1000L

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
