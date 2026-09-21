package com.example.clip.ui.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp

/**
 * 标签折叠下拉（in-place，不弹 Popup）。
 * - 收起：已选 chip（点取消）+ 「＋ 标签」。
 * - 展开：候选列表（点切换选中、右侧 × 删候选）+ 「＋ 添加标签」内联输入。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagSelector(
    candidates: List<String>,
    selected: List<String>,
    onToggle: (String) -> Unit,
    onAdd: (String) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    var newTag by remember { mutableStateOf("") }

    Column(modifier) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            selected.forEach { tag ->
                SelectedChip(tag) { onToggle(tag) }
            }
            ExpandChip(expanded) { expanded = !expanded }
        }

        if (expanded) {
            Spacer(Modifier.height(6.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0x1AFFFFFF))
                    .padding(8.dp)
            ) {
                if (candidates.isEmpty()) {
                    Text("暂无标签", style = MaterialTheme.typography.bodySmall, color = Color(0x66FFFFFF))
                } else {
                    candidates.forEach { tag ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onToggle(tag) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                (if (tag in selected) "✓ " else "") + tag,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (tag in selected) Color.White else Color(0xCCFFFFFF),
                                modifier = Modifier.weight(1f)
                            )
                            Box(
                                Modifier
                                    .size(18.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .clickable { onDelete(tag) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text("×", style = MaterialTheme.typography.labelSmall, color = Color(0x80FFFFFF))
                            }
                        }
                    }
                }
                if (adding) {
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newTag, onValueChange = { newTag = it },
                            modifier = Modifier.weight(1f),
                            label = { Text("新标签") }, singleLine = true,
                            colors = cardFieldColors()
                        )
                        TextButton(onClick = {
                            val t = newTag.trim()
                            if (t.isNotBlank()) { onAdd(t); newTag = ""; adding = false }
                        }) { Text("添加") }
                        TextButton(onClick = { newTag = ""; adding = false }) { Text("取消") }
                    }
                } else {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "＋ 添加标签",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0x80FFFFFF),
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { adding = true }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectedChip(tag: String, onToggle: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF2E6C8C))
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(tag, style = MaterialTheme.typography.labelMedium, color = Color.White)
    }
}

@Composable
private fun ExpandChip(expanded: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x1AFFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (expanded) "收起 ▴" else "＋ 标签",
            style = MaterialTheme.typography.labelMedium,
            color = Color(0x80FFFFFF)
        )
    }
}
