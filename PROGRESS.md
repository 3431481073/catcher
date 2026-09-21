# 手机剪藏 · 开发过程记录

> 本文件记录项目的**开发过程本身**，不是需求，不是代码。
> 重点记录：关键决策、反复修改的地方、错误如何被处理、最后怎么解决、学到了什么。
> 需求/架构见 `CLAUDE.md` 和 `docs/architecture.md`，这里不重复，只引用。

---

## 记录约定（写给未来的自己 / Cloud Code）

- **每次只追加，不改历史。** 旧的决策记错了就新增一条"更正"，不覆盖。
- **一条决策 = 背景 → 选了哪个 → 为什么 → 代价/风险。**
- **反复修改的地方要特别标注 🔁**：这是最有价值的部分，写清楚"改了什么、为什么反复、最后怎么定下来的"。
- **每条错误 = 现象 → 根因 → 解法 → 防止再犯。**
- 格式统一用 `日期 + 里程碑 + 一句话标题`。

---

## 时间线

### 2026-09-19 · M0 启动前（第一轮只读对齐）

- **状态**：CLAUDE.md + docs/architecture.md 已就绪，尚未写任何代码。
- **决策**：建立本文件（PROGRESS.md）作为开发过程记录，与 CLAUDE.md（施工手册）、architecture.md（宪法）分开。
- **约定**：第一轮只读，输出"模块边界/不变量、歧义点、建议目录结构"，用户确认后才开始 M0。

### 2026-09-19 · M0 开工

- 计划已确认（含 3 点拍板 + 3 处修正），开始写 Android UI 骨架。

### 2026-09-19 · 环境与依赖策略

- **环境**：JDK 17 ✓；Android SDK（platforms 33/34/36 + build-tools 34）✓；Gradle 未装、无 Android Studio。
- **决策（用户指示）**：所有依赖局限在项目文件夹内，不污染全局环境。
  - Gradle 发行版下载到 `.tools/`（项目内）。
  - 所有缓存/依赖/daemon 走 `GRADLE_USER_HOME`，即每次 gradle 命令加 `-g D:\MyProject\catcher\.gradle`（项目内）。
  - 不动全局 PATH / JAVA_HOME / 用户级 `~/.gradle`。
  - 注：Android SDK 本身在 `C:\Users\asus\AppData\Local\Android\Sdk`（既有，非本次引入）。

### 2026-09-19 · M0 构建通过

- `assembleDebug` 两种方式均 BUILD SUCCESSFUL：本地 gradle（.tools/） + `gradlew.bat`（wrapper）。
- APK 产物：`android/app/build/outputs/apk/debug/app-debug.apk`（21.81 MB）。
- 三张表（records/blobs/queue）+ domain/repository 分层 + Compose UI + 7 条假数据全部就位。
- **待用户真机验收**：安装看日志列表、悬浮球手势、详情/底部面板（代码层完成，未上真机）。

### 2026-09-19 · M0 真机验收通过 → M1 计划

- M0 真机验收通过（安装看 UI、悬浮球手势、详情/底部面板均正常）。
- 进入 M1：后端可独立验证（FastAPI + SQLite WAL + FFmpeg + faster-whisper + worker）。

### 2026-09-19 · M1 开工

- **4 决策确认**：`GET /records` 分页用 limit/offset（默认 100/0）；whisper 默认 small（env `CLIP_WHISPER_MODEL` 可改）；队列上限 5 只算位次不拒绝超限；pytest 只测 DB + API（转写 mock）。
- **3 补充**：完成后告知 Token 值 + 存放文件（M2 手机端用）；queue_position>5 的 UI 显示 M1 不处理，M2 再看；small 模型首次下载慢是正常。

### 2026-09-19 · M1 代码完成 + pytest 通过

- 后端全量代码写完，pytest **12 通过**（DB 层 + API 层，worker 转写 mock）。
- 环境：Python 3.13（架构写 3.12，用现成的 3.13 + 项目内 venv，不装全局）；FFmpeg 静态版下到 `backend/tools/`；依赖装进 `backend/.venv`。
- 待 curl 验收（uvicorn + /transcribe + /tasks 轮询到 done）。

### 2026-09-19 · M1 curl 验收通过

- 完整管道跑通：health（免 token）→ transcribe（立刻返回 RecordDTO、幂等）→ worker（FFmpeg + whisper）→ tasks 轮询到 done（content 有文本）。
- GET /records 列表 ✓；无 token → 401 ✓；校验错误 → 400 ✓。
- **Token**：`dev-token-0123456789-abcdefghijklmnopqrstuvwx`，存 `backend/app/config.py` 的 `TOKEN`（env `CLIP_TOKEN` 可覆盖）。**M2 手机端填这个值。**
- whisper small 模型已下到项目内 `backend/.cache/huggingface`（走 hf-mirror 镜像）。

### 2026-09-19 · M2 代码完成 + assembleDebug 通过

- Android 语音端到端代码全部落地：Token 设置页 / Retrofit 网络层 / 录音(Opus 16k mono) / RecordingService(microphone) / SyncService(dataSync 单任务) / 剪贴板(前台直接写，后台通知点开写) / Repository 换后端写路径。
- `assembleDebug` BUILD SUCCESSFUL（21s）。APK `android/app/build/outputs/apk/debug/app-debug.apk`（30.4MB，较 M0 新增 Retrofit/OkHttp/DataStore/lifecycle-process +8.6MB）。
- 删除 M0 的 7 条假数据（SeedData.kt），列表从空开始，空态文案"还没有记录，长按悬浮球说话。"。
- 待真机验收：填 token→进主界面 → 长按录音→结束 → 日志绿 → 粘贴到微信是原话。

### 2026-09-19 · M3 代码完成 + 后端 22 测试通过 + assembleDebug 通过

- 文本提交（POST /records type=text）+ 视频分享接收（ACTION_SEND → POST /records type=video）+ 补一句（PATCH note）+ 详情面板拉取刷新（GET /records/{id}）全部落地。
- 后端补 3 接口（POST /records、GET /records/{id}、PATCH /records/{id}）+ 10 条新测试，pytest **22 passed**（12 旧 + 10 新）。
- curl 验收通过：POST text（done 无 task）/ POST video（done + task_status=skipped）/ GET by id / PATCH note / 幂等 / GET 404。
- Android `assembleDebug` BUILD SUCCESSFUL（8s），仅 1 条既有的 stopForeground 弃用警告。
- 待真机验收：① 提交文本 → 日志绿；② 从别的 App 分享链接 → 日志紫 skipped，可补备注；③ 备注保存后重开仍在。

### 2026-09-19 · M4 代码完成 + clean 构建通过（versionCode=3）

- 离线队列（queue 表 + WorkManager UploadWorker + UploadQueue 调度器）、启动自愈、回前台补查、401 暂停（TokenGate）、后台通知 IMPORTANCE_LOW、权限精细引导全部落地。
- 上传从 SyncService 迁移到 UploadWorker，SyncService 退化为纯转写轮询。
- `clean + assembleDebug` BUILD SUCCESSFUL（55s 全量）。APK 25.08MB，versionCode=3 / versionName=0.3.0。
- 待真机验收：① 断网录音 → 重连自动补（无感）；② 401 暂停/恢复；③ 权限拒绝引导。

### 2026-09-19 · M6.5 真悬浮窗 + 保活 代码完成 + 构建通过（versionCode=5）

- 新增常驻前台服务 `FloatingBallService`（specialUse + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`）承载全局悬浮球窗口（WindowManager + `TYPE_APPLICATION_OVERLAY`，ComposeView 复用 App 内 `FloatingBall`）。
- 悬浮球：短按（前台=切面板 / 后台=拉起+展开面板 `open_panel`）、长按录音、录音中点击结束；位置持久化 SharedPreferences（x/y，默认右侧中间，留 `savePosition` 为拖动预留）。
- 录音状态同源：`RecordingController` 改单例，`isRecording` 为进程内共享 StateFlow，球与面板一致。
- 保活：`BootReceiver`（BOOT_COMPLETED，有 overlay 权限才起）、`onTaskRemoved` 自愈（best-effort）、电池优化白名单 + 国产 ROM 自启动引导（尽力）。
- 首次启动引导页 `PermissionGuideScreen`：悬浮窗（必过）/ 通知（推荐）/ 电池（推荐）/ 自启动（看机型），各自"去设置"；悬浮窗未授权时"进入剪藏"禁用。
- App 内原悬浮球删除；MainActivity 拆麦克风/通知两个运行时 launcher；版本号 4→5（0.5.0）。
- assembleDebug BUILD SUCCESSFUL（13s 增量）。APK `android/app/build/outputs/apk/debug/app-debug.apk`（25.14MB）。
- **保活承诺边界（README 级说明）**：划掉后悬浮球可能短暂消失，系统允许时自动恢复；国产 ROM 可能永久杀掉。前台服务通知是 Android 硬性要求（IMPORTANCE_LOW、无声音无震动、内容最简），不可去掉。
- 待真机验收：引导页授权 → 悬浮球全局可见 → 退后台球仍在 → 长按录音/短按切面板/录音中点击结束 → 划掉重进球恢复 → 重启自启。

### 2026-09-19 · M6.5 修复轮：specialUse → dataSync + 文件日志（versionCode=6）

- **真机现象**：走完引导页授权，点"进入剪藏"闪退回桌面，无崩溃框、无悬浮球。
- **根因判断**：录音 FGS（microphone）此前真机跑通过，说明 `startForeground`+通知本身没问题；新变量是 `specialUse` 类型——**一加/ColorOS 对 API34 的 specialUse 支持不全**。
- **降级**：FloatingBallService 的 `foregroundServiceType` 从 `specialUse` 改 `dataSync`（与 SyncService 一致，该类型真机已验证可用）；删 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` 声明 + 无用的 `FOREGROUND_SERVICE_SPECIAL_USE` 权限。
- **文件日志**：新增 `util/FileLogger`，关键路径同时打 logcat + 落盘 `getExternalFilesDir(null)/crash.log`（时间戳+级别+TAG+堆栈），ClipApp.onCreate init，用户连不上 adb 也能把崩溃点发回。
- **4 文件加日志 + 优雅降级**：MainActivity.onEnterMain（runCatching 包住，失败仍进主界面）/ FloatingBallService（startForeground 失败→stopSelf 不崩；窗口挂载分段 try-catch）/ FloatingBallWindow（setContent、addView 各 try-catch）/ MainViewModel.finishOnboarding。
- versionCode 5→6（0.5.1）。APK 25.14MB。待真机复测：若仍崩，取 `crash.log` 定位。

