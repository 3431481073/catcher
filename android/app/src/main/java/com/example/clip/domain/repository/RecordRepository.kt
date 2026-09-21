package com.example.clip.domain.repository

import com.example.clip.domain.model.QueueItem
import com.example.clip.domain.model.Record
import kotlinx.coroutines.flow.Flow

interface RecordRepository {
    fun observeRecords(): Flow<List<Record>>
    fun observeRecord(localId: String): Flow<Record?>

    // M2 写路径
    suspend fun insert(record: Record)                 // 落 records；rawAudio 非空时同时落 blobs(AUDIO)
    suspend fun get(localId: String): Record?
    suspend fun getAudioPath(localId: String): String?
    suspend fun update(record: Record)

    // M4 上传队列
    suspend fun insertQueue(item: QueueItem)
    suspend fun getQueue(recordLocalId: String): QueueItem?
    suspend fun updateQueue(recordLocalId: String, attempts: Int, nextAttemptAt: String?, status: String)
    suspend fun deleteQueue(recordLocalId: String)
    fun observePendingQueue(): Flow<List<QueueItem>>
    suspend fun getPendingQueue(): List<QueueItem>

    // M4 自愈/补查
    suspend fun getUnsynced(): List<Record>            // syncStatus in PENDING/SYNCING
    suspend fun getProcessingWithTask(): List<Record>  // status=PROCESSING 且有 task_id
}
