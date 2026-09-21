package com.example.clip.data.token

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 401 暂停门：保存的 token 失效时暂停上传队列。
 * - 拦截器检测 401 → [pause]（记录 pausedAt，首次暂停才记）。
 * - 用户更新 token → [resume]（TokenStore.save）。
 * - 拦截器收到 2xx 且非 /health → [resume]（token 重新有效，幂等）。
 * - [resumeIfExpired]：暂停超 5 分钟自动恢复（防 Doze 死锁）。
 */
object TokenGate {

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused

    private const val RESUME_AFTER_MILLIS = 5 * 60 * 1000L  // 5 分钟兜底

    @Volatile
    private var pausedAt = 0L

    fun pause() {
        // 首次暂停才记录时间戳，后续 401 不刷新（兜底从首次暂停起算）
        if (!_isPaused.value) {
            pausedAt = System.currentTimeMillis()
            _isPaused.value = true
        }
    }

    fun resume() {
        _isPaused.value = false  // StateFlow 幂等（值相同不 emit）
    }

    /** 暂停超 5 分钟自动恢复。返回是否触发了恢复。 */
    fun resumeIfExpired(): Boolean {
        if (_isPaused.value && pausedAt > 0 &&
            System.currentTimeMillis() - pausedAt > RESUME_AFTER_MILLIS
        ) {
            resume()
            return true
        }
        return false
    }
}
