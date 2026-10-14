package com.example.clip.ui.overlay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.example.clip.domain.model.RecordType
import com.example.clip.recorder.RecordingController
import com.example.clip.recorder.VoiceDestination

/** 悬浮球视觉状态色（v12）。 */
enum class BallVisualState { IDLE, REC, BUSY, DONE_VOICE, DONE_TEXT, DONE_VIDEO, FAILED }

/** 卡片（底部 sheet）模式。 */
enum class CardMode { TEXT_INPUT, VIDEO_INPUT, DETAIL }

/** 待补的视频分享信息（平台由链接推断，识别不了为 null）。 */
data class PendingVideo(val title: String?, val url: String?, val platform: String?)

/**
 * overlay 全局状态 + 球状态机（单一状态源）。
 *
 * 状态机（只有语音走 REC→BUSY→DONE，文本/视频直达 DONE）：
 * ```
 * IDLE ──录音──▶ REC ──停止──▶ BUSY ──识别完成──▶ DONE_VOICE ──1.5s──▶ IDLE
 *                                   └─失败/空文本────▶ FAILED ──1.5s──▶ IDLE
 * IDLE ──提交文本──▶ DONE_TEXT ──1.5s──▶ IDLE
 * IDLE ──提交视频──▶ DONE_VIDEO ──1.5s──▶ IDLE
 * ```
 * 闪色时长见闪色默认值 [flash]（1500ms）。
 */
object OverlayState {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var flashJob: Job? = null

    /** 主面板开/关（点球切换）。与 cardMode 互斥。 */
    val panelOpen = MutableStateFlow(false)

    /** 卡片窗：null=关；TEXT_INPUT/VIDEO_INPUT=开。与 panelOpen 互斥。 */
    val cardMode = MutableStateFlow<CardMode?>(null)

    /** ACTION_SEND 进来的待补视频（手动点「视频」则为 null）。 */
    val pendingVideo = MutableStateFlow<PendingVideo?>(null)

    /** 详情页当前记录 localId（点面板记录进入 DETAIL 卡片）。 */
    val detailLocalId = MutableStateFlow<String?>(null)

    /** 球状态唯一来源（状态机当前态）。 */
    private val _ballState = MutableStateFlow(BallVisualState.IDLE)
    val ballState: StateFlow<BallVisualState> = _ballState

    /** 处理中（语音上传/转写）：期间点球无效（可拖动）。 */
    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy

    // ---- 状态机入口（由 RecordingController / SyncService / OverlayCard 调用） ----

    fun onRecordingStarted() {
        _isBusy.value = false
        _ballState.value = BallVisualState.REC
    }

    fun onRecordingStopped() {
        _isBusy.value = true
        _ballState.value = BallVisualState.BUSY
    }

    fun onVoiceDone() {
        _isBusy.value = false
        // 剪贴板与本机 ASR 都闪绿勾；卡片语音（BODY/NOTE）的结果要填进输入框，不闪色
        if (isCardVoice()) _ballState.value = BallVisualState.IDLE
        else flash(BallVisualState.DONE_VOICE)
    }

    fun onVoiceFailed() {
        _isBusy.value = false
        if (isCardVoice()) _ballState.value = BallVisualState.IDLE
        else flash(BallVisualState.FAILED)
    }

    private fun isCardVoice(): Boolean {
        val d = RecordingController.recordingDestination.value
        return d == VoiceDestination.BODY || d == VoiceDestination.NOTE
    }

    // ---- 文本/视频提交状态机：提交→busy，后端确认成功→done，失败→idle ----

    fun onSubmitStarted() {
        _isBusy.value = true
        _ballState.value = BallVisualState.BUSY
    }

    fun onSubmitDone(type: RecordType) {
        _isBusy.value = false
        if (type == RecordType.TEXT) flash(BallVisualState.DONE_TEXT) else flash(BallVisualState.DONE_VIDEO)
    }

    fun onSubmitFailed() {
        _isBusy.value = false
        _ballState.value = BallVisualState.IDLE
    }

    /** 完成闪色：durationMs 后自动回 IDLE（再次触发会重置计时）。 */
    private fun flash(state: BallVisualState, durationMs: Long = 1500L) {
        _ballState.value = state
        flashJob?.cancel()
        flashJob = scope.launch {
            delay(durationMs)
            if (_ballState.value == state) _ballState.value = BallVisualState.IDLE
        }
    }
}
