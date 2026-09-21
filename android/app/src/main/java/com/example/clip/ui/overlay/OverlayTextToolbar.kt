package com.example.clip.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import com.example.clip.util.FileLogger

/** 自定义文本菜单项状态。 */
data class ToolbarMenuState(
    val rect: Rect,
    val onCut: (() -> Unit)?,
    val onCopy: (() -> Unit)?,
    val onPaste: (() -> Unit)?,
    val onSelectAll: (() -> Unit)?
)

/**
 * overlay 窗口里的自定义 TextToolbar：系统 ActionMode（浮动工具栏）在 overlay 里不显示，
 * 改用 Compose Popup 自绘菜单（剪切/复制/粘贴/全选），功能对齐系统。
 */
class OverlayTextToolbar : TextToolbar {

    val menuState = mutableStateOf<ToolbarMenuState?>(null)
    private val statusState = mutableStateOf<TextToolbarStatus>(TextToolbarStatus.Hidden)

    override val status: TextToolbarStatus get() = statusState.value

    override fun showMenu(
        rect: Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?
    ) {
        FileLogger.i("OverlayTextToolbar", "showMenu rect=$rect copy=${onCopyRequested != null} paste=${onPasteRequested != null} cut=${onCutRequested != null} selectAll=${onSelectAllRequested != null}")
        menuState.value = ToolbarMenuState(rect, onCutRequested, onCopyRequested, onPasteRequested, onSelectAllRequested)
        statusState.value = TextToolbarStatus.Shown
    }

    override fun hide() {
        menuState.value = null
        statusState.value = TextToolbarStatus.Hidden
    }
}

/** 渲染自绘文本菜单（在选中位置下方弹出的深色胶囊）。 */
@Composable
fun OverlayTextToolbarMenu(toolbar: OverlayTextToolbar) {
    val menu by toolbar.menuState
    val m = menu ?: return
    Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset(m.rect.left.toInt(), m.rect.bottom.toInt()),
        onDismissRequest = { toolbar.hide() }
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xF01A1C24))
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            m.onCut?.let { cb ->
                TextButton(onClick = { cb(); toolbar.hide() }) { Text("剪切", color = Color.White, style = MaterialTheme.typography.labelMedium) }
            }
            m.onCopy?.let { cb ->
                TextButton(onClick = { cb(); toolbar.hide() }) { Text("复制", color = Color.White, style = MaterialTheme.typography.labelMedium) }
            }
            m.onPaste?.let { cb ->
                TextButton(onClick = { cb(); toolbar.hide() }) { Text("粘贴", color = Color.White, style = MaterialTheme.typography.labelMedium) }
            }
            m.onSelectAll?.let { cb ->
                TextButton(onClick = { cb(); toolbar.hide() }) { Text("全选", color = Color.White, style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}