### 2026-09-19 · M6.5 简化轮：破环 + 全局崩溃捕获（versionCode=7）

- **真机**：dataSync 起得来（`startForeground ok` + `addView 成功`），但服务 ~1.3s 被硬杀（无 onDestroy 日志）+ START_STICKY 反复拉起 → 崩溃环拖死 App。
- **简化（用户拍板，不做极致保活）**：`START_NOT_STICKY`（被杀不重启）、删 `onTaskRemoved` 自愈（只留 log）、`isRunning` flag 防重复创建。
- **全局未捕获异常处理器**（ClipApp.onCreate）：任何崩溃写完整堆栈 + cause 链到 crash.log，再转默认处理。这是抓"1.3s 真凶"的关键。
- **引导页 4 卡 → 2 卡**（悬浮窗必过 + 通知推荐），删电池/自启动卡 + Permissions 里对应代码 + `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 权限；保留两句"白名单/锁后台"提示。BootReceiver 保留。
- FileLogger 改完整堆栈（不截断）+ cause 链。
- versionCode 6→7（0.5.2）。APK 25.14MB。待真机复测：若球仍 1.3s 被杀，看 crash.log 真凶；若 ComposeView 挂 WindowManager 崩 → 下轮换普通 View。

### 2026-09-19 · M6.5 overlay 生命周期修复（versionCode=8）

- 真凶（全局未捕获处理器抓到）：`ViewTreeLifecycleOwner not found from ComposeView` —— ComposeView 挂 WindowManager 不在 Activity view tree 里，`setContent` 找不到生命周期宿主。
- 修法（官方）：新建 `OverlayLifecycleOwner`（实现 SavedStateRegistryOwner + ViewModelStoreOwner，LifecycleRegistry 推 RESUMED），挂载前把三个 ViewTree owner 塞进 ComposeView，remove 时推 DESTROYED + 清 ViewModelStore。
- **坑**：三个 ViewTree 类（ViewTreeLifecycleOwner / ViewTreeViewModelStoreOwner / ViewTreeSavedStateRegistryOwner）在 `-android` KMP 变体里，字节码是 public static（javap 确认，metadata mv=1.8.0 可读），但 Kotlin 侧 import 报 Unresolved —— KMP -android 变体元数据可见性问题。**解法：用 Java helper（OverlayViewTreeHelper.java）调用，javac 只看字节码不读 Kotlin 元数据**。
- 依赖：显式声明 lifecycle-runtime-android / lifecycle-viewmodel-android / savedstate:1.2.1。
- versionCode 7→8（0.5.3）。APK 25.14MB。

### 2026-09-19 · M7 悬浮窗交互对齐 v12（versionCode=11）

- **overlay 全面化**：点球不再拉起 MainActivity，面板/文本输入/视频补一句全在 overlay 窗口内完成。
- **单窗口**承载「球 + 面板」一棵 Compose 树（`OverlayRoot`）；球 52px 玻璃 + 6 状态色 + 录音波形；手势 tap/long-press/drag 自实现（`withTimeoutOrNull` 区分）。
- 球可**拖动 + 贴边吸附半圆 + 点半圆展开 + 位置持久化** SharedPreferences（存视觉球中心 cx/cy + collapsed）。
- **录音波形**：RecordingService 每 100ms 采 `maxAmplitude` → `RecordingController.amplitude` → 球周围柱状波形。
- **语音填正文/备注**：`VoiceDestination`（CLIPBOARD/BODY/NOTE）；球长按=剪贴板，面板语音按钮=填输入框；`SyncService.onDone` 按 destination 路由（非剪贴板则不写剪贴板/通知）。
- 提交文本/视频抽到 `OverlayActions`（复用 UploadWorker 既有「note→PATCH」）。
- ACTION_SEND 带 URL → overlay 视频补一句面板；纯文本 → in-app 流程。
- **键盘上移**：`WindowInsets.ime` → 窗口 y 上移；文本输入聚焦时临时去 `FLAG_NOT_FOCUSABLE`（否则键盘弹不出）。
- 依赖：复用 M6.5 的 OverlayLifecycleOwner + OverlayViewTreeHelper。
- versionCode 10→11（0.6.0）。APK 25.28MB。待真机验收。

### 2026-09-19 · M7.1 修功能性 bug（versionCode=12）

- **拆双窗口**：球/面板从单棵 Compose 树拆成两个独立 WindowManager 窗口（各自 OverlayLifecycleOwner）。球窗恒定位置（面板开关不再挪球），面板窗常驻挂载 + AnimatedVisibility 显隐，位于球左侧、垂直居中；IME 只顶起面板窗。**根除 M7 单窗口「面板滑出把球往左挤」的抖动**。
- **拖动轻量化**：只 updateViewLayout（球窗），面板开着才跟随；拖动开始即收起面板，不重建窗口。
- **补 busy 状态机**：`OverlayState` 收口为单一状态源 `ballState` + `isBusy`。语音走 IDLE→REC→BUSY→DONE_VOICE→(2s)→IDLE，文本/视频直达 DONE_*。busy 期间点球/长按拦截。终态兜底：SyncService DONE/FAILED/SKIPPED + UploadWorker 上传 20 次放弃，都回状态机（防 BUSY 挂死）。
- **ACTION_SEND 不跳 App**：MainActivity 收分享→解析→有 URL 设 pendingVideo+VIDEO_NOTE / 无 URL 自动存文本+闪青→启动悬浮球服务→立即 finish，全程不见主界面；无悬浮窗权限则回退 in-app。
- 删除 `OverlayRoot`，拆成 `OverlayPanelHost`（承载面板 + queue/在线/IME/焦点 effect）。
- versionCode 11→12（0.6.1）。APK 25.28MB。待真机验收。

### 2026-09-19 · M7.2 交互模型重构（versionCode=13）

- **删长按录音**：录音唯一入口变主面板「语音」按钮；`FloatingBall` 的 tap/long-press/drag 手势整个删掉，球变纯视觉。
- **主面板单模式化**：删 `PanelMode.TEXT_INPUT/VIDEO_NOTE`，面板永远只显示固定三块（绿点+剪藏+在线·队列N / 语音|提交文本 / 记录列表可滚）。
- **新增第三窗口「卡片窗」**：底部全宽 sheet（屏宽-32dp，顶部 24dp 圆角），从下升起；文本提交 + 视频补一句都走它。面板窗与卡片窗互斥。
- **拖动跟手修复**：手势从 Compose `pointerInput`（局部坐标 `positionChange`，被窗口移动污染）改成 View 级 `OnTouchListener` + `rawX/rawY` 绝对坐标，`downCenter + (raw - downRaw)` 1:1 跟手。
- **位置动态计算**：面板在球左半屏→向右展开、右半屏→向左展开，夹屏内不越界；卡片底部居中、IME 顶起。
- **状态机**：REC 点球=结束；IDLE 点球=切面板（卡片模态时无效）；BUSY/DONE 点球无效、可拖动。done 2s 回 idle。
- versionCode 12→13（0.6.2）。APK 25.28MB。待真机验收。

### 2026-09-19 · M7.2 真机修复轮（versionCode=14）

- 真机 5 条，修 3 条（busy 转圈 / 卡片抖动 / 语音填框格式）+ 视频卡片同修 + 详情页预留。
- **busy 转圈**：给 BUSY 加旋转弧动画（Canvas + rememberInfiniteTransition，90° 白弧匀速转，对齐 v12 `.state-busy::before` 的 spin）。状态机本身没错（stop→BUSY 有触发），是转圈动画从未实现，蓝球+静态白点不显眼。
- **卡片抖动根因修复**：卡片窗改 `gravity = BOTTOM|CENTER_HORIZONTAL`（底边钉死，高度变化只向上长），删 `cardHeight` 回调 + `SOFT_INPUT_ADJUST_RESIZE`，断掉「高度→y→再测量→高度」反馈环；IME 用 `imeAnimationTarget`（最终值，一步到位，不逐帧）。
- **语音填输入框**：正文/note 从 `String` 改 `TextFieldValue`，语音结果追加「空格+转写」不换行、`selection` 移到末尾；加 `maxLines` 封顶。
- **详情页预留**：`CardMode` 加 `DETAIL`（枚举 + 空分支）；入口（点记录→面板收起→DETAIL 卡片升起）+ `SelectionContainer` 内容放 M7.3。
- versionCode 13→14（0.6.3）。APK 25.28MB。待真机复测。

### 2026-09-19 · M7.2 追加轮（versionCode=15）

- **done 可点 + 1.5s**：`flash` 默认 2000→1500ms；点球只有 BUSY 忽略，IDLE/DONE 都可开面板。
- **卡片改浮动卡片**：全宽底部 sheet 改固定位置浮动卡片（屏宽-32dp × 400dp，`TOP|LEFT`，底边离屏底 80dp）；删 `imeAnimationTarget` + 整个 IME 重定位链路（抖动根因）；`scope` 观察 `cardMode` 切卡片窗高度 0↔400dp。
- **卡片点别处收起**：卡片窗 + `FLAG_WATCH_OUTSIDE_TOUCH`，OnTouchListener 收 `ACTION_OUTSIDE`→`cardMode=null`；点球（IDLE）时 `downHadCard` 标记关闭卡片、不弹面板（避免 ACTION_OUTSIDE 先关卡导致球 tap 误开面板的竞态）。
- **球贴边 1s 自动缩半圆**：`scheduleCollapse()` 协程 delay(1000)→`collapseToHalf()`（面板/卡片开着时不缩）；球窗也加 `FLAG_WATCH_OUTSIDE_TOUCH`，点别处→`ACTION_OUTSIDE`→立刻缩半圆；拖拽开始取消计时、拖完重新计时。
- **详情页**：`CardMode.DETAIL` + `detailLocalId`；面板记录可点→面板收起→DETAIL 卡片升起；内容 `SelectionContainer` 可选中复制 + 类型标签按类型配色 + 时间 + note + 关闭。
- versionCode 14→15（0.6.4）。APK 25.30MB。待真机复测。

### 2026-09-19 · M7.2 卡片形态重做（versionCode=16）

- 真机穿透 bug + 卡片形态重做 + 3 个逻辑 bug。
- **穿透 bug 根因**：卡片窗「独立 WindowManager + updateViewLayout 切高度(0↔400dp)」不可靠——高度切换不触发 Compose 稳定重测、`FLAG_WATCH_OUTSIDE_TOUCH` 把自身触摸误判成 outside → 卡片自关、残留透明窗口穿透到桌面。
- **卡片改全屏**：`MATCH_PARENT` + Compose 布局（透明 scrim + 底部卡片 + `imePadding`），开→addView、关→removeView，窗口永不 updateViewLayout → 根除位置↔尺寸反馈环 + 残留。
- **深色毛玻璃底色**：`0xC712141C`（v12 rgba(18,20,28,.78)），输入框白字。**坑**：`OutlinedTextFieldDefaults.colors()` 在 Material3 1.3 是 `@Composable`，不能赋给顶层 val，得包成 `@Composable fun`。
- **键盘联动**：`imePadding()` 官方机制；卡片窗改**可聚焦**（去 FLAG_NOT_FOCUSABLE），输入框才能拿到 IME。
- **wrap_content**：卡片高度自适应内容，详情内容区 `heightIn(max=360dp)+scroll` 兜底。
- **逻辑 bug**：`collapseToHalf` 门改 `ballState != IDLE || cardMode != null`；语音填框跟随光标（title/body 都 TextFieldValue + focusedOnTitle）；详情卡片自适应。
- versionCode 15→16（0.6.5）。APK 25.30MB。待真机复测。

### 2026-09-19 · M7.2 球消失修复：坐标比例存储（versionCode=17）

- **真机现象**：打开王者荣耀（横屏）后球消失，重开也不出现。日志 `centerX=2772 collapsed=true`（横屏宽 2772）。
- **根因**：球坐标存的是**绝对 px**。横屏时 `screenSize()` 返回宽 2772，`collapseToHalf`/`onDragEnd` 写 `centerX=2772` 落盘；退回竖屏（宽 1240）后 `2772>1240` → 球整个出屏。`centerY` 同理是横屏中线。
- **修**：坐标改**屏幕比例**（`cx_frac`/`cy_frac` 0..1）持久化，加载 `centerX=(frac*屏幕宽).coerceIn(0,宽)`；所有落盘/吸附/收缩加 `clampToScreen()` 硬 clamp；注册 `DisplayManager.DisplayListener`，`onDisplayChanged` 用比例重推坐标（运行时横竖屏切换也回正确位置）。换 key，旧坏值自然被忽略。
- versionCode 16→17（0.6.6）。APK 25.30MB。待真机复测。

### 2026-09-19 · M7.3 交互体系定稿（versionCode=19）

- **球图标重做**：idle 圆点 / rec 麦克风 / busy 转圈 / done 对勾 / failed 叉（Canvas 画，删白点方块）；`BallVisualState` 加 `FAILED`（色 `0xFFE53935`）。
- **状态机路由**：`onVoiceDone/Failed` 按 `recordingDestination` 路由——CLIPBOARD 闪 DONE_VOICE/FAILED，BODY/NOTE 回 idle（填框/卡片提示失败）。
- **统一规则集中一处**：`canCollapse() = !panelOpen && cardMode==null && ballState==IDLE && !isDragging`；`canDrag() = ballState!=REC && cardMode==null`。
- **onDragEnd 松手永远全圆**（删"拖到边缘吸半圆"），缩半圆只由 canCollapse 的 1s 定时 / 点别处触发。
- **点球收敛**：仅 BUSY 无效；REC=stop；IDLE/DONE/FAILED=expand 或 toggle panel。拖拽尝试加 `movedBeyondSlop` 区分（rec/卡片开的拖拽尝试=无效，不误触发 stop）。
- **面板** window 加 `FLAG_WATCH_OUTSIDE_TOUCH` → 点别处关面板。
- **卡片 scrim** 录音/转写期不关；卡片语音失败显示"转写失败"红字。
- 删所有"球长按"引用（3 处文案/注释）。
- versionCode 18→19（0.6.8）。APK 25.31MB。待真机验收。

### 2026-09-19 · M7.3 真机修复 + 繁体简体（versionCode=20）

- **拖拽后不自动缩**：`dragging` 在 onDragEnd 里没复位 false，`canCollapse()` 的 `!dragging` 恒为 false → 1s 定时 `collapseToHalf` 直接 return。修：`onDragEnd` 开头 `dragging=false`（左右不对称/时灵时不灵都是这个脏标志的表现）。
- **动画全部收进球内**：删外部 `Waveform`（柱子）+ `BusyRing`（环）；rec=球内 `InnerRipple`（中心向外波纹环，半径/透明度随 amplitude 联动，没声看不见）；busy=球内 `InnerBusyPulse`（呼吸脉冲）+ 中心 `SpinnerIcon`（270° 弧转圈）；图标 mic/点/勾/叉 居中。删 `Waveform.kt`。
- **繁体→简体（后端双保险）**：`transcribe.py` 加 whisper `initial_prompt` + `zhconv.convert(text,"zh-cn")`；`requirements.txt` 加 `zhconv`；`build.spec` 的 `collect_all` 加 `zhconv`（含 544KB `zhcdict.json`）。
- 重打包 `backend.exe`。APK versionCode 19→20（0.6.9）。
- **踩坑**：build.bat 在 PowerShell 里被误解析（`rm`/`exist` 报错），改直接跑 `python -m PyInstaller build.spec`；旧 `backend.exe` 占用导致 `PermissionError`，先 `Stop-Process` 再打包。

### 2026-09-20 · M7.3 修复轮（versionCode=21）

- **音浪改横波形**：删 `InnerRipple`（圈圈），改 `InnerWaveform`（球内中间横着竖条波形，高度随 amplitude，没声音塌成平线）。
- **去掉 initial_prompt**：只留 `zhconv` 后处理（不误伤英文/其他语言）。
- **历史记录 note 优先**：`LogItem` 首行 `note ?: title ?: content ?: type`。
- **删面板顶部栏**：绿点+剪藏+在线·队列N 删掉，面板只剩两按钮+列表；连带删 `OverlayState.online/queueCount` + `OverlayPanelHost` 的 queue/在线 effect（不再每 15s ping）。
- **详情卡片底部空隙**：卡片容器 `padding(bottom=80dp)`；详情内容区底部再补 4dp（共 24dp）。
- **点球 toggle（六）**：删面板 `FLAG_WATCH_OUTSIDE_TOUCH`（消除 ACTION_OUTSIDE 与球 toggle 的竞态），纯 toggle。
- **点别处分层退出（七）**：球 `ACTION_OUTSIDE` 里 `if panelOpen 关面板 else collapseToHalf`（每次点别处只退一层）。
- 重打包 backend.exe。APK versionCode 20→21（0.7.0）。

### 2026-09-20 · M7.4 修复轮（versionCode=22）

- **note 覆盖 bug（根因）**：`DtoMapper.toRecord` 用 `note = note`（DTO 值）。视频的 POST /records 不带 note（note 上传后 PATCH 补），POST 响应 `DTO.note=null` → `repo.update` 把本地 note 抹掉 → 详情不显示、后续 PATCH 也读到 null。修：统一 merge 规则「DTO 字段为 null 时保留本地值」（`title/content/note/url/platform` 都 `?: previous.x`）。
- **详情复制**：DetailCard 标题+正文+URL+note 共用一个 `SelectionContainer`（套在 verticalScroll 内），跨行选中 + 视频详情三样内容可复制。
- **显示兜底**：`LogItem` 首行 `note ?: title ?: content ?: (视频→"分享一个作品给你" else 类型名)`。
- versionCode 21→22（0.7.1）。

### 2026-09-20 · M7.5 交互修复（versionCode=24）

- **卡片不点外部关闭**：删卡片 scrim 的 click-to-close。所有卡片（文本/视频/详情）有显式关闭按钮，只由按钮关，点外部不关（写入一半不丢内容）。
- **自绘文本菜单**：overlay 里系统 ActionMode（浮动工具栏）不显示，新增 `OverlayTextToolbar` 自定义 `TextToolbar` + Compose `Popup` 菜单（剪切/复制/粘贴/全选），通过 `CompositionLocalProvider(LocalTextToolbar provides ...)` 注入卡片内容，详情选中 + 输入框长按都能弹自绘菜单。
- **踩坑**：`TextToolbarStatus` 在 Compose 1.7 是**枚举**（`Shown`/`Hidden` 是枚举常量，不带 rect 参数），不是 sealed class；`TextToolbarStatus.Shown(rect)` 编译报 Unresolved，应写 `TextToolbarStatus.Shown`。
- versionCode 22→24（0.7.3）。

### 2026-09-21 · 运维 接入本地服务管理器 + launcher 加 CLIP_NO_AUTOSTART 开关

- **背景**：把 catcher 注册进本地服务管理器（`D:\MyProject\service-manager`，svcmgr 0.1.0）。配置 `config/projects/catcher.yaml`：项目 `catcher` 只含 **1 个**服务 `backend` = `dist\backend.exe` —— SQLite 是进程内嵌的、FFmpeg 是后端自己调用的工具，都不是服务；Android 端不在 PC 上。故无 `dependsOn`。
- **关键参数**：`args: ["--autostart"]`（静默态，端口被占时直接退出，不弹模态框——管理器无人值守拉起必须用）；不写 `readiness`（全项目只有 `/api/v1/health` 一个探针端点，写 readiness 只是和 liveness 打同一 URL）；不写 `stop`（无 HTTP shutdown 端点，走默认 taskkill → 强杀进程树；SQLite 是 WAL，强杀不坏库）；不写 `env`（`CLIP_TOKEN` 必须保持默认否则手机端 token 失效；`PORT` 在 launcher.py 是硬编码常量，写进 env 不生效）。
- **冲突与决策**：launcher.py 每次正常启动都幂等写 `HKCU\...\Run\ClipBackend`，登录时会**绕开管理器自己抢 8000**（2026-09-21 16:39 实测发生过一次：explorer 重启触发自启项拉起了非托管实例）。决策：给 launcher 加 **`CLIP_NO_AUTOSTART=1` 开关**，命中则跳过写自启；不设该变量（手动双击）行为完全不变。选 env 而非命令行 flag，因为管理器 YAML 里配 `env` 就能生效，不必改 `args`。
- **配套**：服务管理器 YAML 改 `autoStart: true` + `env: CLIP_NO_AUTOSTART: "1"`，开机自启统一由管理器负责。
- **顺带修 `--uninstall-autostart`**：去掉 3 处 `_msgbox`，改为 stdout 一行结果 + 退出码（0=成功或本就不存在，1=失败）。理由是模态框会阻塞调用方（脚本/管理器）。
- **产物**：重打包 `dist\backend.exe`（653,324,409 字节 / 16:59:56）。**只跑 PyInstaller，跳过 `build.bat` 第 4 步**——它会 `rmtree` 掉 `dist/.cache` 再复制 1.5GB medium 模型，与本次改动无关；已验模型加载正常（14.2s），无副作用。
- **git**：仓库此前不存在（catcher/backend/android 三处都无 `.git`），建基线 `9adfdda`（含 `.gitignore`，排除 .venv/dist/build/tools/data/.gradle；108 文件 / 0.41MB），改动 `1fe7378`。
- **验证**：三条在打包后的 exe 上通过 —— ①不设变量→键出现（日志 `已写自启项 ClipBackend -> ...`）；②设 `=1`→键不出现（日志 `CLIP_NO_AUTOSTART=1，跳过写自启项`）；③`--uninstall-autostart` 自行退出 exit=0、键删除、无残留进程。**端到端**：管理器带 YAML 的 env 拉起，注册表保持干净。

---

## 决策日志

### 2026-09-19 · 第一轮对齐拍板（M0 前）

| 问题 | 拍板结论 |
|---|---|
| Room 三张表 schema | 由 Cloud Code 反推设计，先出设计稿（见 M0 计划）再写代码 |
| 悬浮球权限 | M0 用方案 A：Activity 内悬浮球，**不申请 overlay 权限**；M4 再升级真悬浮窗 |
| 状态颜色映射 | 灰=pending / 蓝旋=排队中 / 蓝脉=处理中 / 绿=done / 红=failed / 紫=skipped；**加数字角标区分排队位次**（角标放蓝旋还是蓝脉，见计划里的待确认点） |
| ID 方案 | `record_id`=后端 `serverId`；前端用 `localId`（本地主键）+ `serverId`（后端 id，可空） |
| tasks 表 | **不建**，`task_id`/`task_status` 冗余进 records |
| worker | 进程内 asyncio（FastAPI lifespan 启动） |
| `/transcribe` | 立刻返回**完整 RecordDTO** |
| `queue_position` | 非 voice 记录返回 0 |

**工作方式约定**：上下文占用超过 60% 时自行压缩，用户不再提醒。

### 2026-09-19 · M0 最终确认（开工前）

**三点拍板**：
1. 数字角标放**蓝旋**（排队中），蓝脉不带角标（此前"蓝脉加角标"是笔误）。
2. 假数据加到 **7 条**，覆盖全部 6 色 + 蓝旋角标变体（补全验收需要，不算偏离文档）。
3. 包名暂用 `com.example.clip`，项目名定了再改。

**三处修正（对设计稿）**：
- **A. records 表补 `sync_status` 字段**（`TEXT NOT NULL`，取值 PENDING/SYNCING/SYNCED/FAILED）。
  - 🔁 **关键教训**：前端状态有**两条正交轴**——①本地同步态（sync_status：有没有传到后端）②后端处理态（status/task_status：后端转写完没）。我初稿只建模了②，漏了①。根因：前端是"缓存+采集器"，本地同步是它的本分，不能只套后端 DTO。
  - 状态推导改两段式优先级：`syncStatus=PENDING→灰` → `syncStatus=SYNCING→蓝旋` → `taskStatus=SKIPPED→紫` → `status=FAILED→红` → `status=DONE→绿` → `queuePosition>0→蓝旋+数字` → `taskStatus=PROCESSING→蓝脉`。
- **B. blobs 表去掉 `data BLOB` 字段**，只留 `path`（文件统一落文件系统，表只存路径）。
- **C. M0 不建 Retrofit 接口签名**，M2 再建。

### 2026-09-19 · M2 确认（开工前）

**四点拍板（D1–D4）**：
1. **D1 删假数据**：删 M0 的 7 条 SeedData，列表从空开始；空态文案"还没有记录，长按悬浮球说话。"。
2. **D2 Token 验证改打 `/records`**（偏离架构字面）：架构 §五 写"验证 `/health`"，但 `/health` 实现是**免 token**的，拿任何 token 打都 200，验不出真假。改用带鉴权的 `GET /api/v1/records`：200（含空列表）→有效、401→"Token 无效"、网络错误→"连接失败，检查网络/后端"（第三种不提示 Token 无效）。
3. **D3 录音直接用 Opus/OGG**：minSdk 29 起 MediaRecorder 保证支持 Opus，"优先"在 29+ 恒成立，不写 AAC 回退分支。
4. **D4 后台通知点开用 Activity**（非 BroadcastReceiver）：Android 10+ 后台组件写剪贴板受限，通知 PendingIntent 拉 MainActivity（前台）读 Room 写剪贴板才稳。

**风险备注（用户要求记两笔）**：
- `usesCleartextTraffic=true` 是**开发期临时方案**（`http://192.168.137.1:8000` 明文），上线换 HTTPS 后关掉。
- SyncService 是 `dataSync` 前台服务类型，**Android 15 每日 6 小时限制**，真机验证后再决定是否换 WorkManager 轮询降级。

