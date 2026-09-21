package com.example.clip.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "records")
data class RecordEntity(
    @PrimaryKey @ColumnInfo(name = "local_id") val localId: String,
    @ColumnInfo(name = "server_id") val serverId: String?,
    @ColumnInfo(name = "type") val type: String,            // VOICE / VIDEO / TEXT
    @ColumnInfo(name = "title") val title: String?,
    @ColumnInfo(name = "content") val content: String?,
    @ColumnInfo(name = "note") val note: String?,
    @ColumnInfo(name = "url") val url: String?,
    @ColumnInfo(name = "platform") val platform: String?,
    @ColumnInfo(name = "tags") val tags: String?,           // JSON 数组，如 ["AIGC","AI做视频"]
    @ColumnInfo(name = "status") val status: String,        // PENDING / PROCESSING / DONE / FAILED
    @ColumnInfo(name = "error") val error: String?,
    @ColumnInfo(name = "task_id") val taskId: String?,
    @ColumnInfo(name = "task_status") val taskStatus: String?, // PENDING/PROCESSING/DONE/FAILED/SKIPPED/null
    @ColumnInfo(name = "queue_position") val queuePosition: Int,
    @ColumnInfo(name = "sync_status") val syncStatus: String,  // PENDING / SYNCING / SYNCED / FAILED
    @ColumnInfo(name = "raw_audio") val rawAudio: String?,
    @ColumnInfo(name = "deleted") val deleted: Int,         // 0/1 软删
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String
)
