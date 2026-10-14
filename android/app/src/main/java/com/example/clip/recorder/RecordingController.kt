package com.example.clip.recorder

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.example.clip.MainActivity
import com.example.clip.service.RecordingService
import com.example.clip.ui.overlay.OverlayState
import com.example.clip.util.FileLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 语音转写结果的去向。
 *
 * - `CLIPBOARD` 老链路：录 OGG → 传后端 `/transcribe` → 电脑上的 faster-whisper → 结果进剪贴板
 * - `BODY` / `NOTE`：卡片里的语音按钮，结果填进输入框
 * - `LOCAL_ASR` 新链路：**手机上用 sherpa-onnx 本地识别**，音频不出机器；
 *   识别完自动「写剪贴板 + 发后端存 type=voice + 存本地 Room」，不问用户
 */
enum class VoiceDestination { CLIPBOARD, BODY, NOTE, LOCAL_ASR }

/**
 * 录音前台服务薄封装 + 进程内共享录音状态。
 * 悬浮球（服务里）和 overlay 面板都走这里，状态跨组件同源。
 */
object RecordingController {

    private const val TAG = "RecordingController"

    /** 用户点了录音但当时没有麦克风权限 —— 授权回来后要自动接着录。 */
    @Volatile private var pendingLocalAsr = false

    /**
     * 面板上的「录音」按钮走这里：先查权限，没有就拉起到 MainActivity 去申请。
     *
     * **为什么不能在这里申请**：`requestPermissions` 只有 Activity 能调，
     * 而面板跑在 FloatingBallService 的悬浮窗里（进程里可能一个 Activity 都没有）。
     * 所以用 Intent + extra 把请求转给 MainActivity —— 和现成的
     * `EXTRA_CLIPBOARD_LOCAL_ID` / `EXTRA_PANEL_ACTION` 是同一个套路。
     */
    fun startLocalAsr(context: Context) {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            start(context, VoiceDestination.LOCAL_ASR)
            return
        }
        FileLogger.i(TAG, "没有麦克风权限 → 拉起 MainActivity 申请")
        pendingLocalAsr = true
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MainActivity.EXTRA_REQUEST_MIC, true)
            )
        }.onFailure {
            pendingLocalAsr = false
            FileLogger.e(TAG, "拉起 MainActivity 申请权限失败", it)
        }
    }

    /** 授权结果回来时由 MainActivity 调：返回"这次是不是为了录音才申请的"。 */
    fun consumePendingLocalAsr(): Boolean {
        val p = pendingLocalAsr
        pendingLocalAsr = false
        return p
    }

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
        // 失败必须记下来：球会照变红，但根本没在录 —— 不记日志的话就是个静默假动作
        runCatching { RecordingService.start(context) }
            .onFailure { FileLogger.e(TAG, "启动录音服务失败（前台服务被拒？）", it) }
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