### 2026-09-19 · M3 确认（开工前）

**六点拍板（D1–D6）+ 三点补充（A/B/C）**：
1. **D1 视频建 `video_analyze`/`skipped` task**：`build_record_dto` 的 `task_status` 来自 `get_latest_task`，视频不建 task 就返回 null，前端无法显示紫。所以 POST /records 收视频时建一条 `status=skipped` 的 task（架构 tasks.type 本就含 video_analyze）。
2. **D2 `type` 用 Pydantic `Literal["text","video"]`**：非法 type → 校验错误 → 已映射 400 INVALID_PAYLOAD；text 必须 content 非空、video 必须 url 非空。
3. **D3 视频分享后自动弹详情**：ACTION_SEND → 落库 + 上传 → `detailTarget` 设 localId 自动弹详情，用户可补 note 或点空白关。
4. **D4 `detailTarget` 从 `Record?` 改 `String?`**：详情改由 `observeRecord(localId)` 的 Flow 驱动（`flatMapLatest` 派生 `detailRecord`），先读 Room 缓存、后台 GET 刷新后自动更新。
5. **D5 文本/视频走 SyncService 单任务分支**：VOICE=上传+轮询；TEXT/VIDEO=`POST /records` 一次不轮询。
6. **D6 补一句做在详情面板内**：note 编辑框 + 保存 → PATCH。

