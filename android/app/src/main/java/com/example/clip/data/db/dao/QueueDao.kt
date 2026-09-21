package com.example.clip.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.clip.data.db.entity.QueueEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface QueueDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: QueueEntity)

    @Query("SELECT * FROM queue WHERE record_local_id = :recordLocalId")
    suspend fun getByRecord(recordLocalId: String): QueueEntity?

    @Query("SELECT * FROM queue WHERE status = 'PENDING' ORDER BY created_at ASC")
    suspend fun getPending(): List<QueueEntity>

    @Query("SELECT * FROM queue WHERE status = 'PENDING' ORDER BY created_at ASC")
    fun observePending(): Flow<List<QueueEntity>>

    @Query(
        "UPDATE queue SET attempts = :attempts, next_attempt_at = :nextAttemptAt," +
            " status = :status, updated_at = :updatedAt WHERE record_local_id = :recordLocalId"
    )
    suspend fun update(
        recordLocalId: String,
        attempts: Int,
        nextAttemptAt: String?,
        status: String,
        updatedAt: String
    )

    @Query("DELETE FROM queue WHERE record_local_id = :recordLocalId")
    suspend fun deleteByRecord(recordLocalId: String)
}
