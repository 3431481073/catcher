package com.example.clip.asr

import android.content.res.AssetManager
import com.example.clip.util.FileLogger
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflinePunctuation
import com.k2fsa.sherpa.onnx.OfflinePunctuationConfig
import com.k2fsa.sherpa.onnx.OfflinePunctuationModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * 本机 ASR 引擎（sherpa-onnx）—— 只管"引擎的加载"和"一段音频 → 一段文本"。
 *
 * 架构：**VAD 负责在哪儿断，ASR 只负责这段是什么字**。
 * 这样换来两件事：① 没有音频长度上限 ② 单次解码的输入长度可控。
 *
 * 职责边界（按 CLAUDE.md §3「核心做成接口，UI 只是壳」）：
 *   · 这里不碰录音、不碰 VAD 的切段循环（都在 [com.example.clip.recorder.LocalAsrRecorder]）
 *   · 不碰 UI / 球状态（在 OverlayState）
 *   · 不碰落库 / 剪贴板（在 RecordingService + OverlayActions）
 *
 * **三个模型都从 assets 直读，不落磁盘**：
 *   · `assets/paraformer-small/model.int8.onnx`（约 78MB）+ `tokens.txt` —— 中文非流式 ASR
 *   · `assets/silero_vad.onnx`（约 0.6MB）—— 语音活动检测
 *   · `assets/punct-ct-transformer-zh/model.int8.onnx`（约 72MB）—— 标点恢复（CT-Transformer，String 进 String 出）
 *
 * 引擎常驻（不随每次录音重建）：Paraformer 加载一次要读 78MB，放进"点下去到球变红"的路径里会很慢。
 */
object SherpaAsr {

    private const val TAG = "SherpaAsr"

    /** 采样率：AudioRecord、VAD、ASR 三边必须一致。16k 是这两个模型的约定。 */
    const val SAMPLE_RATE = 16000

    private const val DIR = "paraformer-small"
    private const val MODEL = "$DIR/model.int8.onnx"
    private const val TOKENS = "$DIR/tokens.txt"
    private const val VAD_MODEL = "silero_vad.onnx"

    /** 标点模型：CT-Transformer 中英混合（vocab272727）。配置里只要这一个文件，词表嵌在 onnx 里。 */
    private const val PUNCT_DIR = "punct-ct-transformer-zh"
    private const val PUNCT_MODEL = "$PUNCT_DIR/model.int8.onnx"

    @Volatile private var recognizer: OfflineRecognizer? = null

    /** 标点引擎。null = 没加载成功，此时 [punctuate] 原样返回（降级，不挂链路）。 */
    @Volatile private var punct: OfflinePunctuation? = null

    @Volatile private var loadFailed = false