**三点补充（用户）**：
- **A** ACTION_SEND 文本常是"标题+链接"混合：正则提取 `https?://\S+` 作 url，前面去【】作 title，platform 从域名推断，提取不到 URL 当 type=text 存。
- **B** 详情面板：有 serverId 读缓存 + 异步 GET；无 serverId 只读 Room（避免 404）。
- **C** 视频分享自动弹详情确认；详情支持点空白关闭。

**实现关键**：
- 新增 `DtoMapper.toRecord(previous, synced)`：把 SyncService 里的 `applyDto` 提取成共享扩展，集中处理后端小写枚举 → domain 大写枚举的映射 + 未知值兜底（落实 PROGRESS 教训"映射层集中处理"），SyncService 与 MainViewModel 共用。
- 新增 `util/ShareParser`（混合文本解析）+ `util/Platform.infer`（域名推断）。
- note 编辑框用 `remember(record.localId)` 作 key：切换记录才重置，避免后台 GET 刷新覆盖用户正在编辑的备注。

### 2026-09-19 · M4 确认（开工前）

**三点拍板（D1–D3）+ 四点补充（A/B/C/D）**：
1. **D1 上传迁 WorkManager**：UploadWorker 做上传（transcribe/createRecord/patch），SyncService 退化为纯转写轮询。上传逻辑集中一处，WorkManager 天然带持久化（自愈）+ 网络约束 + 退避。
2. **D2 手动精确退避**：不用 WorkManager 原生 backoff（无 60s cap），用 queue 表计数 + `backoff(attempts)=min(60s, 1s×2^(attempts-1))`。20 次到顶 FAILED。
3. **D3 TokenGate 单例 + 拦截器 401**：拦截器检测 401 且非 validateToken（显式 X-Token）→ pause；TokenStore.save → resume。

