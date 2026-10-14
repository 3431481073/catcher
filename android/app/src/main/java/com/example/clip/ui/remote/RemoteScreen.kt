package com.example.clip.ui.remote

import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.clip.ClipApp
import com.example.clip.data.api.LatticeDataDto
import com.example.clip.data.api.LatticeOptionDto
import com.example.clip.data.api.LatticePaneDto
import kotlinx.coroutines.launch

/**
 * 远程页：顶栏 + 输出区 + 输入栏。
 *
 * **轮询**挂在 `repeatOnLifecycle(RESUMED)` 上 —— 进页面才跑、退后台停、回前台重跑。
 *
 * **watch（应答意愿）不跟轮询同寿命**：它只在**离开远程页**时撤销（见下面的
 * `DisposableEffect`），**退后台不撤销**。理由写在 `RemoteViewModel.sessionLoop()` 顶部
 * —— 跟轮询同寿命会把 watch 压到"你正盯着这一页"的几十秒，而电脑上问题出现的时机
 * 不可控；实测五次询问五次都没挂住。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen() {
    val app = LocalContext.current.applicationContext as ClipApp
    val vm: RemoteViewModel = viewModel(factory = RemoteViewModel.factory(app, app.api))
    val context = LocalContext.current

    val title by vm.paneTitle.collectAsStateWithLifecycle()
    val state by vm.paneState.collectAsStateWithLifecycle()
    val data by vm.confirmData.collectAsStateWithLifecycle()
    val lines by vm.screenLines.collectAsStateWithLifecycle()
    val panes by vm.panes.collectAsStateWithLifecycle()
    val banner by vm.errorBanner.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val sheetOpen by vm.panesSheetOpen.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val watchOk by vm.watchOk.collectAsStateWithLifecycle()
    val answering by vm.answering.collectAsStateWithLifecycle()
    val receipts by vm.sendReceipts.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val conn by vm.connState.collectAsStateWithLifecycle()
    val switching by vm.switching.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val summary by vm.paneSummary.collectAsStateWithLifecycle()
    val currentRef by vm.currentRef.collectAsStateWithLifecycle()

    // 🔴 WS 用 `LaunchedEffect`，**不挂 `repeatOnLifecycle`** ——
    // 它只随"离开这一页（离开组合）"被取消，**退后台继续跑**。
    // 这是刻意的：电脑上 Claude 什么时候出问题不可控，退后台就断会漏掉。
    // （上一版轮询挂在 RESUMED 上，退后台停、回前台重来；那是因为轮询本来就不该常驻。）
    LaunchedEffect(Unit) { vm.runStream() }

    // 🔴 撤销应答意愿的唯一触发点：**离开远程页**（切回记录 Tab / 退出界面）。
    // 放在 DisposableEffect 而不是 sessionLoop 的 finally，是因为后者连"退后台"一起管
    // —— 那正是原来失效的原因。切回记录 Tab 时这个 Composable 会离开组合，onDispose 才跑。
    DisposableEffect(Unit) {
        onDispose { vm.revokeWatch() }
    }

    LaunchedEffect(toast) {
        toast?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.clearToast()
        }
    }

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        RemoteTopBar(
            title = title,
            state = state,
            conn = conn,
            refreshing = refreshing,
            onSwitch = vm::openPanesSheet,
            onCopy = vm::copyLastTurn,
            onRefresh = vm::requestRefresh
        )
        HorizontalDivider()

        banner?.let {
            Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = it,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        // 断线横幅：**只有断超过 1 分钟才出**。黄点阶段（短暂抖动）不打扰。
        if (conn == ConnState.Down) {
            Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "连接中断，正在重试…",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        // 应答意愿没声明上 —— 这就是"手机上答不了"的直接原因，必须让你看见，
        // 不再像原来那样被 runCatching 静默吞掉。
        if (watchOk == false) {
            Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "应答意愿未生效 —— 电脑上的问题不会等你（检查网络后退出重进本页）",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        // 应答条：claude 在等回答时才出现
        if (state == "confirm" && data != null) {
            ConfirmBar(data = data!!, answering = answering, onAnswer = vm::answer)
            HorizontalDivider()
        }

        OutputArea(
            lines = lines,
            loading = loading,
            switching = switching,
            summary = summary,
            modifier = Modifier.weight(1f).fillMaxWidth()
        )
        HorizontalDivider()
        SendStatusStrip(
            receipts = receipts,
            onRetry = vm::retrySend,
            onDismiss = vm::dismissReceipt
        )
        // 🔴 **输入框和发送按钮用两个判据，不是一个。**
        //
        //   · 输入框：`!switching && currentRef != null` —— 「打字」这条路基本不上锁，
        //     只有两件事能挡住它：**正在切窗格**、**还没有任何窗格**。
        //     🔴 第二个条件是 2026-10-11 加的：内容区一片空白（还没选出窗格）时，
        //        输入框却是开的 —— 用户根本不知道自己会在往哪儿打字。**只在"连窗格都没有"时锁，
        //        绝不按连接状态锁**（那会造出一个没有本地出口的死锁）。
        //   · 发送按钮：额外要求 **`conn == Connected`**。发送走的是 REST（`deliver()` 里的
        //     `latticeInput`），WS 只是事件通道 —— 但这个条件表达的是"现在发出去大概率没用"，
        //     灰掉 + 写「未连接」比让用户点了没反应好。
        //     🔴 **它只锁按钮，不锁输入框**：连接差的时候仍然能打字，只是发不出去。
        val hasPane = currentRef != null
        InputBar(
            value = draft,
            enabled = !switching && hasPane,
            canSend = conn == ConnState.Connected,
            // 禁用时**说清是哪种**：「切换中」和「还没有窗格」是两回事，
            // 都写「切换中」等于骗人（用户会一直等一个不会自己结束的状态）。
            disabledHint = if (!hasPane) "还没有窗格" else "切换中",
            onValueChange = vm::setDraft,
            onSend = vm::sendInput
        )
    }

    if (sheetOpen) {
        PaneSheet(
            panes = panes,
            currentRef = vm.currentRef.value,
            conn = conn,
            onPick = vm::selectRef,
            onDismiss = vm::closePanesSheet
        )
    }
}

// ── 顶栏 ──────────────────────────────────────────────────────

@Composable
private fun RemoteTopBar(
    title: String?,
    state: String?,
    conn: ConnState,
    refreshing: Boolean,
    onSwitch: () -> Unit,
    onCopy: () -> Unit,
    onRefresh: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(stateColor(state), CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = title ?: "未选择会话",
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onSwitch) { Text("⇄ 切换") }
        TextButton(onClick = onCopy) { Text("📋 复制") }
        TextButton(onClick = onRefresh) { RefreshGlyph(refreshing) }
        // 连接指示：6dp 小点，放在 ↻ 右边。它是"在不在线"的**唯一**判据（横幅也看它），
        // 所以做小但不能省 —— 和左边那个窗格状态点是两回事。
        Spacer(Modifier.width(2.dp))
        Box(modifier = Modifier.size(6.dp).background(connColor(conn), CircleShape))
        Spacer(Modifier.width(10.dp))
    }
}

/**
 * 连接指示色。**和 `stateColor` 是两套东西**：那个是"窗格在干什么"，
 * 这个是"我这条连接还在不在"。
 */
