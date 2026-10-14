package com.example.clip.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.clip.data.db.entity.UsageEventEntity

/**
 * `usage_events` 的读写。分工是「采集线程只写、上传 worker 只读一批」，
 * 所以这里没有 Flow —— 没有界面在看它。
 *
 * 状态值是大写（`PENDING` / `SYNCING` / `SYNCED` / `FAILED`），与 `records.sync_status` 同一套。
 */
@Dao
interface UsageEventDao {

    /**
     * 批量落库。**`IGNORE` 而不是 `REPLACE`** —— 重叠窗口里重拉到的同一条事件要**保持原样**，
     * 不能被当成新行覆盖掉（覆盖会把已经标成 `SYNCING` 的行打回 `PENDING`）。
     * 返回每个元素的行 id，**命中唯一键被忽略的是 -1**（备用，当前调用方不看）。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<UsageEventEntity>): List<Long>

    @Query("SELECT COUNT(*) FROM usage_events WHERE sync_status = 'PENDING'")
    suspend fun countPending(): Int

    /** 取一批待发的，**按时间正序** —— 后端的配对是按时间排的，这里排好省一次排序，
     *  也让"最后一段没闭合"的语义跟真实时间顺序一致。 */
    @Query("SELECT * FROM usage_events WHERE sync_status = 'PENDING' ORDER BY timestamp_ms ASC LIMIT :limit")
    suspend fun takePending(limit: Int): List<UsageEventEntity>

    @Query("UPDATE usage_events SET sync_status = :status WHERE id IN (:ids)")
    suspend fun updateStatus(ids: List<Long>, status: String)

    @Query("DELETE FROM usage_events WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    /**
     * 自愈：进程在"标了 SYNCING、还没收到响应"时被杀，那些行会**永远卡在 SYNCING**
     * （`takePending` 只取 PENDING，再也轮不到它们）。启动时把它们放回 PENDING。
     *
     * 这与后端 `reset_processing_to_pending()` 是同一件事的两半 —— 重发是安全的
     * （后端唯一键去重），所以宁可重发也不能丢。
     */
    @Query("UPDATE usage_events SET sync_status = 'PENDING' WHERE sync_status = 'SYNCING'")
    suspend fun resetSyncing(): Int
}
