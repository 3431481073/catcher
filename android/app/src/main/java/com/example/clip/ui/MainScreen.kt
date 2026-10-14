package com.example.clip.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.example.clip.collector.UsagePrefs
import com.example.clip.data.token.TokenGate
import com.example.clip.util.Permissions
import com.example.clip.ui.bottompanel.BottomPanel
import com.example.clip.ui.detail.DetailPanel
import com.example.clip.ui.loglist.LogListScreen
import com.example.clip.ui.panel.Panel
import com.example.clip.ui.remote.RemoteScreen

private const val TAB_RECORDS = 0
private const val TAB_REMOTE = 1

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val records by viewModel.records.collectAsStateWithLifecycle()
    val panelVisible by viewModel.panelVisible.collectAsStateWithLifecycle()
    val detailRecord by viewModel.detailRecord.collectAsStateWithLifecycle()
    val bottomPanelVisible by viewModel.bottomPanelVisible.collectAsStateWithLifecycle()
    val tokenPaused by TokenGate.isPaused.collectAsStateWithLifecycle()
    val onboardingDone by viewModel.onboardingDone.collectAsStateWithLifecycle()
    val usageGranted by Permissions.usage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // remember(flow) —— DataStore 的 .data 是个冷流，每次重组都新建一个没意义
    val hintDismissed by remember(context) { UsagePrefs.hintDismissed(context) }
        .collectAsStateWithLifecycle(initialValue = false)

    var tab by rememberSaveable { mutableIntStateOf(TAB_RECORDS) }

    Box(modifier = Modifier.fillMaxSize()) {
        // 顶部 chrome（标题 + Tab）归这里 —— 两个页面都不再自带 Scaffold
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                Column {
                    TopAppBar(title = { Text("剪藏") })
                    TabRow(selectedTabIndex = tab) {
                        Tab(
                            selected = tab == TAB_RECORDS,
                            onClick = { tab = TAB_RECORDS },
                            text = { Text("记录") }
                        )
                        Tab(
                            selected = tab == TAB_REMOTE,
                            onClick = { tab = TAB_REMOTE },
                            text = { Text("远程") }
                        )
                    }
                }
            }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                when (tab) {
                    TAB_REMOTE -> RemoteScreen()
                    else -> LogListScreen(
                        records = records,
                        onRecordClick = viewModel::onRecordClick,
                        // 「没开使用情况访问」的提示条。见 LogListScreen 里的说明。
                        // ⚠️ `onboardingDone` 在这里**恒为 true**（AppRoot 已经拿它当门槛了，
                        // 没走完引导根本进不来这一屏），所以这个条件实际等价于
                        // `!usageGranted && !hintDismissed`。保留它是因为意图是"老用户才提示"。
                        // 🔴 `hintDismissed` 是**永久**的：划掉之后再撤销授权也不会回来
                        //    （见 UsagePrefs.dismissHint 的说明）。
                        showUsageHint = onboardingDone && !usageGranted && !hintDismissed,
                        onOpenUsageSettings = { Permissions.openUsageSettings(context) },
                        onDismissUsageHint = {
                            scope.launch { UsagePrefs.dismissHint(context) }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        // 记录相关的叠层只在"记录"Tab 下渲染 —— 免得在远程页误弹文本面板
        if (tab == TAB_RECORDS) {
            if (panelVisible) {
                Panel(
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
        }

        // Token 失效与两个 Tab 都有关（远程页也要带 token），保持全局
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
