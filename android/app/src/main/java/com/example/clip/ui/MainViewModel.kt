package com.example.clip.ui

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.clip.data.api.ApiClient
import com.example.clip.data.api.toRecord
import com.example.clip.data.token.TokenGate
import com.example.clip.data.token.TokenStore
import com.example.clip.domain.model.NoteEntry
import com.example.clip.domain.model.QueueItem
import com.example.clip.domain.model.Record
import com.example.clip.domain.model.RecordStatus
import com.example.clip.domain.model.RecordType
import com.example.clip.domain.model.SyncStatus
import com.example.clip.domain.repository.RecordRepository
import com.example.clip.recorder.RecordingController
import com.example.clip.service.UploadQueue
import com.example.clip.util.FileLogger
import com.example.clip.util.Ids
import com.example.clip.util.Platform
import com.example.clip.util.ShareParser
import com.example.clip.util.TimeUtil
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import retrofit2.HttpException

class MainViewModel(
    application: Application,
    private val repository: RecordRepository
) : AndroidViewModel(application) {

    val records: StateFlow<List<Record>> = repository.observeRecords()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val token: StateFlow<String?> = TokenStore.tokenFlow(application)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val panelVisible = MutableStateFlow(false)
    val detailTarget = MutableStateFlow<String?>(null)
    val bottomPanelVisible = MutableStateFlow(false)
    val noteDrafts = MutableStateFlow<Map<String, String>>(emptyMap())

    /** 按记录 ID 存/清草稿（text 空 = 清空该记录草稿）。 */
    fun setNoteDraft(localId: String, text: String) {
        noteDrafts.value = if (text.isBlank()) noteDrafts.value - localId else noteDrafts.value + (localId to text)
    }

    val tokenSaving = MutableStateFlow(false)
    val tokenError = MutableStateFlow<String?>(null)

    /** 麦克风权限被拒（用于 UI 提示 + 去设置） */
    val micPermissionDenied = MutableStateFlow(false)

    /** 引导页是否已走完（SharedPreferences 持久化）。 */
    private val onboardingPrefs = application.getSharedPreferences("onboarding", Context.MODE_PRIVATE)
    val onboardingDone = MutableStateFlow(onboardingPrefs.getBoolean("done", false))

    fun finishOnboarding() {
        FileLogger.i("MainViewModel", "finishOnboarding：引导页完成，进入主界面")
        onboardingPrefs.edit().putBoolean("done", true).apply()
        onboardingDone.value = true
    }

    /** 详情面板数据：先读 Room 缓存，后台刷新后 Flow 自动更新。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    val detailRecord: StateFlow<Record?> = detailTarget
        .flatMapLatest { localId ->
            if (localId == null) flowOf(null) else repository.observeRecord(localId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** 提交 token：200→有效入主界面；401→"Token 无效"；其它/网络→"连接失败"。 */
    fun submitToken(candidate: String) {
        val token = candidate.trim()
        if (token.isBlank()) return
        viewModelScope.launch {
            tokenSaving.value = true
            tokenError.value = null
            try {
                ApiClient.api.validateToken(token, 1, 0) // 200（含空列表）= token 有效
                TokenStore.save(getApplication(), token)
            } catch (e: Exception) {
                tokenError.value = when {
                    e is HttpException && e.code() == 401 -> "Token 无效"
                    else -> "连接失败，检查网络/后端"
                }
            } finally {
                tokenSaving.value = false
            }
        }
    }

    fun onSpeak() {
        panelVisible.value = false
        RecordingController.start(getApplication())
    }

    /** 悬浮球短按（App 在前台）→ 切面板。 */
    fun togglePanel() {
        panelVisible.value = !panelVisible.value
    }

    /** 悬浮球短按（App 在后台）→ 拉起并展开面板。 */
    fun openPanel() {
        panelVisible.value = true
    }

    fun onSubmitTextTap() {
        panelVisible.value = false
        bottomPanelVisible.value = true
    }

    fun onRecordClick(record: Record) {
        detailTarget.value = record.localId
        refreshDetail(record.localId)
    }

    fun dismissDetail() {
        detailTarget.value = null
    }

    fun dismissBottomPanel() {
        bottomPanelVisible.value = false
    }

    /** 提交文本：落 Room + 上传队列 → 投递 UploadWorker。 */
    fun onSubmitText(text: String) {
        val content = text.trim()
        bottomPanelVisible.value = false
        if (content.isBlank()) return
        viewModelScope.launch {
            val now = TimeUtil.nowIso()
            val localId = Ids.newLocalId()
            val record = Record(
                localId = localId,
                serverId = null,
                type = RecordType.TEXT,
                title = null,
                content = content,
                url = null,
                platform = null,
                status = RecordStatus.DONE,
                error = null,
                taskId = null,
                taskStatus = null,
                queuePosition = 0,
                syncStatus = SyncStatus.SYNCING,
                rawAudio = null,
                createdAt = now,
                updatedAt = now
            )
            repository.insert(record)
            repository.insertQueue(QueueItem(localId, "UPLOAD", null, "PENDING", 0, now))
            UploadQueue.scheduleNow(getApplication(), localId)
        }
    }

    /** 分享接收：有链接存 video，无链接当 text 存；落队列 + 投递；弹详情（补一句入口）。 */
    fun onVideoShare(raw: String) {
        if (raw.isBlank()) return
        viewModelScope.launch {
            val parsed = ShareParser.parse(raw)
            val now = TimeUtil.nowIso()
            val localId = Ids.newLocalId()
            val record = if (parsed.url != null) {
                Record(
                    localId = localId, serverId = null,
                    type = RecordType.VIDEO,
                    title = parsed.title,
                    content = null,
                    url = parsed.url, platform = Platform.infer(parsed.url),
                    status = RecordStatus.DONE, error = null,
                    taskId = null, taskStatus = null, queuePosition = 0,
                    syncStatus = SyncStatus.SYNCING, rawAudio = null,
                    createdAt = now, updatedAt = now
                )
            } else {
                Record(
                    localId = localId, serverId = null,
                    type = RecordType.TEXT,
                    title = null, content = raw.trim(),
                    url = null, platform = null,
                    status = RecordStatus.DONE, error = null,
                    taskId = null, taskStatus = null, queuePosition = 0,
                    syncStatus = SyncStatus.SYNCING, rawAudio = null,
                    createdAt = now, updatedAt = now
                )
            }
            repository.insert(record)
            repository.insertQueue(QueueItem(localId, "UPLOAD", null, "PENDING", 0, now))
            UploadQueue.scheduleNow(getApplication(), localId)
            detailTarget.value = localId
        }
    }

    /** 追加备注：本地先记；有 serverId 走 PATCH 队列，无 serverId 等 UPLOAD 成功后由 Worker 补。 */
    fun appendNote(record: Record, note: String) {
        val t = note.trim()
        if (t.isBlank()) return
        viewModelScope.launch {
            val entry = NoteEntry(Ids.newNoteId(), t, TimeUtil.nowIso())
            repository.update(record.copy(notes = record.notes + entry, updatedAt = TimeUtil.nowIso()))
            if (record.serverId != null) {
                repository.insertQueue(
                    QueueItem(
                        recordLocalId = record.localId,
                        operation = "PATCH",
                        payload = null,
                        status = "PENDING",
                        attempts = 0,
                        nextAttemptAt = TimeUtil.nowIso()
                    )
                )
                UploadQueue.scheduleNow(getApplication(), record.localId)
            }
        }
    }

    fun onMicPermissionResult(granted: Boolean) {
        micPermissionDenied.value = !granted
    }

    /** Token 失效提示条点击：清 token 回设置页 + 恢复队列门。 */
    fun clearToken() {
        viewModelScope.launch {
            TokenStore.clear(getApplication())
            TokenGate.resume()
        }
    }

    /** 详情刷新：有 serverId 才异步 GET，无 serverId 只读 Room。 */
    private fun refreshDetail(localId: String) {
        viewModelScope.launch {
            val record = repository.get(localId) ?: return@launch
            val serverId = record.serverId ?: return@launch
            try {
                val dto = ApiClient.api.getRecord(serverId).data ?: return@launch
                repository.update(dto.toRecord(record, synced = true))
            } catch (e: Exception) {
                // 拉取失败：保留 Room 缓存，不报错
            }
        }
    }

    companion object {
        private const val TAG = "ClipSubmit"

        fun factory(application: Application, repository: RecordRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    MainViewModel(application, repository) as T
            }
    }
}