**四点补充（用户）**：
- **A** 录音结束前台时立即 enqueue + setExpedited 减少首传延迟。⚠️ **记一笔：WorkManager 受 Doze 影响，息屏期间上传可能延迟到下次唤醒。M4 不解决，M5 再看。**
- **B** D2 确认。
- **C** D3 确认。
- **D** 状态颜色：上传失败重试期间 syncStatus 保持 SYNCING（蓝旋），不退回灰点；20 次到顶才 FAILED（红）。

**实现关键**：
- queue 表 M0 建好一直空着，M4 启用（id=recordLocalId，一个 record 一个队列项 UPLOAD/PATCH）。
- 写路径统一「落 Room + insertQueue + UploadQueue.scheduleNow」。
- note 无 serverId 时只本地记，UPLOAD 成功后由 Worker 补 PATCH（覆盖"上传前补 note"）。
- 自愈/前台补查统一走 Recovery.scan（幂等，KEEP 去重 + 5 秒防抖）。
- 401 暂停用 TokenGate.isPaused，Worker 里暂停时 Result.retry()（不消耗 attempts）。

### 2026-09-19 · M6 确认（开工前）

**三点拍板（launcher / ffmpeg 引用 / --status 退出码）+ 三点补充（用户）**：
1. **入口用新增 `launcher.py` 而非 `app/main.py`**：`app/main.py` 是纯 FastAPI 组装，放不进「自启/单实例/参数」这类进程级逻辑；改它才是动业务代码。launcher 包一层，app/ 业务代码零改动。
2. **ffmpeg 走「引用」不内嵌**：内嵌让 exe 多 102MB 且每次启动多解压 2-5 秒；外置 `dist/tools/ffmpeg.exe` 启动快，符合「或引用」。
3. **`--status` 契约是退出码 0/1**：`--noconsole` 的 exe 无控制台，`print` 不显示，bat 里读 `%errorlevel%`。

**三点补充（用户）**：
- **A** 注册表每次正常启动都幂等重写 `Run\ClipBackend = "<exe> --autostart"`，exe 移动后自启仍指向新路径。
- **B** build.bat 自动复制 `tools/ffmpeg.exe` 和 `.cache/huggingface` 到 dist，不存在就跳过（首启下载）。
- **C** 单实例分两种：注册表写 `--autostart`，带 `--autostart` 时已有实例静默退出；手动双击（无参）时弹 MessageBox「后端已在运行」。

### 2026-09-19 · M6 实现 + 打包验证通过

- 唯一触碰 `app/` 的是 `config.py` 加了 3 行冻结路径判定：`sys.frozen` 时 `BASE_DIR = Path(sys.executable).parent`（否则 `__file__` 指向临时解压目录 `_MEIPASS`），data/tools/.cache 全部随之落到 exe 旁。其余 app/ 零改动。
- 新增：`launcher.py`（自启注册/单实例/参数/uvicorn）、`build.spec`、`build.bat`、`backend-control.bat`、`stop.bat`、`status.bat`、`README-打包.md`。
- PyInstaller 6.22.3（装进 backend/.venv）+ pyinstaller-hooks-contrib，`collect_all` 收集 av/ctranslate2/tokenizers/huggingface_hub/faster_whisper 的原生库。
- **验证全过**：`/health` 返回 ok；自启键写入 `"…\dist\backend.exe" --autostart`；`--status` 退出码 0/1；单实例二次启动退出 0 且进程数不变；`--uninstall-autostart` 删键；**端到端转写 POST→processing→done（content="You"）**，证明 native 库 + 外部 ffmpeg + 模型缓存全通。
- 产物：`backend/dist/backend.exe`（100.5MB 单文件）+ `dist/tools/ffmpeg.exe` + `dist/.cache/huggingface/`（模型）。数据目录 `dist/data/` 与开发态 `backend/data/` 独立。

### 2026-09-19 · M6.5 确认（开工前）

**四个决策（D1–D4）**：
1. **D1 新建专用 `FloatingBallService`**（specialUse），不复用 SyncService/RecordingService（短命服务，承载不了常驻悬浮球）。
2. **D2 短按语义**：悬浮球全局，"主面板"仍是 App 内捕获 sheet；短按拉起 App + 切面板（不做第二个悬浮面板）。
3. **D3 引导页门槛**：初稿"悬浮窗 + 通知 必过"，后按五点第 2 条改为仅悬浮窗必过。
4. **D4 录音状态同源**：RecordingController 单例持有 isRecording，球与 App 跨组件一致。

**五点补充（用户）**：
1. **短按分前台/后台**：后台拉起带 extra `open_panel=true`（默认展开，非 toggle）；前台短按才 toggle。
2. **通知权限不强制**：Android 13+ 可拒绝，拒后 FGS 无通知栏仍可用；引导页"推荐开启"不阻塞。
3. **悬浮球位置持久化**：默认右侧中间，存 SharedPreferences，重启不变，留 x/y 字段为拖动预留。（注：App 内原球是 BottomEnd 右下角，此处按用户口径"右侧中间"实现。）
4. **onTaskRemoved 是 best-effort**：代码要做，但写明"划掉后可能短暂消失，系统允许时自动恢复；国产 ROM 可能永久杀掉"，不承诺"永远在"。
5. **前台服务通知不可去掉**：Android 硬性要求，IMPORTANCE_LOW、无声音无震动、内容最简，平台限制非设计选择。

### 2026-09-19 · M7 确认（开工前）

**五个决策（D1–D5）**：
1. **D1 单窗口**（球+面板一棵 Compose 树），拖动时面板自然跟随。
2. **D2 语音按钮 = 原语音功能**：转写结果填输入框而非剪贴板。`VoiceDestination` 分 CLIPBOARD/BODY/NOTE；所有语音照常产日志记录，不做特殊处理。
3. **D3** "在线"=每 15s ping `/health`（免 token）；"队列"=pending queue size。
4. **D4** 球 done-* 态闪 ~2s 自动回 idle。
5. **D5** 毛玻璃=半透明背景+22px 圆角+细边框（真 blur-behind 在 overlay 上不可行）。

**不做**：M5 细节、抖音分享。

### 2026-09-19 · M7.1 确认（开工前）

**两点拍板**：
1. **纯文本分享（无 URL）**：自动存文本记录 + 闪青 + finish，不弹主界面。
2. **文本/视频不走 BUSY**：只有语音有 REC→BUSY→DONE_VOICE 这段；文本/视频确认后直达 DONE_* 闪色。

**实现关键**：
- 状态机入口：`RecordingController.start/stop` → `onRecordingStarted/Stopped`；`SyncService.poll` DONE/FAILED/SKIPPED → `onVoiceDone/onVoiceFailed`；`UploadWorker` 20 次放弃 → `onVoiceFailed`（仅 VOICE）；`OverlayPanel` 确认 → `onTextDone/onVideoDone`。
- 🔁 `queueCount` 降级为仅面板「在线 · 队列 N」显示，**不再驱动球 BUSY**：M7 的「queueCount>0→BUSY」是错误代理——上传成功即删队列项，转写还在跑时 queueCount 已归 0，球提前回 IDLE、转写期无 busy；且转写完成从不触发 done-voice 绿闪。M7.1 用显式状态机 + 记录终态兜底替换。

### 2026-09-19 · M7.2 确认（开工前）

**三点结构性变化（用户拍板）**：
1. 删长按录音（录音唯一入口 = 面板「语音」按钮）。
2. 主面板单模式化（TEXT_INPUT / VIDEO_NOTE 拆出去）。
3. 新增第三个「卡片窗」（底部 sheet，文本/视频共用）。

**两点补充（A/B）**：
- **A** 卡片取消 = 只关卡片不闪；确认 = 关卡片 + 闪青/紫。
- **B** 主面板顶部保留「在线 · 队列 N」（跟 HTML 一致）。

**实现关键**：
- 🔁 **拖动不跟手根因**：Compose `pointerInput.positionChange()` 是窗口局部坐标，拖动时每帧 `updateViewLayout` 移动窗口 → 下一帧 `MotionEvent.getX()` 相对新窗口位置算 → 增量被污染、球来回抖。解法：View 级 `OnTouchListener` 用 `event.rawX/rawY` 绝对坐标，`downCenter + (raw - downRaw)` 直接算球中心。
- 状态模型：`panelOpen: Boolean` + `cardMode: CardMode?`（TEXT_INPUT/VIDEO_NOTE）替换 `PanelMode` 枚举；两者互斥。
- 卡片窗用「remember 最后非 null 模式」保留退出动画期间的渲染内容。

### 2026-09-19 · M7.2 真机修复确认

**三确认 + 一预留**：
1. busy 转圈提前到本轮（Canvas 旋转弧）。
2. 卡片抖动：`BOTTOM|CENTER_HORIZONTAL` + 删 cardHeight/`SOFT_INPUT_ADJUST_RESIZE` + `imeAnimationTarget`；真机不顺手再改回 `ime`。
3. 语音格式：`TextFieldValue`，不换行，光标到末尾。
4. 详情页：`CardMode` 预留 `DETAIL`，本轮不改实现，M7.3 填（入口=点记录→面板收起→DETAIL 升起，内容 `SelectionContainer`）。

**踩坑**：`imeAnimationTarget` 是实验 API，编译报「experimental and likely to change」，需 `@OptIn(ExperimentalLayoutApi::class)` + import `ExperimentalLayoutApi`。

### 2026-09-19 · M7.2 追加确认（6 条）

- 四条（done 1.5s / 浮动卡片 / 语音格式不动 / 详情页）确认 + 追加两条（5 卡片点别处收起、6 球贴边 1s 自动缩半圆）。
- **卡片浮动代价接受**：键盘会盖住卡片下部按钮区，打字时先收键盘再点确认/取消。
- **决策**：`collapseToHalf()` 在面板/卡片开着时不缩（否则点面板按钮会触发球窗 `ACTION_OUTSIDE` 把球缩掉、面板跟着跳）。这是对"点别处→缩半圆"的边界收敛，真机看是否要再放开。

### 2026-09-19 · M7.2 卡片形态重做确认

