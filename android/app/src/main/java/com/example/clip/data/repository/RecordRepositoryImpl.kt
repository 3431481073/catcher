package com.example.clip.data.repository

import com.example.clip.data.db.dao.BlobDao
import com.example.clip.data.db.dao.QueueDao
import com.example.clip.data.db.dao.RecordDao
import com.example.clip.data.db.entity.BlobEntity
import com.example.clip.data.db.entity.QueueEntity
import com.example.clip.data.db.entity.RecordEntity
import com.example.clip.domain.model.NoteEntry
import com.example.clip.domain.model.QueueItem
import com.example.clip.domain.model.Record
import com.example.clip.domain.model.RecordStatus
import com.example.clip.domain.model.RecordType
import com.example.clip.domain.model.SyncStatus
import com.example.clip.domain.model.TaskStatus
import com.example.clip.domain.repository.RecordRepository
import com.example.clip.util.Ids
import com.example.clip.util.JsonList
import com.example.clip.util.TimeUtil
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val noteGson = Gson()
private val noteListType = object : TypeToken<List<NoteEntry>>() {}.type

class RecordRepositoryImpl(
    private val recordDao: RecordDao,
    private val blobDao: BlobDao,
    private val queueDao: QueueDao
) : RecordRepository {

    override fun observeRecords(): Flow<List<Record>> =
        recordDao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeRecord(localId: String): Flow<Record?> =
        recordDao.observeById(localId).map { it?.toDomain() }

    override suspend fun insert(record: Record) {
        recordDao.insert(record.toEntity())
        record.rawAudio?.let { path ->
            blobDao.insert(
                BlobEntity(
                    id = Ids.newBlobId(),
                    recordLocalId = record.localId,
                    kind = "AUDIO",
                    path = path,
                    size = null,
                    createdAt = TimeUtil.nowIso()
                )
            )
        }
    }

    override suspend fun get(localId: String): Record? =
        recordDao.getByLocalId(localId)?.toDomain()

    override suspend fun getAudioPath(localId: String): String? =
        blobDao.listByRecord(localId).firstOrNull { it.kind == "AUDIO" }?.path

    override suspend fun update(record: Record) {
        recordDao.update(record.toEntity())
    }

    // ---- M4 上传队列 ----

    override suspend fun insertQueue(item: QueueItem) {
        queueDao.insert(
            QueueEntity(
                id = item.recordLocalId,
                recordLocalId = item.recordLocalId,
                operation = item.operation,
                payload = item.payload,
                status = item.status,
                attempts = item.attempts,
                nextAttemptAt = item.nextAttemptAt,
                createdAt = TimeUtil.nowIso(),
                updatedAt = TimeUtil.nowIso()
            )
        )
    }

    override suspend fun getQueue(recordLocalId: String): QueueItem? =
        queueDao.getByRecord(recordLocalId)?.toDomain()

    override suspend fun updateQueue(recordLocalId: String, attempts: Int, nextAttemptAt: String?, status: String) {
        queueDao.update(recordLocalId, attempts, nextAttemptAt, status, TimeUtil.nowIso())
    }

    override suspend fun deleteQueue(recordLocalId: String) {
        queueDao.deleteByRecord(recordLocalId)
    }

    override fun observePendingQueue(): Flow<List<QueueItem>> =
        queueDao.observePending().map { list -> list.map { it.toDomain() } }

    override suspend fun getPendingQueue(): List<QueueItem> =
        queueDao.getPending().map { it.toDomain() }

    // ---- M4 自愈/补查 ----

    override suspend fun getUnsynced(): List<Record> =
        recordDao.getUnsynced().map { it.toDomain() }

    override suspend fun getProcessingWithTask(): List<Record> =
        recordDao.getProcessingWithTask().map { it.toDomain() }

    // ---- 映射 ----

    private fun QueueEntity.toDomain(): QueueItem = QueueItem(
        recordLocalId = recordLocalId,
        operation = operation,
        payload = payload,
        status = status,
        attempts = attempts,
        nextAttemptAt = nextAttemptAt
    )

    private fun Record.toEntity(): RecordEntity = RecordEntity(
        localId = localId,
        serverId = serverId,
        type = type.name,
        title = title,
        content = content,
        note = if (notes.isEmpty()) null else noteGson.toJson(notes),
        url = url,
        platform = platform,
        tags = JsonList.toJson(tags),
        status = status.name,
        error = error,
        taskId = taskId,
        taskStatus = taskStatus?.name,
        queuePosition = queuePosition,
        syncStatus = syncStatus.name,
        rawAudio = rawAudio,
        deleted = 0,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun RecordEntity.toDomain(): Record = Record(
        localId = localId,
        serverId = serverId,
        type = RecordType.valueOf(type),
        title = title,
        content = content,
        notes = note?.let { runCatching { noteGson.fromJson<List<NoteEntry>>(it, noteListType) }.getOrNull() } ?: emptyList(),
        url = url,
        platform = platform,
        tags = JsonList.fromJson(tags),
        status = RecordStatus.valueOf(status),
        error = error,
        taskId = taskId,
        taskStatus = taskStatus?.let { TaskStatus.valueOf(it) },
        queuePosition = queuePosition,
        syncStatus = SyncStatus.valueOf(syncStatus),
        rawAudio = rawAudio,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}
