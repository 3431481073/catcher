package com.example.clip.ui.loglist

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.clip.domain.model.DisplayState
import com.example.clip.domain.model.Record
import com.example.clip.ui.displayName
import com.example.clip.ui.displayTitle
import com.example.clip.ui.formatTime

@Composable
fun LogListScreen(
    records: List<Record>,
    onRecordClick: (Record) -> Unit,
    /** 顶部的「没开使用情况访问」提示条要不要显示（判断在 MainScreen 里）。 */
    showUsageHint: Boolean = false,
    onOpenUsageSettings: () -> Unit = {},
    onDismissUsageHint: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // 顶部 chrome（TopAppBar + TabRow）现在归 MainScreen —— 这里只画内容。
    // 原来这里自带 Scaffold+TopAppBar，加 Tab 后会变成嵌套 Scaffold。
    Column(modifier = modifier.fillMaxSize()) {
        if (showUsageHint) UsageHintBanner(onOpenUsageSettings, onDismissUsageHint)

        if (records.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text("还没有记录，点「文本」或「视频」开始。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                items(records, key = { it.localId }) { record ->
                    LogListItem(record = record, onClick = { onRecordClick(record) })
                }
            }
        }
    }
}

/**
 * 「没开使用情况访问」提示条。
 *
 * 存在意义：**这个权限缺了是完全静默的** —— 引导页过后用户再也不会看到它，
 * 采集则一条不出。不提示的话，"装了但没数据"和"采集坏了"长得一模一样，
 * 正是本项目最忌讳的那种失效。
 *
 * 用 secondaryContainer 而不是 errorContainer：这不是错误，是「少开了一个开关」，
 * 而且要和 MainScreen 里那条 Token 失效的红色横幅**一眼区分开**。
 *
 * 两行布局（正文一行、按钮一行）：挤成一行的话「去开启」+「不再提示」两个按钮会把
 * 正文压到三行，窄屏上很难看。
 */
@Composable
private fun UsageHintBanner(onOpen: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 2.dp
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 2.dp)) {
            Text(
                text = "未开启「使用情况访问」，App 使用时长不会采集",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onOpen) { Text("去开启") }
                TextButton(onClick = onDismiss) { Text("不再提示") }
            }
        }
    }
}

@Composable
private fun LogListItem(record: Record, onClick: () -> Unit) {
    val state = record.displayState()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusIndicator(state = state, queuePosition = record.queuePosition)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = record.displayTitle(),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${record.type.displayName()} · ${formatTime(record.createdAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun StatusIndicator(state: DisplayState, queuePosition: Int) {
    val color = when (state) {
        DisplayState.PENDING -> Color(0xFF9E9E9E)
        DisplayState.SYNCING, DisplayState.QUEUED, DisplayState.PROCESSING -> Color(0xFF2196F3)
        DisplayState.DONE -> Color(0xFF4CAF50)
        DisplayState.FAILED -> Color(0xFFF44336)
        DisplayState.SKIPPED -> Color(0xFF9C27B0)
    }

    Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        when (state) {
            DisplayState.SYNCING, DisplayState.QUEUED -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = color,
                    strokeWidth = 2.dp
                )
                if (state == DisplayState.QUEUED) {
                    Text(
                        text = "$queuePosition",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = color
                    )
                }
            }
            DisplayState.PROCESSING -> {
                val transition = rememberInfiniteTransition()
                val scale by transition.animateFloat(
                    initialValue = 0.7f,
                    targetValue = 1.3f,
                    animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse)
                )
                Box(
                    Modifier
                        .size(10.dp)
                        .scale(scale)
                        .background(color, CircleShape)
                )
            }
            else -> {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(color, CircleShape)
                )
            }
        }
    }
}
