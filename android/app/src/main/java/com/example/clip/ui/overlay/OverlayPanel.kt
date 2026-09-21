package com.example.clip.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.clip.ClipApp
import com.example.clip.domain.model.Record
import com.example.clip.recorder.RecordingController
import com.example.clip.ui.displayName
import com.example.clip.ui.displayTitle
import com.example.clip.ui.formatTime
import com.example.clip.util.FileLogger

/**
 * 主面板：三个按钮（语音｜文本｜视频）+ 下方记录列表（可滚动）。
 * 点语音 → 录音；点文本/视频 → 面板收起并弹对应卡片。
 */
@Composable
fun OverlayPanel(app: ClipApp) {
    val context = LocalContext.current
    val records by app.repository.observeRecords().collectAsState(emptyList())

    Box(
        Modifier
            .width(300.dp)
            .heightIn(max = 460.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xE8FFFFFF))
            .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(22.dp))
            .padding(16.dp)
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        OverlayState.panelOpen.value = false
                        RecordingController.start(context)
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("语音") }
                Button(
                    onClick = {
                        OverlayState.panelOpen.value = false
                        OverlayState.cardMode.value = CardMode.TEXT_INPUT
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("文本") }
                Button(
                    onClick = {
                        FileLogger.i("OverlayPanel", "点视频按钮 → VIDEO_INPUT")
                        OverlayState.panelOpen.value = false
                        OverlayState.pendingVideo.value = null
                        OverlayState.cardMode.value = CardMode.VIDEO_INPUT
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("视频") }
            }
            Spacer(Modifier.height(12.dp))
            if (records.isEmpty()) {
                Text(
                    "还没有记录，点「语音」说话。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(Modifier.heightIn(max = 220.dp)) {
                    items(records, key = { it.localId }) { record -> LogItem(record) }
                }
            }
        }
    }
}

@Composable
private fun LogItem(record: Record) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable {
                OverlayState.panelOpen.value = false
                OverlayState.detailLocalId.value = record.localId
                OverlayState.cardMode.value = CardMode.DETAIL
            }
            .padding(vertical = 6.dp)
    ) {
        Text(
            record.displayTitle(),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            "${record.type.displayName()} · ${formatTime(record.createdAt)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}
