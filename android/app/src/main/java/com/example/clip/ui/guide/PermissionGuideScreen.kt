package com.example.clip.ui.guide

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.clip.util.Permissions

/**
 * 首次启动引导页：悬浮窗（必过）+ 通知（推荐）。
 * 悬浮窗未授权时"进入剪藏"不可点。
 */
@Composable
fun PermissionGuideScreen(
    onEnter: () -> Unit,
    onRequestNotification: () -> Unit
) {
    val context = LocalContext.current
    val overlay by Permissions.overlay.collectAsStateWithLifecycle()
    val notification by Permissions.notification.collectAsStateWithLifecycle()

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(Modifier.height(16.dp))
            Text("开启悬浮球", style = MaterialTheme.typography.headlineSmall)
            Text(
                "开启后悬浮球会浮在所有应用上方，点球唤起面板、语音录音。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))

            GuideCard(
                title = "悬浮窗权限",
                desc = "必须开启，否则悬浮球无法显示。",
                granted = overlay,
                required = true,
                actionLabel = "去设置",
                onAction = { context.startActivity(Permissions.overlayIntent(context)) }
            )
            GuideCard(
                title = "通知权限",
                desc = "推荐开启；不开启时悬浮球仍可用，只是没有常驻通知。",
                granted = notification,
                required = false,
                actionLabel = "去开启",
                onAction = onRequestNotification
            )

            Spacer(Modifier.height(8.dp))
            Text(
                "悬浮球若消失，去系统设置把「剪藏」加入白名单 / 锁定后台。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "部分机型还要在省电策略里关闭对本 App 的限制。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "如果重启手机后悬浮球没自动出现，请去系统设置里允许「剪藏」自启动、允许后台活动、锁定最近任务。这是 Android 国产 ROM 的标准要求。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = onEnter,
                enabled = overlay,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (overlay) "进入剪藏" else "请先开启悬浮窗权限")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun GuideCard(
    title: String,
    desc: String,
    granted: Boolean,
    required: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                StatusBadge(granted, required)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!granted) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@Composable
private fun StatusBadge(granted: Boolean, required: Boolean) {
    val (text, color) = when {
        granted -> "已开启" to Color(0xFF2E7D32)
        required -> "必须" to MaterialTheme.colorScheme.error
        else -> "推荐" to Color(0xFF8A63D2)
    }
    Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(50)) {
        Text(
            text,
            color = color,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}
