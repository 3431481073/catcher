package com.example.clip.ui.overlay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.example.clip.ClipApp
import com.example.clip.data.catalog.CatalogStore
import com.example.clip.domain.model.RecordType
import com.example.clip.domain.model.SyncStatus
import com.example.clip.domain.model.TaskStatus
import com.example.clip.service.OverlayActions
import com.example.clip.ui.formatTime
import com.example.clip.util.ClipboardCache
import com.example.clip.util.FileLogger
import kotlinx.coroutines.flow.flowOf

/** 深色毛玻璃卡片底色（v12 rgba(18,20,28,.78)）。 */
private val CardBackground = Color(0xC712141C)

/** 深色卡片上的输入框配色（白字、浅描边）。 */
@Composable
internal fun cardFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color(0xE6FFFFFF),
    focusedBorderColor = Color(0x80FFFFFF),
    unfocusedBorderColor = Color(0x33FFFFFF),
    focusedLabelColor = Color(0xB3FFFFFF),
    unfocusedLabelColor = Color(0x66FFFFFF),
    cursorColor = Color.White
)

/**
 * 卡片窗 content：全屏透明窗口，底部深色浮动卡片 + 透明 scrim（点别处收卡片）。
 * 键盘用 Compose `imePadding()` 处理，窗口自身永不动。
 */
@Composable
fun OverlayCardHost(app: ClipApp, toolbar: OverlayTextToolbar) {
    val cardMode by OverlayState.cardMode.collectAsState()
    val mode = cardMode ?: return

    // 卡片只由取消/确认/关闭按钮关闭，不点外部关；窗口 touch-modal 已挡穿透
    Box(Modifier.fillMaxSize().imePadding()) {
        OverlayCard(app = app, mode = mode, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp))
        OverlayTextToolbarMenu(toolbar)
    }
}

/** 深色浮动卡片：顶部 24dp 圆角，宽度撑满、高度自适应内容。 */
@Composable
private fun OverlayCard(app: ClipApp, mode: CardMode, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(max = 440.dp)
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(CardBackground)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            when (mode) {
                CardMode.TEXT_INPUT -> TextInputCard(app)
                CardMode.VIDEO_INPUT -> VideoInputCard(app)
                CardMode.DETAIL -> DetailCard(app)
            }
        }
    }
}

// ---- 文本提交卡片 ----

@Composable
private fun TextInputCard(app: ClipApp) {
    val context = LocalContext.current
    var title by remember { mutableStateOf(TextFieldValue("")) }
    var body by remember { mutableStateOf(TextFieldValue("")) }
    var focusedOnTitle by remember { mutableStateOf(false) }
    var tags by remember { mutableStateOf<List<String>>(emptyList()) }
    val textTags by CatalogStore.textTags.collectAsState()

    Column(Modifier.fillMaxWidth()) {
        Text("提交文本", style = MaterialTheme.typography.titleMedium, color = Color.White)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = title, onValueChange = { title = it },
            modifier = Modifier.fillMaxWidth().onFocusChanged { focusedOnTitle = it.isFocused },
            label = { Text("标题（可选）") }, singleLine = true,
            colors = cardFieldColors()
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            SmallActionButton("粘贴") {
                val text = readClipboard(context)
                if (!text.isNullOrEmpty()) title = pasteInto(title, text)
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = body, onValueChange = { body = it },
            modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) focusedOnTitle = false },
            label = { Text("正文") }, minLines = 3, maxLines = 6,
            colors = cardFieldColors()
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            SmallActionButton("粘贴") {
                val text = readClipboard(context)
                if (!text.isNullOrEmpty()) body = pasteInto(body, text)
            }
            SmallActionButton("复制") { copySelectionOrAll(body, context) }
            SmallActionButton("全选") { body = selectAll(body) }
        }
        Spacer(Modifier.height(6.dp))
        Text("标签", style = MaterialTheme.typography.labelMedium, color = Color(0x80FFFFFF))
        Spacer(Modifier.height(4.dp))
        TagSelector(
            candidates = textTags,
            selected = tags,
            onToggle = { tag -> tags = if (tag in tags) tags - tag else tags + tag },
            onAdd = { tag ->
                CatalogStore.addTextTag(context, tag)
                tags = tags + tag
            },
            onDelete = { tag ->
                CatalogStore.removeTextTag(context, tag)
            },
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { OverlayState.cardMode.value = null },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xE6FFFFFF))
            ) { Text("取消") }
            Button(
                onClick = {
                    OverlayActions.submitText(app, title.text, body.text, tags)
                    OverlayState.onSubmitStarted()
                    OverlayState.cardMode.value = null
                },
                modifier = Modifier.weight(1f),
                enabled = title.text.isNotBlank() || body.text.isNotBlank()
            ) { Text("确认") }
        }
    }
}

