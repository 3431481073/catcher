package com.example.clip.collector

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import com.example.clip.data.db.dao.UsageEventDao
import com.example.clip.data.db.entity.UsageEventEntity
import com.example.clip.util.FileLogger
import com.example.clip.util.Permissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * App 使用时长采集：**只做「拉 → 存」**，不配对、不算时长、不碰界面。
 *
 * 配对是后端的活（`backend/app/usage.py`），因为配对要跨批、要看锁屏和换 App，
 * 手机上存不下那个状态。这里唯一的"聪明"是**区间起点**：
 *
 * ```
 * 上次扫到 T  ──[T-5min ────────────── now]──  这一次拉的范围
 *                  ↑ 5 分钟重叠
 * ```
 * 留重叠是因为"正好跨在两次扫描之间"的段会被截断 —— 宁可重复（后端唯一键去重、
 * 本地 `INSERT OR IGNORE` 也去重），不能漏。
 *
 * 🔴 **重叠窗口决定了它必须能被重复调用**：`collectIncremental` 调几次结果都一样，
 * 多的那部分被丢掉。所以触发时机可以随便加（启动 / 15 分钟 / WS / 被杀前）。
 *
 * 游标存在 [UsagePrefs]（DataStore `usage` 分区）。
 */
object UsageCollector {

    private const val TAG = "UsageCollector"

    /** 与 `UsageEventEntity.syncStatus` 同一个取值域。 */
    const val SYNC_PENDING = "PENDING"
    const val SYNC_SYNCING = "SYNCING"
    const val SYNC_FAILED = "FAILED"

    /** 每次增量重拉的重叠长度。见类注释。 */
    const val OVERLAP_MS = 5 * 60 * 1000L

    /** 首次授权后回溯的天数。系统一般只保留最近 1~7 天，要多了也拿不到（不是错误）。 */
    const val BACKFILL_DAYS = 7

    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /**
     * **只留这 6 类**（2026-10-12 加）。其余类型的原始事件**直接丢弃** —— 不写 Room、不上报。
     *
     * 🔴 **必须和后端 `backend/app/usage.py` 的 `KNOWN_TYPES` 完全一致。**
     *    那边有一张同样的表，而且有一条 pytest（`test_event_constants_match_android`）
     *    把数值钉住了。**改这里就要改那里。**
     *
     * 🔴 **为什么用 `UsageEvents.Event.ACTIVITY_RESUMED` 这些【名字】而不是写字面量 1/2/15/…：**
     *    理由是**可读 + 有编译期检查**，
     *    🔴 **不是"值会随 Android 版本变"** —— 那 6 个整数是 on-device 的 ABI，
     *    系统把使用历史**按这些值存在磁盘上**，改了就读不了旧数据，所以值固定。
     *    （API 29 变的是**名字**：`MOVE_TO_FOREGROUND` → `ACTIVITY_RESUMED`，
     *     `MOVE_TO_BACKGROUND` → `ACTIVITY_PAUSED`，**值仍是 1 和 2**。）
     *
     * 🔴 **过滤【不省】`queryEvents` 的遍历** —— 那个 API 只能按时间区间查、
     *    **没有按类型过滤的参数**，系统照样把整批事件吐出来、我们照样一条条读过去。
     *    **省下来的是两件事：① 不写 Room ② 不上传。**
     *    **⇒ 收益在链路上，不在 CPU 上。** 别指望它让采集变快。
     *
     * ⚠️ **代价：丢掉的事件永远拿不回来**（Room 不留、上报不发）。
     *    将来若要用别的类型（比如 `ACTIVITY_STOPPED`）去更精确地断段，
     *    **过滤期间的数据是缺的**。这是明确接受的取舍，不是疏漏。
     */
    private val KNOWN_TYPES = setOf(
        UsageEvents.Event.ACTIVITY_RESUMED,
        UsageEvents.Event.ACTIVITY_PAUSED,
        UsageEvents.Event.SCREEN_INTERACTIVE,
        UsageEvents.Event.SCREEN_NON_INTERACTIVE,
        UsageEvents.Event.KEYGUARD_SHOWN,
        UsageEvents.Event.KEYGUARD_HIDDEN,
    )