    /**
     * 加载 ASR 引擎（幂等、线程安全）。
     *
     * `@Synchronized` 不只是防重复加载 —— 它让"预热还没跑完就点了录音"这种情况
     * **等**预热结束，而不是再加载一份（两份 onnxruntime 会把内存翻倍）。
     */
    @Synchronized
    fun ensureLoaded(assets: AssetManager): Boolean {
        recognizer?.let { return true }
        if (loadFailed) return false
        return try {
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    paraformer = OfflineParaformerModelConfig(model = MODEL),
                    tokens = TOKENS,
                    // 2 线程：够跑（RTF 远小于 1），又不跟 UI 抢核
                    numThreads = 2,
                    provider = "cpu",
                    modelType = "paraformer",
                ),
            )
            recognizer = OfflineRecognizer(assetManager = assets, config = config)
            FileLogger.i(TAG, "ASR 引擎就绪：$DIR（paraformer-small int8 / cpu）")
            // 标点单独 try：它挂了不该让识别也挂（降级为"结果不带标点"，功能仍在）
            loadPunctuation(assets)
            true
        } catch (t: Throwable) {
            loadFailed = true
            FileLogger.e(TAG, "ASR 引擎加载失败（assets/$DIR 在不在？）", t)
            false
        }
    }

    /** 兜在 [ensureLoaded] 的锁里调用，不用单独同步。 */
    private fun loadPunctuation(assets: AssetManager) {
        if (punct != null) return
        try {
            punct = OfflinePunctuation(
                assetManager = assets,
                config = OfflinePunctuationConfig(
                    model = OfflinePunctuationModelConfig(
                        ctTransformer = PUNCT_MODEL,
                        numThreads = 2,
                        provider = "cpu",
                    ),
                ),
            )
            FileLogger.i(TAG, "标点引擎就绪：$PUNCT_MODEL")
        } catch (t: Throwable) {
            FileLogger.e(
                TAG,
                "标点引擎加载失败 —— 识别照常，但结果【不会带标点】（assets/$PUNCT_MODEL 在不在？）", t,
            )
        }
    }

    /**
     * 给一段文字加标点（String 进、String 出 —— 不涉及 token）。
     *
     * 🔴 **只在解码线程调用**：[OfflinePunctuation] 内部是**一个** native 指针，
     * `addPunctuation` **不是线程安全的**。目前唯一调用点是 `LocalAsrRecorder.decodeLoop()`
     * （单线程），所以天然安全 —— **将来谁要在别处调，必须先想清楚这件事**。
     *
     * **降级**：引擎没加载成功、或这一句异常，都**原样返回** ——
     * 标点是锦上添花，绝不能因为它把识别结果搞没。
     */
    fun punctuate(text: String): String {
        val engine = punct ?: return text
        return try {
            engine.addPunctuation(text).ifBlank { text }
        } catch (t: Throwable) {
            FileLogger.e(TAG, "加标点失败（本段降级为不加标点）", t)
            text
        }
    }

    /** 预热：在后台线程调，把 78MB 的加载藏进用户"看到球、点球"的这几秒里。 */
    fun warmUp(assets: AssetManager) {
        ensureLoaded(assets)
    }

    /**
     * 解码一整段音频 → 文本（非流式：喂完才能出结果）。
     *
     * ⚠️ **每段一条流，用完立刻 release** —— `OfflineStream` 是 native 句柄，
     * 不释放的话每说一句漏一个（本项目在别处吃过句柄泄漏的亏）。
     *
     * @return 文本；空/失败返回 null
     */
    fun recognize(samples: FloatArray): String? {
        val engine = recognizer ?: return null
        val stream = try {
            engine.createStream()
        } catch (t: Throwable) {
            FileLogger.e(TAG, "createStream 失败", t)
            return null
        }
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            // 非流式没有 inputFinished()：喂完整段，直接 decode 一次就是全部
            engine.decode(stream)
            engine.getResult(stream).text.trim().ifBlank { null }
        } catch (t: Throwable) {
            FileLogger.e(TAG, "解码失败（段长 ${samples.size} 样本）", t)
            null
        } finally {
            runCatching { stream.release() }
        }
    }

    /**
     * 新建一个 VAD 引擎。**每次录音新建、录完释放** —— 不复用，避免上一轮的残留状态
     * （`Vad` 有 `reset()`，但新建更不容易出错，代价只是读一个 0.6MB 的小模型）。
     *
     * 参数（每个都有理由，别随手改）：
     *  · `windowSize = 512` —— **正好等于 [LocalAsrRecorder] 的 `read()` 块大小**（512 样本 = 32ms @16k），
     *    一次 read 正好一个 VAD 窗口，不用另设缓冲
     *  · `minSilenceDuration = 0.35s` —— 比默认 0.25 长一点：**说话中间换气不会被切碎**。
     *    切碎了不丢字（段间会拼接），但边界变多
     *  · `minSpeechDuration = 0.25s` —— 比这还短的"段"会被 VAD **丢弃**。
     *    ⚠️ **这是整条链路里唯一会丢内容的地方**，0.25 秒大致是单个字的量级
     *  · `maxSpeechDuration = 15s` —— 默认 5 秒太短（一句话会被硬切几段）。
     *    这里限住的是**单次解码的输入长度**，让延迟可控
     */
    fun newVad(assets: AssetManager): Vad? = try {
        Vad(
            assetManager = assets,
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = VAD_MODEL,
                    threshold = 0.5f,
                    minSilenceDuration = 0.35f,
                    minSpeechDuration = 0.25f,
                    windowSize = 512,
                    maxSpeechDuration = 15.0f,
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
                provider = "cpu",
            ),
        )
    } catch (t: Throwable) {
        FileLogger.e(TAG, "VAD 加载失败（assets/$VAD_MODEL 在不在？）", t)
        null
    }
}