// ---- 视频卡片 ----

@Composable
private fun VideoInputCard(app: ClipApp) {
    val context = LocalContext.current
    val pending by OverlayState.pendingVideo.collectAsState()
    val platforms by CatalogStore.platforms.collectAsState()
    val videoTags by CatalogStore.videoTags.collectAsState()

    var title by remember { mutableStateOf(TextFieldValue(pending?.title ?: "")) }
    var url by remember { mutableStateOf(TextFieldValue(pending?.url ?: "")) }
    var platform by remember { mutableStateOf(pending?.platform ?: "") }
    var note by remember { mutableStateOf(TextFieldValue("")) }
    var tags by remember { mutableStateOf<List<String>>(emptyList()) }
    var platformExpanded by remember { mutableStateOf(false) }
    var addingPlatform by remember { mutableStateOf(false) }
    var newPlatform by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { FileLogger.i("OverlayCard", "VideoInputCard 组合完成") }

    Column(Modifier.fillMaxWidth()) {
        Text("视频", style = MaterialTheme.typography.titleMedium, color = Color.White)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = title, onValueChange = { title = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("标题（可选）") }, singleLine = true,
            colors = cardFieldColors()
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            SmallActionButton("粘贴") {
                val text = readClipboard(context)
                if (!text.isNullOrEmpty()) title = pasteInto(title, text)
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = url, onValueChange = { url = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("链接（可选）") }, singleLine = true,
            colors = cardFieldColors()
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            SmallActionButton("粘贴") {
                val text = readClipboard(context)
                if (!text.isNullOrEmpty()) url = pasteInto(url, text)
            }
        }
        Spacer(Modifier.height(8.dp))
        // 平台：自绘 in-place 下拉（不弹 Popup 独立窗口，overlay 稳定不崩）
        Column(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { platformExpanded = !platformExpanded }
                    .background(Color(0x1AFFFFFF))
                    .padding(horizontal = 12.dp, vertical = 14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (platform.isBlank()) "平台（必填）" else platform,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (platform.isBlank()) Color(0x66FFFFFF) else Color.White
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (platformExpanded) "▲" else "▼",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0x80FFFFFF)
                    )
                }
            }
            if (platformExpanded) {
                Spacer(Modifier.height(4.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xF01A1C24))
                        .padding(vertical = 4.dp)
                ) {
                    platforms.forEach { p ->
                        Text(
                            p,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xE6FFFFFF),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    platformExpanded = false
                                    if (p == "其他") addingPlatform = true else platform = p
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        )
                    }
                }
            }
        }
        if (addingPlatform) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newPlatform, onValueChange = { newPlatform = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("新平台") }, singleLine = true,
                    colors = cardFieldColors()
                )
                TextButton(onClick = {
                    val t = newPlatform.trim()
                    if (t.isNotBlank()) {
                        platform = t
                        CatalogStore.addPlatform(context, t)
                        newPlatform = ""
                        addingPlatform = false
                    }
                }) { Text("确定") }
                TextButton(onClick = { newPlatform = ""; addingPlatform = false }) { Text("取消") }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = note, onValueChange = { note = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("备注（可选）") }, minLines = 2, maxLines = 4,
            colors = cardFieldColors()
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            SmallActionButton("粘贴") {
                val text = readClipboard(context)
                if (!text.isNullOrEmpty()) note = pasteInto(note, text)
            }
            SmallActionButton("复制") { copySelectionOrAll(note, context) }
            SmallActionButton("全选") { note = selectAll(note) }
        }
        Spacer(Modifier.height(6.dp))
        Text("标签", style = MaterialTheme.typography.labelMedium, color = Color(0x80FFFFFF))
        Spacer(Modifier.height(4.dp))
        TagSelector(
            candidates = videoTags,
            selected = tags,
            onToggle = { tag -> tags = if (tag in tags) tags - tag else tags + tag },
            onAdd = { tag ->
                CatalogStore.addVideoTag(context, tag)
                tags = tags + tag
            },
            onDelete = { tag ->
                CatalogStore.removeVideoTag(context, tag)
            },
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { OverlayState.cardMode.value = null },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xE6FFFFFF))
            ) { Text("取消") }
            Button(
                onClick = {
                    OverlayActions.submitVideo(app, title.text, url.text, platform, note.text, tags)
                    OverlayState.onSubmitStarted()
                    OverlayState.cardMode.value = null
                },
                modifier = Modifier.weight(1f),
                enabled = platform.isNotBlank()
            ) { Text("确认") }
        }
    }
}