private fun connColor(c: ConnState): Color = when (c) {
    ConnState.Connected -> Color(0xFF7EE787)    // 绿：连着
    ConnState.Connecting -> Color(0xFF8B95A8)   // 灰：首次连接中（还谈不上"重连"）
    ConnState.Reconnecting -> Color(0xFFFFC857) // 黄：重连中
    ConnState.Down -> Color(0xFFFF7B7B)         // 红：断超 1 分钟
}

/**
 * ↻ —— 刷新中真的转起来。
 *
 * **不在不刷新时也挂着 infiniteTransition**：那会每帧都重组一次，白烧电。
 * 所以只有 `refreshing` 为真时才建这个过渡。
 */
@Composable
private fun RefreshGlyph(refreshing: Boolean) {
    if (!refreshing) {
        Text("↻")
        return
    }
    val spin = rememberInfiniteTransition(label = "refresh-spin")
    val angle by spin.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing)),
        label = "refresh-angle"
    )
    Text("↻", modifier = Modifier.rotate(angle))
}

/**
 * 状态点颜色 —— 与 Lattice 的默认四色一致（**不是**记录列表那套状态色）。
 * 用户可在 Lattice 里改色，所以这里只用 `state` 这个名字做判据。
 */
private fun stateColor(state: String?): Color = when (state) {
    "idle" -> Color(0xFF7EE787)
    "busy" -> Color(0xFFFFC857)
    "confirm" -> Color(0xFFFF7B7B)
    else -> Color(0xFF8B95A8)
}

