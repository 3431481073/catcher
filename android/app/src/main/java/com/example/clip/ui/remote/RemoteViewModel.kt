package com.example.clip.ui.remote

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.clip.ClipApp
import com.example.clip.collector.UsageCollector
import com.example.clip.config.ApiConfig
import com.example.clip.data.api.ApiClient
import com.example.clip.data.api.ApiError
import com.example.clip.data.api.ApiService
import com.example.clip.data.api.LatticeAnswerRequest
import com.example.clip.data.api.LatticeDataDto
import com.example.clip.data.api.LatticeEventDto
import com.example.clip.data.api.LatticeInputRequest
import com.example.clip.data.api.LatticePaneDto
import com.example.clip.data.api.LatticeWatchRequest
import com.example.clip.service.UploadQueue
import com.example.clip.util.ClipboardWriter
import com.example.clip.util.FileLogger
import com.example.clip.util.Ids
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import retrofit2.HttpException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** 后端错误信封，只取 error —— 用来把 code 翻成人话。 */
private class ErrBody(val error: ApiError?)

/** 本地缓存的 pane 列表的反序列化类型（Gson 要它，擦除后拿不到泛型）。 */
private val paneListType = object : TypeToken<List<LatticePaneDto>>() {}.type

/** 一条发送的去向。`Duplicate` = 这次请求命中了 Lattice 的 nonce 去重 —— 即上一次其实已经送达。 */
enum class SendState { Pending, Sent, Duplicate, Failed }

/**
 * 一条发送的回执。
 *
 * **为什么要它**：原来发送是"一次性"的 —— 失败写进全局 toast，成功什么都不留。
 * 于是「没发出去」和「发出去了但响应丢了」在手机上完全同形，用户只能靠猜，
 * 猜不出来就再点一次，而再点一次会**真的重复发一条**。
 *
 * `nonce` 在重试时**原样复用**：上一次如果其实已经落到 Lattice，它会回
 * `duplicate:true` 而不是再写一遍 PTY（60 秒窗口，见 apiServer.ts:1376-1380）。
 * 这是"重试不会发两遍"的唯一依据。
 */
data class SendReceipt(
    val id: String,
    val nonce: String,
    val text: String,
    val state: SendState,
    /** 仅 Failed 有值 —— 直接给用户看的原因 */
    val reason: String? = null,
)

private class ReceiptResult(val state: SendState, val reason: String? = null)

/**
 * WS 连接状态 —— 顶栏那个小点，也是横幅的唯一判据。
 *
 * 有了它之后，"在线/断线"不再需要靠"连续失败几次"去猜（那套 `failStreak` 已经删掉）。
 */
enum class ConnState { Connecting, Connected, Reconnecting, Down }

/**
 * 远程页的状态与轮询。
 *
 * **轮询循环不由 ViewModel 自己起** —— 它是个 suspend 函数，由 Composable 用
 * `repeatOnLifecycle(RESUMED)` 驱动。这样"进页面才跑、退后台停、切回记录 Tab 也停"
 * 是同一件事，不必维护三套开关。
 *
 * watch（应答意愿）就挂在这个循环上：
 *   进入/回前台 → 声明 3600 秒；每 5 分钟续期；**协程一被取消就撤销**。
 * 撤销必须包 `NonCancellable` —— 取消后普通 suspend 调用会立刻抛
 * CancellationException，撤销就永远发不出去，Lattice 那边会一直以为有人要应答。
 */
