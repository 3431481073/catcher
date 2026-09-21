# 手机剪藏 · Cloud Code 操作手册

这份文件是最高约束。所有代码必须服从这里的不变量和契约。
架构文档是宪法，这份文件是施工手册。

---

## 一、项目概述

一个安卓端的信息捕获器。

用户只管：刷、说、转、贴。
系统负责：存、转写、分析、找回。

三个入口：
- 语音：点 → 说 → 粘贴。
- 视频：转发 → 可选补一句 → 结束。
- 文本：复制 → 粘贴 → 存。

一个出口：剪贴板（前台自动，后台通知点击）。

原则：不确认、不选标签、不等处理、不管技术。

---

## 二、架构不变量（不可违反）

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

## 三、技术栈

**后端**
- Python 3.12
- FastAPI
- SQLite（WAL 模式）
- FFmpeg
- faster-whisper
- uvicorn

**Android**
- Kotlin
- Jetpack Compose
- Room
- Retrofit + OkHttp
- DataStore
- WorkManager（只做上传重试）
- 前台服务（录音 / 同步）
- Kotlin Coroutines + Flow

---

## 四、目录结构

```
project/
  backend/
    app/
      main.py
      config.py
      db.py
      models.py
      schemas.py
      auth.py
      audio.py
      transcribe.py
      worker.py
      routes/
        health.py
        records.py
        tasks.py
    data/
      app.db
      audio/
      wav/
    tests/
    requirements.txt
  android/
    app/
      src/main/java/com/example/clip/
        ui/
        data/
          db/
          api/
          repository/
        domain/
        service/
        recorder/
        receiver/
        util/
      src/main/AndroidManifest.xml
    build.gradle.kts
  docs/
    architecture.md
  CLAUDE.md
```

---

## 五、数据模型（SQLite）

### records

```sql
CREATE TABLE records (
  id          TEXT PRIMARY KEY,
  local_id    TEXT UNIQUE NOT NULL,
  type        TEXT NOT NULL,           -- voice / video / text
  title       TEXT,
  content     TEXT,
  note        TEXT,
  url         TEXT,
  platform    TEXT,
  status      TEXT NOT NULL DEFAULT 'pending',
                                       -- pending / processing / done / failed
  error       TEXT,
  raw_audio   TEXT,
  wav_audio   TEXT,
  analysis    TEXT,
  deleted     INTEGER NOT NULL DEFAULT 0,
  created_at  TEXT NOT NULL,
  updated_at  TEXT NOT NULL
);

CREATE INDEX idx_records_created ON records(created_at DESC);
CREATE INDEX idx_records_status ON records(status);
```

### tasks

```sql
CREATE TABLE tasks (
  id          TEXT PRIMARY KEY,
  record_id   TEXT NOT NULL,
  type        TEXT NOT NULL,           -- transcribe / video_analyze
  status      TEXT NOT NULL DEFAULT 'pending',
                                       -- pending / processing / done / failed / skipped
  progress    INTEGER NOT NULL DEFAULT 0,
  result      TEXT,
  error       TEXT,
  created_at  TEXT NOT NULL,
  updated_at  TEXT NOT NULL
);

CREATE INDEX idx_tasks_status ON tasks(status);
CREATE INDEX idx_tasks_record ON tasks(record_id);
```

### 初始化

```sql
PRAGMA journal_mode=WAL;
PRAGMA synchronous=NORMAL;
PRAGMA busy_timeout=5000;
```

---

## 六、API 契约

### 前缀

所有接口 `/api/v1`。除 `/health` 外，全部校验 `X-Token`。

### 统一响应

```json
{ "ok": true, "data": {...}, "error": null }
{ "ok": false, "data": null, "error": { "code": "XXX", "message": "..." } }
```

### RecordDTO

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

### 错误码

| code | HTTP | 说明 |
|---|---|---|
| `UNAUTHORIZED` | 401 | Token 无效 |
| `INVALID_PAYLOAD` | 400 | 请求格式错 |
| `NOT_FOUND` | 404 | 资源不存在 |
| `SERVER_ERROR` | 500 | 服务端错误 |

### 接口清单

| # | 方法 | 路径 | 里程碑 |
|---|---|---|---|
| 1 | GET | `/api/v1/health` | M1 |
| 2 | POST | `/api/v1/transcribe` | M1 |
| 3 | POST | `/api/v1/records` | M3 |
| 4 | GET | `/api/v1/records` | M1 |
| 5 | GET | `/api/v1/records/{id}` | M3 |
| 6 | PATCH | `/api/v1/records/{id}` | M3 |
| 7 | DELETE | `/api/v1/records/{id}` | M5 |
| 8 | GET | `/api/v1/tasks/{id}` | M1 |
| 9 | POST | `/api/v1/tasks/{id}/retry` | M5 |

### 状态机

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

### 契约级语义（五条）

1. `local_id` 幂等：所有写接口用 `local_id` 去重。命中返回真实状态，不重开任务。
2. `/transcribe` 立刻返回：不阻塞等转写。
3. `GET /tasks/{id}` 返回完整 `RecordDTO`：前端一次更新。
4. 软删对 `GET /records/{id}` 和 `PATCH` 返回 404。
5. 视频 `record.status=done`, `task.status=skipped`。

---

## 七、里程碑

**一次只做一个里程碑。先计划，后实现，再验证。不通过不进入下一个。**

### M0：Android UI 骨架（当前里程碑）

**目标**：装上 App，能看到完整 UI，日志列表里有假记录。