// ── 应答条 ────────────────────────────────────────────────────

@Composable
private fun ConfirmBar(
    data: LatticeDataDto,
    answering: Boolean,
    onAnswer: (String, Map<String, String>?, String?) -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = data.question ?: data.message ?: "Claude 需要你确认",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(Modifier.height(8.dp))

            if (!data.canAnswer) {
                // 没有钩子在等 —— 给了答案也没人接，别画按钮骗人
                Text(
                    text = "需在电脑上回答",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                return@Column
            }

            when (data.kind) {
                "question" -> {
                    val opts = data.options ?: emptyList()
                    if (opts.isEmpty()) {
                        Text(
                            text = "认不出选项，请到电脑上回答",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    } else {
                        // TODO(多选)：data.questions[0].multiSelect 为真时应当允许多选，
                        // 现在只做单选（逗号连写的 answers 值留给以后）。
                        opts.forEach { o ->
                            OptionRow(o, enabled = !answering) {
                                onAnswer("answer", mapOf((data.question ?: "") to (o.label ?: "")), null)
                            }
                        }
                    }
                }
                "plan-approval" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onAnswer("allow", null, null) }, enabled = !answering) {
                        Text(if (answering) "提交中…" else "批准")
                    }
                    OutlinedButton(onClick = { onAnswer("deny", null, null) }, enabled = !answering) { Text("打回") }
                }
                // permission / unknown / null
                else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onAnswer("allow", null, null) }, enabled = !answering) {
                        Text(if (answering) "提交中…" else "允许")
                    }
                    OutlinedButton(onClick = { onAnswer("deny", null, null) }, enabled = !answering) { Text("拒绝") }
                }
            }
        }
    }
}

