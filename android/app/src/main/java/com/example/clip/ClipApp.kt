package com.example.clip

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.clip.data.api.ApiClient
import com.example.clip.data.api.ApiService
import com.example.clip.data.catalog.CatalogStore
import com.example.clip.data.db.AppDatabase
import com.example.clip.data.repository.RecordRepositoryImpl
import com.example.clip.data.token.TokenStore
import com.example.clip.domain.repository.RecordRepository
import com.example.clip.service.Recovery
import com.example.clip.service.UploadQueue
import com.example.clip.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ClipApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: AppDatabase by lazy { AppDatabase.get(this) }

    val repository: RecordRepository by lazy {
        RecordRepositoryImpl(database.recordDao(), database.blobDao(), database.queueDao())
    }

    val api: ApiService by lazy { ApiClient.api }

    private var lastScanAt = 0L

    override fun onCreate() {
        super.onCreate()
        FileLogger.init(this)
        // 全局未捕获异常 → 写 crash.log（抓崩溃真凶），再转交默认处理（保持弹框/退出行为）
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            FileLogger.e("Uncaught", "进程未捕获异常 thread=${thread.name}", throwable)
            previous?.uncaughtException(thread, throwable)
        }
        // 恢复 token 到内存（拦截器用），不阻塞启动
        appScope.launch { TokenStore.load(this@ClipApp) }
        // 载入标签/平台候选列表到内存缓存 + 自检 textTags/videoTags 重叠
        appScope.launch { CatalogStore.load(this@ClipApp) }
        // 上传队列调度 + 网络恢复监听
        UploadQueue.start(this)
        // 启动自愈
        appScope.launch { Recovery.scan(this@ClipApp) }
        // 回前台补查
        ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                scanWithDebounce()
            }
        })
    }

    private fun scanWithDebounce() {
        val now = System.currentTimeMillis()
        if (now - lastScanAt < 5_000) return  // 5 秒内不重复扫
        lastScanAt = now
        appScope.launch { Recovery.scan(this@ClipApp) }
    }
}
