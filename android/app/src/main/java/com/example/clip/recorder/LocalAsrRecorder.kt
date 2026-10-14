package com.example.clip.recorder

import android.annotation.SuppressLint
import android.content.res.AssetManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.example.clip.asr.SherpaAsr
import com.example.clip.util.FileLogger
import com.k2fsa.sherpa.onnx.Vad
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.abs
import kotlin.math.log10

/**
 * 本机录音 + VAD 切段 + 分段识别。
 *
 * **为什么要两个线程**：Paraformer 是**非流式**的 —— 一段 5~15 秒的音频，
 * 解码要几百毫秒到一秒多。如果**在录音线程里同步解码**，这段时间就不 `read(AudioRecord)` 了，
 * 麦克风的环形缓冲会**溢出丢音频**。用户要的是"说多久都行、不丢内容"，所以：
 *
 * ```
 * 录音线程（紧循环，绝不阻塞）              解码线程（可以慢慢来）
 *   read(512) ─┬─ dB 映射 + attack-release 包络 → 音量条
 *              ├─ vad.acceptWaveform
 *              └─ while(!vad.empty()) ──put──▶ 队列 ──take──▶ SherpaAsr.recognize
 *                  front()+pop()                              → punctuate() → results
 * ```
 *
 * **不缓存整段录音**：VAD 吐一段 → 解码 → **只留文本**，那段 PCM 随即被回收。
 * 内存上界 ≈ `maxSpeechDuration`(15s) 对应的约 1MB + 队列里积压的几段。
 *
 * @param onAmplitude 0..1 的实时音量，在录音线程上回调（约 31 次/秒）
 * @param onDone      识别结束回调，**恰好一次**；全部为空时传 null
 */
