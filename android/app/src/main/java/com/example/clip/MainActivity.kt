package com.example.clip

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.example.clip.service.FloatingBallService
import com.example.clip.service.OverlayActions
import com.example.clip.ui.AppRoot
import com.example.clip.ui.MainViewModel
import com.example.clip.ui.overlay.CardMode
import com.example.clip.ui.overlay.OverlayState
import com.example.clip.ui.overlay.PendingVideo
import com.example.clip.ui.theme.ClipTheme
import com.example.clip.util.ClipboardCache
import com.example.clip.util.ClipboardWriter
import com.example.clip.util.FileLogger
import com.example.clip.util.Permissions
import com.example.clip.util.Platform
import com.example.clip.util.ShareParser
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val app get() = application as ClipApp

    private val viewModel: MainViewModel by viewModels {
        MainViewModel.factory(application, app.repository)
    }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        Permissions.refresh(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Permissions.refresh(this)
        // 分享入口：解析 → 交给 overlay → 立即 finish，用户全程看不到主界面
        if (handleShareIntent(intent)) return
        handleClipboardIntent(intent)
        handlePanelIntent(intent)
        setContent {
            ClipTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot(
                        viewModel = viewModel,
                        onRequestNotificationPermission = {
                            if (Build.VERSION.SDK_INT >= 33) {
                                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                        onEnterMain = {
                            FileLogger.i(TAG, "进入主界面，启动悬浮球服务")
                            runCatching { FloatingBallService.start(this) }
                                .onFailure { FileLogger.e(TAG, "启动悬浮球服务失败", it) }
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 前台有焦点 → 读剪贴板存缓存（供 overlay 粘贴用，Android 10+ 限制）
        ClipboardCache.init(this)
        // 用户从设置页/引导页回来 → 重查权限，驱动引导页状态
        Permissions.refresh(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (handleShareIntent(intent)) return
        handleClipboardIntent(intent)
        handlePanelIntent(intent)
    }

    /** 后台通知点开：读 Room 内容写剪贴板（此时已在前台）。 */
    private fun handleClipboardIntent(intent: Intent?) {
        val localId = intent?.getStringExtra(EXTRA_CLIPBOARD_LOCAL_ID)
        if (localId != null) {
            intent.removeExtra(EXTRA_CLIPBOARD_LOCAL_ID)
            lifecycleScope.launch {
                val content = app.repository.get(localId)?.content
                if (!content.isNullOrBlank()) {
                    ClipboardWriter.write(applicationContext, content)
                }
            }
        }
    }

    /**
     * 接收其他 App 分享（ACTION_SEND）。返回 true = 已按 overlay 处理并 finish()，调用方应 return。
     * 有 URL → 视频补一句面板；无 URL → 自动存文本 + 闪青。全程不跳 App。
     * 无悬浮窗权限时回退 in-app 流程（返回 false，正常渲染主界面）。
     */
    private fun handleShareIntent(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_SEND) return false
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (text.isNullOrBlank()) return false
        if (!Settings.canDrawOverlays(this)) {
            viewModel.onVideoShare(text)
            return false
        }
        val parsed = ShareParser.parse(text)
        if (parsed.url != null) {
            OverlayState.pendingVideo.value = PendingVideo(parsed.title, parsed.url, Platform.infer(parsed.url))
            OverlayState.cardMode.value = CardMode.VIDEO_INPUT
        } else {
            OverlayActions.submitText(app, null, text)
            OverlayState.onSubmitStarted()
        }
        runCatching { FloatingBallService.start(this) }
        finish()
        return true
    }

    /** 悬浮球短按：open=拉起并展开面板（后台），toggle=切面板（前台）。 */
    private fun handlePanelIntent(intent: Intent?) {
        when (intent?.getStringExtra(EXTRA_PANEL_ACTION)) {
            PANEL_OPEN -> {
                intent.removeExtra(EXTRA_PANEL_ACTION)
                viewModel.openPanel()
            }
            PANEL_TOGGLE -> {
                intent.removeExtra(EXTRA_PANEL_ACTION)
                viewModel.togglePanel()
            }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        const val EXTRA_CLIPBOARD_LOCAL_ID = "extra_clipboard_local_id"
        const val EXTRA_PANEL_ACTION = "extra_panel_action"
        const val PANEL_OPEN = "open"
        const val PANEL_TOGGLE = "toggle"
    }
}
