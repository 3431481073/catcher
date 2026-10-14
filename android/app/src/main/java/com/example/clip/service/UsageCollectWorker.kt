package com.example.clip.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.clip.ClipApp
import com.example.clip.collector.UsageCollector
import com.example.clip.util.FileLogger
import java.util.concurrent.TimeUnit

/**
 * 采集的调度壳：**只负责"什么时候去拉"**，拉取逻辑全在 [UsageCollector]。
 *
 * 三种触发（对应架构里的 2.5）：
 *  · **周期**：[schedulePeriodic] —— `PeriodicWorkRequest` 15 分钟。
 *    🔴 **这是 WorkManager 允许的最小周期**（再小它自己会拉到 15 分钟），
 *    而且 Doze 下会被推迟 —— 所以"15 分钟"**不是数据完整性的前提**：
 *    拉取区间永远是「上次扫到的时间 − 5 分钟 到 now」，被推迟 3 小时也只是这一次
 *    多拉 3 小时的事件，中间不会漏。
 *  · **启动 / 回前台**：`ClipApp` 里直接调 [UsageCollector]（进程已活着，不必绕 WorkManager）。
 *  · **WS trigger**：`RemoteViewModel` 收到 `trigger_usage` 后同样直接调。
 *
 * 失败**不重试**：采集是幂等的，下一次触发自然会补上，重试只会在 Doze 里排队排队再排队。
 */
class UsageCollectWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as ClipApp
        val dao = app.database.usageEventDao()
        val mode = inputData.getString(KEY_MODE)
        return try {
            when (mode) {
                MODE_BACKFILL -> UsageCollector.backfillIfNeeded(app, dao)
                else -> UsageCollector.collectIncremental(app, dao)
            }
            UploadQueue.enqueueUsageIfPending(app)
            Result.success()
        } catch (e: Exception) {
            if (mode == MODE_BACKFILL) {
                // 🔴 **回溯失败要重试**（2026-10-11 改）。它是**可续传**的：游标每跑完一天
                //    就落盘，重试从断点接着来，不会白跑。而原来 `Result.success()` 等于
                //    "失败了但装作成功" —— WorkManager 不会再排，那次回溯就**永久断在那儿**，
                //    除非用户下次开 App 触发新的一发（这正是"每次开 App 都在接着搬"的来源）。
                //    WorkManager 自带指数退避，不会打成热循环。
                FileLogger.e(TAG, "回溯失败，交给 WorkManager 重试（游标会续上）", e)
                Result.retry()
            } else {
                // 增量失败**不重试**：重叠窗口本身就是容错的（下次扫描区间会自动覆盖这一段），
                // 而重复触发点很多（周期 / 启动 / 回前台），重试只会排在 Doze 里白等。
                FileLogger.e(TAG, "增量采集失败（不重试，下次触发会覆盖）", e)
                Result.success()
            }
        }
    }

    companion object {
        private const val TAG = "UsageCollectWorker"

        const val KEY_MODE = "mode"
        const val MODE_INCREMENTAL = "incremental"
        const val MODE_BACKFILL = "backfill"

        private const val UNIQUE_PERIODIC = "usage_collect_periodic"
        private const val UNIQUE_BACKFILL = "usage_collect_backfill"
        private const val UNIQUE_INCREMENTAL = "usage_collect_incremental"

        /**
         * 「立刻采一次增量」—— 启动 / 回前台时排一发。
         *
         * 🔴 **它和周期任务共用同一份工作**，单次即弃；`KEEP` 保证启动那一下不会和
         * 正在跑的周期任务撞车（撞了也只是白跑一次——`collectIncremental` 幂等）。
         *
         * ⚠️ **代价说清楚**：走 WorkManager 就**不再"立即"**了 —— 系统可能过几秒到几十秒
         * 才调度它。**但这不丢数据**：增量窗口是「上次扫到 − 5 分钟 到 now」，
         * 晚跑只是这次多拉一段，中间不会漏（见 `UsageCollector` 类注释）。
         * 换来的是"App 一启动就被杀也不影响"。
         */
        fun scheduleIncremental(ctx: Context) {
            val request = OneTimeWorkRequestBuilder<UsageCollectWorker>()
                .setInputData(workDataOf(KEY_MODE to MODE_INCREMENTAL))
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                UNIQUE_INCREMENTAL,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        /** 15 分钟 —— WorkManager 的周期下限。幂等，重复调用不会堆任务。 */
        fun schedulePeriodic(ctx: Context) {
            val request = PeriodicWorkRequestBuilder<UsageCollectWorker>(15, TimeUnit.MINUTES)
                .setInputData(workDataOf(KEY_MODE to MODE_INCREMENTAL))
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC,
                // KEEP：已经排过就不重排 —— 用 UPDATE 会把周期重置，每次启动都往后推 15 分钟
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /**
         * 首次回溯 7 天。**本身幂等**（`backfill_done` 已置就直接返回 0），
         * 所以"没授权"时排进去也无害 —— 授权后第一次前台会真的跑起来。
         *
         * 🔴 **每次启动 / 回前台都要调它**（2026-10-11 明确）：
         * `KEEP` 只挡"**还在队列里**"的同名任务 —— 跑完的 work **不会**被重排。
         * 所以"onCreate 里排一次就够"是错的：回溯一旦被打断，那一发就结束了，
         * **再也没人续**，只能靠用户下次开 App 触发的另一条路径。
         * `backfill_done=true` 时它跑一秒返回 0，代价可忽略。
         *
         * 用 WorkManager 而不是在 `ClipApp` 里裸起协程：回溯是 7 次 queryEvents + 上万条插入，
         * 用户很可能中途就切走了 —— 实测过（整个 7 天被重传第二遍），
         * 裸协程会随进程一起断，WorkManager 至少能被系统重新调度。
         */
        fun scheduleBackfill(ctx: Context) {
            val request = OneTimeWorkRequestBuilder<UsageCollectWorker>()
                .setInputData(workDataOf(KEY_MODE to MODE_BACKFILL))
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                UNIQUE_BACKFILL,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
