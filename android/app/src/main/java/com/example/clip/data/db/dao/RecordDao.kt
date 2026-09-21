package com.example.clip.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.clip.data.db.entity.RecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RecordDao {
    @Query("SELECT * FROM records WHERE deleted = 0 ORDER BY created_at DESC")
    fun observeAll(): Flow<List<RecordEntity>>

    @Query("SELECT * FROM records WHERE local_id = :localId AND deleted = 0")
    fun observeById(localId: String): Flow<RecordEntity?>

    @Query("SELECT * FROM records WHERE local_id = :localId AND deleted = 0")
    suspend fun getByLocalId(localId: String): RecordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: RecordEntity)

    @Update
    suspend fun update(record: RecordEntity)

    // M4 自愈/补查：未同步（含重试中）
    @Query("SELECT * FROM records WHERE deleted = 0 AND sync_status IN ('PENDING','SYNCING')")
    suspend fun getUnsynced(): List<RecordEntity>

    // M4 自愈/补查：上传完成、转写中（有 task_id 待轮询）
    @Query("SELECT * FROM records WHERE deleted = 0 AND status = 'PROCESSING' AND task_id IS NOT NULL")
    suspend fun getProcessingWithTask(): List<RecordEntity>
}
