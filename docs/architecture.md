# 手机剪藏 · 架构文档

## 一、产品定义

一个安卓端的信息捕获器。你只管刷、说、转、贴；系统负责存、转写、分析、找回。

三个入口：语音（点→说→粘贴）、视频（转发→可选补一句→结束）、文本（复制→粘贴→存）。

一个出口：剪贴板（前台自动，后台通知点击）。

一个原则：不确认、不选标签、不等处理、不管技术。

---

## 二、架构不变量（十条）

1. 后端是唯一权威源，前端是缓存 + 采集器。
2. 所有写操作先落本地，再异步同步。
3. `local_id` 幂等，重试安全。
4. 任务持久化 + 自愈，进程重启不悬挂（前后端都成立）。
5. 耗时任务异步 + 轮询，接口立刻返回。
6. 用户不确认、不选标签、不等处理。
7. 断网自动重试，丢了自动补，重复自动去重。
8. 剪贴板前端写，后端不碰。
9. 视频分析本期不做，只占位（`task_status=skipped`）。
10. 工作台集成最后做，只是壳。

---

## 三、系统架构

```text
┌──────────────────────────────────────┐
│  Android App (Kotlin)                │
│  ├─ UI：悬浮球 / 日志 / 面板          │
│  ├─ 本地库：Room                     │
│  ├─ 网络：Retrofit + X-Token         │
│  ├─ 队列：本地表 + 重试              │
│  ├─ 录音：MediaRecorder              │
│  ├─ 前台服务：Recording / Sync       │
│  └─ 剪贴板：前台自动 / 后台通知      │
└──────────────┬───────────────────────┘
               │ HTTPS + X-Token
               │ Cloudflare Tunnel
               ▼
┌──────────────────────────────────────┐
│  后端 (FastAPI)                      │
│  ├─ 接口层：收 / 存 / 建任务 / 返回  │
│  ├─ worker：DB 轮询，自愈            │
│  ├─ 转写：faster-whisper             │
│  ├─ 转码：FFmpeg → 16k 单声道 WAV    │
│  ├─ 存储：SQLite[WAL] + 本地文件     │
│  └─ 鉴权：X-Token 中间件             │
└──────────────────────────────────────┘
```

**数据流**：

```text
用户动作
  → 前端写本地（Room + 文件）
  → 乐观 UI
  → 异步上传
  → 后端存 + 建任务
  → worker 处理
  → 前端轮询
  → 前端写剪贴板
  → 更新本地
```

---

## 四、同步协议

**方向**：只推不拉。前端只推写操作，只拉列表刷新。后端不主动推。

**触发**：

| 动作 | 推 | 拉 |
|---|---|---|
| 录音结束 | `POST /transcribe` | 轮询 `GET /tasks/{id}` |
| 文本提交 | `POST /records` | 无 |
| 视频分享 | `POST /records` | 无 |
| 补一句 | `PATCH /records/{id}` | 无 |
| 打开 App / 下拉 | 无 | `GET /records` |
| 任务处理中 | 无 | 轮询 `GET /tasks/{id}` |

**范围**：单用户、单设备。不做多设备同步，不做增量同步。列表全量拉，分页。

**冲突解决**：last-write-wins，以后端接收时间为准。不做版本号，不做时间戳比较。极端乱序场景接受覆盖。

---

## 五、Token 生命周期

**形态**：静态配置。后端生成一次，写在 `config.py`。前端首次启动由用户手动填入。

**存储**：DataStore。明文可接受。如担心泄露，可换 `EncryptedSharedPreferences`，但非必须。

**初始状态**：
- 首次启动无 Token → 显示设置界面，功能全部禁用。
- 只留一个输入框 + 保存按钮。
- 保存后立即验证 `GET /api/v1/health`。
- 验证通过 → 进入主界面。
- 验证失败 → 提示"Token 无效"，留在设置界面。

**轮换**：手动。用户改后端配置 + 改前端配置。旧 Token 立即失效。前端收到 401 → 暂停队列 → 提示更新。

**长度**：≥ 32 字符。

---

## 六、容量边界

**假设**：

