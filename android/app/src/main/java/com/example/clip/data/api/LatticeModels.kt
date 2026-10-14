package com.example.clip.data.api

import com.google.gson.JsonElement

/**
 * Lattice 数据口的 DTO。
 *
 * 上游字段是 **camelCase**，属性名直接对齐，**不用** `@SerializedName`
 * （剪藏自己的后端是 snake_case，那一套在 ApiModels.kt 里）。
 *
 * 🔴 **引用类型一律可空**。Gson 用 Unsafe 分配对象，JSON 里缺的字段不会走 Kotlin
 * 默认值，而是留成 null —— 写成 `List<X> = emptyList()` 一样会在运行时拿到 null。
 * 基本类型（Boolean/Int/Long）零初始化成 false/0，可以不放问号。
 */

data class LatticeHealthDto(
    val app: String?,
    val runId: String?,
    val port: Int?,
    val apiVersion: Int?,
    val uptimeMs: Long?
)

/** `state` 四值：off / idle / busy / confirm */
data class LatticePaneDto(
    val id: String?,
    val ref: String?,
    val seq: Int?,
    val title: String?,
    val projectName: String?,
    val alias: String?,
    val cwd: String?,
    val focused: Boolean = false,
    val zoomed: Boolean = false,
    val cols: Int?,
    val rows: Int?,
    val shellAlive: Boolean = false,
    val state: String?,
    val stateMeta: LatticeStateMetaDto? = null,
    val data: LatticeDataDto? = null
)

data class LatticeStateMetaDto(
    val event: String?,
    val since: String?,
    val ageMs: Long?,
    val notificationType: String?,
    val message: String?
)

data class LatticePanesDto(
    val runId: String?,
    val paneCount: Int?,
    val maxPanes: Int?,
    val focusedPaneId: String?,
    val layout: String?,
    val panes: List<LatticePaneDto>? = null
)

data class LatticeStatusDto(val pane: LatticePaneDto?)

data class LatticeScreenDto(
    val paneId: String?,
    val mode: String?,
    val text: String?,
    val lines: Int?,
    val bufferLines: Int?,
    /** 为真时砍的是**头部**、保尾部 */
    val truncated: Boolean = false,
    /** 全屏 TUI（claude 在跑时是 true）—— 这时回滚里基本什么都没有 */
    val altScreen: Boolean = false,
    val cols: Int?,
    val rows: Int?,
    val atBottom: Boolean = true
)

data class LatticeLastTurnDto(
    val paneId: String?,
    val text: String?,
    val chars: Int?,
    val blocks: Int?,
    val toolCount: Int?,
    /** 这一轮还没跑完（text 是"到目前为止"的） */
    val busy: Boolean = false,
    val prompt: String?,
    val truncated: Boolean = false,
    val transcriptPath: String?
)

data class LatticeOptionDto(
    val index: Int?,
    val label: String?,
    val selected: Boolean = false,
    val description: String?
)

data class LatticeActivityDto(
    val kind: String?,
    val tool: String?,
    val summary: String?,
    val source: String?
)

/**
 * `pane.data` —— 上游是**按 `state` 判别的联合**，同一字段名在不同 state 下根本不存在。
 * Gson 不做判别，所以这里扁平 + 全可空：用不到的就是 null。
 * 别按 state 拆成密封类，Gson 映射不了。
 */