- **A** 底色定死 `0xC712141C`（深色 v12 玻璃），blur/边框留 M7.3；**B** scrim 透明不加遮罩。
- `collapseToHalf` 门改成 `ballState != IDLE || cardMode != null`（去掉 panelOpen 门：面板开着时球也可缩，点面板按钮时面板立即关所以不影响）。
- 🔁 **卡片窗从「独立窗口切高度」→「全屏窗口 + Compose 布局」**：M3 那版不抖，是因为卡片跟主面板在同一 overlay 窗口用 Compose 布局，没有独立 WindowManager 定位。本轮把卡片窗改全屏 MATCH_PARENT，窗口永不 updateViewLayout，抖动 + 穿透 + 键盘挡按钮一并解决。
- 🔁 **卡片窗改可聚焦**（去 `FLAG_NOT_FOCUSABLE`）：全屏模态卡片本应可聚焦，输入框才能拿到 IME；之前的 focus 切换（NOT_FOCUSABLE + onFocusChanged toggle）这轮直接不要了。

### 2026-09-19 · M7.2 球消失修复确认

- 比例存储 / 硬 clamp / DisplayListener 三点确认；可选项选**比例存储**（不语义化），贴边旋转差几像素可接受。
- 🔁 **教训**：悬浮窗坐标绝不能存绝对 px——横竖屏/分辨率一变就失效。存「屏幕比例」，加载/旋转时用当前屏幕尺寸重推 + clamp 兜底。

### 2026-09-19 · M7.3 确认

- **A** FAILED 色 `0xFFE53935` + 闪 1.5s；**B** 卡片失败提示红字一行"转写失败"。
- **三处纠正**：①done/failed 可点击（仅 BUSY 无效）；②删球长按引用（CLIPBOARD 只从主面板"语音"按钮触发）；③onDragEnd 保留贴边吸附、只删"松手立刻半圆"（松手全圆，1s 无操作才缩）。
- 🔁 我初稿把 done 写成"不可点"，用户纠正回"done/failed 可点击、仅 busy 不可点"——与 M7.2 追加的"done 可点击"一致。

### 2026-09-19 · M7.3 真机修复确认

- busy = 呼吸脉冲 + 中心转圈图标（两个一起，非二选一）。
- 繁体双保险：whisper `initial_prompt` + zhconv 后处理，两个都做。

### 2026-09-20 · M7.3 修复确认（六/七）

- 🔁 **点球 toggle 竞态根因**：面板带 `FLAG_WATCH_OUTSIDE_TOUCH`，点球（在面板外）触发面板 `ACTION_OUTSIDE` 关面板 + 球 toggle 又开面板 → 净效果面板关不掉。解法：删面板 `FLAG_WATCH_OUTSIDE_TOUCH`，把「点别处关面板/缩半圆」统一进球的 `ACTION_OUTSIDE`（分层退出）。
- 🔁 **initial_prompt 撤销**：上一轮加的 initial_prompt 强制简体导致英文识别不出，撤销，只保留 zhconv 后处理。

### 2026-09-20 · M7.4 确认

- note 覆盖 bug 不止 DtoMapper 一处，检查所有 DTO→Room 路径：统一规则「DTO null 不覆盖本地值」，`title/content/note/url/platform` 全走 `?: previous`。
- 详情复制：标题+正文+URL+note 共用一个 SelectionContainer，视频详情同一套。
- 显示兜底顺序：note → title → content → 视频"分享一个作品给你" → 其他类型名。

### 2026-09-20 · M7.5 确认

- 自绘 Compose 菜单（不依赖系统 ActionMode），功能对齐系统：剪切/复制/粘贴/全选。
- 详情卡片也改成只有 × 按钮才关。统一原则：所有卡片有显式关闭按钮，一律不点外部关。

### 2026-09-20 · M7.5 根层 toolbar 修复（versionCode=25）

- 自绘菜单只覆盖 Text（SelectionContainer）没覆盖 TextField。字节码确认 `BasicTextField` 读 `LocalTextToolbar`（`getLocalTextToolbar()`），但 TextField 用 `TextFieldSelectionManager.setTextToolbar` 缓存式注入、SelectionContainer 是直接读，缓存读点没拿到卡片内部 provider 的值。
- 修：`LocalTextToolbar` provider 提到 ComposeView 根（`FloatingBallWindow.showCard` 的 setContent 里，toolbar 由 window 层持有），`OverlayCardHost` 删内部 provider、改接收 toolbar 参数。
- `OverlayTextToolbar.showMenu` 加诊断日志（`FileLogger.i`），真机测 TextField 长按是否走到 showMenu。
- versionCode 24→25（0.7.4）。

### 2026-09-20 · M7.5 粘贴兜底（versionCode=26）

- 根因：Android 10+ 只允许焦点应用/默认输入法读剪贴板，overlay 窗口读 `ClipboardManager` 失败 → BasicTextField 的 `onPaste` 回调为 null（日志 `paste=false`）→ 菜单没粘贴。
- 修：`OverlayTextToolbar` 加 `pasteAction` 兜底；菜单四按钮（剪切/复制/粘贴/全选）按可用性显示，粘贴按钮 `onPaste?.invoke() ?: pasteAction?.invoke()`。卡片（TextInputCard/VideoNoteCard）用 `SideEffect` 注册 `pasteAction`：点击时读 `ClipboardManager.getPrimaryClip()` 非空则插入当前光标处（标题/正文/note 各自光标）。所有操作 try-catch 静默失败。
- 备选方案 B（若粘贴仍失败）：注册 `OnPrimaryClipChangedListener` 提前缓存剪贴板。
- versionCode 25→26（0.7.5）。

### 2026-09-20 · M7.5 修复轮四问题（versionCode=27）

- **输入框复制失败**：点自绘菜单按钮时 Popup 抢焦点 → TextField 失焦清空选中 → onCopy 拿空。修：`Popup(properties = PopupProperties(focusable = false))` 不抢焦点。
- **输入框粘贴失败**：overlay 读不到系统剪贴板（Android 10+）。新增 `ClipboardCache` 单例（MainActivity.onResume + OnPrimaryClipChangedListener 刷新缓存）；`pasteAction` 先直接读→失败从缓存读→都没有静默返回。
- **提交文本按钮 enabled**：`title.isNotBlank() || body.isNotBlank()`（标题或正文任一非空即可点）。
- **球状态时序（准确状态）**：提交时 `onSubmitStarted()`（busy）→ UploadWorker 文本/视频 createRecord 成功 `onSubmitDone(type)`（flash done）→ 永久失败 `onSubmitFailed()`（idle）。删 `onTextDone`/`onVideoDone`。busy 统一规则（点球无效/拖动允许/不缩半圆）由 `ballState==BUSY` 驱动，语音/文本/视频一致。
- 踩坑：`Popup` 没有 `focusable` 直接参数，在 `PopupProperties(focusable=)` 里。
- versionCode 26→27（0.7.6）。

### 2026-09-20 · M7.5 修复轮两问题（versionCode=28）

- **只填标题球一直 busy**：`submitText` 有 `if (content.isBlank()) return`，只填标题时 content 空直接 return（没插记录没排上传），但卡片 confirm 无条件 `onSubmitStarted()` → busy 永不结束。修：submitText 改「title 或 content 任一非空才 return，content 空存 null」；后端 `records.py` text 校验改「title 或 content 任一非空」；UploadWorker 400（客户端错误）→ 立即永久失败 `onSubmitFailed` 不重试。
- **Popup(focusable=false) 回退**：导致菜单不弹。撤销 focusable=false 恢复默认 Popup；卡片自己实现 copy/cut/selectAll（用 `TextFieldValue.selection`，不因失焦被清），注册 `copyAction/cutAction/selectAllAction`（类似 pasteAction），菜单按钮 `copyAction ?: onCopy`。Text（SelectionContainer）仍用 onCopy/onSelectAll 不受影响。
- 重打包 backend.exe。versionCode 27→28（0.7.7）。

### 2026-09-20 · M7.5 TextField 拦长按菜单（versionCode=29）

- 澄清：TextField 菜单从没弹过，根因是 TextField 走 `TextFieldSelectionManager.setTextToolbar` 缓存，用默认 AndroidTextToolbar（ActionMode），LocalTextToolbar 注入对它无效（之前日志是详情卡片 SelectionContainer 打的）。
- 修：绕开 LocalTextToolbar，TextField 自己拦长按——每个输入框外包 Box + `onGloballyPositioned` + `pointerInput(detectTapGestures(onLongPress))`，长按手动 `toolbar.showMenu(rect, null×4)` 弹菜单（复用 OverlayTextToolbarMenu）；按钮走已注册的 copyAction/cutAction/selectAllAction/pasteAction（读 TextFieldValue.selection）。菜单关闭：点菜单项 hide + `Popup(onDismissRequest=hide)` 点外关 + 字段 `onFocusChanged` 失焦 hide。LocalTextToolbar 注入保留给详情卡片 SelectionContainer。
- 踩坑：`positionInWindow` 需 import `androidx.compose.ui.layout.positionInWindow`。
- versionCode 28→29（0.7.8）。

### 2026-09-20 · M7.5 常驻按钮（versionCode=30）

- 输入框长按菜单在 overlay 里做不稳（4 版没成），放弃长按，改常驻小按钮。
- 删 TextField 外的 Box + pointerInput 拦长按、SideEffect、OverlayTextToolbar 的 copyAction/cutAction/selectAllAction/pasteAction；OverlayTextToolbarMenu 按钮回真实回调（详情卡片 SelectionContainer 仍走 LocalTextToolbar）。
- 每个输入框下方加小号浅色按钮：标题 [粘贴]、正文 [粘贴][复制][全选]、note [粘贴][复制][全选]（`SmallActionButton`：labelSmall + 0x80FFFFFF）。粘贴走 ClipboardCache，复制「有选中复制选中、无则复制全文」，全选 selection=全文。
- versionCode 29→30（0.7.9）。