@Composable
private fun OptionRow(option: LatticeOptionDto, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text("${option.index ?: ""}. ${option.label ?: ""}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            option.description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ── 输出区 ────────────────────────────────────────────────────

@Composable
private fun OutputArea(
    lines: List<String>,
    /** 摘要层（WS 的 `data` 渲染出来的）。完整内容没到时拿它兜底 —— 见 `OutputArea` 的优先级。 */
    summary: List<String>?,
    loading: Boolean,
    switching: Boolean,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    /**
     * **用户是不是在读历史** —— 自动滚动的唯一判据。
     *
     * 🔴 关键是这个标志**只能由用户手势改**，布局的瞬态改不了它。
     *
     * 原来判"在底部"用的是 `layoutInfo.visibleItemsInfo.lastOrNull() == null`，
     * 而**列表刚被替换、布局还没跑完的那一帧 `visibleItemsInfo` 就是空的** ——
     * 于是用户往上翻着看历史时，新内容一到，这个瞬态把 `atBottom` 判成 true，
     * `scrollToItem(末尾)` 就把他顶回底部。这就是"滚动被顶跑"。
     *
     * 现在：`isScrollInProgress`（真手势）置位，`canScrollForward == false`（真到底）清零。
     */
    var userReading by remember { mutableStateOf(false) }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling) userReading = true
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.canScrollForward }.collect { canForward ->
            // 滚到底了（或内容还没铺满一屏）→ 恢复自动跟随
            if (!canForward) userReading = false
        }
    }

    // 🔴 **"有没有内容"要按"有没有非空行"判**，不能只判 `lines.isEmpty()`：
    //    终端的一屏**大部份是空白行**（`"".split("\n")` 也会给出 `[""]`），
    //    只判 size 的话会出现"列表里全是空行 ⇒ 画一片空白、连兜底文案都不显示"。
    val hasContent = lines.any { it.isNotBlank() }

    Box(modifier = modifier) {
        // 优先级（2026-10-11 定）：
        //   ① 切换中（`switching` 最高 —— 切窗格时输入栏已经写着「切换中」，
        //      内容区不能还显示旧窗格，两处必须同步）
        //   ② **完整内容**（REST 拉的，含滚动历史，实测 24 KB / 256 行）
        //   ③ **摘要**（WS 的 data，实测 871 B / 438~556 B）—— 只在完整内容没到之前顶一下
        //   ④ 连接中 / 还没有输出
        if (switching) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "切换中…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        } else if (hasContent) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
            ) {
                items(lines.size) { i ->
                    Text(
                        text = lines[i],
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = MaterialTheme.typography.bodySmall.fontSize * 0.9f
                        )
                    )
                }
            }
        } else if (!summary.isNullOrEmpty()) {
            // 摘要兜底：**完整内容还在路上时先显示它**（WS 带来的，零延迟）。
            // 用 `onSurfaceVariant` 淡一档，和真正的屏幕内容区分开 ——
            // 它有 `"──────── 摘要 · 完整内容加载中 ────────"` 那行头，颜色再压一下更清楚。
            // 🔴 **别拿它当滚动历史**：实测摘要 871 B，完整内容 24,001 B。
            // 它**不绑 `listState`**（那是给完整内容用的），所以下面那个"回到底部"按钮要判 `hasContent`。
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
            ) {
                items(summary.size) { i ->
                    Text(
                        text = summary[i],
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = MaterialTheme.typography.bodySmall.fontSize * 0.9f
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (loading) "连接中…" else "还没有输出",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // 🔴 判 `hasContent` 而不是 `lines.isNotEmpty()`：这个按钮滚的是 `listState`，
        //    而 `listState` 只绑在**完整内容**那个 LazyColumn 上。显示摘要时按它会把
        //    一个没挂到任何列表上的 state 拿去 scrollToItem。
        if (userReading && hasContent) {
            SmallFloatingActionButton(
                onClick = { scope.launch { listState.scrollToItem(lines.lastIndex) } },
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)
            ) { Text("↓") }
        }
    }

    // 在读历史时**一动不动**：不滚，也不做锚点恢复 —— 因为增量合并是纯追加的（见
    // RemoteViewModel.mergeTail），已有内容的索引永远不变，LazyColumn 自己就保住了位置。
    LaunchedEffect(lines) {
        if (!userReading && lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }
}

// ── 发送回执条 ────────────────────────────────────────────────

private const val MAX_STRIP = 3

/**
 * 输入栏上方那一条：**每条消息只报自己那一条的去向**。
 *
 * 为什么要有它：原来发送是"一次性"的 —— 失败写进全局 toast，成功什么都不留，
 * 而 toast 是单值、取走即清。两次失败挨得近就互相覆盖，用户看到的是
 * "攒了几次才蹦一个"，而且**分不清是哪一条**。
 * 现在一条一个状态，失败只影响自己，不外溢到全局横幅。
 */
@Composable
private fun SendStatusStrip(
    receipts: List<SendReceipt>,
    onRetry: (String) -> Unit,
    onDismiss: (String) -> Unit
) {
    val shown = receipts.takeLast(MAX_STRIP)
    if (shown.isEmpty()) return

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        shown.forEach { r ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = receiptLabel(r),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (r.state == SendState.Failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (r.state == SendState.Failed) {
                    TextButton(onClick = { onRetry(r.id) }) { Text("重试") }
                    TextButton(onClick = { onDismiss(r.id) }) { Text("✕") }
                }
            }
        }
    }
}