    // ── 增量 ──────────────────────────────────────────────────

    /**
     * 拉「上次到现在」的事件并落库，返回**真正新增的行数**（被去重吃掉的不算）。
     *
     * 没授权时**什么都不做、也不推进游标** —— 推了的话授权那一瞬间就会凭空缺一段。
     */
    suspend fun collectIncremental(context: Context, dao: UsageEventDao): Int =
        withContext(Dispatchers.IO) {
            if (!Permissions.isUsageAccessGranted(context)) return@withContext 0
            val now = System.currentTimeMillis()
            val last = UsagePrefs.lastScanMs(context)
            // 从没扫过（首次启动/刚升级）时**不从 epoch 拉** —— 那是把系统留着的全部历史
            // 一次倒进来；"回溯 7 天"是 backfill 的活，别在这里重复干。
            val begin = if (last <= 0L) now - OVERLAP_MS else last - OVERLAP_MS
            val added = pullAndStore(context, dao, begin, now)
            UsagePrefs.setLastScan(context, now)
            FileLogger.i(TAG, "增量采集：${(now - begin) / 1000}s 窗口，新增 $added 条")
            added
        }

    // ── 首次回溯 ──────────────────────────────────────────────

    /**
     * 首次授权后回溯 [BACKFILL_DAYS] 天。**幂等**：跑完置 `backfill_done`，之后直接返回 0。
     *
     * **按天切分**：① 可中断续传（每段跑完就把游标落盘，被杀不用整段重来）；
     * ② 单次 `queryEvents` 的返回规模可控。
     * 跨午夜的那一段不用特殊照顾 —— 后端 `usage_pending` 表会把被切开的段补上。
     */
    suspend fun backfillIfNeeded(context: Context, dao: UsageEventDao): Int =
        withContext(Dispatchers.IO) {
            val enteredAt = System.currentTimeMillis()
            if (!Permissions.isUsageAccessGranted(context)) {
                FileLogger.i(TAG, "回溯入口：未授权 → 跳过")
                return@withContext 0
            }
            // 🔴 **这三个值必须落日志**（2026-10-11 加）。理由：实测出现过"整个 7 天被
            //    重新上传了一遍"（94% 的行被重写），说明**回溯跑了第二遍** ——
            //    那是 `backfill_done` / `cursor` 没落住。**没有这一行，下次再发生只能继续猜。**
            val done = UsagePrefs.backfillDone(context)
            val cursorStored = UsagePrefs.backfillCursorMs(context)
            val lastScan = UsagePrefs.lastScanMs(context)
            FileLogger.i(
                TAG,
                "回溯入口：done=$done cursor=$cursorStored lastScan=$lastScan → " +
                    (if (done) "跳过" else "开跑"),
            )
            if (done) return@withContext 0

            val now = System.currentTimeMillis()
            var cursor = cursorStored.takeIf { it > 0L } ?: (now - BACKFILL_DAYS * DAY_MS)
            if (cursorStored <= 0L) {
                FileLogger.i(TAG, "回溯：没有游标，从 now-${BACKFILL_DAYS}天 开始（$cursor）")
            }

            var total = 0
            var chunks = 0
            while (cursor < now) {
                if (!Permissions.isUsageAccessGranted(context)) {
                    // 回溯途中被撤销授权 —— **绝不置 done**，否则补授之后就永远不回填了
                    FileLogger.w(TAG, "回溯中授权被撤销，停在 $cursor（下次接着来）")
                    return@withContext total
                }
                val end = minOf(cursor + DAY_MS, now)
                total += pullAndStore(context, dao, cursor, end)
                cursor = end
                chunks++
                UsagePrefs.setBackfillCursor(context, cursor)   // 🔴 每段落盘 = 断点
            }
            UsagePrefs.setBackfillDone(context, true)
            UsagePrefs.setLastScan(context, now)
            // 🔴 出口日志带**耗时毫秒** —— 这就是判断"要不要前台服务"的那个数
            //    （见 docs：判据是 <30 秒 ⇒ WorkManager 够，>30 秒 ⇒ 才考虑前台服务）。
            //    7 次 queryEvents + 约 1.5 万行 Room 插入，**无网络**，预期是秒级。
            FileLogger.i(
                TAG,
                "回溯完成：$chunks 段 / 新增 $total 条 / 耗时 ${System.currentTimeMillis() - enteredAt}ms",
            )
            total
        }

