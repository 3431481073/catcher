package com.example.clip.ui.remote

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.example.clip.R
import com.example.clip.util.FileLogger

/**
 * 远程页的两个提示音。
 *
 * 用 **SoundPool** 而不是 MediaPlayer —— 这是 <1s 的短音效，SoundPool 加载后常驻内存、
 * 播放延迟低、可并发；MediaPlayer 每次都要 prepare，那是给长音频的。
 *
 * 音色**对齐 Lattice 的内置音**（`app/src/renderer/src/sound.ts` 的 chime / alert）：
 * ```
 * chime  880 → 1318.5 Hz            上行两音 · "完成"
 * alert  1046.5 → 784 → 587.33 Hz   下行三音 · "需要你"
 * ```
 * 两个音色**方向相反**，余光里不会混。WAV 是脚本合成的正弦/三角 + 指数衰减包络，
 * 无版权问题（见 res/raw/ 两个文件）。
 *
 * **"只在 App 前台播"由调用方保证**：轮询循环挂在 `repeatOnLifecycle(RESUMED)` 上，
 * 后台根本不跑，自然不会调到这里。
 */
object RemoteSounds {

    private const val TAG = "RemoteSounds"

    private var pool: SoundPool? = null
    private var doneId = 0
    private var attentionId = 0

    /** 已加载完成的 sampleId。SoundPool.load 是异步的，没加载完就播是静音的。 */
    private val ready = HashSet<Int>()

    /** 设置开关的落点。本轮没有设置 UI，默认开。 */
    @Volatile
    var enabled: Boolean = true

    fun init(context: Context) {
        if (pool != null) return
        val p = SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // 🔴 走【媒体】流，**不走通知流**。
                    //
                    // 实测踩过：手机关静音（状态栏那个铃铛斜杠）时，USAGE_NOTIFICATION_EVENT
                    // 会被系统直接掐掉 —— 代码明明触发了、就是没声，而且没有任何报错。
                    // 媒体流和静音模式是两条**独立**的音量流，静音下照样出声。
                    //
                    // 代价：手机静音时也会响。对"App 前台的完成/待答提示"来说这正是要的行为
                    // （它本来就不该被当成一条系统通知）。
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
        p.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) ready.add(sampleId) else FileLogger.e(TAG, "提示音加载失败 sampleId=$sampleId status=$status")
        }
        doneId = p.load(context, R.raw.chime, 1)
        attentionId = p.load(context, R.raw.alert, 1)
        pool = p
    }

    /** 全部跑完 —— 上行两音。 */
    fun done() = play(doneId)

    /** 需要你回答 —— 下行三音。 */
    fun attention() = play(attentionId)

    private fun play(sampleId: Int) {
        if (!enabled || sampleId == 0) return
        val p = pool ?: return
        // 没加载完就**跳过、不排队** —— 一声迟到的提示音比不响更糟。
        // 但别静默跳过：不记这一行的话，"没响"和"压根没触发"在事后分不开。
        if (sampleId !in ready) {
            FileLogger.w(TAG, "提示音还没加载好，跳过 sampleId=$sampleId")
            return
        }
        p.play(sampleId, 1f, 1f, 1, 0, 1f)
    }
}