data class LatticeDataDto(
    val state: String?,

    // ── busy ──
    val activity: LatticeActivityDto? = null,
    val prompt: String? = null,
    val toolCount: Int? = null,
    val elapsedMs: Long? = null,

    // ── idle ──
    // 🔴 **2026-10-11 补**：`pane.data` 里其实**一直带着**这两个，只是 DTO 没有对应字段
    //    ⇒ Gson 把它们丢掉了 ⇒ `idle` 状态下**"最后一轮汇总"整个不可见**。
    //    而 `idle` 恰恰是"claude 干完了"那一刻，用户要看的正是它。
    //    实测（挂 WS 抓的 hello）：`lastTurn` 438~556 B，`lastTool` 88~126 B。
    //    形状直接复用现成的两个 DTO —— `lastTurn` 与 `GET /v1/panes/{ref}/last-turn`
    //    的返回**是同一个东西**（所以 `LatticeLastTurnDto` 本来就在本文件里）；
    //    `lastTool` 是 `{tool, summary}`，是 `LatticeActionDto` 的子集，Gson 会留空其余字段。
    val lastTurn: LatticeLastTurnDto? = null,
    val lastTool: LatticeActivityDto? = null,

    // ── confirm ──
    /** question / plan-approval / permission / unknown —— 决定应答条画什么按钮 */
    val kind: String? = null,
    val options: List<LatticeOptionDto>? = null,
    /** pending / transcript / screen / none —— 选项来源有多可信（展示用） */
    val optionsSource: String? = null,
    /** false = 没有钩子在等，给了答案也没人接 —— 这时**不该**显示按钮 */
    val canAnswer: Boolean = false,
    /** 应答时原样带回 `POST answer` 的 id */
    val pendingId: String? = null,
    /** 钩子挂住的截止时刻（ISO）。过了这点答案就没处送了 */
    val answerDeadline: String? = null,
    val question: String? = null,
    val message: String? = null,

    // ── 各状态共用的原文兜底 ──
    val screenTail: String? = null,
    val note: String? = null
)

// ── 事件流（WS / SSE 推来的信封）────────────────────────────

/**
 * 一个推来的事件。字段与 Lattice 的 SSE 信封一致（后端转发时原样保留）。
 *
 * 🔴 **`data` 的形状是按 `event` 判别的**，所以这里只能是 `JsonElement`：
 * ```
 * hello          → { runId, apiVersion, rendererReady, snapshot, panes: [ApiPane…] }
 * pane.state     → 一个完整的 ApiPane（含 state + data）
 * pane.output    → { bytes }
 * panes.changed  → snapshot（**只有 brief，没有 state/data**）
 * pane.answer    → { paneId, id, action, keys }
 * ```
 * Gson 不做判别，硬塞成某个具体类型会在别的 event 上炸。
 *
 * `message` 只有后端自己发的 `upstream-error` 用（它连不上 Lattice 时告诉我们），
 * 不是 Lattice 的字段。
 */
data class LatticeEventDto(
    val event: String?,
    val seq: Int? = null,
    val at: String? = null,
    val paneId: String? = null,
    val message: String? = null,
    val data: JsonElement? = null
)

// ── 请求体（camelCase，与上游一致）──────────────────────────

data class LatticeInputRequest(
    val text: String? = null,
    val keys: List<String>? = null,
    val enter: Boolean? = null,
    /** 60 秒幂等去重，防弱网重发 */
    val clientNonce: String? = null,
    val awaitOutputMs: Int? = null
)

data class LatticeAnswerRequest(
    val id: String? = null,
    /** answer（选择题）/ allow / deny */
    val action: String? = null,
    /** action=answer 时必填：{"<题面原文>": "<选项 label 原文>"} */
    val answers: Map<String, String>? = null,
    val message: String? = null
)

data class LatticeWatchRequest(
    val answer: Boolean? = null,
    val seconds: Int? = null
)

// ── 响应 ────────────────────────────────────────────────────

data class LatticeWatchDto(
    val watching: Boolean = false,
    val answerUntil: Long? = null,
    val answerUntilIso: String?,
    val note: String?
)

data class LatticeInputDto(
    val paneId: String?,
    val bytes: Int?,
    val delivered: Boolean = false,
    val duplicate: Boolean = false
)

data class LatticeAnswerDto(
    val paneId: String?,
    val id: String?,
    val action: String?,
    val remainingMs: Long?,
    val note: String?
)
