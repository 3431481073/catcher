package com.example.clip.collector

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 🔴 **`usage` 这个 DataStore 只能在这里声明一次。**
 * `preferencesDataStore` 是"每个属性一个实例"，同一个文件名声明两处会在**第二次访问时崩**
 * （`There are multiple DataStores active for the same file`）。所以采集游标和界面上的
 * "不再提示"都收在这个对象里，谁要读就读这里的。
 */
private val Context.usageStore by preferencesDataStore(name = "usage")

/** App 使用时长这一摊的持久化小状态。 */
object UsagePrefs {

    /** 增量采集游标：上次扫到哪个时刻。 */
    private val KEY_LAST_SCAN = longPreferencesKey("last_scan_ms")

    /** 首次回溯的断点（跑到哪天了）。 */
    private val KEY_BACKFILL_CURSOR = longPreferencesKey("backfill_cursor_ms")

    /** 首次回溯是否已完成 —— 完成了就不再做第二次（之后的空档归增量管）。 */
    private val KEY_BACKFILL_DONE = booleanPreferencesKey("backfill_done")

    /** 记录页那条「没开使用情况访问」提示条被用户划掉过。 */
    private val KEY_HINT_DISMISSED = booleanPreferencesKey("usage_hint_dismissed")

    // ── 采集游标 ──────────────────────────────────────────────

    suspend fun lastScanMs(context: Context): Long = readLong(context, KEY_LAST_SCAN)
    suspend fun setLastScan(context: Context, v: Long) = writeLong(context, KEY_LAST_SCAN, v)

    suspend fun backfillCursorMs(context: Context): Long = readLong(context, KEY_BACKFILL_CURSOR)
    suspend fun setBackfillCursor(context: Context, v: Long) = writeLong(context, KEY_BACKFILL_CURSOR, v)

    suspend fun backfillDone(context: Context): Boolean = readBool(context, KEY_BACKFILL_DONE)
    suspend fun setBackfillDone(context: Context, v: Boolean) = writeBool(context, KEY_BACKFILL_DONE, v)

    // ── 提示条 ────────────────────────────────────────────────

    /** 用户在记录页把「没开使用情况访问」那条提示划掉过没有。 */
    fun hintDismissed(context: Context): Flow<Boolean> =
        context.usageStore.data.map { it[KEY_HINT_DISMISSED] ?: false }

    /**
     * 永久不再提示。
     *
     * ⚠️ **"永久"是真的永久**：之后即使撤销授权也不会再出现（它记的是"用户不想被提醒"，
     * 不是"当前是否授权"）。这是刻意的 —— 一个能被反复顶回来的提示等于没有关闭按钮。
     */
    suspend fun dismissHint(context: Context) {
        context.usageStore.edit { it[KEY_HINT_DISMISSED] = true }
    }

    // ── 读写 ──────────────────────────────────────────────────

    private suspend fun readLong(context: Context, key: Preferences.Key<Long>): Long =
        context.usageStore.data.first()[key] ?: 0L

    private suspend fun writeLong(context: Context, key: Preferences.Key<Long>, v: Long) {
        context.usageStore.edit { it[key] = v }
    }

    private suspend fun readBool(context: Context, key: Preferences.Key<Boolean>): Boolean =
        context.usageStore.data.first()[key] ?: false

    private suspend fun writeBool(context: Context, key: Preferences.Key<Boolean>, v: Boolean) {
        context.usageStore.edit { it[key] = v }
    }
}
