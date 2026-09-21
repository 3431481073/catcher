package com.example.clip.service

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalTextToolbar
import com.example.clip.ClipApp
import com.example.clip.recorder.RecordingController
import com.example.clip.ui.floatingball.FloatingBall
import com.example.clip.ui.overlay.BallVisualState
import com.example.clip.ui.overlay.OverlayCardHost
import com.example.clip.ui.overlay.OverlayPanelHost
import com.example.clip.ui.overlay.OverlayState
import com.example.clip.ui.overlay.OverlayTextToolbar
import com.example.clip.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 三个独立 WindowManager 窗口：
 * - 球窗：只放球，View 级 OnTouchListener（raw 坐标）处理 tap/drag/outside，跟手。
 * - 面板窗：主面板，位置动态计算（球左半屏→向右，右半屏→向左，夹屏内）。
 * - 卡片窗：全屏 MATCH_PARENT + Compose 布局（scrim + 底部卡片 + imePadding），
 *   开→addView、关→removeView，窗口永不 updateViewLayout 重定位。
 * 球坐标按「屏幕比例」持久化（横竖屏安全）+ 硬 clamp + DisplayListener 旋转重推。
 */
class FloatingBallWindow(private val context: Context) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = context.getSharedPreferences("floating_ball", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var ballView: ComposeView? = null
    private var ballOwner: OverlayLifecycleOwner? = null
    private var panelView: ComposeView? = null
    private var panelOwner: OverlayLifecycleOwner? = null
    private var cardView: ComposeView? = null
    private var cardOwner: OverlayLifecycleOwner? = null

    // 视觉球（52dp）中心在屏幕上的坐标（px）
    private var centerX = 0
    private var centerY = 0
    private var collapsed = false
    private var panelHeight = 0
    private var collapseJob: Job? = null

    // 屏幕比例（0..1），横竖屏切换按此重推
    private var cxFrac = 0.5f
    private var cyFrac = 0.5f
    private var lastScreenW = 0
    private var lastScreenH = 0

    // 拖动跟手：触摸起点（屏幕绝对坐标）+ 球中心起点
    private var downRawX = 0f
    private var downRawY = 0f
    private var downCenterX = 0
    private var downCenterY = 0
    private var dragging = false
    private var movedBeyondSlop = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            val (w, h) = screenSize()
            FileLogger.i(TAG, "displayChanged id=$displayId size=${w}x$h last=${lastScreenW}x$lastScreenH")
            if (w != lastScreenW || h != lastScreenH) {
                lastScreenW = w
                lastScreenH = h
                logBallPos("displayChanged-before")
                rederivePosition()
            }
        }
    }

    fun show() {
        if (ballView != null) return
        val (screenW, screenH) = screenSize()
        val defaultCxFrac = if (screenW > 0) (screenW - dp(42)).toFloat() / screenW else 1f
        cxFrac = prefs.getFloat(KEY_CX_FRAC, defaultCxFrac)
        cyFrac = prefs.getFloat(KEY_CY_FRAC, 0.5f)
        centerX = (cxFrac * screenW).toInt().coerceIn(0, screenW)
        centerY = (cyFrac * screenH).toInt().coerceIn(0, screenH)
        collapsed = prefs.getBoolean(KEY_COLLAPSED, false)
        lastScreenW = screenW
        lastScreenH = screenH

        FileLogger.i(TAG, "挂载球+面板 centerX=$centerX centerY=$centerY collapsed=$collapsed")
        logBallPos("show-loaded")
        val app = context.applicationContext as ClipApp

        val (ball, bOwner) = createOverlayWindow {
            val ballState by OverlayState.ballState.collectAsState()
            val amplitude by RecordingController.amplitude.collectAsState()
            FloatingBall(state = ballState, amplitude = amplitude)
        }
        val (panel, pOwner) = createOverlayWindow {
            OverlayPanelHost(
                app = app,
                onPanelHeightChanged = { h -> panelHeight = h; updatePanelPosition() }
            )
        }

        ballOwner = bOwner
        panelOwner = pOwner
        ballView = ball
        panelView = panel

        attachBallTouch(ball)

        try {
            wm.addView(ball, buildBallLayoutParams())
            wm.addView(panel, buildPanelLayoutParams())
            FileLogger.i(TAG, "球+面板 addView 成功")
        } catch (e: Exception) {
            ballView = null
            panelView = null
            ballOwner = null
            panelOwner = null
            runCatching { wm.removeView(ball) }
            runCatching { wm.removeView(panel) }
            FileLogger.e(TAG, "WindowManager.addView 失败", e)
            throw e
        }

        // 卡片按需 add/remove（全屏窗口）
        scope.launch {
            OverlayState.cardMode.collect { mode ->
                if (mode != null) showCard() else hideCard()
            }
        }

        if (!collapsed) scheduleCollapse()
        registerDisplayListener()
    }

    fun remove() {
        scope.cancel()
        unregisterDisplayListener()
        hideCard()
        ballView?.let { v -> runCatching { wm.removeView(v) } }
        panelView?.let { v -> runCatching { wm.removeView(v) } }
        ballView = null
        panelView = null
        ballOwner?.performDestroy()
        panelOwner?.performDestroy()
        ballOwner = null
        panelOwner = null
    }

    // ---- 卡片（全屏）add/remove ----

    private fun showCard() {
        if (cardView != null) return
        val app = context.applicationContext as ClipApp
        // 文本菜单 toolbar 在窗口层持有，根层注入 LocalTextToolbar，确保覆盖 TextField 内部的缓存读点
        val toolbar = OverlayTextToolbar()
        val (card, owner) = createOverlayWindow {
            CompositionLocalProvider(LocalTextToolbar provides toolbar) {
                OverlayCardHost(app = app, toolbar = toolbar)
            }
        }
        cardOwner = owner
        cardView = card
        try {
            wm.addView(card, buildCardLayoutParams())
            FileLogger.i(TAG, "卡片窗 addView 成功")
        } catch (e: Exception) {
            cardView = null
            cardOwner = null
            FileLogger.e(TAG, "卡片窗 addView 失败", e)
        }
    }

    private fun hideCard() {
        cardView?.let { v -> runCatching { wm.removeView(v) } }
        cardView = null
        cardOwner?.performDestroy()
        cardOwner = null
    }

    // ---- 球触摸（raw 坐标跟手 + outside 收缩） ----

    private fun attachBallTouch(view: View) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downCenterX = centerX
                    downCenterY = centerY
                    dragging = false
                    movedBeyondSlop = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (dx * dx + dy * dy) > slop * slop) {
                        movedBeyondSlop = true
                        if (canDrag()) {
                            dragging = true
                            onDragStart()
                        }
                    }
                    if (dragging) {
                        centerX = downCenterX + dx.toInt()
                        centerY = downCenterY + dy.toInt()
                        clampDuringDrag()
                        updateBallPosition()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    when {
                        dragging -> onDragEnd()
                        !movedBeyondSlop -> onBallTap()
                        else -> {} // 拖拽尝试但 canDrag 失败（rec/卡片开）→ 无效
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) onDragEnd()
                    true
                }
                MotionEvent.ACTION_OUTSIDE -> {
                    // 点别处分层退出：先关面板，再缩半圆
                    if (OverlayState.panelOpen.value) {
                        OverlayState.panelOpen.value = false
                    } else {
                        collapseToHalf()
                    }
                    true
                }
                else -> false
            }
        }
    }

    // ---- 半圆自动收缩 ----

    private fun scheduleCollapse() {
        collapseJob?.cancel()
        collapseJob = scope.launch {
            delay(1000)
            collapseToHalf()
        }
    }

    private fun canCollapse(): Boolean =
        !OverlayState.panelOpen.value &&
            OverlayState.cardMode.value == null &&
            OverlayState.ballState.value == BallVisualState.IDLE &&
            !dragging

    private fun canDrag(): Boolean =
        OverlayState.ballState.value != BallVisualState.REC &&
            OverlayState.cardMode.value == null

    private fun collapseToHalf() {
        if (collapsed) return
        if (!canCollapse()) return
        logBallPos("collapse-before")
        val (screenW, _) = screenSize()
        collapsed = true
        centerX = if (centerX < screenW / 2) 0 else screenW
        clampToScreen()
        logBallPos("collapse-after")
        persistPosition()
        updateBallPosition()
        updatePanelPosition()
    }

    private fun clampDuringDrag() {
        val (screenW, screenH) = screenSize()
        collapsed = false
        centerX = centerX.coerceIn(-dp(26), screenW + dp(26))
        centerY = centerY.coerceIn(dp(34), screenH - dp(34))
    }

    private fun onBallTap() {
        when (OverlayState.ballState.value) {
            BallVisualState.REC -> RecordingController.stop(context)
            BallVisualState.BUSY -> {} // 只有 busy 不可点
            else -> { // IDLE / DONE_* / FAILED
                if (collapsed) {
                    expand()
                } else {
                    OverlayState.panelOpen.value = !OverlayState.panelOpen.value
                    scheduleCollapse()
                }
            }
        }
    }

    private fun onDragStart() {
        collapseJob?.cancel()
        OverlayState.panelOpen.value = false
    }

    private fun onDragEnd() {
        dragging = false
        logBallPos("dragEnd-before")
        val (screenW, screenH) = screenSize()
        centerY = centerY.coerceIn(dp(34), screenH - dp(34))
        val leftSide = centerX < screenW / 2
        collapsed = false // 松手保持全圆
        centerX = if (leftSide) dp(34) else screenW - dp(34) // 吸附到边缘位置（全圆）
        clampToScreen()
        logBallPos("dragEnd-after")
        persistPosition()
        updateBallPosition()
        updatePanelPosition()
        scheduleCollapse() // 松手后 1 秒无操作才缩半圆
    }

    /** 点半圆 → 展开回完整球 + 重新计时。 */
    private fun expand() {
        logBallPos("expand-before")
        val (screenW, _) = screenSize()
        centerX = if (centerX < screenW / 2) dp(34) else screenW - dp(34)
        collapsed = false
        clampToScreen()
        logBallPos("expand-after")
        persistPosition()
        updateBallPosition()
        updatePanelPosition()
        scheduleCollapse()
    }

    // ---- 位置 / 横竖屏 ----

    private fun clampToScreen() {
        val (screenW, screenH) = screenSize()
        centerX = centerX.coerceIn(0, screenW)
        centerY = centerY.coerceIn(0, screenH)
    }

    private fun logBallPos(phase: String) {
        val (sw, sh) = screenSize()
        val bx = centerX - dp(34)
        val by = centerY - dp(34)
        FileLogger.i(TAG, "$phase screen=${sw}x$sh center=($centerX,$centerY) collapsed=$collapsed frac=($cxFrac,$cyFrac) ball=($bx,$by)")
    }

    /** 屏幕旋转/尺寸变化 → 用比例重推坐标。 */
    private fun rederivePosition() {
        val (screenW, screenH) = screenSize()
        if (screenW <= 0 || screenH <= 0) return
        logBallPos("rederive-before")
        centerX = (cxFrac * screenW).toInt().coerceIn(0, screenW)
        centerY = (cyFrac * screenH).toInt().coerceIn(0, screenH)
        logBallPos("rederive-after")
        updateBallPosition()
        updatePanelPosition()
    }

    private fun registerDisplayListener() {
        runCatching {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            dm.registerDisplayListener(displayListener, null)
        }
    }

    private fun unregisterDisplayListener() {
        runCatching {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            dm.unregisterDisplayListener(displayListener)
        }
    }

    private fun updateBallPosition() {
        val v = ballView ?: return
        val lp = v.layoutParams as WindowManager.LayoutParams
        lp.x = centerX - dp(34)
        lp.y = centerY - dp(34)
        wm.updateViewLayout(v, lp)
    }

    private fun panelX(): Int {
        val screenW = screenSize().first
        val onLeft = centerX < screenW / 2
        return if (onLeft) {
            (centerX + dp(34) + dp(8)).coerceAtMost(screenW - dp(300) - dp(8))
        } else {
            (centerX - dp(34) - dp(300) - dp(8)).coerceAtLeast(dp(8))
        }
    }

    private fun updatePanelPosition() {
        val v = panelView ?: return
        val (_, screenH) = screenSize()
        val lp = v.layoutParams as WindowManager.LayoutParams
        lp.x = panelX()
        val maxTop = (screenH - panelHeight - dp(8)).coerceAtLeast(dp(8))
        lp.y = (centerY - panelHeight / 2).coerceIn(dp(8), maxTop)
        wm.updateViewLayout(v, lp)
    }

    // ---- 构建 ----

    private fun createOverlayWindow(content: @Composable () -> Unit): Pair<ComposeView, OverlayLifecycleOwner> {
        val owner = OverlayLifecycleOwner()
        owner.performAttach()
        val view = ComposeView(context).apply {
            OverlayViewTreeHelper.setOwners(this, owner, owner, owner)
            setContent { content() }
        }
        owner.performResume()
        return view to owner
    }

    private fun buildBallLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = centerX - dp(34)
            y = centerY - dp(34)
        }

    private fun buildPanelLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = panelX()
            y = centerY - panelHeight / 2
        }

    /** 卡片窗：全屏可聚焦 + Compose 布局（imePadding 处理键盘），窗口自身永远不动。 */
    private fun buildCardLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            0, // 可聚焦 + 触摸模态：卡片是模态，焦点给输入框、IME 才能弹出
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = 0
            y = 0
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

    private fun persistPosition() {
        val (screenW, screenH) = screenSize()
        if (screenW <= 0 || screenH <= 0) return
        cxFrac = centerX.toFloat() / screenW
        cyFrac = centerY.toFloat() / screenH
        prefs.edit()
            .putFloat(KEY_CX_FRAC, cxFrac)
            .putFloat(KEY_CY_FRAC, cyFrac)
            .putBoolean(KEY_COLLAPSED, collapsed)
            .apply()
    }

    private fun screenSize(): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = context.resources.displayMetrics
            dm.widthPixels to dm.heightPixels
        }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "FloatingBallWindow"
        private const val KEY_CX_FRAC = "cx_frac"
        private const val KEY_CY_FRAC = "cy_frac"
        private const val KEY_COLLAPSED = "collapsed"
    }
}
