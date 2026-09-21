package com.example.clip.recorder

import android.content.Context
import com.example.clip.service.RecordingService
import com.example.clip.ui.overlay.OverlayState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 语音转写结果的去向。 */
enum class VoiceDestination { CLIPBOARD, BODY, NOTE }

/**
 * 录音前台服务薄封装 + 进程内共享录音状态。
 * 悬浮球（服务里）和 overlay 面板都走这里，状态跨组件同源。
 */
object RecordingController {

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    /** 录音振幅 0..1（RecordingService 每 100ms 采样 maxAmplitude）。 */
    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude

    /** 本次语音转写结果去向：主面板"语音"按钮=剪贴板，卡片语音按钮=正文/备注。 */
    val recordingDestination = MutableStateFlow(VoiceDestination.CLIPBOARD)

    /** 最近一次语音记录的 localId（面板 observe 它拿转写结果填输入框）。 */
    val lastVoiceId = MutableStateFlow<String?>(null)

    fun start(context: Context, destination: VoiceDestination = VoiceDestination.CLIPBOARD) {
        recordingDestination.value = destination
        runCatching { RecordingService.start(context) }
        _isRecording.value = true
        _amplitude.value = 0f
        OverlayState.onRecordingStarted()
    }

    fun stop(context: Context) {
        runCatching { RecordingService.stop(context) }
        _isRecording.value = false
        _amplitude.value = 0f
        OverlayState.onRecordingStopped()
    }

    /** RecordingService 采样回调（内部用）。 */
    fun updateAmplitude(value: Float) {
        _amplitude.value = value
    }

    /** RecordingService 生成新记录 localId 时调用（内部用）。 */
    fun onVoiceRecordCreated(localId: String) {
        lastVoiceId.value = localId
    }
}
