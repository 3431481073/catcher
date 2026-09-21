package com.example.clip.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "queue")
data class QueueEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "record_local_id") val recordLocalId: String,
    @ColumnInfo(name = "operation") val operation: String, // UPLOAD / PATCH / DELETE
    @ColumnInfo(name = "payload") val payload: String?,
    @ColumnInfo(name = "status") val status: String,       // PENDING / RETRYING / DONE / FAILED
    @ColumnInfo(name = "attempts") val attempts: Int,
    @ColumnInfo(name = "next_attempt_at") val nextAttemptAt: String?,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String
)
