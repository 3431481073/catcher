package com.example.clip

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.example.clip.asr.SherpaAsr
import com.example.clip.collector.UsageCollector
import com.example.clip.data.api.ApiClient
import com.example.clip.data.api.ApiService
import com.example.clip.data.catalog.CatalogStore
import com.example.clip.data.db.AppDatabase
import com.example.clip.data.repository.RecordRepositoryImpl
import com.example.clip.data.token.TokenStore
import com.example.clip.domain.repository.RecordRepository
import com.example.clip.service.Recovery
import com.example.clip.service.UsageCollectWorker
import com.example.clip.ui.remote.RemoteSounds
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
        // 远程页的两个提示音（SoundPool 加载是异步的，早点起，别等第一声才加载）
        RemoteSounds.init(this)
        // 本机 ASR 引擎预热：把 24MB 模型的加载藏在用户"看到球、点球"这几秒里。
        // 放在后台线程 —— 不加载完也不挡启动；万一用户手快，LocalAsrRecorder 里
        // 的 @Synchronized ensureLoaded 会等它，不会加载两份。
        appScope.launch { SherpaAsr.warmUp(assets) }
        // 启动自愈
        appScope.launch { Recovery.scan(this@ClipApp) }
        // App 使用时长：周期采集 + 首屏/回前台的排期。
        // 🔴 **三个都只是 enqueue，不 await** —— onCreate 里一行阻塞都不能有，
        //    主界面必须立刻出来（见 scheduleUsageCollect 的注释）。
        UsageCollectWorker.schedulePeriodic(this)
        scheduleUsageCollect()
        // 回前台补查
        ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                scanWithDebounce()
                scheduleUsageCollect()
            }
        })
    }

    private fun scanWithDebounce() {
        val now = System.currentTimeMillis()
        if (now - lastScanAt < 5_000) return  // 5 秒内不重复扫
        lastScanAt = now
        appScope.launch { Recovery.scan(this@ClipApp) }
    }

    /**
     * 排一发采集（增量 + 回溯各一发），**启动时和每次回前台各一次**。
     *
     * 🔴 **2026-10-11 从"进程内协程"改成 WorkManager**，两个原因：
     *   1. **以前是 `appScope.launch { backfillIfNeeded() }`** —— 它随进程一起死。
     *      App 一进后台被回收，回溯就断了，**下次开 App 又从头接着搬**。实测出过一次
     *      "整个 7 天被重传第二遍"（94% 的行被重写），就是这么来的。
     *   2. WorkManager 能被系统**重新调度**，进程死了也认账。
     *
     * 🔴 **这里只 enqueue，不 await、不 suspend** —— `onCreate` 里不许有任何阻塞，
     *    采集快慢完全不影响主界面。
     *
     * ⚠️ **代价（明确写下来）**：走 WorkManager 就**不再"立即"** —— 系统可能过几秒到
     *    几十秒才调度。**但不丢数据**：增量窗口是「上次扫到 − 5 分钟 到 now」，
     *    晚跑只是多拉一段；回溯有游标，随时续。而且**周期任务（15 分钟）本来就一直在跑**，
     *    这两发只是"回来时顺手补一下"。
     *
     * 🔴 **两发都要排**，尤其 **[UsageCollectWorker.scheduleBackfill]**：
     *    `KEEP` 只挡"还在队列里"的，跑完的 work 不会自己重排 —— 只排一次的话，
     *    回溯被打断后就再也没人续。
     */
    private fun scheduleUsageCollect() {
        UsageCollectWorker.scheduleIncremental(this)
        UsageCollectWorker.scheduleBackfill(this)
    }
}