### 2026-09-20 · M8 三按钮 + 视频卡片 + 标签系统（versionCode=31）

- **主面板三按钮**：语音｜文本｜视频（"提交文本"改名"文本"）；视频按钮 = `panelOpen=false; pendingVideo=null; cardMode=VIDEO_INPUT`。
- **视频卡片重写**：`CardMode.VIDEO_NOTE`→`VIDEO_INPUT`，`VideoNoteCard`→`VideoInputCard`。字段：标题(可选)/链接(可选)/平台(必填下拉)/备注(可选)/标签(可选)；从 `PendingVideo(title,url,platform)` 预填；确认 `enabled = platform.isNotBlank()`。平台下拉用 `ExposedDropdownMenuBox`（Material3 实验 API，`@OptIn(ExperimentalMaterial3Api::class)`）；「其他」→ 内联自由输入 → 加入候选并选中（"其他"本身不入库）。
- **标签系统（前端 DataStore + 后端 tags 列）**：
  - 新增 `data/catalog/CatalogStore`（`preferencesDataStore("catalog")`）：三套独立候选 `platforms`/`textTags`/`videoTags`，JSON 字符串存、保序；平台默认 `[抖音,视频号,B站,快手,小红书,其他]`，未写时 seed、删空后不回填。
  - 新增 `ui/overlay/TagSelector`：FlowRow chip 多选，点文字 toggle、**chip 右侧 × 删候选**（用户定：不用长按）、"＋ 标签"内联添加。
  - 文本标签 / 视频标签互不可见（各自 flow）。
- **后端**：`db.py` records 加 `tags TEXT` + `_migrate`（`PRAGMA table_info` 查列缺则 `ALTER TABLE ADD COLUMN`，老库兼容）；`models.py` `create_record` 存 `json.dumps(tags)`、`build_record_dto` 返回 `json.loads`；`schemas.py` RecordDTO/CreateRecordRequest 加 `tags`；`records.py` **video 校验 `url 必填`→`platform 必填`**、传 tags。
- **数据层**：domain `Record` 加 `tags: List<String> = emptyList()`（默认值→存量构造点不炸）；`RecordEntity` 加 `tags: String?`；`AppDatabase` v1→v2 + `MIGRATION_1_2`（ALTER ADD tags）；`RecordRepositoryImpl` 用新 `util/JsonList` 做 List↔JSON；`DtoMapper` merge 加 `tags = tags ?: previous.tags`。
- **上传**：`OverlayActions.submitText/submitVideo` 加 tags 参数（submitVideo 改传显式 platform、删 `Platform.infer`）；`UploadWorker` `CreateRecordRequest` 带 tags。
- **平台中文**：`Platform.infer` 改返回中文（B站/抖音/快手/小红书，删 youtube/weibo/zhihu）；历史英文 platform 不迁移（列表/详情不显示 platform）。
- **详情卡片显示标签**（用户补：只读 chip `ReadOnlyTagChips`）；记录列表不显示。
- **五点拍板**：①video 校验 url→platform；②平台值中文、删 youtube/weibo/zhihu、历史不迁移；③"其他"自由输入入候选、本身不入库；④标签删除用 chip × 不用长按；⑤详情显示标签、列表不显示。
- **踩坑**：`JsonList.toJson` 初版空列表返回 null，但 DataStore `stringPreferencesKey` setter 要非空 String，编译报类型不匹配——改空列表返回 `"[]"`，既满足 DataStore，又让"从未写入(null)=seed 默认"与"用户删空([])=空"可区分。
- 后端导入冒烟 + 隔离测试（fresh 建表 tags 往返 + legacy 无 tags 列跑 `_migrate` 补列）全过。重打包 backend.exe。
- versionCode 30→31（0.8.0）。

### 2026-09-21 · M8 真机修复轮（versionCode=32）

- **两个严重 bug 同源**：视频卡片 `ExposedDropdownMenuBox`（Material3 实验 API，内部 `Popup` 弹独立窗口）在 overlay 自绘窗口里崩。点「视频」= 前台崩退；分享进来 = 后台崩、球窗随进程死、START_NOT_STICKY 不重启 → 球永久消失。文本卡片（TagSelector/FlowRow/DataStore）能开，证明崩点唯一在 ExposedDropdownMenuBox。
- **修**：删 ExposedDropdownMenuBox，平台改**自绘 in-place 下拉**（卡片 Compose 布局内展开选项列，不弹 Popup）。加 FileLogger 打点（视频按钮 / VideoInputCard 组合完成）确认崩点消失。
- **标签选择器折叠**：`TagSelector` 收起态只显示已选 chip + 「＋ 标签」，点开 in-place 展开候选列表（✓ 选中 / 点切换 / 右侧 × 删候选 / 「＋ 添加标签」内联输入）。
- **卡片滚动**：`OverlayCard` 加 `heightIn(max=440dp)` + 内容 `verticalScroll`；DetailCard 删内层 scroll 改用外层统一滚。IME 弹起时卡片先封顶再滚，不再挤压。
- **备注无限追加**：`Record.note: String?` → `notes: List<NoteEntry(id,text,time)>`，id 客户端 `Ids.newNoteId()` 生成；`PATCH /records/{id}` 语义改追加（`append_note` 按 id 幂等去重，重试安全）；`doPatch` 逐条 appendNote，无需同步计数列。老单条 note `_migrate_notes` 转数组（空/null 不生成空条目）；`build_record_dto` 返回 note 数组。详情（in-app DetailPanel + overlay DetailCard）显示所有备注带时间戳，空列表整块不显示；in-app「保存备注」→「追加备注」+ 草稿缓存（MainViewModel.noteDraft，退出重开不丢）+ 输入框 maxLines + sheet 滚动。
- 后端隔离测试全过：老 note 迁移 / appendNote 幂等（同 id 只算一次）/ 空 note 不生成空条目。重打包 backend.exe。
- versionCode 31→32（0.8.1）。

### 2026-09-21 · M8.1 界面职责 + 草稿隔离 + 不点外部关（versionCode=33）

- **界面职责确认（已满足，零改动）**：悬浮球层与 App 内层读同一份 Room 数据，列表/详情一致；悬浮球详情只读（无「追加备注」按钮），App 内详情可追加；备注是 `Record.notes` 字段挂在记录下，不出现在列表。
- **bug1 草稿串卡片**：`noteDraft` 单全局 String → `noteDrafts: Map<localId, String>` + `setNoteDraft(localId, text)`（text 空 = 移除 key = 清空）；`DetailPanel` 的 `note` 改 `remember(record.localId)` 按记录重置。切记录不再带旧草稿。
- **bug2 不点外部关**：App 内 `DetailPanel` 弃 `ModalBottomSheet`，改自绘底部卡片（全屏 Box + 半透明 scrim「点外部只挡穿透不关闭」+ 底部卡片显式「关闭」按钮 + `BackHandler(onBack=onClose)` 返回键关详情不退出 App）+ `heightIn(max=520dp)` + `verticalScroll`。
- 版本号 32→33（0.8.2）。纯 Android 改动，无后端改动。

### 2026-09-21 · M8.2 记录摘要统一 displayTitle（versionCode=34）

- **bug（追加备注"多了一条"）根因**：不是 insert（`appendNote` 用 `repository.update`→`@Update` 按 localId 更新，无新行；`Record.notes` 是 records 表内 JSON 字段，无独立 notes 表）。真正原因是**悬浮球 `OverlayPanel.LogItem` 首行优先级把 `notes.lastOrNull()?.text` 排在 title/content 前面** → 追加备注后，同一条记录的列表项文案从「标题/正文」变成「备注内容」，看起来像新条目。App 内 `LogListScreen` 无此优先级，所以是对的。
- **通用规则**：抽 `Record.displayTitle()`（`ui/Format.kt`），统一优先级 `title → content → url → platform → type.displayName()`，删掉"分享一个作品给你"兜底。所有"取字段做摘要/首行"处统一调它：`OverlayPanel.LogItem`、`LogListScreen.LogListItem`、`DetailPanel` 标题行（内容/URL 用 `!= header` 去重，不重复显示）。
- 通知/剪贴板不涉及（传的是转写 content，非"选字段做摘要"）。
- 版本号 33→34（0.8.3）。纯 Android 改动。

### 2026-09-21 · M8.3 标签池隔离加固（versionCode=35）

- **诊断**：文本/视频标签静态上本就分开存（`CatalogStore` 两个独立键 `text_tags`/`video_tags`，卡片各自 `textTagsFlow`/`videoTagsFlow` + `saveTextTags`/`saveVideoTags`，无跨池写路径）。但真机出现重叠 → 根因是两个时序 bug：①`onAdd`/`onDelete` 用 `collectAsState` 的**滞后值**拼列表，快速连建会丢标签；②写入用卡片的 `rememberCoroutineScope`，卡片关闭即 cancel，写可能被中止。两者让「内存看到的候选」和「DataStore 实际存的」脱节。
- **重构 CatalogStore 为内存单一来源**：三套候选各一个 `MutableStateFlow` 缓存（同步读、无滞后），`add/remove` 同步更新缓存 + 长生命周期 IO scope 异步持久化（不随卡片取消）；`load(context)` 启动时从 DataStore 载入缓存。API 改为 `addTextTag/removeTextTag/addVideoTag/removeVideoTag/addPlatform`，卡片 `onAdd`/`onDelete` 直接调用（不再 `scope.launch`），删 `rememberCoroutineScope`/`launch` 引用。
- **自检**：`load()` 里 `textTags ∩ videoTags` 非空 → `FileLogger.w` 警告（`FileLogger` 新增 `w` 方法），旧数据污染能查出来。`ClipApp.onCreate` 调 `CatalogStore.load`。
- 版本号 34→35（0.8.4）。纯 Android 改动。

### 2026-09-21 · M8.4 App 内详情补标签 chip（versionCode=36）

