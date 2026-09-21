package com.example.clip.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.clip.data.db.entity.BlobEntity

@Dao
interface BlobDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(blob: BlobEntity)

    @Query("SELECT * FROM blobs WHERE record_local_id = :recordLocalId")
    suspend fun listByRecord(recordLocalId: String): List<BlobEntity>
}
