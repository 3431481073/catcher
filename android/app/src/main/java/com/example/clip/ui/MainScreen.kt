package com.example.clip.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.clip.data.token.TokenGate
import com.example.clip.ui.bottompanel.BottomPanel
import com.example.clip.ui.detail.DetailPanel
import com.example.clip.ui.loglist.LogListScreen
import com.example.clip.ui.panel.Panel

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val records by viewModel.records.collectAsStateWithLifecycle()
    val panelVisible by viewModel.panelVisible.collectAsStateWithLifecycle()
    val detailRecord by viewModel.detailRecord.collectAsStateWithLifecycle()
    val bottomPanelVisible by viewModel.bottomPanelVisible.collectAsStateWithLifecycle()
    val tokenPaused by TokenGate.isPaused.collectAsStateWithLifecycle()
    val micDenied by viewModel.micPermissionDenied.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Box(modifier = Modifier.fillMaxSize()) {
        LogListScreen(
            records = records,
            onRecordClick = viewModel::onRecordClick,
            modifier = Modifier.fillMaxSize()
        )

        if (panelVisible) {
            Panel(
                onSpeak = viewModel::onSpeak,
                onSubmitText = viewModel::onSubmitTextTap,
                onDismiss = { viewModel.panelVisible.value = false }
            )
        }

        detailRecord?.let { record ->
            DetailPanel(
                record = record,
                onClose = viewModel::dismissDetail,
                onAppendNote = { viewModel.appendNote(record, it) },
                noteDraft = viewModel.noteDrafts.value[record.localId] ?: "",
                onDraftChange = { viewModel.setNoteDraft(record.localId, it) }
            )
        }

        if (bottomPanelVisible) {
            BottomPanel(
                onSubmit = viewModel::onSubmitText,
                onDismiss = viewModel::dismissBottomPanel
            )
        }

        if (tokenPaused) {
            Banner(
                text = "Token 失效，请更新",
                action = "去更新",
                onAction = viewModel::clearToken,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(8.dp)
            )
        }

        if (micDenied) {
            Banner(
                text = "需要麦克风权限才能录音",
                action = "去设置",
                onAction = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(8.dp)
            )
        }
    }
}

@Composable
private fun Banner(text: String, action: String, onAction: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}