class RemoteViewModel(
    application: Application,
    private val api: ApiService
) : AndroidViewModel(application) {

    val panes = MutableStateFlow<List<LatticePaneDto>>(emptyList())
    val currentRef = MutableStateFlow<String?>(null)
    val paneTitle = MutableStateFlow<String?>(null)
    /** off / idle / busy / confirm */
    val paneState = MutableStateFlow<String?>(null)
    val confirmData = MutableStateFlow<LatticeDataDto?>(null)
    val screenLines = MutableStateFlow<List<String>>(emptyList())

    /**
     * **摘要层** —— 由 WS 事件（`hello` / `pane.state`）里的 `data` 直接渲染出来的东西。
     *
     * 🔴 **为什么要这一层**（2026-10-11）：实测挂在 WS 上抓的——
     *   · `pane.state` 在 `busy` 期间 **45 秒 0 次**，`pane.output` 45 秒 106 次
     *     但**每条只有 `{bytes:N}`、不带正文**；
     *   · 所以"内容"过去**只能靠 REST**，而 `pane.state` 到了只更新颜色和音效
     *     ⇒ **音效响了、画面还停在上一屏**，而且 `idle` 之后不再有 `pane.output`（没有 ping），
     *     那一屏就**永远不动了**。
     *   · 而 `pane.state` 的 `data` 其实是**带内容的**：`idle` → `lastTurn`（438~556 B），
     *     `busy` → `screenTail`（871 B）+ `activity`。**够铺一屏摘要，不够当滚动历史。**
     *
     * ⇒ 分成两层：**摘要层零延迟（事件到就渲染）**，**内容层（[screenLines]）管完整屏幕和滚动
     *   历史**（实测 REST 的 `tail&lines=2000` 有 24,001 B / 256 行，比摘要大 27 倍）。
     *   `null` = 还没有摘要；`[]` 不会被写进来。
     *
     * 🔴 **两个流各写各的，不互相覆盖** —— 上层 `OutputArea` 按优先级挑一个显示
     *   （完整内容优先，摘要是兜底）。
     */
    val paneSummary = MutableStateFlow<List<String>?>(null)
    val errorBanner = MutableStateFlow<String?>(null)
    val loading = MutableStateFlow(true)
    val panesSheetOpen = MutableStateFlow(false)
    /**
     * 一次性提示，显示完调 clearToast。
     *
     * 🔴 **发送失败不再走这里**（2026-10-08）。原来发送失败和"复制成功"共用一个槽位，
     * 而它是个单值 StateFlow + 取走即清 —— 两次失败挨得近就互相覆盖，用户看到的是
     * "攒了几次才蹦一个"。发送的去向现在归 [sendReceipts]，一条一个状态。
     */
    val toast = MutableStateFlow<String?>(null)

    /** 最近的发送回执（新的在后）。UI 取尾部渲染 1~3 条。 */
    val sendReceipts = MutableStateFlow<List<SendReceipt>>(emptyList())

    /** 手动刷新进行中 —— 顶栏那个 ↻ 据此转圈。 */
    val refreshing = MutableStateFlow(false)

    /**
     * **正在切换窗格**：已经点了、乐观 UI 也换了，但新窗格的数据还没回来。
     *
     * 这段时间里界面处于"指向新窗格、显示旧内容/空内容"的过渡态，用户看不出来
     * 现在算哪边 —— 所以：输入区显示"切换中…"，发送按钮禁用。
     *
     * 注意这不是**正确性**问题（`sendInput` 是在点击那一刻同步取 `currentRef` 的，
     * 不存在竞态），纯粹是"让用户知道现在算哪边"。
     */
    val switching = MutableStateFlow(false)

    /**
     * 正在进行的窗格切换。
     *
     * 🔴 **新的一次会取消上一次** —— 不取消的话两条协程会同时跑，各自带着**自己捕获的 `id`**
     * 回写 `buffer` / `screenLines`，**内容区会被旧窗格的数据覆盖**。
     * （实测过：后端 access log 里出现过"切到 pane-2 之后 3 秒还在拉 pane-5 的 text"。）
     *
     * 它同时是 `finally` 里判断"我是不是当前那一次"的依据 —— 见 `selectRef`。
     */
    private var refSwitchJob: Job? = null

    /**
     * **每个窗格各自的输入草稿。**
     *
     * 切窗格时输入框不是"清空"，而是**立刻换成那个窗格自己的草稿** —— 于是：
     *  · A 里打了一半的话，不会因为"去看一眼 B"就丢掉；
     *  · 也不会被顺手误发到 B（那是切换完成后真正危险的那件事）。
     * 发送时清掉**对应窗格**那一份：内容进了 [sendReceipts]，重试由它负责。
     *
     * 只在内存里，进程重启就没了。（要跨重启得进 prefs，本轮没做。）
     */
    private val drafts = MutableStateFlow<Map<String, String>>(emptyMap())

    /** 当前窗格的草稿（没有就是空串）。输入框直接绑它 —— 换窗格它自己就跟着换。 */
    val draft: StateFlow<String> =
        combine(currentRef, drafts) { ref, map -> if (ref == null) "" else map[ref].orEmpty() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    fun setDraft(text: String) {
        val ref = currentRef.value ?: return
        drafts.value = drafts.value + (ref to text)
    }

    /** WS 连接状态。顶栏那个点 + 横幅都只看它。 */
    val connState = MutableStateFlow(ConnState.Connecting)

    /**
     * WS 事件队列。
     *
     * OkHttp 的回调跑在**它自己的后台线程**上，不能在那里直接做这些事
     * （改 buffer、判音效、发起 REST）—— 所以回调只做一件事：投递。
     */
    private val wsEvents = Channel<LatticeEventDto>(Channel.BUFFERED)

    /**
     * 输出抓取的合并信号。**`CONFLATED` 就是"合并"本身。**
     *
     * Lattice 每 400ms 推一个 `pane.output`，连续输出时到 2.5 次/秒；塌缩成
     * "还有一个待办"之后，抓取节奏由消费端决定，不会被事件推着跑。
     */
    private val outputPing = Channel<Unit>(Channel.CONFLATED)

    /** `panes.changed` 的节流信号 —— 实测 5 分钟能来 24 次，不能每次都打 REST。 */
    private val panesPing = Channel<Unit>(Channel.CONFLATED)

    private var lastOutputAt = 0L
    /** 最后一次连上 WS 的时刻 —— 用来判"连续失败超过 1 分钟"（顶栏红点）。 */
    private var lastConnectedAt = 0L

    private val gson = Gson()

    // ── 提示音用的状态记忆（判据照搬 Lattice，见 onStateObserved）──
    private var lastState: String? = null
    private var sawBusy = false
    private var doneTimer: Job? = null

    /**
     * 应答意愿状态：`null` = 还没声明过，`true` = 已生效，`false` = 上次声明失败。
     *
     * 这个字段是**新增的可见性**：原来 watch 的失败全被 `runCatching` 吞掉，
     * 而"声明失败"的表现恰好就是"手机上答不了" —— 正是用户在报的那个现象。
     */
    val watchOk = MutableStateFlow<Boolean?>(null)

    /** 串行化 watch 调用：撤销（onDispose 发起）和声明（新协程发起）可能挨得很近。 */
    private val watchLock = Mutex()

    /** 应答请求进行中：按钮据此变灰 + 显示"提交中…"，也是防重复点的闸。 */
    val answering = MutableStateFlow(false)

    private val prefs = application.getSharedPreferences("remote", Application.MODE_PRIVATE)

    /** 输出区的本地缓冲。**不等于** screenLines：内容没变时不发布，避免每拍无谓重组。 */
    private var buffer: List<String> = emptyList()

    /**
     * 记下"哪些窗格是全屏 TUI（altScreen）"，值是**记下来的时刻**。
     *
     * 🔴 **为什么只能自己记**：`LatticePaneDto`（`/status` 和 `/panes` 的返回）里
     * **没有这个字段** —— 只有 `/text` 的响应（`LatticeScreenDto.altScreen`）才有。
     * 所以第一次碰到某个窗格，只能先探一发 `tail`才知道。
     *
     * ⚠️ **带 TTL（[ALT_MEMO_TTL_MS]）**：窗格**退出**全屏 TUI 之后（claude 退出了），
     * 一直信这个记录就会永远走 `mode=screen` —— 那一屏**内容是对的**，但**滚动历史拿不回来**。
     * 过期后自动退回"先探 tail"的稳妥路径，所以是自愈的。
     */
    private val altScreenMemo = mutableMapOf<String, Long>()

    /** 下一拍要不要整块重取（进页面 / 切窗格 / 接不上时置真）。 */
    private var needFullOutput = true

    fun clearToast() {
        toast.value = null
    }

    /** 打开 ⇄ 列表：**立即刷一次**，否则看到的是进页面那一刻的快照。 */
    fun openPanesSheet() {
        panesSheetOpen.value = true
        viewModelScope.launch { loadPanes() }
    }

    fun closePanesSheet() {
        panesSheetOpen.value = false
    }

    /**
     * 远程页的主循环：**一条 WebSocket，加上它自己的重连**。
     *
     * 取消它 = 离开远程页（由 `RemoteScreen` 的 `LaunchedEffect(Unit)` 驱动）。
     * 它**不**挂在 `repeatOnLifecycle(RESUMED)` 上 —— 退后台要接着收，
     * 这是和上一版轮询最大的区别。
     *
     * 为什么从轮询换成 WS：实测穿 Cloudflare Tunnel 的 SSE 会在 10~100 秒之间被
     * 不可预测地掐断（两轮 88.8s / 96.9s），而**同一条隧道上 WS 两轮都挂满了 299 秒**。
     *
     * watch（应答意愿）跟着连接走：连上就声明，之后按 [WATCH_RENEW_MS] 续期。
     * **撤销仍然只由 `revokeWatch()` 触发**（`DisposableEffect`）—— 那才是"真的离开了远程页"。
     */
    suspend fun runStream() {
        // 进页面先复位：音效记忆（否则"上次退出去时是 confirm"会诈响）、
        // 刷新转圈标志（上次转到一半退出会把它留在 true）。
        lastState = null
        sawBusy = false
        cancelDoneChime()
        refreshing.value = false
        switching.value = false // 上次切到一半就退出会把它留在 true，那会把发送按钮锁死
        // 🔴 **先把上次的 pane 列表铺上**（本地缓存，**0 个往返**）。
        //    不铺的话，进页面到 `/panes` 或 WS hello 回来那几秒里，sheet 显示的是
        //    「没有可切换的窗格」—— 用户会以为电脑上压根没开 Lattice。
        //    几秒后 REST / hello 到了会**覆盖**它，所以过期内容最多存在几秒。
        restoreCachedPanes()

        // `coroutineScope` 给下面几个 `launch` 提供接收者，并保证：
        // 这个函数一被取消（离开远程页），三条子协程一起收摊。
        coroutineScope {
            launch { consumeEvents() }
            launch { outputLoop() }
            launch { panesLoop() }

            var attempt = 0
            var lastWatch = 0L
            while (isActive) {
                connState.value = when {
                    attempt == 0 -> ConnState.Connecting
                    System.currentTimeMillis() - lastConnectedAt > DOWN_AFTER_MS -> ConnState.Down
                    else -> ConnState.Reconnecting
                }

                // 🔴 首屏不靠 hello：实测"建连 + Lattice 现算 hello"要 5~9 秒
                //    （hello 里 buildAllPanes 要给每个窗格读 transcript）。
                //    所以进来先并发打一发 REST 抢首屏，hello 只当"确认 + 重同步"。
                // 🔴 **必须吞异常**：它跑在子协程里，抛出去会顺着 coroutineScope 冒到
                //    LaunchedEffect —— 那是**直接崩进程**。（实测崩过一次：Lattice 重启
                //    后窗格 id 变了，首屏那发 404 没人接，App 进来三四秒就闪退。）
                if (attempt == 0) launch { guardQuietly("首屏刷新") { refreshAll() } }

                val closed = CompletableDeferred<Unit>()
                val ws = ApiClient.webSocketClient.newWebSocket(
                    Request.Builder().url(wsUrl()).build(),
                    wsListener(closed),
                )
                closed.await()
                ws.cancel()

                // 连过一轮就续一次 watch（也兜住"Lattice 刚重启、换了实例"）
                if (currentRef.value != null &&
                    System.currentTimeMillis() - lastWatch >= WATCH_RENEW_MS
                ) {
                    watch(true)
                    lastWatch = System.currentTimeMillis()
                }

                attempt++
                delay(backoffMs(attempt))
            }
        }
    }

    // ── WebSocket ─────────────────────────────────────────────

    private fun wsUrl(): String = ApiConfig.BASE_URL
        .replaceFirst("https://", "wss://")
        .replaceFirst("http://", "ws://")
        .trimEnd('/') + "/api/v1/lattice/ws"

    /** 1s → 2 → 4 → 8 → 16 → 30（封顶）。 */
    private fun backoffMs(attempt: Int): Long {
        val shift = (attempt - 1).coerceIn(0, 5)
        return (WS_BACKOFF_MIN_MS shl shift).coerceAtMost(WS_BACKOFF_MAX_MS)
    }

    /**
     * OkHttp 的回调跑在**它自己的后台线程**上：这里只做投递、以及几个线程安全的
     * StateFlow 赋值。真正的处理在 [consumeEvents] 里，跑在主协程上下文。
     */
    private fun wsListener(closed: CompletableDeferred<Unit>) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            connState.value = ConnState.Connected
            lastConnectedAt = System.currentTimeMillis()
            errorBanner.value = null
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val e = runCatching { gson.fromJson(text, LatticeEventDto::class.java) }.getOrNull() ?: return
            wsEvents.trySend(e)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            FileLogger.e(TAG, "WS 断开：${t.javaClass.simpleName} ${t.message}", t)
            closed.complete(Unit)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            FileLogger.i(TAG, "WS 关闭 code=$code reason=$reason")
            closed.complete(Unit)
        }
    }

    private suspend fun consumeEvents() {
        for (e in wsEvents) {
            try {
                handleEvent(e)
            } catch (ex: Exception) {
                FileLogger.e(TAG, "处理事件失败 event=${e.event}", ex)
            }
        }
    }

    private suspend fun handleEvent(e: LatticeEventDto) {
        val d = e.data
        when (e.event) {
            "hello" -> if (d != null) applyHello(d)

            "pane.state" -> {
                if (d == null) return
                // 窗格被关掉时推的是 {id, state:"off", removed:true}，不是完整 pane
                if (d.asJsonObject?.get("removed")?.asBoolean == true) {
                    panesPing.trySend(Unit)
                    return
                }
                val pane = runCatching { gson.fromJson(d, LatticePaneDto::class.java) }.getOrNull() ?: return
                replacePaneInList(pane)
                // 🔴 **"音效响、画面不动"就死在这一行上**（2026-10-11 修）。
                //    `pane.state` 带的是【完整 ApiPane】，`data` 里**有内容**
                //    （idle→`lastTurn`，busy→`screenTail`+`activity`，都是实测的）；
                //    而原来只读了 `state` 去更新颜色 + 判音效 ⇒ 状态变了、画面还停在上一屏。
                //    现在整份进摘要层，**音效和画面同一刻更新**。
                applyPaneFull(pane)
                // 🔴 再补一发**内容层**刷新（走既有的节流单飞循环，不新起协程）：
                //    ① `idle` 之后 `pane.output` 不再来 ⇒ `outputLoop` 没有 ping ⇒
                //       内容层**永远停在最后一屏**，就是"画面延迟"；
                //    ② 代价可忽略 —— 实测 `busy` 期间 `pane.state` 45 秒 0 次，
                //       状态变化本来就稀少，不会形成请求风暴。
                outputPing.trySend(Unit)
            }

            // 只通知"有输出"，内容要自己拉。CONFLATED 负责把突发合并成一次。
            "pane.output" ->
                if (e.paneId == null || e.paneId == currentRef.value) outputPing.trySend(Unit)

            // 只带 brief（没有 state/data）⇒ 必须打一发 REST，所以走了节流
            "panes.changed" -> panesPing.trySend(Unit)

            // 后端自己发的：它连不上电脑上的 Lattice。不说的话，"Lattice 没开"和
            // "连着但没动静"在手机上长得一模一样。
            "upstream-error" -> errorBanner.value = "后端连不上电脑上的 Lattice：${e.message ?: "原因未知"}"

            // 后端自己发的（同上，来自 `POST /api/v1/usage/trigger`）：让手机立刻采一次
            // App 使用时长。
            //
            // ⚠️ **这条只在「远程」Tab 开着时收得到** —— 它就是这条 WS 的寿命
            // （`RemoteScreen` 的 `LaunchedEffect` 驱动，离开这一页连接就没了）。
            // 所以它只是"顺手加一次触发"，**周期采集（15 分钟）才是主路径**，
            // 后端那个接口的返回里也带 `delivered` 说明推到了几条连接上。
            "trigger_usage" -> viewModelScope.launch {
                // 用 launch 而不是直接 await：别把 Lattice 事件流卡在一次采集后面。
                guardQuietly("触发使用时长采集") {
                    val clip = getApplication<Application>() as? ClipApp ?: return@guardQuietly
                    val dao = clip.database.usageEventDao()
                    UsageCollector.collectIncremental(clip, dao)
                    UploadQueue.enqueueUsageIfPending(clip)
                }
            }

            // ping：只用于判活，什么都不做。
            // pane.answer：紧随其后的 pane.state 会带来新状态，忽略。
        }
    }

    /** `hello` = 全量基线。**只填状态，不发音** —— 重连拿到的是现状，不是"变化"。 */
    private fun applyHello(d: JsonElement) {
        val arr = d.asJsonObject?.getAsJsonArray("panes") ?: return
        val list = arr.mapNotNull {
            runCatching { gson.fromJson(it, LatticePaneDto::class.java) }.getOrNull()
        }
        if (list.isEmpty()) return
        panes.value = list
        cachePanes(list)

        // 记着的窗格可能已经被关了 —— 那就重新挑一个
        val cur = currentRef.value
        val switched = cur == null || list.none { it.id == cur }
        if (switched) {
            currentRef.value = list.firstOrNull { it.focused }?.id ?: list.firstOrNull()?.id
            buffer = emptyList()
            needFullOutput = true
        }
        // 🔴 hello 带的就是 `ApiPane[]`，走**唯一的渲染入口** —— 这是"进页面空窗"的兜底：
        //    `refreshAll` 一旦在它那句 `?: return` 早退，就再没人拉内容了；
        //    而 hello 至少能立刻铺一屏摘要（实测 busy 871 B / idle 438~556 B）。
        list.firstOrNull { it.id == currentRef.value }?.let { applyPaneFull(it) }
        // 只有**真的换了窗格**才补内容层刷新。
        // 🔴 不能无条件发：hello 每次 WS 重连都会来一遍（实测 5~9 秒一次建连），
        //    无条件发就等于每次重连都打一发整块重取。
        if (switched && currentRef.value != null) outputPing.trySend(Unit)

        // 🔴 把当前状态当【基线】记下来，而不是当成一次变化 ——
        //    否则每次重连都会误响一声"完成"。
        lastState = paneState.value
        sawBusy = false
        cancelDoneChime()
        loading.value = false

        // hello 到了 = 上游通了 ⇒ 把先前可能挂上的"后端连不上 Lattice"横幅清掉。
        // 🔴 必须清：`onOpen` 只在 **WS 建连**时清一次，而**上游重连不会再走 onOpen**
        //    （WS 一直连着，是后端那侧在重连上游）。不清的话，一次瞬时抖动留下的横幅
        //    会永远挂在手机上 —— 实测后端的回环连接超时是 2 秒，Lattice 忙的时候
        //    这个瞬时失败并不罕见。
        errorBanner.value = null
    }

    /**
     * **用【一个完整的 ApiPane】把摘要层整个刷一遍。**
     *
     * 它是所有"手上有 ApiPane"的地方的唯一渲染入口：
     * WS 的 `hello` / `pane.state`、REST 的 `/status`（`refreshPane`）、`/panes`（`loadPanes`）——
     * 全都走这里，所以"屏幕上显示的东西"只有一个来源，不会再出现
     * "音效按 state 判、画面按另一条路走"这种两套判据。
     *
     * 🔴 **只认当前窗格**：`pane.id != currentRef.value` 直接丢弃。
     *    REST 那两条是**带网络往返**的，回来时用户可能已经切走了
     *    —— 不判的话就是上一轮那个"旧窗格数据盖掉新窗格"的 bug（`refreshOutput` 里那份
     *    注释是同一件事，那是内容层，这是摘要层）。
     *
     * @return 真的应用了没有（调用方据此决定要不要清 `loading`）
     */
    private fun applyPaneFull(pane: LatticePaneDto): Boolean {
        if (pane.id != currentRef.value) return false
        paneTitle.value = pane.title ?: pane.projectName
        paneState.value = pane.state
        confirmData.value = pane.data
        paneSummary.value = renderSummary(pane)
        onStateObserved(pane.state)
        return true
    }

    /**
     * 把一个 ApiPane 的 `data` 翻成几行字。
     *
     * **每种状态给的东西不一样**（Lattice 的 `OUTPUT-SLOT.md:86-88` 定的原则：
     * "每种状态对应的数据跟着状态一起给"），实测（挂 WS 抓的）：
     *
     * | state | `data` 里有什么 | 实测大小 |
     * | --- | --- | --- |
     * | `busy` | `activity{tool,summary}` + `prompt` + **`screenTail`** | 871 B + 302 B + 148 B |
     * | `idle` | **`lastTurn{text,…}`** + `lastTool` | 438~556 B |
     * | `confirm` | `question` / `options` / `message` | — |
     * | `off` | `note`（"这个窗格里没有 claude 在跑"） | 53 B |
     *
     * 🔴 **它是摘要，不是屏幕** —— 别拿它当滚动历史用（`screenTail` 871 B vs REST 的 24 KB）。
     * 返回 null = 这个状态没什么可说的，让走下面的兜底文案。
     */
    private fun renderSummary(pane: LatticePaneDto): List<String>? {
        val d = pane.data
        val out = ArrayList<String>()

        // 单行
        fun add(s: String?) { if (!s.isNullOrBlank()) out.add(s) }
        // 多行原文（屏幕尾 / 汇总正文），按行摊开 —— 内容区是等宽一行的 Text
        fun addBlock(s: String?) { if (!s.isNullOrBlank()) out.addAll(s.split("\n")) }

        when (pane.state) {
            "off" -> add(d?.note)
            "confirm" -> {
                add(d?.question)
                d?.options?.forEach { o -> add("  [${o.index ?: "-"}] ${o.label.orEmpty()}") }
                add(d?.message)
                add(d?.note)
            }
            "busy" -> {
                d?.activity?.let { a ->
                    add(listOfNotNull(a.tool, a.summary).joinToString(" · ").ifBlank { null })
                }
                add(d?.prompt)
                addBlock(d?.screenTail)
            }
            else -> {   // idle（含 null）
                addBlock(d?.lastTurn?.text)
                d?.lastTool?.let { t ->
                    add(listOfNotNull(t.tool, t.summary).joinToString(" · ").ifBlank { null })
                }
                add(d?.note)
            }
        }
        if (out.isEmpty()) return null
        // 一条细分隔线，说明"这是摘要，完整内容还在路上" ——
        // 完整内容一到，这一层就整体让位（见 OutputArea 的优先级），所以这句话永远是真的。
        return listOf("──────── 摘要 · 完整内容加载中 ────────") + out
    }

    private fun replacePaneInList(pane: LatticePaneDto) {
        val list = panes.value
        val i = list.indexOfFirst { it.id == pane.id }
        if (i < 0) return
        panes.value = list.toMutableList().also { it[i] = pane }
    }

    /**
     * 输出抓取：**单飞 + 250ms 下限**。
     *
     * 事件来得比隧道往返快（连续输出时 2.5 次/秒），所以不能"来一个拉一个"。
     * CONFLATED 把突发塌缩成"还有一个待办"，下限保证不会把隧道打满。
     */
    private suspend fun outputLoop() {
        for (unused in outputPing) {
            val ref = currentRef.value ?: continue
            val wait = OUTPUT_MIN_INTERVAL_MS - (System.currentTimeMillis() - lastOutputAt)
            if (wait > 0) delay(wait)
            lastOutputAt = System.currentTimeMillis()
            refreshOutput(ref)
        }
    }

    /** `panes.changed` 的节流循环 —— 同样的"塌缩 + 下限"。实测这个事件比预想频繁（5 分钟 24 次）。 */
    private suspend fun panesLoop() {
        for (unused in panesPing) {
            loadPanes()
            delay(PANES_REFRESH_MIN_MS)
        }
    }

    /**
     * 一发 REST 全量：首次进页面抢首屏、顶栏 ↻、重连后补拉，都走它。
     * **不是轮询** —— 一次性调用，没有循环。
     *
     * 冷启动分两阶段。**核心事实：一条冷连接固定 ~1 秒，跟返回 47 字节还是 235KB
     * 无关**（实测 —— 所以首屏的代价是**往返次数**，不是数据量）：
     *
     *  1. **首屏只要一个往返**：`/status` + `mode=screen`（1~2KB）并发。
     *     - 不等 `/panes`（最贵的一发，要为每个窗格读 transcript）—— "上次看的是哪个
     *       窗格"本地 `prefs` 就有。
     *     - **不再先拉 `tail&lines=2000`**：claude 是全屏 TUI，`altScreen=true` 时
     *       tail 本来就是空的，白跑一个往返。原来首屏是两个往返。
     *  2. 首屏出来后再补**滚动历史 + pane 列表**，且**不跟首屏抢连接** ——
     *     实测并发打多条冷连接时每条都变慢（并发 1089/923/1169ms vs 串行 958/406/704ms）。
     */
    private suspend fun refreshAll() {
        // 本地记住的窗格直接拿来用，不为等 /panes 把首屏拖住
        if (currentRef.value == null) {
            currentRef.value = prefs.getString(KEY_REF, null)
        }
        if (currentRef.value == null) {
            // 本地真没有（首次安装，或者之前的窗格都没了）—— 这一种只能等 /panes
            if (loadPanes()) {
                currentRef.value = panes.value.firstOrNull { it.focused }?.id
                    ?: panes.value.firstOrNull()?.id
            }
        }
        val ref = currentRef.value ?: return

        // ── 第 1 阶段：首屏 ──
        // 只要"当前一屏" + 状态，**一个往返**。
        //
        // 原来首屏走的是"先 tail&lines=2000，发现 altScreen 再补 screen" —— 两个往返。
        // 而实测（冷连接）：一条连接固定 ~1 秒，跟返回 47 字节还是 235KB 无关
        //   /health 47B 1653ms ／ /text lines=2000 235KB 1169ms
        // ⇒ **首屏的代价 = 往返次数，不是数据量**。所以这里先把往返砍到一个。
        // 而且对 claude 这种全屏 TUI，tail 本来就是空的 —— 那一发是纯浪费。
        coroutineScope {
            launch { refreshPane(ref) }
            launch { fetchScreenNow(ref)?.let { publishLines(ref, it) } }
            // 🔴 **pane 列表放第 1 阶段，不放第 2 阶段**（2026-10-11 改）。
            //    第 2 阶段要**等首屏做完**才开始，而"能不能切窗格"是进页面后的第二件事：
            //    原来 `/panes` 要等 1~2 秒才发出去，用户那几秒看到的是"没有可切换的窗格"。
            //    提到这里**总请求数一发不变**，只是早 1~2 秒。
            //    代价：它与首屏抢连接（见下面那段实测），首屏可能慢一点 —— 已接受。
            launch { loadPanes() }
        }

        // ── 第 2 阶段：只剩滚动历史了 ──
        // pane 列表已经在第 1 阶段发过，**这里不再发**（`/panes` 是最贵的一发 ——
        // 它要给每个窗格读 transcript，重复发纯浪费）。
        // 剩下这一发**不跟首屏抢连接** —— 实测并发打多条冷连接时，
        // 每一条都变慢（并发 1089/923/1169ms vs 串行 958/406/704ms），4G 上更明显。
        needFullOutput = true
        refreshOutput(ref)
    }

    /**
     * 只要当前一屏。首屏专用 —— **一个往返**，1~2KB。
     *
     * 🔴 **必须自己吞异常**。它是在 `launch { }` 里裸调的，抛出去会顺着
     *    `coroutineScope` 冒到 `runStream`、再冒到 `LaunchedEffect` —— **直接崩进程**。
     *    实测崩过：Lattice 重启后窗格 id 变了，这里 404 → 闪退。
     */
    private suspend fun fetchScreenNow(ref: String): List<String>? = try {
        api.latticeText(ref, mode = "screen").data?.text?.split("\n")
    } catch (e: CancellationException) {
        throw e // 取消要放行，见 guardQuietly 的说明
    } catch (e: Exception) {
        onError(e)
        null
    }

    /**
     * 把抓到的内容发布出去（首屏那一步用；全量的走 `refreshOutput` 的合并逻辑）。
     *
     * 🔴 **`ref` 必须带上并校验**：调用方 `refreshAll` 在 :534 捕获 `ref`，而这一发
     *    （`fetchScreenNow`）同样是**带网络往返**的 —— 回来时 `currentRef` 可能已经换了。
     *    不校验就是同一个 bug 的**第二个入口**（`refreshAll` 与 `selectRef` 撞车）。
     *    详见 `refreshOutput` 里那段说明。
     */
    private fun publishLines(ref: String, lines: List<String>) {
        if (currentRef.value != ref) return
        if (lines != buffer) {
            buffer = lines
            screenLines.value = lines
        }
        loading.value = false
    }

    // ── REST 读写 ─────────────────────────────────────────────

    private suspend fun refreshPane(ref: String): Boolean {
        return try {
            val st = api.latticeStatus(ref)
            val pane = st.data?.pane
            if (st.ok && pane != null) {
                // `/status` 返回的**就是同一个 ApiPane**（含 data）—— 走同一个渲染入口，
                // 不再手写四行赋值。顺带白拿摘要层：`/status` 一到，摘要也就有了。
                applyPaneFull(pane)
            } else {
                errorBanner.value = "读不到这个窗格"
            }
            loading.value = false
            true
        } catch (e: CancellationException) {
            // 🔴 **必须放行**。`CancellationException` 在 JVM 上就是 `IllegalStateException` 的子类
            //    —— **它属于 Exception**。吞掉它有两条实际后果：
            //      ① 每次切窗格都会在日志里写一条**假错误**（其实是正常取消）；
            //      ② 吞掉之后协程会继续往下走，还会去 `refreshOutput(旧id)`，
            //         把**上一个窗格**的输出写进 buffer。
            throw e
        } catch (e: Exception) {
            onError(e)
            false
        }
    }

    /**
     * 输出区。
     *
     * **整块 + 增量**：进页面/切窗格取一次 `tail&lines=2000` 铺满本地缓冲，
     * 之后每拍只取 `tail&lines=60`，按"找重叠"并进尾部。
     *
     * 为什么不能每拍都取 2000 行：实测 120.2 KB/拍 × 1800 拍/小时 = **211 MB/小时**。
     * 现在这套 ≈ 5 MB/小时，且本地仍可滚全部历史。
     */
    private suspend fun refreshOutput(ref: String, forceLines: Int = -1): Boolean {
        return try {
            // 🔴 `forceLines > 0` = **强制按这个行数整块重取**（切窗格的首屏用，
            //    先 200 行抢显示、再 2000 行覆盖）。`-1` = 走原来的"整块 / 增量"判断。
            val wantFull = forceLines > 0 || needFullOutput || buffer.isEmpty()
            val wantLines = if (forceLines > 0) forceLines else if (wantFull) TAIL_FULL else TAIL_DELTA
            val fetched = fetchText(ref, wantLines)
            if (fetched == null) return false

            val newBuf: List<String> = if (wantFull) {
                fetched
            } else {
                val merged = mergeTail(buffer, fetched)
                if (merged != null) {
                    merged
                } else {
                    // 接不上（两拍之间跳掉的行数超过了增量窗口）→ 立即整块重取
                    fetchText(ref, wantLines) ?: buffer
                }
            }

            // 🔴 **写之前先问一句"我还是当前窗格吗"**（2026-11 修复）。
            //
            // 从上面那次 `fetchText` 发出、到现在，**可能已经隔了 1~2 秒**（它的窗口 =
            // 节流 delay ≤250ms + 一次网络往返 1~2s）—— 而这段时间里用户完全可能已经点了
            // 另一个窗格。`buffer` / `screenLines` 是**全局单值**，`selectRef` 在 :796-797
            // 刚把它们清干净、正等着新窗格的数据来填；不校验的话，**旧窗格的结果会把新窗格
            // 刚铺好的内容盖掉**。
            //
            // 而且**盖掉之后没人来纠正**：`outputLoop` 只在收到 `pane.output` 的 ping 时才动
            // （:404 的门是 `e.paneId == currentRef.value`，切走之后旧窗格就不再发 ping），
            // 新窗格如果是 idle 就也没有 ping。实测这一次错误写回之后，**整整 72 秒**没有任何
            // 重取（后端 access log 20:56:13.815 → 20:57:28.809），用户看到的就是
            // "切过去一直是旧窗格的内容"。
            //
            // 🔴 **必须放在 `needFullOutput = false` 之前。** 那个标志是 `selectRef` 在 :797
            //    置成 `true` 的（意思是"我来铺新窗格的全量"）—— 旧 ref 这次调用**没有资格**
            //    把它清掉；清掉之后新窗格那一发会被降级成增量合并，只能靠 :641 的兜底救回来。
            if (currentRef.value != ref) return false

            needFullOutput = false
            if (newBuf != buffer) {
                buffer = newBuf
                screenLines.value = newBuf
            }
            true
        } catch (e: CancellationException) {
            throw e   // 同 refreshPane：取消是正常的，别当成错误，也别让它继续往下写 buffer
        } catch (e: Exception) {
            onError(e)
            false
        }
    }

    /**
     * 取屏幕文本。
     *
     * `altScreen=true` 时（claude 在全屏 TUI 里）**回滚缓冲区是空的**，
     * `tail` 什么都拿不到 —— 这时降级回当前一屏。
     *
     * 🔴 **已知是全屏 TUI 时直接要那一屏，跳过没用的 `tail`**（2026-10-11 加）：
     * 冷连接实测"一条固定 ~1 秒，跟返回数据量无关" ⇒ **每一发都是一个往返**，
     * 而 claude 的窗格**永远**是 altScreen ⇒ 原来每次全量取数都白跑一发 tail。
     * 依据记在 [altScreenMemo]（带 TTL，见那个字段）。
     */
    private suspend fun fetchText(ref: String, lines: Int): List<String>? {
        val knownAlt =
            System.currentTimeMillis() - (altScreenMemo[ref] ?: 0L) < ALT_MEMO_TTL_MS
        if (knownAlt) {
            return api.latticeText(ref, mode = "screen").data?.text?.split("\n")
        }

        var d = api.latticeText(ref, mode = "tail", lines = lines).data
        if (d != null && d.altScreen) {
            altScreenMemo[ref] = System.currentTimeMillis()
            val screen = api.latticeText(ref, mode = "screen").data
            if (screen != null) d = screen
        }
        return d?.text?.split("\n")
    }

    /**
     * 把增量**纯追加**到本地缓冲：找 `current` 的**最长后缀**、同时也是 `fresh` 的**前缀**。
     *
     * 🔴 原来的做法是拿 `fresh` 的第一行非空行当**单行锚点**、`indexOfLast` 找位置，然后
     * `current.subList(0, at) + fresh.subList(...)` —— **那根本不是纯追加**。锚点一旦匹配到
     * 偏早的那次出现（终端里 `❯ `、`─────` 这种重复行很多），锚点之后的内容会被整段丢掉
     * 换成新尾巴，**缓冲区中段缩水 = 用户看到的内容换了一批**，表现为"滚动跳了"。
     *
     * 现在要求**整段 n 行都相同**才算重叠，而且结果**只可能变长**（`current` 原样保留）。
     * 已有内容的索引永远不变 —— 这正是"用户上翻时位置不动"能成立的前提，
     * 也是 `RemoteScreen.OutputArea` 里不需要做显式锚点恢复的原因。
     *
     * 返回 `null` = 完全没有重叠（两拍之间新增超过 `TAIL_DELTA` 行，一节大输出），
     * 调用方据此做一次整块重取 —— 宁可多拉一次全量，也不能静默吞掉中段。
     */
    private fun mergeTail(current: List<String>, fresh: List<String>): List<String>? {
        if (current.isEmpty()) return fresh
        if (fresh.isEmpty()) return current
        if (current.size < MERGE_ANCHOR || fresh.size < MERGE_ANCHOR) return null

        // 拿 fresh【开头】的 8 行当锚点 —— 刻意不取尾部：
        // Claude Code 的 TUI 底部有每秒重绘的动态行（spinner、`1m 56s`、`↓ 16.6k tok`），
        // 取尾部当锚点会被它们搅黄。而 60 行窗口的开头离底部还有几十行，是稳定文本。
        val anchor = fresh.subList(0, MERGE_ANCHOR)

        // 从后往前找 —— 锚点通常落在缓冲尾部附近，而且多个命中时取【最后一次】才对
        // （终端里重复行多，取早了会把中间整段丢掉）。
        for (i in (current.size - MERGE_ANCHOR) downTo 0) {
            if (current.subList(i, i + MERGE_ANCHOR) == anchor) {
                // 锚点之前原样保留，锚点之后由这次拉到的 fresh 完整重新描述 —— 不丢内容
                return current.subList(0, i) + fresh
            }
        }
        return null // 锚点完全不在了（两拍之间跳掉太多行）→ 调用方整块重取
    }

    private suspend fun loadPanes(): Boolean {
        return try {
            val list = api.latticePanes().data?.panes ?: emptyList()
            panes.value = list
            cachePanes(list)
            val cur = currentRef.value
            val next = list.firstOrNull { it.focused }?.id ?: list.firstOrNull()?.id
            if (cur == null && next != null) {
                // 🔴 **从没选中过窗格**（冷启动 + `refreshAll` 又在 `?: return` 早退了）
                //    —— 主动选一个。走 `selectRef` 而不是自己赋值：`selectRef` 会
                //    ① 立 `switching` 闸 ② 拉状态 ③ 拉内容，是唯一"选完就去取内容"的路径。
                //    实测这条路径原来是断的：`applyHello` 把 `currentRef` 填上就完事，
                //    **没有任何人去拉内容**，页面就一直空着。
                selectRef(next)
            } else if (cur != null && list.isNotEmpty() && list.none { it.id == cur }) {
                // 当前指着的窗格可能已经被关了 —— 别让界面一直指着一个不存在的东西。
                // 这条是"冷启动先用本地记住的 ref 抢首屏"的配套：先信本地，再用 /panes 校正。
                if (next != null) selectRef(next)
            }
            true
        } catch (e: Exception) {
            onError(e)
            false
        }
    }

    /** 把 pane 列表写进 prefs，供**下次**进页面零往返先铺一屏。 */
    private fun cachePanes(list: List<LatticePaneDto>) {
        // 🔴 **空列表不覆盖**：`/panes` 偶尔会回空（Lattice 正在重启、窗格还没建好），
        // 拿它盖掉上次的好缓存，等于把"0 秒有内容"换成"0 秒空白"。
        if (list.isEmpty()) return
        runCatching { prefs.edit().putString(KEY_PANES_JSON, gson.toJson(list)).apply() }
    }

    /**
     * 进页面时先把上次的列表铺上。**不覆盖内存里已有的** —— 那可能是刚拉回来的更新的。
     *
     * 过期内容靠现有逻辑自愈，不需要额外的过期判断：
     * `loadPanes` / `applyHello` 都会"当前窗格不在列表里就换一个"，而且它们回来时
     * 整份 `panes.value = list` 覆盖。所以最坏情况是**几秒钟的陈旧列表**，点错了会 404，
     * 与"几秒钟的空白 + 用户以为电脑没开 Lattice"相比是划算的。
     */
    private fun restoreCachedPanes() {
        if (panes.value.isNotEmpty()) return
        val cached = prefs.getString(KEY_PANES_JSON, null) ?: return
        val list: List<LatticePaneDto>? = runCatching {
            gson.fromJson<List<LatticePaneDto>>(cached, paneListType)
        }.getOrNull()
        if (!list.isNullOrEmpty()) {
            panes.value = list
            FileLogger.i(TAG, "先用本地缓存的 ${list.size} 个窗格铺屏（等 REST / hello 校正）")
        }
    }

    /**
     * 手动切窗格。**乐观 UI：先换画面，数据后到。**
     *
     * 原来的抱怨是"点另一个 pane，主界面半天不动" —— 因为要等 `/status` + `/text`
     * 两个往返回来才重绘。现在：状态点用**列表里已有的 `state`** 立刻切、输出区立刻
     * 清空，数据回来再填。失败也不卡死。
     *
     * 不用重新 watch —— Lattice 的 answerUntil 是实例级、不是窗格级。
     */
    fun selectRef(id: String) {
        if (id == currentRef.value) {
            closePanesSheet()
            return
        }

        // 🔴 **取消上一次切换** —— 见 [refSwitchJob] 的说明。
        // 放在早返回之后：点的是同一个窗格时根本没有"上一次"要取消。
        refSwitchJob?.cancel()

        // ── 立刻部分：**全程零网络**（2026-10-11 重构）──
        currentRef.value = id
        prefs.edit().putString(KEY_REF, id).apply()
        // 换了窗格：输出区要整块重铺，不能拿上一个窗格的缓冲去接
        buffer = emptyList()
        needFullOutput = true
        // 摘要层同理 —— 不清的话，新窗格若既没内容也没摘要，
        // 内容区会显示**上一个窗格的摘要**冒充它。
        paneSummary.value = null
        confirmData.value = null
        loading.value = true                // 输出区显示"连接中…"（内容还没到）

        // 🔴 **摘要直接吃 `panes` 缓存里的 ApiPane —— 不发 `/status`**（本轮核心改动）。
        //
        //    缓存里存的就是**完整 ApiPane（含 `data`）**：`loadPanes`（REST `/panes`）、
        //    `applyHello`、`pane.state` 三处写进去的都是完整对象。
        //    而 WS 在 `busy` 期间**实测 45 秒 0 次 `pane.state`**，`pane.output` 又**不带 `data`**
        //    —— 所以缓存里的摘要是"上次状态变化时"的。
        //
        // 🔴 **取舍（明确写下来，别以后当 bug 查）**：
        //    用缓存渲染 ⇒ **摘要 / 标题 / 状态点 / 确认框（应答条）都可能比 `/status` 旧一个
        //    状态变化周期**。换来的是**切 pane 0 延迟出内容**（原来是两个请求、在慢链路上
        //    几乎每次都撞超时）。**`pane.state` 一到就自动纠正**（`applyPaneFull` 是唯一入口）。
        //    ⚠️ 应答条若已过期，点了会失败 —— 那条路本来就有 `reasonOf` 的兜底文案，不是静默。
        val cached = panes.value.firstOrNull { it.id == id }
        if (cached != null) applyPaneFull(cached)

        // 🔴 **`switching` 同步开、同步关**：整个窗口**没有任何网络**。
        //    原来它从"点下去"一直开到"两个请求都回来"，而唯一的开闸点是那个协程的 `finally`
        //    —— 于是要挂一个 15 秒超时兜底，否则一旦协程跑不到 finally 就**永久锁死输入栏**。
        //    现在窗口是同步的 ⇒ **不可能卡住** ⇒ 超时和归属判断**一起删掉**。
        switching.value = false

        // 音效记忆跟着换人 —— 否则"A 窗格忙过"会把 B 窗格的 idle 当成完成
        lastState = null
        sawBusy = false
        cancelDoneChime()
        closePanesSheet()

        // ── 异步补数据：**只拉内容，且不再计时**──
        refSwitchJob = viewModelScope.launch {
            try {
                if (cached == null) {
                    // 兜底（罕见）：缓存里没这个窗格 —— 比如刚启动还没收到 hello。
                    // 这是**唯一**还会发 `/status` 的地方。
                    refreshPane(id)
                }
                // 🔴 **串行两发，不是并发**（本轮改）。理由：
                //  ① 首屏那一发小（`TAIL_FIRST`=200 行 ≈ 21 KB），慢链路上快得多；
                //  ② 第二发（2000 行 ≈ 254 KB）**显式发** —— 不能指望 `outputLoop`：
                //     它只在收到 `pane.output` 的 ping 时才动，而**切过去那个窗格若是 idle
                //     就永远没有 ping**，`buffer` 会一直停在 200 行。
                //  两发都走 `refreshOutput` ⇒ 都带 ref 校验 ⇒ 切走了就丢弃，不会互写。
                refreshOutput(id, forceLines = TAIL_FIRST)
                refreshOutput(id, forceLines = TAIL_FULL)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
            }
            // 🔴 **这里没有 `finally` 了** —— `switching` 已在上面同步关掉。
            //    但 `refSwitchJob?.cancel()` **必须留着**：它防的是"两条切换协程各自把
            //    自己窗格的内容写进 buffer"（`refreshOutput` 的 ref 校验是第二道闸）。
        }
    }

    /**
     * 后台任务的兜底：**普通异常只记日志，取消照常放行**。
     *
     * 🔴 为什么不能直接 `catch (e: Exception)`：`CancellationException` 在 JVM 上就是
     * `IllegalStateException` 的子类 —— **它属于 Exception**。一起吞掉的话，协程的取消
     * 语义就坏了（离开页面时这些任务该停却停不下来）。
     */
    private suspend fun guardQuietly(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "$what 失败：${reasonOf(e)}", e)
        }
    }

    // ── 动作 ──────────────────────────────────────────────────

    /**
     * 发指令。**取的是当前窗格那一份草稿**（不接收参数 —— 免得调用方传错窗格的文本）。
     *
     * 每条先落一条 [SendReceipt]（Pending），再异步送。**失败只改自己这一条** ——
     * 不进全局横幅（那个属于连接状态）、不进 toast（那个属于复制成功）。
     */
    fun sendInput() {
        val ref = currentRef.value ?: return
        val t = draft.value.trim()
        if (t.isEmpty()) return
        // 发出去了就把**这个窗格**的草稿清掉。内容已经进了 sendReceipts，
        // 失败重试由那条回执负责（它存着原文和 nonce）。
        drafts.value = drafts.value - ref
        val receipt = SendReceipt(
            id = Ids.newLocalId(),
            nonce = Ids.newLocalId(),
            text = t,
            state = SendState.Pending,
        )
        publishReceipt(receipt)
        deliver(ref, receipt)
    }

    /**
     * 重发一条失败的。
     *
     * 🔴 **复用同一个 `nonce`** —— 这正是修掉的那个隐藏 bug。原来 nonce 每次现生成，
     * 「不确定发出去没 → 再点一次」会**真的重复发一条**（apiServer.ts:1376 的去重窗口
     * 只认同一个 nonce）。现在重试命中 `duplicate:true` 而不是再写一遍 PTY。
     */
    fun retrySend(id: String) {
        val ref = currentRef.value ?: return
        val r = sendReceipts.value.firstOrNull { it.id == id } ?: return
        if (r.state == SendState.Pending) return
        updateReceipt(id) { it.copy(state = SendState.Pending, reason = null) }
        deliver(ref, r)
    }

    /** 划掉一条失败回执。不划掉它会一直占着那 1~3 条的位置。 */
    fun dismissReceipt(id: String) {
        sendReceipts.value = sendReceipts.value.filterNot { it.id == id }
    }

    private fun deliver(ref: String, receipt: SendReceipt) {
        viewModelScope.launch {
            val result = try {
                val resp = withTimeout(SEND_TIMEOUT_MS) {
                    api.latticeInput(
                        ref,
                        LatticeInputRequest(
                            text = receipt.text,
                            enter = true,
                            clientNonce = receipt.nonce,
                        )
                    )
                }
                val d = resp.data
                when {
                    !resp.ok -> ReceiptResult(SendState.Failed, "后端拒绝")
                    // 判据是**上游确认写进 PTY 了**，不是"我们收到了 200"。
                    // 不靠"看输出有没有变化"判断 —— 那是 Claude 的事，与发送成功与否无关。
                    d == null || !d.delivered -> ReceiptResult(SendState.Failed, "Lattice 未确认送达")
                    d.duplicate -> ReceiptResult(SendState.Duplicate)
                    else -> ReceiptResult(SendState.Sent)
                }
            } catch (e: TimeoutCancellationException) {
                // withTimeout 取消了 Retrofit 的 call，但**取消 ≠ 没送到**（请求可能已经
                // 落到电脑上了）。所以这里只说"超时"，不谎称失败 —— 重试是安全的，见 retrySend。
                ReceiptResult(SendState.Failed, "超时 ${SEND_TIMEOUT_MS / 1000} 秒")
            } catch (e: CancellationException) {
                throw e // ViewModel 被清掉，不是发送失败
            } catch (e: Exception) {
                ReceiptResult(SendState.Failed, reasonOf(e))
            }
            updateReceipt(receipt.id) { it.copy(state = result.state, reason = result.reason) }
        }
    }

    private fun publishReceipt(r: SendReceipt) {
        sendReceipts.value = trimReceipts(sendReceipts.value + r)
    }

    private fun updateReceipt(id: String, f: (SendReceipt) -> SendReceipt) {
        sendReceipts.value = trimReceipts(sendReceipts.value.map { if (it.id == id) f(it) else it })
    }

    /**
     * 最多留 [MAX_RECEIPTS] 条，但**失败的不被挤掉**（挤掉就没法重试了）。
     * 失败条另有一道 [MAX_FAILED_RECEIPTS] 上限，防止一直失败时无限涨。
     */
    private fun trimReceipts(list: List<SendReceipt>): List<SendReceipt> {
        if (list.size <= MAX_RECEIPTS) return list
        val keep = (
            list.filter { it.state == SendState.Failed }.takeLast(MAX_FAILED_RECEIPTS) +
                list.filter { it.state != SendState.Failed }.takeLast(MAX_RECEIPTS)
            ).map { it.id }.toSet()
        return list.filter { it.id in keep }
    }

    /** 一键复制：拿最后一轮汇总，写**手机**剪贴板（不是电脑的）。 */
    fun copyLastTurn() {
        val ref = currentRef.value ?: return
        viewModelScope.launch {
            try {
                val r = api.latticeLastTurn(ref)
                val text = r.data?.text
                if (r.ok && !text.isNullOrBlank()) {
                    ClipboardWriter.write(getApplication(), text)
                    toast.value = if (r.data.busy) "已复制（这一轮还没跑完）" else "已复制"
                } else {
                    toast.value = "没有可复制的内容"
                }
            } catch (e: Exception) {
                toast.value = if (codeOf(e) == "NOT_FOUND") "这一轮没有可复制的文本" else reasonOf(e)
            }
        }
    }

    /** 应答。失败不做乐观更新 —— 下一拍的 /status 会给出真实状态。 */
    fun answer(action: String, answers: Map<String, String>?, message: String? = null) {
        // 防重复点：第一次点下去按钮就灰了，但事件可能已经在队列里 —— 这里再挡一道。
        // 弱网下这条要紧：`/answer` 往返可能要几百毫秒，那期间连点会重复提交。
        if (answering.value) return
        val ref = currentRef.value ?: return
        val id = confirmData.value?.pendingId
        viewModelScope.launch {
            answering.value = true
            try {
                val r = api.latticeAnswer(ref, LatticeAnswerRequest(id = id, action = action, answers = answers, message = message))
                if (r.ok) {
                    // 成功 → 立刻收起应答条，不等下一拍轮询。
                    // 钩子那边收到答案后会自己把状态写成 busy，下一拍回来还是 busy，不冲突。
                    confirmData.value = null
                } else {
                    toast.value = "应答失败"
                }
            } catch (e: Exception) {
                // 失败**不动** confirmData —— 应答条留着让你重试
                toast.value = reasonOf(e)
            } finally {
                answering.value = false
            }
        }
    }

    // ── watch ─────────────────────────────────────────────────

    /**
     * 声明 / 撤销应答意愿。
     *
     * **失败不再被吞掉**：原来整段包在 `runCatching` 里，声明失败你我都不可能知道 ——
     * 而"声明失败"的**表现**恰好就是"手机上答不了"，正是用户在报的那个现象。
     * 现在结果写进 `watchOk`，顶栏据此显示提示。
     *
     * 用 `watchLock` 串行化：`revokeWatch()`（onDispose 发起）和 `sessionLoop` 开头的
     * 声明可能挨得很近，串行化保证两者按**发起顺序**落到 Lattice，不会让撤销后到的
     * 声明被一个晚到的撤销盖掉。
     */
    private suspend fun watch(answer: Boolean) {
        watchLock.withLock {
            val r = runCatching {
                api.latticeWatch(
                    LatticeWatchRequest(answer = answer, seconds = if (answer) WATCH_SECONDS else null)
                )
            }
            watchOk.value = r.isSuccess && (r.getOrNull()?.ok == true)
        }
    }

    /**
     * 撤销应答意愿。**离开远程页时调**（切回记录 Tab / 退出界面），不 suspend
     * —— `DisposableEffect.onDispose` 里不能挂起。
     */
    fun revokeWatch() {
        viewModelScope.launch { watch(false) }
        cancelDoneChime() // 人都离开这一页了，那声还没响的"完成"不该再响
    }

    // ── 手动刷新 ──────────────────────────────────────────────

    /**
     * 顶栏 ↻：**只发一发 REST 全量**，不碰 WS。
     *
     * 原来它是"把轮询提前叫醒"，现在没有轮询了 —— WS 是推的，
     * 手动刷新要的是"把当前的一切重新拉一遍"。
     */
    fun requestRefresh() {
        if (refreshing.value) return
        refreshing.value = true
        viewModelScope.launch {
            try {
                refreshAll()
            } finally {
                delay(REFRESH_MIN_SPIN_MS) // 让"转一下"看得见，别一闪而过
                refreshing.value = false
            }
        }
    }

    // ── 提示音 ────────────────────────────────────────────────

    /**
     * 每观测到一次状态调一次（`refreshPane` 里）。
     *
     * **判据照搬 Lattice**（`renderer/src/store/gridStore.ts:466-477, 869-963`），
     * 它是踩过坑才定下来的，两个要点：
     *
     * 1. **"全部跑完"的判据是「跑过活、现在空了」，不是「上一个状态是 busy」。**
     *    一轮里 Claude Code 会多次交回控制权，中间还可能夹 `confirm`；只看相邻两态
     *    会把真正干完的那一声漏掉。（`sawBusy` 就是干这个的。）
     * 2. **完成音延迟 [DONE_DEBOUNCE_MS]，期间只要又忙起来就撤掉。**
     *    否则会出现"叮一下 → 变绿 → 一秒后又变黄"的误报 —— 用户的原话是
     *    「中间那种中间态还是不要识别，不要有反馈」。
     *
     * `confirm` 那一声**不延迟** —— 要人马上知道。
     * 手机端还有个额外好处：轮询本来就是 2 秒一拍，比 Lattice 渲染层慢得多，
     * 大部分中间态根本采不到。
     */
    private fun onStateObserved(next: String?) {
        if (next == null) return
        val prev = lastState
        lastState = next

        if (next == "busy") {
            sawBusy = true
            cancelDoneChime() // 又忙起来了 → 撤掉还没响的那声"完成"
        }

        // prev == null 是第一拍（刚进页面/刚切窗格），不响 —— 否则一进来就诈响
        if (next == "confirm" && prev != null && prev != "confirm") {
            RemoteSounds.attention()
        }

        if (next == "idle" && sawBusy) {
            sawBusy = false
            cancelDoneChime()
            doneTimer = viewModelScope.launch {
                delay(DONE_DEBOUNCE_MS)
                RemoteSounds.done()
            }
        }
    }

    private fun cancelDoneChime() {
        doneTimer?.cancel()
        doneTimer = null
    }

    // ── 错误 ──────────────────────────────────────────────────

    /**
     * REST 调用失败（`refreshPane` / `refreshOutput` / `loadPanes`）。
     *
     * **只记日志，不动横幅。** 现在"在不在线"由 WS 连接状态说话（`connState` →
     * 顶栏那个点），REST 偶发失败就是一次没拉到的数据，下一个事件/下一次刷新会补上。
     * 原来那套"连续 N 次才显示"的 `failStreak` 已经删掉 —— 它正是
     * "不立即报、攒几次才一起显示"的来源。
     */
    private fun onError(e: Exception) {
        loading.value = false
        FileLogger.e(TAG, "REST 请求失败：${reasonOf(e)}", e)
    }

    private fun codeOf(e: Throwable): String? {
        if (e !is HttpException) return null
        return try {
            Gson().fromJson(e.response()?.errorBody()?.string(), ErrBody::class.java)?.error?.code
        } catch (_: Exception) {
            null
        }
    }

    /** 失败原因分级 —— 别把超时/网络/后端错全挤成"检查网络"。 */
    private fun reasonOf(e: Throwable): String = when {
        // 注意顺序：SocketTimeoutException 也是 IOException 的子类
        e is SocketTimeoutException -> "请求超时"
        e is ConnectException || e is UnknownHostException -> "网络不通"
        e is IOException -> "网络不通"
        e is HttpException -> when (codeOf(e)) {
            "LATTICE_UNAVAILABLE" -> "Lattice 未运行"
            "UNAUTHORIZED" -> "Token 失效"
            "CONFLICT" -> "状态已变化"
            "NOT_FOUND" -> "窗格不存在了"
            else -> "后端错误 ${e.code()}"
        }
        else -> "响应异常"
    }

    companion object {
        private const val TAG = "RemoteVM"
        private const val WATCH_SECONDS = 3600
        private const val WATCH_RENEW_MS = 5 * 60_000L

        // ── WS ──
        /** 重连退避的起点：1s → 2 → 4 → 8 → 16 → [WS_BACKOFF_MAX_MS] 封顶。 */
        private const val WS_BACKOFF_MIN_MS = 1_000L
        private const val WS_BACKOFF_MAX_MS = 30_000L
        /** 断线超过这个时长，顶栏那个点转红 + 出横幅。 */
        private const val DOWN_AFTER_MS = 60_000L
        /** 输出抓取的两次之间最少间隔（`pane.output` 本身 400ms 一个）。 */
        private const val OUTPUT_MIN_INTERVAL_MS = 250L
        /** `panes.changed` 的节流 —— 实测 5 分钟能来 24 次。 */
        private const val PANES_REFRESH_MIN_MS = 1_000L
        /**
         * 单条发送的超时。
         *
         * 后端正常 0.5~1 秒；最坏 ~20.4 秒（enter 拆成两次 Lattice 调用，各带 10 秒 read 超时）。
         * 30 秒 ⇒ **只有真的断了才会报超时**，不会把正常的慢当成失败。
         */
        private const val SEND_TIMEOUT_MS = 30_000L
        /** 发送回执的可见条数上限。 */
        private const val MAX_RECEIPTS = 3
        /** 失败回执不被挤掉，但也要有上限，防止一直失败时无限涨。 */
        private const val MAX_FAILED_RECEIPTS = 3
        /** 手动刷新"转一下"的最短可见时长 —— 网络快时否则一帧就转完了。 */
        private const val REFRESH_MIN_SPIN_MS = 450L
        // 🔴 **这里原本是 `SWITCH_TIMEOUT_MS = 15_000L`（一次切窗格的时间上限），
        //    2026-10-11 删掉了 —— 别再加回来。**
        //
        // 它当时存在的唯一理由：`switching` 是个**单向闩锁**，唯一的开闸点是切换协程的
        // `finally`，而那个协程要跑两个网络请求（`/status` + `/text`，慢链路上单发就可能
        // 挂满 OkHttp 的 60 秒 `readTimeout`）⇒ 协程跑不到 `finally` 就**永久锁死输入栏**。
        //
        // 现在 `selectRef` 把 `switching` 改成**同步开、同步关**（整个窗口零网络），
        // 摘要直接吃 `panes` 缓存 ⇒ **没有任何东西能让它卡住** ⇒ 超时和归属判断一起下岗。
        // 如果将来又要把网络放回 `switching` 的窗口里，**必须把超时一起加回来**。

        /**
         * [altScreenMemo] 的有效期。
         *
         * 为什么必须有它：窗格**退出**全屏 TUI 之后（claude 退出了），一直信这个记录就会永远走
         * `mode=screen` —— 内容是对的，但**滚动历史拿不回来**。
         * 5 分钟过期一次，退回"先探一发 tail"的稳妥路径；代价是每个窗格每 5 分钟多一发探测。
         */
        private const val ALT_MEMO_TTL_MS = 5 * 60_000L
        /** 完成音的延迟，期间忙起来就撤销（照搬 Lattice 的 DONE_DEBOUNCE_MS）。 */
        private const val DONE_DEBOUNCE_MS = 1_500L
        /** 进页面/切窗格时铺满本地缓冲的行数（一次性，约 120 KB） */
        private const val TAIL_FULL = 2000
        /**
         * **切窗格的首屏行数**（2026-10-11 加）。
         *
         * `TAIL_FULL` 那一发现在实测返回 **254,115 字符（254 KB）**，而这条链路
         * TLS 握手就要 3~9 秒 —— 传 254 KB 会把"切窗格"拖到十几秒。
         * 200 行约 **21 KB**，先把内容顶上屏，再让 `TAIL_FULL` 那一发覆盖。
         */
        private const val TAIL_FIRST = 200
        /** 每拍的增量窗口（约 2.9 KB） */
        private const val TAIL_DELTA = 60
        /**
         * 增量合并的锚点长度（行）。
         *
         * 取 8 是为了"既不可能巧合命中、又不会因为 TUI 底部每秒重绘的动态行而失配"。
         * 1 行太脆（终端里重复行多），60 行太严（底部动态行必然搅黄）。
         */
        private const val MERGE_ANCHOR = 8
        private const val KEY_REF = "current_ref"

        /** 本地缓存的 pane 列表（JSON）。进页面先拿它铺屏，0 个往返 —— 见 `restoreCachedPanes`。 */
        private const val KEY_PANES_JSON = "panes_json"

        fun factory(application: Application, api: ApiService): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    RemoteViewModel(application, api) as T
            }
    }
}
