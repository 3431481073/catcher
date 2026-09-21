package com.example.clip.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "blobs")
data class BlobEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "record_local_id") val recordLocalId: String,
    @ColumnInfo(name = "kind") val kind: String,          // AUDIO / WAV / COVER
    @ColumnInfo(name = "path") val path: String?,
    @ColumnInfo(name = "size") val size: Long?,
    @ColumnInfo(name = "created_at") val createdAt: String
)
