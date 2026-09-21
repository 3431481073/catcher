package com.example.clip.ui.floatingball

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.example.clip.ui.overlay.BallVisualState

/** 球状态 → 颜色（v12）。 */
fun BallVisualState.color(): Color = when (this) {
    BallVisualState.IDLE -> Color(0x66000000)        // 半透明黑
    BallVisualState.REC -> Color(0xFFFF4D6D)         // 255,77,109 红
    BallVisualState.BUSY -> Color(0xFF6C8CFF)        // 108,140,255 蓝
    BallVisualState.DONE_VOICE -> Color(0xFF34C759)  // 52,199,89 绿
    BallVisualState.DONE_TEXT -> Color(0xFF2FD4C4)   // 47,212,196 青
    BallVisualState.DONE_VIDEO -> Color(0xFFB06CFF)  // 176,108,255 紫
    BallVisualState.FAILED -> Color(0xFFE53935)      // 229,57,53 红（错误）
}

/**
 * 悬浮球（纯视觉）：52px 玻璃圆，所有动画都在球内部（不外画柱子/环）。
 * 手势由窗口层 OnTouchListener（raw 坐标）处理，这里不挂 pointerInput。
 * 外层 Box 68px 只做触摸区，球本体 52px 居中、clip 成圆，动画+图标都收在球内。
 */
@Composable
fun FloatingBall(
    state: BallVisualState,
    amplitude: Float,
    modifier: Modifier = Modifier
) {
    val color = state.color()
    Box(
        modifier = modifier.size(68.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.85f)),
            contentAlignment = Alignment.Center
        ) {
            when (state) {
                BallVisualState.REC -> InnerWaveform(amplitude, Modifier.fillMaxSize())
                BallVisualState.BUSY -> InnerBusyPulse(Modifier.fillMaxSize())
                else -> {}
            }
            BallIcon(state)
        }
    }
}

/** 球中心图标：idle 圆点 / rec 麦克风 / busy 转圈 / done 对勾 / failed 叉。 */
@Composable
private fun BallIcon(state: BallVisualState) {
    when (state) {
        BallVisualState.IDLE ->
            Box(Modifier.size(8.dp).background(Color.White.copy(alpha = 0.9f), CircleShape))
        BallVisualState.REC -> MicIcon(Modifier.size(20.dp))
        BallVisualState.BUSY -> SpinnerIcon(Modifier.size(18.dp))
        BallVisualState.DONE_VOICE, BallVisualState.DONE_TEXT, BallVisualState.DONE_VIDEO ->
            CheckIcon(Modifier.size(20.dp))
        BallVisualState.FAILED -> CrossIcon(Modifier.size(20.dp))
    }
}

/** rec：球内横着的波形（中间高两边低的竖条），整体高度随音量联动，没声音塌成平线。 */
@Composable
private fun InnerWaveform(amplitude: Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val cy = size.height / 2f
        val a = amplitude.coerceIn(0f, 1f)
        if (a < 0.03f) {
            // 没声音：平线
            drawLine(
                Color.White.copy(alpha = 0.5f),
                Offset(w * 0.22f, cy), Offset(w * 0.78f, cy),
                strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round
            )
            return@Canvas
        }
        val barCount = 7
        val barW = 2.5.dp.toPx()
        val gap = 3.dp.toPx()
        val maxHalfH = 9.dp.toPx()
        val mid = (barCount - 1) / 2f
        for (i in 0 until barCount) {
            val env = 1f - kotlin.math.abs(i - mid) / mid
            val halfH = maxHalfH * env * a
            val x = w / 2f + (i - mid) * (barW + gap)
            drawLine(
                Color.White,
                Offset(x, cy - halfH), Offset(x, cy + halfH),
                strokeWidth = barW, cap = StrokeCap.Round
            )
        }
    }
}

/** busy：球内亮度呼吸脉冲。 */
@Composable
private fun InnerBusyPulse(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition()
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse)
    )
    Canvas(modifier) {
        drawCircle(
            Color.White.copy(alpha = 0.05f + 0.15f * t),
            radius = size.minDimension / 2f,
            center = center
        )
    }
}

@Composable
private fun MicIcon(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val stroke = 2.dp.toPx()
        val capsuleW = 7.dp.toPx()
        val capsuleH = 11.dp.toPx()
        val capsuleTop = 1.dp.toPx()
        drawRoundRect(
            color = Color.White,
            topLeft = Offset(cx - capsuleW / 2f, capsuleTop),
            size = Size(capsuleW, capsuleH),
            cornerRadius = CornerRadius(capsuleW / 2f)
        )
        val stemTop = capsuleTop + capsuleH
        val stemBottom = h - 4.dp.toPx()
        drawLine(Color.White, Offset(cx, stemTop), Offset(cx, stemBottom), strokeWidth = stroke, cap = StrokeCap.Round)
        drawArc(
            color = Color.White,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(cx - 5.dp.toPx(), stemBottom - 2.dp.toPx()),
            size = Size(10.dp.toPx(), 5.dp.toPx()),
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
    }
}

/** busy：中心转圈图标（270° 弧旋转）。 */
@Composable
private fun SpinnerIcon(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition()
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Restart)
    )
    Canvas(modifier) {
        val r = size.minDimension / 2f
        drawArc(
            color = Color.White,
            startAngle = angle,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = Offset(center.x - r, center.y - r),
            size = Size(r * 2f, r * 2f),
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}

@Composable
private fun CheckIcon(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = 2.5.dp.toPx()
        drawLine(
            Color.White,
            Offset(size.width * 0.25f, size.height * 0.55f),
            Offset(size.width * 0.45f, size.height * 0.75f),
            strokeWidth = stroke, cap = StrokeCap.Round
        )
        drawLine(
            Color.White,
            Offset(size.width * 0.45f, size.height * 0.75f),
            Offset(size.width * 0.78f, size.height * 0.32f),
            strokeWidth = stroke, cap = StrokeCap.Round
        )
    }
}

@Composable
private fun CrossIcon(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = 2.5.dp.toPx()
        val m = 0.32f
        drawLine(
            Color.White,
            Offset(size.width * m, size.height * m),
            Offset(size.width * (1 - m), size.height * (1 - m)),
            strokeWidth = stroke, cap = StrokeCap.Round
        )
        drawLine(
            Color.White,
            Offset(size.width * (1 - m), size.height * m),
            Offset(size.width * m, size.height * (1 - m)),
            strokeWidth = stroke, cap = StrokeCap.Round
        )
    }
}