| 项 | 假设 |
|---|---|
| 用户数 | 1 |
| 设备数 | 1 |
| 年语音条数 | < 2000 |
| 年视频条数 | < 1000 |
| 年文本条数 | < 1000 |
| 单条音频上限 | 50MB / 15 分钟 |
| 年数据量（含音频文件） | < 10GB |

**排队**：转写串行（`Semaphore(1)`）。单次排队上限 5 条。

**排队超限展示**：`RecordDTO` 含 `queue_position` 字段。`0` 表示正在处理或已完成，`>0` 表示排队中，前端显示"排队中 · 第 N 位"。

**磁盘**：SQLite 单文件 10GB 以内可用。软删文件 7 天后清理，正常记录不自动清理。磁盘满由用户手动处理。

**超边界**：数据量 > 10GB → 换 Postgres + 对象存储。并发 > 1 → 换 Celery + Redis。本期不实现，只标注。

---

## 七、前端自愈

| 场景 | 行为 |
|---|---|
| App 启动，Room 有 `processing` | 扫出来，重挂轮询 |
| App 回前台，有 `processing` | 立即补一次状态查询 |
| 轮询超时（活跃 10 分钟） | 标记 `needsRepoll`，下次启动重挂 |
| 网络恢复 | 补发队列 + 补查状态 |
| Token 更新 | 恢复队列 |
| 上传失败 | 退避重试，上限 20 次 |

**前后端分工**：后端 worker 启动时重置 `processing` → `pending`；前端启动/回前台时扫本地 `processing` 重挂轮询。两层都做，才叫任务自愈。

---

## 八、组件边界

### 8.1 Android 组件

| 组件 | 职责 | 生命周期 |
|---|---|---|
| `RecordingService` | 录音 | 录音开始 → 结束 |
| `SyncService` | 上传 + 轮询 | 有任务 → 全部完成 |
| `MainActivity` | UI | App 可见 |
| `ShareReceiver` | 接收分享 | 分享触发 |
| `Room` | 本地缓存 | App 生命周期 |
| `WorkManager` | 可靠重试 | 系统调度 |

按 `foregroundServiceType` 拆分：录音 `microphone`，同步 `dataSync`。不合并。

### 8.2 SyncService 多任务并发

- 内部维护 `jobs: Map<taskId, Job>`。
- `onStartCommand` 用 `computeIfAbsent(taskId)` 幂等启动协程。
- 每个协程结束时从 `jobs` 移除自己。
- `jobs` 为空时才 `stopSelf()`。
- 支持 `CANCEL` action：删除记录时按 taskId 取消对应协程。

**不变量**：Service 的生命周期由 `jobs` 是否为空决定，不由单个任务决定。

### 8.3 后端组件

| 组件 | 职责 |
|---|---|
| FastAPI 接口层 | 收、存、建任务、返回 |
| worker | DB 轮询，执行任务 |
| SQLite | 主数据 |
| 本地文件 | 音频、WAV、封面 |
| FFmpeg | 转码 |
| faster-whisper | 转写 |

---

## 九、平台兼容基线

**目标平台**：
- 最低：Android 10（API 29）
- 目标：Android 14（API 34）
- 待验证：Android 15（API 35）

**已知约束**：

| 约束 | 应对 |
|---|---|
| Android 10+ 后台不能写剪贴板 | 前台自动，后台通知 |
| Android 12+ `startForeground` 5 秒限制 | 第一行调用 |
| Android 13+ 通知权限 | 录音前请求 |
| Android 14 前台服务类型强制 | 按类型拆分 |
| Android 15 dataSync 每日 6 小时 | 真机验证后再决定 |

**Android 15 降级方案**：如果 6 小时限制触发，改用 WorkManager 短周期轮询。代价：Doze 模式下轮询不准，息屏期间可能不轮询。只作为降级，不作为默认。

**原则**：不追求全版本兼容。新版本限制真机验证后再处理。

---

## 十、接口契约

### 10.1 统一响应

```json
{ "ok": true, "data": {...}, "error": null }
{ "ok": false, "data": null, "error": { "code": "XXX", "message": "..." } }
```

