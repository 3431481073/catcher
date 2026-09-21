package com.example.clip.ui.detail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.clip.domain.model.Record
import com.example.clip.ui.displayName
import com.example.clip.ui.displayTitle
import com.example.clip.ui.formatTime

/**
 * App 内详情（自绘底部卡片，不用 ModalBottomSheet，避免"点外部关闭丢草稿"）。
 * - 打开：detailTarget 非空；关闭：点「关闭」按钮或返回键。
 * - 显示所有备注（带时间戳）+ 追加备注。
 * - 草稿按 record.localId 隔离（noteDraft 来自 MainViewModel.noteDrafts Map）。
 */
@Composable
fun DetailPanel(
    record: Record,
    onClose: () -> Unit,
    onAppendNote: (String) -> Unit,
    noteDraft: String,
    onDraftChange: (String) -> Unit,
) {
    // 按记录 ID 重置草稿：切到别的记录不带旧草稿
    var note by remember(record.localId) { mutableStateOf(noteDraft) }

    BackHandler(onBack = onClose)

    Box(Modifier.fillMaxSize()) {
        // scrim：点外部只挡穿透、不关闭
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { /* no-op：不点外部关 */ }
        )
        // 底部卡片：显式「关闭」按钮，内容可滚动
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(MaterialTheme.colorScheme.surface)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = record.type.displayName(),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF3B6EF6),
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("关闭") }
            }
            Spacer(Modifier.height(10.dp))
            val header = record.displayTitle()
            Text(
                text = header,
                style = MaterialTheme.typography.titleLarge
            )
            record.content?.takeIf { it.isNotBlank() && it != header }?.let { content ->
                Spacer(Modifier.height(8.dp))
                Text(content, style = MaterialTheme.typography.bodyMedium)
            }
            record.url?.takeIf { it.isNotBlank() && it != header }?.let { url ->
                Spacer(Modifier.height(8.dp))
                Text(url, style = MaterialTheme.typography.bodySmall, color = Color(0xFF3B6EF6))
            }

            // 所有备注（追加式，带时间戳）；空列表整块不显示
            if (record.notes.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "备注",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                record.notes.forEach { n ->
                    Text(n.text, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = formatTime(n.time),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }

            // 只读标签 chip（标签在提交时选，详情里不改）
            if (record.tags.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                TagChips(record.tags)
            }

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = note,
                onValueChange = {
                    note = it
                    onDraftChange(it)
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("追加备注（可选）") },
                minLines = 2,
                maxLines = 5
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    val t = note
                    note = ""
                    onDraftChange("")
                    onAppendNote(t)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = note.isNotBlank()
            ) { Text("追加备注") }
            Spacer(Modifier.height(10.dp))
            Text(
                text = "创建于 ${formatTime(record.createdAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 只读标签 chip（light 主题，风格对齐 overlay 的 ReadOnlyTagChips）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagChips(tags: List<String>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        tags.forEach { tag ->
            Box(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    tag,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