- **诊断**：App 内 `DetailPanel` 漏渲染 `record.tags`（overlay `DetailCard` 有 `ReadOnlyTagChips`），是唯一缺的字段；标题/正文/URL/备注两层内容一致，仅明暗主题样式差异，不算 bug。
- **修**：`DetailPanel` 加只读 `TagChips`（light 主题：`surfaceVariant` 底 + `onSurfaceVariant` 字 + 12dp 圆角 + labelMedium，风格对齐 overlay），位置在**备注之后、追加输入框之前**（对齐 overlay `notes→tags` 顺序）。App 内详情不做标签编辑（标签提交时选）。
- 版本号 35→36（0.8.5）。纯 Android 改动。

---

## 🔁 反复修改的地方

_（暂无，遇到后再填。这里专门放"改了好几次才定下来"的问题。）_

---

## 错误与解决

### 2026-09-19 · Room KSP 报 "no such column"（M0 首次构建）

- **现象**：`kspDebugKotlin` 失败，报 `no such column: created_at / local_id / record_local_id`。
- **根因**：Room 默认把 Kotlin 属性名直接当列名（camelCase），而 DAO 查询里写的是 snake_case（`created_at` 等），两边对不上。实体属性是 camelCase、查询是 snake_case，缺 `@ColumnInfo` 显式映射。
- **解法**：给三个实体的字段加 `@ColumnInfo(name = "snake_case")`，显式声明列名，与设计稿/后端蛇形命名一致。
- **防止再犯**：写 Room 实体时，列名要么全 camelCase（查询跟着用），要么全 snake_case（每个字段 `@ColumnInfo` 显式声明），不要混。

### 2026-09-19 · FastAPI 校验错误 422 vs 契约 400（M1）

- **现象**：`POST /transcribe` 空 `local_id` 返回 422，契约要求 400 INVALID_PAYLOAD。
- **根因**：FastAPI 默认把请求校验错误（缺字段/类型错）返回 422，但契约错误码表只有 UNAUTHORIZED/INVALID_PAYLOAD/NOT_FOUND/SERVER_ERROR，没有 422。
- **解法**：注册 `RequestValidationError` 处理器，统一映射成 400「请求格式错」（INVALID_PAYLOAD）。
- **防止再犯**：FastAPI 项目要在 errors 层显式把 422 映射到契约错误码，别让默认 422 漏给前端。

### 2026-09-19 · SyncService 编译错误：ApiResponse vs RecordDto 类型不匹配（M2）

- **现象**：`compileDebugKotlin` 报 `Argument type mismatch: ApiResponse<RecordDto> but RecordDto expected`（SyncService 轮询处）。
- **根因**：`api.task(taskId)` 返回统一响应 `ApiResponse<RecordDto>`，我直接把整个响应丢给 `applyDto`（它只要 `RecordDto`），漏了取 `.data`。
- **解法**：先 `val resp = api.task(...)`，再 `val dto = resp.data ?: run { 标记失败; return }`，`data` 为空也按失败处理。
- **防止再犯**：后端所有接口都包在 `{ok,data,error}` 里，Retrofit suspend 返回类型是 `ApiResponse<T>`，**每次都要手动 `.data` 解包 + 判空**，别把响应体当 `T` 用。

### 2026-09-19 · uvicorn 在 --noconsole 打包下启动即崩（M6）

- **现象**：`backend.exe` 双击后无窗口、无报错，日志停在「启动后端」，进程静默退出，`/health` 不通。
- **根因**：`--noconsole`（PyInstaller `console=False`）把 `sys.stdout/stderr` 置为 `None`；uvicorn 配日志时 `DefaultFormatter.__init__` 调 `sys.stdout.isatty()` → `AttributeError: 'NoneType' object has no attribute 'isatty'` → dictConfig 抛 ValueError → `uvicorn.run` 崩。窗口态 stderr 又丢了，所以毫无痕迹（靠 venv python 复现 `sys.stdout=None` 抓到 traceback）。
- **解法**：launcher 在 `uvicorn.run` 前，若 `sys.stdout/stderr is None` 就把它们重定向到 `data/backend.log`（`open(..., buffering=1)`），再 `uvicorn.run(..., log_config=None)` 不让它重配日志。
- **防止再犯**：PyInstaller `--noconsole` 下任何库只要假定 `sys.stdout/stderr` 非空（`.isatty()`/`.write()`）都会崩；打包入口要先把这两个流重定向，别依赖"默认有控制台"。

### 2026-09-19 · Compose 挂 WindowManager overlay 的两个坑（M6.5）

- **坑 1（崩溃）**：`ComposeView.setContent` 在 overlay 窗口抛 `IllegalStateException: ViewTreeLifecycleOwner not found`。根因：ComposeView 不在 Activity view tree 里，`WindowRecomposerFactory` 找不到 ViewTreeLifecycleOwner。解法：自定义 LifecycleOwner + 挂载前 `ViewTreeLifecycleOwner.set(view, owner)` 三件套。
- **坑 2（编译）**：`import androidx.lifecycle.ViewTreeLifecycleOwner` 报 Unresolved reference，但类明明在 classpath 上（javap 确认 public static、metadata mv=1.8.0 可读）。根因：这些类在 lifecycle 的 `-android` KMP 变体里，Kotlin 编译器对 -android 变体的元数据可见性有问题（Java 类如 LifecycleRegistry 能 import，Kotlin 类如 ViewTreeLifecycleOwner 不能）。解法：把 ViewTree owner 的调用放进 Java helper（javac 只看字节码），Kotlin 侧只调 helper。
- **防止再犯**：KMP 库的 `-android` 变体里的 Kotlin 类，若 Kotlin import 报 Unresolved 但 javap 能看到 public 字节码，用 Java 中间层绕开。

### 2026-09-21 · 误判 `--uninstall-autostart`「删不掉键、卡住」（服务管理器接入）

- **现象**：测 `dist\backend.exe --uninstall-autostart` 时，注册表 `ClipBackend` 没被删、进程还活着卡在对话框。据此判定"这个卸载入口坏了、既删不掉键又阻塞调用方"，并把这个结论报给了用户。
- **根因**：**测量早于事实**。PyInstaller onefile 要先解包才会跑真正的子进程（本包 653MB 带 CUDA DLL，实测 5~15 秒），而我在 +3~4 秒就查注册表，**并且把 bootloader 父进程 taskkill 了** → 子进程根本没机会执行删除。删除功能一直是好的。真正的缺陷只有"删完弹模态框阻塞调用方"这一条。
- **澄清过程**：换个方式直连 `winreg.DeleteValue` 测原语，返回 `FileNotFoundError` —— 说明值**已经被那个"被我判死"的进程删掉了**，只是晚于我的检查。同一 exe、同一参数、观测时间不同，结论完全相反。
- **解法**：等子进程真正跑完再判定 —— 用**健康 200 / 退出码**当同步点，别按秒数猜。
- **防止再犯**：①本文件 2026-09-19「PyInstaller onefile + 无窗口 + 原生库」那条**已经写过这个问题**（"别 2 秒就 taskkill（会撞上还没解压完）"），我没先读就下结论 —— 改东西前先搜本文件有没有同类先例。②注册表"键不存在"这类**终态观测无法区分"抑制了写入"与"压根没走到写那段代码"**，必须配一条分支日志才能定论。

---

## 学到的东西

### 2026-09-19 · 国内网络环境下的构建

- `services.gradle.org` 在国内极慢（几分钟才 3MB）；改腾讯镜像 `mirrors.cloud.tencent.com/gradle/` 后 8 秒下完 128MB。
- Maven 依赖同理：`settings.gradle.kts` 把阿里云镜像（google/central/gradle-plugin）排在 google()/mavenCentral() 前面。
- 两者都是项目内配置，不改全局。

### 2026-09-19 · DTO(后端)与 domain(前端)枚举大小写不一致（M2）

- 后端 `RecordDTO` 的 `type`/`status`/`task_status` 是**小写**（`voice`/`done`/`pending`），前端 domain 枚举是**大写**（`VOICE`/`DONE`）。两者接缝处必须显式 `.uppercase()` 再 `valueOf()`，否则运行时 `IllegalArgumentException`。
- 这次在 `SyncService.applyDto` 里用 `runCatching { valueOf(dto.type.uppercase()) }.getOrDefault(旧值)` 兜底：既转大小写，又防后端返回未知值导致崩溃。
- **防止再犯**：跨端枚举要么两边统一大小写，要么在映射层集中处理，别散在各处手写 `valueOf`。

### 2026-09-19 · PyInstaller onefile + 无窗口 + 原生库（M6）

- onefile 每次启动都要把 ~100MB 解压到临时目录，`--status`/`--uninstall-autostart` 这类一次性命令也要 ~1-3 秒；测试这些参数要给足等待，别 2 秒就 taskkill（会撞上"还没解压完"）。
- onefile 运行时会跑出父子两个 `backend.exe` 进程（bootloader 父 + 应用子），查进程/单实例测试要按"两个进程 = 一个实例"看。
- `collect_all` 只收集"被 import"的包；faster-whisper 的模型不走包数据，靠 `HF_HOME` 指向 exe 旁 `.cache/huggingface`（build.bat 复制旧缓存可跳过 460MB 下载）。
- 数据/工具/模型目录用 `sys.executable.parent` 定位是打包态唯一可靠锚点（`__file__` 会指向 `_MEIPASS` 临时目录）。
- **更正 2026-09-19 条**：上面写"一次性命令要 ~1-3 秒"是低估。本包 onefile 653MB（含 CUDA DLL），实测**解包到子进程开始干活要 5~15 秒**。用"健康 200 / 退出码"当同步点，比按秒数猜稳。
- **观测要能区分「没发生」和「没走到」**：注册表键、端口占用这类**终态观测，对"提前 return"和"正确抑制"长得一模一样**。判定一个开关是否生效，必须同时拿到一条能标明分支的日志，否则实验设计就是无效的（2026-09-21 被这个坑过一次）。