### 10.2 统一 DTO

所有返回 record 的接口，返回同一个 `RecordDTO`：

```json
{
  "record_id": "rec_...",
  "local_id": "loc_...",
  "type": "voice|video|text",
  "title": "",
  "content": "",
  "note": "",
  "url": null,
  "platform": null,
  "status": "pending|processing|done|failed",
  "error": null,
  "task_id": "task_...",
  "task_status": "pending|processing|done|failed|skipped|null",
  "queue_position": 0,
  "created_at": "ISO8601Z",
  "updated_at": "ISO8601Z"
}
```

### 10.3 接口清单

| # | 方法 | 路径 | 用途 |
|---|---|---|---|
| 1 | GET | `/api/v1/health` | 健康检查 |
| 2 | POST | `/api/v1/transcribe` | 语音转写 |
| 3 | POST | `/api/v1/records` | 文本 / 视频 |
| 4 | GET | `/api/v1/records` | 列表 |
| 5 | GET | `/api/v1/records/{id}` | 详情 |
| 6 | PATCH | `/api/v1/records/{id}` | 更新 note |
| 7 | DELETE | `/api/v1/records/{id}` | 软删 |
| 8 | GET | `/api/v1/tasks/{id}` | 任务状态 |
| 9 | POST | `/api/v1/tasks/{id}/retry` | 重试 |
| 10 | POST | `/api/v1/usage` | App 使用时长的**原始事件上报**（M9） |
| 11 | GET | `/api/v1/usage` | 按时段列"段"（M9） |
| 12 | GET | `/api/v1/usage/summary` | 按 App 汇总时长（M9） |
| 13 | POST | `/api/v1/usage/trigger` | 让手机立刻采一次（M9，走 WS 推 `trigger_usage`） |

> ⚠️ 第 7、9 条**路由尚未实现**（M5 的活）；第 10~13 条是 **M9**（2026-10-11）新增。
> 🔴 **第 10~13 条不返回 `RecordDTO`** —— 形状见下面 **§10.8**。

### 10.4 错误码

| code | HTTP | 通用行为 |
|---|---|---|
| `UNAUTHORIZED` | 401 | 暂停队列，提示改 token |
| `INVALID_PAYLOAD` | 400 | 标 failed，不重试 |
| `NOT_FOUND` | 404 | 标 failed，清本地 |
| `CONFLICT` | 409 | 上游状态冲突（Lattice 的确认框已过期等），刷新自愈，不展示技术细节 |
| `FEATURE_DISABLED` | 503 | 语音已停用（`CLIP_VOICE_ENABLED=0`），标 failed，不重试 |
| `LATTICE_UNAVAILABLE` | 503 | Lattice 未运行 / 数据口连不上，显示横幅并继续轮询（远端可达即自愈） |
| `SERVER_ERROR` | 5xx | 进队列，退避 |
| `NETWORK_UNREACHABLE` | — | 进队列，退避 |

### 10.5 错误传播

| 场景 | 401 | 5xx | 404 |
|---|---|---|---|
| 上传中 | 暂停队列，保留本地 | 退避重试 | 标 failed |
| 轮询中 | 暂停轮询，保留状态 | 退避重试 | 标 failed |
| PATCH 中 | 暂停队列 | 退避重试 | 从队列移除 |
| DELETE 中 | 暂停队列 | 退避重试 | 视为成功 |
| 列表刷新 | 提示用户 | 静默重试 | — |
| 健康检查 | 标记离线 | 标记离线 | — |

**`error.message` 不展示给用户。** 用户只看状态颜色。技术细节写日志。

### 10.6 状态机

| 场景 | record.status | task.status |
|---|---|---|
| 语音刚提交 | processing | pending |
| 语音转写中 | processing | processing |
| 语音完成 | done | done |
| 语音失败 | failed | failed |
| 文本 | done | 无 |
| 视频 | done | skipped |
| 软删 | deleted=1 | 不变 |

前端轮询只看 `task_status`。权威内容是 `record.content`。

### 10.7 契约级语义（五条）

