package com.example.clip.ui.loglist

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogListScreen(
    records: List<Record>,
    onRecordClick: (Record) -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("剪藏") }) }
    ) { padding ->
        if (records.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("还没有记录，点「语音」说话。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                items(records, key = { it.localId }) { record ->
                    LogListItem(record = record, onClick = { onRecordClick(record) })
                }
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
