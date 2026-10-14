package com.example.clip.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一条**原始的** App 使用事件（`UsageStatsManager.queryEvents` 吐什么就存什么）。
 *
 * 🔴 **本地不做任何配对** —— 手机只负责「拉 → 存 → 发」，配对/去重/覆盖全在后端。
 * 所以这张表里没有"段"，只有事件；`duration` 这种东西在手机上根本算不出来
 * （配对要跨批、要看锁屏和换 App，本地存不下那个状态）。
 *
 * 生命周期很短：上传成功就删掉（见 `UploadWorker` 的 usage 分支），
 * 所以它只是个**待发缓冲区**，不是历史库。
 *
 * ### 两个索引
 * - **`index_usage_events_dedup`（唯一）**：`(event_type, timestamp_ms, package_name)`。
 *   拉取区间是「`last_scan - 5min` 到 now」，**每次都重拉 5 分钟**，靠它 + `INSERT OR IGNORE`
 *   把重叠部分吃掉。
 *
 *   ⚠️ **已知空档**：SQLite 唯一索引里 NULL 互不相等 —— 屏幕/锁屏事件 `package_name` 是 NULL，
 *   所以**那几条不会被去重**，5 分钟重叠里会重复插入。不修的理由：① 量极小
 *   （屏幕/锁屏事件一天约几十上百条，每次重叠平均约 1 条）；② 后端的屏幕事件只当"断段边界"用，
 *   重复送完全幂等；③ 这些行上传成功就删了。真要修，把手机侧的无包名事件写成 `""` 即可（一行）。
 * - **`index_usage_events_sync_status`**：worker 每次只取 `PENDING` 的那一批。
 */
@Entity(
    tableName = "usage_events",
    indices = [
        Index(
            value = ["event_type", "timestamp_ms", "package_name"],
            unique = true,
            name = "index_usage_events_dedup",
        ),
        Index(value = ["sync_status"], name = "index_usage_events_sync_status"),
    ],
)
data class UsageEventEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,

    /** 屏幕/锁屏事件没有包名（见类注释里的已知空档）。 */
    @ColumnInfo(name = "package_name") val packageName: String?,

    /** `UsageEvents.Event` 的常量值（1/2/15/16/17/18，见 `docs/deps.md`）。 */
    @ColumnInfo(name = "event_type") val eventType: Int,

    /** 事件发生的时刻，epoch 毫秒（UTC）。 */
    @ColumnInfo(name = "timestamp_ms") val timestampMs: Long,

    /** Android ID 的 SHA256 前 16 位。 */
    @ColumnInfo(name = "device_id") val deviceId: String,

    /** 落库时刻，epoch 毫秒。用来排查"拉了但没发出去"。 */
    @ColumnInfo(name = "collected_at_ms") val collectedAtMs: Long,

    /** `PENDING` / `SYNCING` / `SYNCED` / `FAILED` —— 大写，与 `records.sync_status` 同一套。 */
    @ColumnInfo(name = "sync_status") val syncStatus: String,
)
