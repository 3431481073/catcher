package com.example.clip.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF3B6EF6),
    secondary = Color(0xFF8A63D2),
    background = Color(0xFFF7F7F9),
    surface = Color(0xFFFFFFFF)
)

@Composable
fun ClipTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LightColors, content = content)
}