    /** 调试/自检用（对 `crash.log` 里的时间线）。 */
    suspend fun lastScanMs(context: Context): Long = UsagePrefs.lastScanMs(context)

    // ── 拉一次 ────────────────────────────────────────────────

    /** 拉一次并落库，返回真正新增的行数。 */
    private suspend fun pullAndStore(
        context: Context,
        dao: UsageEventDao,
        beginMs: Long,
        endMs: Long,
    ): Int {
        val batch = collectBatch(context, beginMs, endMs) ?: return 0
        if (batch.isEmpty()) return 0
        // IGNORE：重叠窗口里重拉到的同一条事件保持原样（不会被 REPLACE 打回 PENDING）。
        // 返回 -1 的是"命中唯一键被忽略"的，不算新增。
        return dao.insertAll(batch).count { it != -1L }
    }

    /**
     * `queryEvents(begin, end)` → 行（**不落库**）。
     *
     * 一条事件 = 一行，**原样存**（含 `package_name` 为 null 的屏幕/锁屏事件）——
     * 后端要靠这几条判断"该断段了"，这里过滤掉就永远配不出正确的段。
     *
     * 🔴 **只留 [KNOWN_TYPES] 那 6 类，其余直接丢**（2026-10-12 加）。
     *    **依据是实测**：手机上送来的事件里**约一半是别的类型**
     *    （`ACTIVITY_STOPPED` / `CONFIGURATION_CHANGE` / 前台服务事件…），
     *    后端的 `usage 上报` 日志里 `unknown` 常年 33~993 —— **那些字节是白传的**。
     *    后端配对**本来也只用这 6 类**，所以丢掉它们对配对没有任何影响。
     */
    private fun collectBatch(context: Context, beginMs: Long, endMs: Long): List<UsageEventEntity>? {
        val stats = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null
        val events = runCatching { stats.queryEvents(beginMs, endMs) }.getOrNull() ?: return null

        val deviceId = DeviceId.get(context)
        val collectedAt = System.currentTimeMillis()
        val batch = ArrayList<UsageEventEntity>()

        var scanned = 0
        var dropped = 0

        // Event 对象复用（官方推荐）：每轮只 getNextEvent 填同一个实例，
        // 把要的值**取出来**再构造行 —— 不能把 e 本身存进列表，那样全是同一个引用。
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            scanned++
            // 🔴 不认识的类型直接丢（丢的后果见 KNOWN_TYPES 的注释）
            if (e.eventType !in KNOWN_TYPES) {
                dropped++
                continue
            }
            batch += UsageEventEntity(
                packageName = e.packageName,
                eventType = e.eventType,
                timestampMs = e.timeStamp,
                deviceId = deviceId,
                collectedAtMs = collectedAt,
                syncStatus = SYNC_PENDING,
            )
        }
        // 日志放在这儿（不放在调用方）：它已经有 context，而且**回溯时 7 个 day-chunk
        // 各打一条** —— 那正是最想看过滤效果的时候（回溯是最大的一批）。
        FileLogger.i(TAG, "扫描 $scanned 个事件，保留 ${scanned - dropped} 个，丢弃 $dropped 个")
        return batch
    }
}