1. `local_id` 幂等：所有写接口用 `local_id` 去重。命中返回真实状态，不重开任务。
2. `/transcribe` 立刻返回：不阻塞等转写。
3. `GET /tasks/{id}` 返回完整 `RecordDTO`：前端一次更新。
4. 软删对 `GET /records/{id}` 和 `PATCH` 返回 404。
5. 视频 `record.status=done`, `task.status=skipped`。

### 10.8 App 使用时长（M9，2026-10-11）

> 需求来自 `snapshot` 项目。**这一节是新增的，上面 §10.1~§10.7 一字未动**（不重编号，
> 因为别处的文档引用的是 `§10.3` / `§10.4` 这些编号）。

**分工的硬边界：手机只做「拉 / 存 / 发」，后端做全部计算。**

| 谁 | 做什么 | 不做什么 |
|---|---|---|
| **Android** | `UsageStatsManager.queryEvents` 拉原始事件 → 本地 Room 缓冲 → 批量上报 | ❌ 不配对、❌ 不算时长、❌ 不去重 |
| **后端** | 配对成"段" · 去重 · 覆盖 · 跨批续段 · 汇总 | — |

**为什么配对一定要在后端**：配对要跨批、要知道锁屏和换 App 的顺序 ——
手机上存不下那个状态（进程被杀、跨天、回溯 7 天都要求它一直在）。

**新增两张表**（同一个 `app.db`，**`records` / `tasks` 的列一个没动**）：

| 表 | 是什么 | 键 |
|---|---|---|
| `usage_records` | 配对好的段 | **唯一** `(device_id, package_name, start_time)` |
| `usage_pending` | 每设备**还没闭合**的当前段 | `device_id` 主键 |

**配对的四条边界**（写死在后端，`app/usage.py`）：

| 事件 | 动作 |
|---|---|
| `ACTIVITY_RESUMED(pkg)` | 关掉当前段，开新段 |
| `ACTIVITY_PAUSED(pkg)` | 仅当当前段就是该 pkg 才关；否则忽略 |
| `SCREEN_NON_INTERACTIVE` / `KEYGUARD_SHOWN` | 关掉当前段 |
| `SCREEN_INTERACTIVE` / `KEYGUARD_HIDDEN` | **不关** —— 熄屏再点亮回到的往往还是同一个 App，断段会把一段真实使用切成两截 |

**采集的三个触发**（对应不变量 6「用户不等处理」）：

| 触发 | 周期 | 说明 |
|---|---|---|
| WorkManager 周期任务 | **15 分钟** | 🔴 **这是 WorkManager 允许的最小周期**；Doze 下会被推迟 |
| App 启动 / 回前台 | — | 进程已活着，直接调，不绕 WorkManager |
| `POST /usage/trigger` → WS | 手动 | 🔴 **只在手机开着「远程」Tab 时有效**（那条 WS 的寿命） |

> 🔴 **15 分钟不是数据完整性的前提**：拉取区间永远是「上次扫到的时间 − 5 分钟 到 now」，
> 所以被推迟 3 小时也只是这一次多拉 3 小时的事件，**中间不会漏**。
> 那 5 分钟重叠是留给"正好跨在两次扫描之间"的段的 —— 宁可重复（两端都去重），不能漏。

**系统限制（不是本项目的缺陷）**：`queryEvents` 一般只保留最近 **1~7 天**。
App 8 天没跑，中间那段**拿不回来**。见 [`run.md`](run.md)。

---

## 十一、架构层与实现层的边界

| 归架构层 | 归实现层 |
|---|---|
| 组件划分 | 组件内部代码 |
| 数据流方向 | 具体协议细节 |
| 状态机 | 状态存储方式 |
| 接口形状 | 接口实现 |
| 同步协议 | 同步算法 |
| 冲突策略 | 冲突代码 |
| Token 形态 | Token 校验代码 |
| 容量边界 | 容量优化 |
| 平台兼容基线 | 平台适配代码 |
| 不变量 | 不变量检查代码 |
| 错误传播语义 | 错误处理代码 |

这份文档只写到架构层。开工顺序、验收标准、具体代码、平台细节实现、测试策略、部署步骤，不属于本文档。