**做**：
- Compose 骨架
- 悬浮球（手势完整：短按切面板、长按录音、录音中点击结束）
- 日志列表（状态颜色：灰点 / 蓝旋 / 蓝脉 / 绿 / 红 / 紫）
- 面板（说话、提交文本两个按钮都在）
- 详情面板
- 底部面板
- Room 三张表建好（records / blobs / queue）
- Repository 从 Room 读
- 预置 5 条假数据（voice / video / text，各种状态都有）

**不做**：
- 真实录音
- 真实上传
- 任何后端接口
- Token 设置页

**关键约束**：
- **UI 从 Room 的 Flow 读，不写死假数据。**
- Repository 早期实现返回预置数据，接口签名按真实设计。
- 接后端时只换 Repository 实现，UI 一行不动。

**验收**：
- `./gradlew assembleDebug` 通过。
- 安装到手机，看到日志列表有 5 条假记录。
- 短按悬浮球弹出面板，长按进入录音状态（假），录音中点击结束。
- 点日志项弹出详情面板。
- 点“提交文本”弹出底部面板。

---

### M1：后端可独立验证

**目标**：curl 能跑通 `/transcribe`。

**做**：
- FastAPI 骨架
- SQLite WAL + 表
- X-Token 中间件
- 统一响应 + 错误码
- `POST /transcribe` + FFmpeg + faster-whisper
- worker + task 状态
- `GET /tasks/{id}`
- `GET /records`
- `GET /health`

**验收**：
```bash
# 启动
uvicorn app.main:app --host 0.0.0.0 --port 8000

# 健康检查
curl -H "X-Token: <token>" http://localhost:8000/api/v1/health

# 上传音频
curl -H "X-Token: <token>" \
  -F "audio=@test.webm" \
  -F "local_id=test1" \
  http://localhost:8000/api/v1/transcribe

# 拿结果
curl -H "X-Token: <token>" \
  http://localhost:8000/api/v1/tasks/<task_id>
```

---

### M2：Android 语音端到端

**目标**：对着手机说一句话，粘贴出来是刚才那句话。

**做**：
- Token 设置页
- 录音（MediaRecorder，Opus 优先）
- RecordingService
- 上传（Retrofit + X-Token）
- SyncService（单任务版）
- 轮询
- 剪贴板写入（前台自动）
- Repository 从“返回假数据”换成“调后端”

**关键**：UI 不动，只换 Repository 实现。

**验收**：
- 手测录音 → 日志出现 → 转写完成 → 粘贴到微信。
- 粘贴出来的是刚才说的话。

---

### M3：文本 + 视频入口

**目标**：粘贴文本能存，分享视频能存。

**做**：
- 文本提交（`POST /records`）
- 视频分享接收（ACTION_SEND）
- 补一句（`PATCH`）
- 详情面板拉 `GET /records/{id}`

---

### M4：无感

**目标**：断网录音，重连后自动补上。

**做**：
- 本地队列
- 退避重试
- App 启动扫 `processing` 重挂
- 回前台补查
- 401 暂停队列
- 后台剪贴板通知
- 权限请求与降级

---

### M5：打磨

**做**：
- SyncService 多任务并发
- 通知渠道
- 返回键处理
- 删除取消轮询
- 分页合并
- 软删、`PATCH` 404
- `POST /tasks/{id}/retry`

---

### M6：工作台集成

**做**：
- PyInstaller 打包 `backend.exe`
- 工作台 sidecar
- 状态面板

---

## 八、硬规则

1. **架构文档是最高约束，不得擅自改。**
2. **所有写接口必须 `local_id` 幂等。**
3. **耗时任务必须异步 + 轮询，接口立刻返回。**
4. **视频分析不实现，`task_status=skipped`。**
5. **不做多设备、不做增量同步、不做工作台（M6 之前）。**
6. **一次只做一个里程碑，先计划，后实现，再测试。**
7. **UI 从 Room 读，不写死假数据。**
8. **接后端时只换 Repository 实现，UI 不动。**
9. **偏离文档要说明原因。**
10. **不通过验收不进入下一个里程碑。**

---

## 九、工作流

### 第一轮（只读，不写）

```
先不要写代码。
请阅读 CLAUDE.md、docs/architecture.md。
然后输出：
1. 你理解的模块边界和不变量。
2. 你认为文档里还有歧义的地方。
3. 建议的目录结构。
我确认后你再开始。
```

### 之后每轮

```
本轮只做 Mx。
不要做 Mx+1。
先给实现计划，我确认后你写代码。
完成后：
1. 运行验收命令并贴出结果。
2. 总结改了什么。
3. 如果偏离了文档，说明原因。
```

---

## 十、完成定义

**后端里程碑**：
- pytest 通过
- uvicorn 可启动
- curl 验收命令通过

**Android 里程碑**：
- `./gradlew assembleDebug` 通过
- 手动场景按验收标准通过

---

## 十一、禁止事项

- 不引入 Redis、Celery、Postgres、MinIO（本期不用）
- 不做多设备同步
- 不做增量同步
- 不做视频分析（只占位）
- 不做工作台（M6 之前）
- 不写死假数据在 UI 层
- 不在一个里程碑里做下一个里程碑的事
- 不擅自改架构文档

---

## 十二、当前状态

- 里程碑：**M0**
- 上一步：无
- 下一步：等 Cloud Code 输出计划

---

## 十三、给 Cloud Code 的第一句话

> 先不要写代码。请阅读本文件和 `docs/architecture.md`，输出你理解的模块边界、不变量、歧义点和建议的目录结构。我确认后你再开始 M0。