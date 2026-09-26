package com.example.clip.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.clip.ui.guide.PermissionGuideScreen
import com.example.clip.ui.setup.TokenSetupScreen
import com.example.clip.util.Permissions

/**
 * 三层门控：无 token → 设置页；未走引导/无悬浮窗权限 → 引导页；就绪 → 主界面 + 起球。
 */
@Composable
fun AppRoot(
    viewModel: MainViewModel,
    onRequestNotificationPermission: () -> Unit,
    onEnterMain: () -> Unit
) {
    val token by viewModel.token.collectAsStateWithLifecycle()
    val saving by viewModel.tokenSaving.collectAsStateWithLifecycle()
    val error by viewModel.tokenError.collectAsStateWithLifecycle()
    val onboardingDone by viewModel.onboardingDone.collectAsStateWithLifecycle()
    val overlay by Permissions.overlay.collectAsStateWithLifecycle()

    when {
        token == null -> TokenSetupScreen(saving = saving, error = error, onSubmit = viewModel::submitToken)
        !onboardingDone || !overlay -> PermissionGuideScreen(
            onEnter = viewModel::finishOnboarding,
            onRequestNotification = onRequestNotificationPermission
        )
        else -> {
            LaunchedEffect(Unit) { onEnterMain() }
            MainScreen(viewModel)
        }
    }
}
