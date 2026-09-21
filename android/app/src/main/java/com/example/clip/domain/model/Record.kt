package com.example.clip.domain.model

enum class RecordType { VOICE, VIDEO, TEXT }
enum class RecordStatus { PENDING, PROCESSING, DONE, FAILED }
enum class TaskStatus { PENDING, PROCESSING, DONE, FAILED, SKIPPED }
enum class SyncStatus { PENDING, SYNCING, SYNCED, FAILED }

enum class DisplayState { PENDING, SYNCING, QUEUED, PROCESSING, DONE, FAILED, SKIPPED }

/** 一条备注（追加式，id 用于 PATCH 幂等去重）。 */
data class NoteEntry(val id: String, val text: String, val time: String)

data class Record(
    val localId: String,
    val serverId: String?,
    val type: RecordType,
    val title: String?,
    val content: String?,
    val notes: List<NoteEntry> = emptyList(),
    val url: String?,
    val platform: String?,
    val tags: List<String> = emptyList(),
    val status: RecordStatus,
    val error: String?,
    val taskId: String?,
    val taskStatus: TaskStatus?,
    val queuePosition: Int,
    val syncStatus: SyncStatus,
    val rawAudio: String?,
    val createdAt: String,
    val updatedAt: String
) {
    /** 两段式优先级推导显示状态（见 PROGRESS.md 决策日志） */
    fun displayState(): DisplayState = when {
        syncStatus == SyncStatus.PENDING -> DisplayState.PENDING
        syncStatus == SyncStatus.SYNCING -> DisplayState.SYNCING
        syncStatus == SyncStatus.FAILED -> DisplayState.FAILED // 本地同步失败也算失败
        taskStatus == TaskStatus.SKIPPED -> DisplayState.SKIPPED
        status == RecordStatus.FAILED -> DisplayState.FAILED
        status == RecordStatus.DONE -> DisplayState.DONE
        queuePosition > 0 -> DisplayState.QUEUED
        taskStatus == TaskStatus.PROCESSING -> DisplayState.PROCESSING
        else -> DisplayState.PROCESSING
    }
}
