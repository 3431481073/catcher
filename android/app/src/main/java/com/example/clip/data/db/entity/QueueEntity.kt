package com.example.clip.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "queue")
data class QueueEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    /** 队列的"主体"：记录的 local_id，或 usage 那个固定主体 id。 */
    @ColumnInfo(name = "record_local_id") val recordLocalId: String,
    @ColumnInfo(name = "operation") val operation: String, // UPLOAD / PATCH / DELETE
    @ColumnInfo(name = "payload") val payload: String?,
    @ColumnInfo(name = "status") val status: String,       // PENDING / RETRYING / DONE / FAILED
    @ColumnInfo(name = "attempts") val attempts: Int,
    @ColumnInfo(name = "next_attempt_at") val nextAttemptAt: String?,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String,

    /**
     * `record` / `usage` —— 同一个调度循环（[com.example.clip.service.UploadQueue]）
     * 要拉两种东西：剪藏记录，和 App 使用事件。`UploadWorker` 靠它分叉。
     *
     * 🔴 **`defaultValue` 一个字符都不能少。** 这是 `MIGRATION_2_3` 里
     * `ALTER TABLE queue ADD COLUMN` 加出来的列，老行会落到库里的默认值上；
     * 少了它，Room 会拿**实体推导出来的** schema 去比**实际表**，报
     * `Migration didn't properly handle: queue` —— 而且是在**打开库那一刻**崩。
     */
    @ColumnInfo(name = "kind", defaultValue = "record") val kind: String = "record",
)