// ---- 详情卡片 ----

@Composable
private fun DetailCard(app: ClipApp) {
    val detailLocalId by OverlayState.detailLocalId.collectAsState()
    val recordFlow = remember(detailLocalId) {
        detailLocalId?.let { app.repository.observeRecord(it) } ?: flowOf(null)
    }
    val record by recordFlow.collectAsState(initial = null)

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            record?.let { r ->
                TypeTag(r.type)
                Spacer(Modifier.width(8.dp))
                Text(
                    formatTime(r.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0x80FFFFFF)
                )
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { OverlayState.cardMode.value = null }) { Text("关闭", color = Color(0xB3FFFFFF)) }
        }
        Spacer(Modifier.height(8.dp))
        if (record == null) {
            Text("加载中…", style = MaterialTheme.typography.bodyMedium, color = Color(0xB3FFFFFF))
        } else {
            val r = record!!
            // 整个内容区（标题 + 正文 + URL）共用一个 SelectionContainer，可跨行选中复制
            SelectionContainer {
                Column(Modifier.fillMaxWidth()) {
                    r.title?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.titleMedium, color = Color.White)
                        Spacer(Modifier.height(6.dp))
                    }
                    val content = r.content
                    if (!content.isNullOrBlank()) {
                        Text(content, style = MaterialTheme.typography.bodyMedium, color = Color(0xE6FFFFFF))
                    }
                    r.url?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFF8FB3FF))
                    }
                }
            }
            if (r.notes.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                r.notes.forEach { n ->
                    Text(n.text, style = MaterialTheme.typography.bodySmall, color = Color(0xFFFF9DB2))
                    Text(
                        formatTime(n.time),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0x80FFFFFF)
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
            if (r.tags.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                ReadOnlyTagChips(r.tags)
            }
            Spacer(Modifier.height(4.dp)) // 内容区底部 padding 补到 ≥24dp（卡片已有 20dp）
        }
    }
}

@Composable
private fun TypeTag(type: RecordType) {
    val (bg, fg, label) = when (type) {
        RecordType.VOICE -> Triple(Color(0x2E6C8CFF), Color(0xFF8FB3FF), "语音")
        RecordType.VIDEO -> Triple(Color(0x2EB06CFF), Color(0xFFC99BFF), "视频")
        RecordType.TEXT -> Triple(Color(0x2934D6C0), Color(0xFF5EE0D4), "文本")
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = fg, fontWeight = FontWeight.Bold)
    }
}

/** 把剪贴板文字插入到当前光标处（替换选区），光标移到插入内容末尾。 */
private fun pasteInto(field: TextFieldValue, text: String): TextFieldValue {
    val cur = field.text
    val start = field.selection.min.coerceIn(0, cur.length)
    val end = field.selection.max.coerceIn(0, cur.length)
    val newText = cur.substring(0, start) + text + cur.substring(end)
    return field.copy(text = newText, selection = TextRange(start + text.length))
}

/** 复制：有 selection 复制选中，无 selection 复制全文。 */
private fun copySelectionOrAll(field: TextFieldValue, context: Context) {
    val start = field.selection.min.coerceIn(0, field.text.length)
    val end = field.selection.max.coerceIn(0, field.text.length)
    val selected = field.text.substring(start, end)
    val text = if (selected.isNotEmpty()) selected else field.text
    if (text.isNotEmpty()) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("text", text))
    }
}

/** 全选。 */
private fun selectAll(field: TextFieldValue): TextFieldValue {
    return field.copy(selection = TextRange(0, field.text.length))
}

/** 详情卡片只读标签 chip（不参与选择/删除）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReadOnlyTagChips(tags: List<String>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        tags.forEach { tag ->
            Box(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x1AFFFFFF))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(tag, style = MaterialTheme.typography.labelMedium, color = Color(0xCCFFFFFF))
            }
        }
    }
}

/** 小号浅色动作按钮（粘贴/复制/全选）。 */
@Composable
private fun SmallActionButton(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = Color(0x80FFFFFF),
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

/** 读剪贴板：先直接读（overlay 可能失败），失败/空从 ClipboardCache 缓存读。 */
private fun readClipboard(context: Context): String? {
    val direct = try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.getPrimaryClip()?.getItemAt(0)?.text?.toString()
    } catch (_: Exception) {
        null
    }
    return if (!direct.isNullOrEmpty()) direct else ClipboardCache.cachedText()
}