/** 附上消息开头 —— 否则连发几条时分不清是哪条失败了。 */
private fun receiptLabel(r: SendReceipt): String {
    val preview = r.text.replace('\n', ' ').take(14)
    return when (r.state) {
        SendState.Pending -> "⏳ 发送中 · $preview"
        SendState.Sent -> "✓ 已送达 · $preview"
        // 命中去重 = 上一次其实已经送到了。这条最能消掉"到底发出去没"的疑虑。
        SendState.Duplicate -> "✓ 上次其实已送达 · $preview"
        SendState.Failed -> "✗ ${r.reason ?: "发送失败"} · $preview"
    }
}

// ── 输入栏 ────────────────────────────────────────────────────

/**
 * 输入栏。**受控组件** —— 内容不在本地 `remember` 里，而是绑"当前窗格的草稿"
 * （`RemoteViewModel.draft`），所以切窗格时框里的内容**自己就换成那一份**。
 *
 * 只在**切换窗格的那 0.5~1 秒**里整条锁死（输入框 + 按钮）。
 * **不按窗格状态置灰** —— 用户 2026-10-06 拍板：idle/off/busy/confirm 都能发。
 */
@Composable
private fun InputBar(
    value: String,
    /** 输入框能不能打字。**只表示"切换中 / 还没有窗格"**，不代表连接状态。 */
    enabled: Boolean,
    /** 发送按钮是否还要求连接。**不影响输入框** —— 连接差时照样能打字。 */
    canSend: Boolean,
    /** 禁用时显示哪一个原因（「切换中」/「还没有窗格」）。两种都哑着不说，用户只能干等。 */
    disabledHint: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            modifier = Modifier.weight(1f),
            placeholder = { Text(if (enabled) "发指令给 Claude Code…" else "$disabledHint…") },
            // Enter 换行（默认行为），发送只走右侧按钮
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            keyboardActions = KeyboardActions(),
            maxLines = 3
        )
        Spacer(Modifier.width(8.dp))
        // 按钮的三个状态要**分得开**（都灰，但原因不同 —— 不说出来用户只能猜）：
        //   · 切换中   → 窗格正在换，等一下
        //   · 未连接   → WS 没连上，现在发出去大概率没反应
        //   · 发送     → 只是没内容（灰是因为框里是空的，点一下就能用了）
        Button(
            onClick = onSend,
            enabled = enabled && canSend && value.isNotBlank()
        ) {
            Text(
                when {
                    !enabled -> disabledHint
                    !canSend -> "未连接"
                    else -> "发送"
                }
            )
        }
    }
}

// ── pane 切换 ─────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaneSheet(
    panes: List<LatticePaneDto>,
    currentRef: String?,
    /** 用来把"列表空"的三种原因说开 —— 见下面的空态文案。 */
    conn: ConnState,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                text = "切换会话",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            if (panes.isEmpty()) {
                // 🔴 **列表空有三种原因，不能都说成"没有窗格"**：
                //    原来只有一句「没有可切换的窗格」—— 而进页面头几秒 `panes` 本来就是空的，
                //    用户看到这句话会以为**电脑上压根没开 Lattice**（他报的就是这个）。
                //    `Down`（WS 超过 60 秒没连上）恰恰就是"Lattice 没开"最可能的样子，
                //    所以单独给它一句话，别混在"正在连接"里。
                Text(
                    text = when (conn) {
                        ConnState.Connected -> "没有可切换的窗格"
                        ConnState.Connecting,
                        ConnState.Reconnecting -> "正在连接…"
                        else -> "连不上电脑上的 Lattice，正在重试…"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                )
            }
            panes.forEach { pane ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(stateColor(pane.state), CircleShape)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = pane.title ?: pane.projectName ?: pane.id ?: "?",
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        pane.cwd?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (pane.id == currentRef) {
                        Text("当前", style = MaterialTheme.typography.labelSmall)
                    } else {
                        TextButton(onClick = { pane.id?.let(onPick) }) { Text("切换") }
                    }
                }
            }
        }
    }
}
