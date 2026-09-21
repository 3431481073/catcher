package com.example.clip.ui.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import com.example.clip.ClipApp

/**
 * 面板窗口 content：AnimatedVisibility(panelOpen) 包裹主面板。
 * 面板无输入框 → 恒 NOT_FOCUSABLE，不观察 IME/焦点。
 */
@Composable
fun OverlayPanelHost(
    app: ClipApp,
    onPanelHeightChanged: (Int) -> Unit
) {
    val panelOpen by OverlayState.panelOpen.collectAsState()

    Box(Modifier.onSizeChanged { onPanelHeightChanged(it.height) }) {
        AnimatedVisibility(
            visible = panelOpen,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut()
        ) {
            OverlayPanel(app)
        }
    }
}