class LocalAsrRecorder(
    private val assets: AssetManager,
    private val onAmplitude: (Float) -> Unit,
    private val onDone: (String?) -> Unit,
) {

    private companion object {
        const val TAG = "LocalAsrRecorder"

        /** 512 样本 = 32ms @16k。**必须等于 [SherpaAsr.newVad] 里的 windowSize**。 */
        const val CHUNK = 512

        /** 点停止后再收一会儿：AudioRecord 内部缓冲里还有已录到、没交出来的尾音。 */
        const val TAIL_MS = 150L

        /**
         * 兜底上限。VAD 切段之后**本来没有时长限制**（这正是选 Paraformer 的理由），
         * 这条只是防"忘了点停止"录一整夜：30 分钟足够任何人说一句话。
         */
        const val MAX_RECORD_MS = 30 * 60 * 1000L

        // ── 音量条包络（attack-release）──────────────────────────────
        // 为什么需要：每 32ms 直接跟随峰值，说话时竖条会"一闪一闪"地碎。
        // 音频表的标准做法是"快上慢落" —— 声音一出来立刻弹上去，停了缓缓落。

        /** **快上**：约 2.5 帧（80ms）到目标值的 90%，视觉上是"弹上去"而不是"爬上去"。 */
        const val ATTACK = 0.6f

        /** **慢落**：每帧乘这个数（衰减 15%）。降到 50% 约 137ms、降到 10% 约 453ms。 */
        const val RELEASE = 0.85f

        /**
         * dB 映射的下限：-60dB 以下算底噪，条归 0。
         *
         * 映射是 `(db + DB_FLOOR) / DB_FLOOR`，所以：
         * `0dB`(满幅)=1.00 · `-20dB`(**正常说话**)=0.67 · `-40dB`(小声)=0.33 · `-60dB`=0。
         * 具体 dB→条高 的对照表见 `docs/` 里的记录。
         */
        const val DB_FLOOR = 60f
    }

    @Volatile private var running = false
    private var recordThread: Thread? = null
    private var decodeThread: Thread? = null

    /**
     * 音量条的包络值（0..1）。
     * 🔴 **只由录音线程读写**（每 32ms 一次），不需要同步。
     * 每次录音新建一个 recorder，所以它天然从 0 开始，不用手动 reset。
     */
    private var level = 0f

    /** 切出来的音频段。只有录音线程写、只有解码线程读；队列自身保证线程安全且**不阻塞写入**。 */
    private val segments = LinkedBlockingQueue<FloatArray>()

    /** 解码结果，按段顺序追加。只在解码线程写；录音线程 `join()` 之后才读（join 建立 happens-before）。 */
    private val results = ArrayList<String>()

    /**
     * 队列结束哨兵。
     * 🔴 **不能靠"线程还活着吗"来决定解码线程何时退出** —— 那样最后一段会被丢掉。
     * 用一个空数组显式表示"没有更多了"，解码线程排空队列后才退出。
     */
    private val endOfStream = FloatArray(0)

    fun start() {
        if (recordThread != null) return
        running = true
        decodeThread = Thread({ decodeLoop() }, "local-asr-decode").apply {
            isDaemon = true
            start()
        }
        recordThread = Thread({ loop() }, "local-asr-record").apply {
            isDaemon = true
            start()
        }
    }

    /** 请求停止；真正结束（含尾音、尾段和解码）以 [onDone] 为准。 */
    fun stop() {
        running = false
    }

    // ── 录音线程：读 → 音量条 + VAD → 段入队 ────────────────────────────────

    private fun loop() {
        var record: AudioRecord? = null
        var vad: Vad? = null
        try {
            if (!SherpaAsr.ensureLoaded(assets)) error("ASR 引擎未就绪")
            val v = SherpaAsr.newVad(assets) ?: error("VAD 未就绪")
            vad = v
            val r = openRecord()
            record = r

            val shorts = ShortArray(CHUNK)
            val floats = FloatArray(CHUNK)
            val deadline = System.currentTimeMillis() + MAX_RECORD_MS
            r.startRecording()

            while (running && System.currentTimeMillis() < deadline) {
                if (!feed(r, shorts, floats, v)) break
            }
            // 循环退出了但 running 还是 true ⇒ 是撞上限退出的（不是用户停的）
            if (running) FileLogger.w(TAG, "到达 ${MAX_RECORD_MS / 60000} 分钟上限，自动停止")

            // 尾音：点停止之后缓冲里剩下的那点，别丢
            val tailUntil = System.currentTimeMillis() + TAIL_MS
            while (System.currentTimeMillis() < tailUntil) {
                if (!feed(r, shorts, floats, v)) break
            }

            // 收尾：把尾部**还没闭合**的那段吐出来（不做这步，最后半句话会留在 VAD 内部缓冲里）
            runCatching { v.flush() }
            drain(v)
        } catch (t: Throwable) {
            FileLogger.e(TAG, "录音失败", t)
        } finally {
            onAmplitude(0f)
            record?.let {
                runCatching { it.stop() }
                runCatching { it.release() }
            }
            vad?.let { runCatching { it.release() } }
            // 告诉解码线程"没有更多了"；offer 不阻塞、不抛
            segments.offer(endOfStream)
        }

        runCatching { decodeThread?.join() }
        val text = results.joinToString("").trim()
        FileLogger.i(TAG, "识别完成：${results.size} 段 / ${text.length} 字")
        onDone(text.ifBlank { null })
    }

    /**
     * 读一块 → 同一份数据喂两处（音量条 + VAD）。
     *
     * @return false = 致命错误（麦克风被抢/句柄失效），调用方必须跳出循环。
     *   **不能 continue** —— read() 出错时立刻返回，继续 while 就是空转烧 CPU。
     */
    private fun feed(record: AudioRecord, shorts: ShortArray, floats: FloatArray, vad: Vad): Boolean {
        val n = record.read(shorts, 0, CHUNK)
        if (n < 0) {
            FileLogger.e(TAG, "AudioRecord.read 出错：$n")
            return false
        }
        if (n == 0) return true

        var maxAbs = 0
        for (i in 0 until n) {
            val v = shorts[i].toInt()
            val a = abs(v)
            if (a > maxAbs) maxAbs = a
            floats[i] = v / 32768f
        }
        // 音量条分两步，都在**数据层**做（31Hz），不放渲染层（60fps）——
        // 放渲染层的话"条的跳法"会依赖掉帧情况，这里才是唯一真相。
        //   ① dB 映射：把"幅度"换成"分贝"
        //   ② attack-release 包络：快上慢落，消掉 32ms 一跳的"碎"
        //
        // 🔴 为什么不再用 sqrt + 增益：那套是把**低端顶上去**，结果是"小声就满格、大声涨不动"。
        // dB 映射相反 —— 低端压平、高端拉开，动态范围才够宽。
        val peak = maxAbs / 32768f
        val db = if (peak > 0f) 20f * log10(peak) else -DB_FLOOR   // 防 log(0)
        val target = ((db + DB_FLOOR) / DB_FLOOR).coerceIn(0f, 1f)
        level = if (target > level) {
            level + (target - level) * ATTACK   // 快上：一出来就弹上去
        } else {
            level * RELEASE                     // 慢落：停了缓缓落，不是硬切
        }
        onAmplitude(level)

        vad.acceptWaveform(if (n == CHUNK) floats else floats.copyOf(n))
        drain(vad)
        return true
    }

    /**
     * 把 VAD 里已经闭合的段全部取出来入队。
     * 🔴 **必须 `front()` 取、`pop()` 移除，两步** —— sherpa 的 `pop()` 在 Kotlin 层返回 `Unit`，
     * 拿不到段（`private external fun pop(ptr: Long)` 没写返回类型）。这是官方的用法。
     */
    private fun drain(vad: Vad) {
        while (!vad.empty()) {
            val seg = vad.front()
            vad.pop()
            if (seg.samples.size > 0) segments.put(seg.samples)
        }
    }

    @SuppressLint("MissingPermission")  // 调用方（RecordingController）已经先查过 RECORD_AUDIO
    private fun openRecord(): AudioRecord {
        val minBuf = AudioRecord.getMinBufferSize(
            SherpaAsr.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        // 约 2 秒的缓冲：解码在另一个线程，但系统调度抖动时也要有地方放
        val bufSize = maxOf(minBuf, SherpaAsr.SAMPLE_RATE * 2 * 2)
        val r = AudioRecord(
            // VOICE_RECOGNITION：带降噪/AGC，识别质量比裸 MIC 好
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SherpaAsr.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize,
        )
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            error("AudioRecord 初始化失败（麦克风被占用？）")
        }
        return r
    }

    // ── 解码线程：段出队 → 整段解码 → 文本按序追加 ──────────────────────────

    private fun decodeLoop() {
        while (true) {
            val seg = try {
                segments.take()
            } catch (e: InterruptedException) {
                break
            }
            if (seg.size == 0) break  // 哨兵
            val raw = SherpaAsr.recognize(seg) ?: continue
            // **每段独立加标点**：在这一层做（不是录完统一做）——
            // 开销分摊在录音过程中，用户点停止后的等待只含最后一段的标点。
            // 🔴 punctuate 只能在这个线程调：OfflinePunctuation 内部是单个 native 指针，不是线程安全的。
            val text = SherpaAsr.punctuate(raw)
            if (text.isNotBlank()) results.add(text)
        }
    }
}
