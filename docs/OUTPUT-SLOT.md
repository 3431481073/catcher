# 输出槽：catcher 的对外产物

> 🔴 **本文件由 PM 侧 agent 起草，待项目自己确认。**
>
> **起草日 2026-10-04。** 依据：本项目 `docs\architecture.md`（**接口契约那一节最完整**）·
> `docs\{README,data,deps,service}.md` ＋ **PM 侧当场实测（只读打开数据库 / 列目录）**。
> 🔴 **两侧数字不一致的地方，本文以【实测】为准，并把旧值标出来** —— 实测发现好几处。
>
> **为什么要这份**：本项目**被 4 个别的项目依赖（6 条边）**，是全工程被依赖最广的两个之一。
> **但它从来没有一份「我的产物是什么」的对外声明。**
> **下游里已经有人在"猜"了** —— `video-panel` 的 `data.rs:316` 写着「读不到就返回空表」（**降级**），
> 而 `video-workbench` 是**响亮报错**。**同一条边，两个消费者判得不一样，因为没有声明可依。**
>
> 🔴 **2026-10-11 按 `D:\MyProject\CLAUDE.md` §8.4 改成新格式** ——
> 程序只读 `## 我往外给` 下面那张**两列表**，其余一律不管。**内容一字未删，只重新归位。**
> 🔴 **同日二次修**：按 §8.4 新增的**四条禁令**复核 → **补了一行**（`services.yaml`，禁令 1）。

## 我往外给

| 锚点 | 是什么 |
| --- | --- |
| 127.0.0.1:8000 | HTTP API（记录 / 语音 / 任务 / Lattice 远程转发 / **App 使用时长**）—— 统一 JSON 信封 + `X-Token` |
| D:\MyProject\catcher\backend\dist\data\app.db | 生产数据库（SQLite[WAL]）—— `records` / `tasks` / `events` / **`usage_records`** / **`usage_pending`** 五张表 |
| D:\MyProject\catcher\backend\dist\data\audio | 原始录音 / 分享音频（`.ogg` / `.webm`）—— **用户自己的声音** |
| D:\MyProject\catcher\backend\dist\data\wav | 转码产物（16k 单声道 WAV，FFmpeg 转的） |
| D:\MyProject\catcher\backend\tools\ffmpeg.exe | 🔴 **借出去的二进制**（三方共用，只引用路径、不复制不修改） |
| D:\MyProject\catcher\services.yaml | 服务声明 —— 常驻服务的命令 / 参数 / cwd / 端口 / 健康探针 / 托管关系（`service-manager` 按这个文件拉起本项目） |

> ⚠️ **两处不在表里、但真实存在的东西**（§8.4 的锚点只认三种形态：**绝对路径** / **`127.0.0.1:xxxx`** / **通配**）：
> · **公网入口 `catcher.xbywebwork.xyz`** —— 不是路径也不是回环端口（cloudflared 转发，**不属于本项目**）
> · **实际监听是 `0.0.0.0:8000`**（不是回环）—— 表里写 `127.0.0.1:8000` 是**本机消费者的取法**
> 两条都在 [`## 详情`](#一我放到哪) 里写全。
>
> 🔴 **2026-10-11 按 §8.4 的四条禁令复核**：
> · **禁令 1**（声明不许只写在 `## 详情` 里）—— 🔴 **补了一行 `services.yaml`**。
>   它原来**只写在详情里、表里没有 → 程序读不到 → 那条边当场丢**。
>   （`service-manager` 吃的正是各项目的 `services.yaml` —— §8.4 的通配例子写的就是 `D:\MyProject\*\services.yaml`。）
> · **禁令 2**（不许写到项目根 / 生态根）—— ✅ 六个锚点全是**具体的东西**（1 个端口 + 4 个文件 + 2 个目录），未改。
> · **禁令 3**（项目外路径统一用 `~`）—— ✅ 本表锚点**全在本项目内**，没有项目外路径，未改。
> · **禁令 4**（读什么写什么）—— ✅ `app.db` / `ffmpeg.exe` / `services.yaml` 是**文件**，
>   `audio` / `wav` 是**目录**，都按**实际形态**写，未改。
> · ⚠️ **故意没列 `backend\dist\backend.exe`**：它是**服务本体**，
>   被 `service-manager` **按 `services.yaml` 里的 `command` 拉起** —— 那是**控制流**（TRIGGER），
>   不是"别人读的数据"。§8 说的"二进制工具"指 `ffmpeg.exe` 那种**给别人用的**工具，两者不是一回事。

> 🔴 **2026-10-11 加了 App 使用时长（M9），锚点数【没有增加】—— 这是刻意的。**
> 新东西是 **`usage_records` / `usage_pending` 两张表**（住在**同一个** `app.db` 里）
> 和 **4 条 `/api/v1/usage*` 端点**（听在**同一个** `127.0.0.1:8000` 上）。
> §8.4 的锚点只认三种形态（**绝对路径 / 端口 / 通配**），**表名和路由都不是锚点** ——
> 所以我没有加重复锚点的新行，而是**把这两行原来的「是什么」改全**。
> **⇒ 锚点集合没变，变的是锚点后面的东西。** 两张表的字段见下面 §2.4。

---

## 详情

> 以下**程序不读**，供人 / agent 查阅。原 §一 ~ §八 的内容全部保留于此。

### 一、我放到哪

> **池子在哪：表名 / 文件路径 / 端口。**

🔴 **本项目对外产出【两类】东西，性质完全不同** —— 一类是数据，一类是**借出去的二进制**。

#### A. 数据产出（三处）

| # | 产物 | 池子在哪 | 形态 | 实测（2026-10-04） |
| ---: | --- | --- | --- | --- |
| **1** | 🔴 **HTTP API** | 端口 **`0.0.0.0:8000`** | FastAPI / JSON | **PID 2536 在听** |
| **2** | 🔴 **生产数据库** | `backend\dist\data\app.db` | SQLite[WAL] | **385,024 B**（mtime 2026-10-04 02:08:17） |
| **3** | **音频** | `backend\dist\data\audio\` · `wav\` | `.ogg` / `.webm` / `.wav` | **各 91 个文件** |

#### B. 🔴 另一类产出：**不是数据，是一个二进制**

| | |
| --- | --- |
| **产物** | **`backend\tools\ffmpeg.exe`** |
| **实测** | **102,856,192 B**（≈98.1 MB） |
| **SHA256** | `72A489ECCD008C2EC2C0A5856C5C75BC3D8BBFA90166C4566865C246445E6AA3`（`docs\deps.md` 实测） |
| **怎么被拿** | **只引用路径，不复制、不修改** —— 借二进制 |

> 🔴 **它为什么被单列出来**：它**不是一个"数据输出"** —— 它是构建产物里的一个工具，
> 而 `backend\dist\` 按惯例是"可以删"的目录。
> **把一条"别的项目按绝对路径借用我的构建产物"的边，写进输出槽，本身就是一件别扭的事。**
> 但**这条边是真实存在的、断掉会响的**，所以写下来，并把它标成"借出去的二进制"。
> **§8.4 的两列里没有这一格** —— 见 §七 未解决 第 1 条。

> ⚠️ **`ffmpeg.exe` 在本工程里有三份，字节完全相同**：
> `backend\tools\` · `backend\dist\tools\` · `_tools\ffmpeg\bin\`。
> **被外部借用的是 `backend\tools\` 那一份** —— 表里给的是这一条的锚点。

#### C. 服务声明（一个配置文件）

| | |
| --- | --- |
| **产物** | **`D:\MyProject\catcher\services.yaml`**（项目根下） |
| **形态** | YAML —— `services:` 一条：`catcher-backend` |
| **里面有什么** | `command`（`…\dist\backend.exe`）· `args` · `cwd` · `host: 0.0.0.0` · `port: 8000` · `health` · `manager: service-manager` · `logs` · `public_entry` · `persistent: true` |
| **谁读它** | **`service-manager`** —— §8.4 的通配例子写的就是 `D:\MyProject\*\services.yaml`（它吃**每个项目**的那一份） |
| **怎么被拿** | 读文件（YAML）。**不是**复制 |

> 🔴 **它为什么 2026-10-11 才进表**：原来只写在本文 §详情 的"谁启动它"那一行里，
> **表里没有 → 程序读不到 → 那条边当场丢**。这正是 §8.4 **禁令 1** 说的情况
> （实测：`catcher` / `Lattice` / `Note` / `service-manager` 四个项目的服务声明都写在详情里没进表，**4 条边当场丢**）。
> **注意区分**：「内容没丢」≠「声明没丢」—— 详情里写得再清楚，程序也不读。

#### 位置

| 项 | 值 |
| --- | --- |
| **监听** | 🔴 **`0.0.0.0:8000`**（不是回环 —— 因为有公网入口） |
| **公网入口** | 🔴 **`catcher.xbywebwork.xyz`** ← cloudflared → `localhost:8000` |
| **鉴权** | **`X-Token` 头**（静态配置，见 §三 边界四） |
| **谁启动它** | 🔴 **不在本项目里** —— `service-manager\config\projects\catcher.yaml`（`autoStart: true`） |
| **路径基准** | 数据路径由 `config.py` 的 `BASE_DIR` **推导**（打包态取 `sys.executable` 的目录）—— **代码里零硬编码绝对路径** |

---

### 二、放了什么

> **字段表：名字 / 类型 / 含义。**

#### 2.1 HTTP API —— 🔴 **21 条 HTTP + 1 条 WebSocket**

> 🔴 **本小节 2026-10-09 更新过。** 起草时（10-04）是 **9 个端点**；
> 之后本项目加了「**远程**」功能（手机在外面看/操作电脑上的 Claude Code），
> 新增 **8 条 HTTP + 1 条 WS**。**旧版只有 A 组，B 组是漏的。**
> 🔴 **2026-10-11 又加了 C 组（4 条 HTTP）** —— App 使用时长（M9），见 §2.4。

**A 组 · 记录 / 语音 / 任务（契约 9 条，**实测只实现了 7 条**）**

> 🔴 **2026-10-11 复核**：`backend\app\routes\` 下**没有** `DELETE /api/v1/records/{id}`，也**没有** `POST /api/v1/tasks/{id}/retry`。
> 下面两条标了 ⚠️。**这两条在 `docs\architecture.md` §10.3 的契约里有，但代码没写（M5 的活）。**

| # | 方法 | 路径 | 用途 |
| ---: | --- | --- | --- |
| 1 | **GET** | `/api/v1/health` | 健康检查（**也是 `service-manager` 的探针**） |
| 2 | **POST** | `/api/v1/transcribe` | 语音转写（**立刻返回，不等结果**） |
| 3 | **POST** | `/api/v1/records` | 文本 / 视频 / **语音**（`type` ∈ `text` / `video` / `voice`） |
| 4 | **GET** | `/api/v1/records` | 列表（全量拉，分页） |
| 5 | **GET** | `/api/v1/records/{id}` | 详情 |
| 6 | **PATCH** | `/api/v1/records/{id}` | 更新 note |
| 7 | **DELETE** | `/api/v1/records/{id}` | 软删（⚠️ **路由尚未实现**，M5） |
| 8 | **GET** | `/api/v1/tasks/{id}` | 任务状态（🔴 **返回完整 `RecordDTO`**） |
| 9 | **POST** | `/api/v1/tasks/{id}/retry` | 重试（⚠️ **路由尚未实现**，M5） |

**B 组 · 🔴 Lattice 远程转发（2026-10-05 新增；8 条 HTTP + 1 条 WS）**

| # | 方法 | 路径 | 用途 |
| ---: | --- | --- | --- |
| 10 | GET | `/api/v1/lattice/health` | Lattice 探活（带 `runId`） |
| 11 | GET | `/api/v1/lattice/panes` | 窗格列表 + 状态 |
| 12 | GET | `/api/v1/lattice/panes/{ref}/status` | 单窗格状态 |
| 13 | GET | `/api/v1/lattice/panes/{ref}/text` | 屏幕快照（`mode=screen\|tail\|all`） |
| 14 | GET | `/api/v1/lattice/panes/{ref}/last-turn` | 最后一轮汇总文本 |
| 15 | 🔴 POST | `/api/v1/lattice/panes/{ref}/input` | **写字 / 按键 —— 等于在电脑上敲键盘** |
| 16 | POST | `/api/v1/lattice/panes/{ref}/answer` | 替用户答确认框 |
| 17 | POST | `/api/v1/lattice/watch` | 声明"我要应答" |
| **W1** | 🔴 **WS** | `/api/v1/lattice/ws` | **事件流**（上游是 Lattice 的 SSE，对手机是 WS） |

**C 组 · 🔴 App 使用时长（2026-10-11 新增；4 条 HTTP）**

| # | 方法 | 路径 | 用途 |
| ---: | --- | --- | --- |
| 18 | POST | `/api/v1/usage` | **原始事件上报**（手机只发事件，配对在后端） |
| 19 | GET | `/api/v1/usage` | 按时段列"段"（`date` 或 `from`/`to`） |
| 20 | GET | `/api/v1/usage/summary` | 按 App 汇总总时长 |
| 21 | POST | `/api/v1/usage/trigger` | 推一条 WS `trigger_usage` 让手机立刻采一次 |

🔴 **C 组的形状与 A 组不同**：**不返回 `RecordDTO`**（那 16 个字段跟使用时长无关）。
`POST /usage` 回 `{accepted, duplicates, unknown}`；两个 GET 回 `{records:[…], from, to}`；
`trigger` 回 `{delivered}`（**0 = 手机没连着那条 WS**，不是错误）。
详见 [`architecture.md`](architecture.md) §10.8。

🔴 **B 组的性质：透明通道** —— 路径 / query / body 原样透传，外面套剪藏的信封 + `X-Token`。
**唯一一条"有脑子"的是 #15**（把 `enter` 拆成两次上游调用）。**上游是谁、它的假设是什么，见 [`INPUT-SLOT.md`](INPUT-SLOT.md)。**

⚠️ **B 组中间短暂存在过一条 SSE 转发路由（`/api/v1/lattice/events`），已删**
—— 实测它穿 Cloudflare Tunnel 会在 10~100 秒被掐断。**现在事件流走 #W1。**

**统一响应信封**：

```json
{ "ok": true,  "data": {...}, "error": null }
{ "ok": false, "data": null,  "error": { "code": "XXX", "message": "..." } }
```

**统一 DTO**（所有返回 record 的接口都是这一个 `RecordDTO`，**16 个字段**）：
`record_id` · `local_id` · `type` · `title` · `content` · `note` · `url` · `platform` · `tags` ·
`status` · `error` · `task_id` · `task_status` · `queue_position` · `created_at` · `updated_at`

**错误码（7 个）**：`UNAUTHORIZED`(401) · `INVALID_PAYLOAD`(400) · `NOT_FOUND`(404) ·
**`CONFLICT`(409)** · `FEATURE_DISABLED`(503) · **`LATTICE_UNAVAILABLE`(503)** · `SERVER_ERROR`(500)

> 🔴 **加粗那两个是 2026-10-05 之后新增的**（起草时只有 5 个）：
> **`CONFLICT`** = 上游状态冲突、**刷新即可自愈**（如 Lattice 的确认框过期）；
> **`LATTICE_UNAVAILABLE`** = Lattice 没在运行 / 数据口连不上。
> 两个都已写进 `docs\architecture.md` §10.4 和 `CLAUDE.md` §六 的错误码表。

> 🔴 **`FEATURE_DISABLED` 是活的** —— 老语音链路（`POST /transcribe`）已停用（`CLIP_VOICE_ENABLED=0`），
> `/transcribe` 现在返 503。**代码和历史数据全部保留，恢复只需给 YAML 加一行。**
> ⚠️ **注意与本机 ASR 无关**：手机端现在的语音是**本机识别**（sherpa-onnx，音频不出手机），
> 走的是 `POST /records` 的 `type=voice` 路径，**不经过 `/transcribe`**。

#### 2.2 `backend\dist\data\app.db`（实测 schema）

| 表 | 行数（实测） | 列 |
| --- | ---: | --- |
| **`records`** | **364**（2026-10-04） | `id, local_id, type, title, content, note, url, platform, status, error, raw_audio, wav_audio, analysis, deleted, created_at, updated_at, tags`（**17 列**） |
| **`tasks`** | **244**（2026-10-04） | `id, record_id, type, status, progress, result, error, created_at, updated_at`（**9 列**） |
| **`events`** | **54**（2026-10-04） | `id, record_id, event_type, created_at`（**4 列**） |
| **`usage_records`** | **0**（2026-10-11 建表） | `id, device_id, package_name, app_label, start_time, end_time, duration_seconds, created_at, updated_at`（**9 列**） |
| **`usage_pending`** | **0**（2026-10-11 建表） | `device_id, package_name, start_time, updated_at`（**4 列**） |
| `sqlite_sequence` | 1 | SQLite 内部表 |

> ⚠️ **上面是 2026-10-04 的快照。** 2026-10-11 复核：`records` **333 行**、`events` **225 行**
> （数字在漂，见 §七 第 3 条）。

> 🔴 **列的类型不在本文里** —— 数据库侧无版本表、无 schema 声明，
> 唯一的读法是对着库 `PRAGMA table_info` 反读（见 §四）。

🔴 **外部逐字引用这些列**（`docs\deps.md` 第一节）—— **改列名 = 断下游，而且是静默的**：

| 表 | 被外部逐字引用的列 |
| --- | --- |
| `records` | `id` · `type` · `title` · `platform` · `tags` · `status` · `deleted` |
| `tasks` | `id` · `record_id` · `type` · `status` |

#### 2.3 `audio\` / `wav\`

- `audio\` —— **91 个原始录音 / 分享音频**（88 `.ogg` + 3 `.webm`）
- `wav\` —— **91 个转码产物**（16k 单声道 WAV，FFmpeg 转的）

🔴 **它们是"用户自己的声音"，不是中间产物。**
⚠️ **2026-10-11 起有一类例外**：**手机本机 ASR 的记录不落音频**（`raw_audio` 为 NULL，音频不出手机），
所以 `audio\` 只会随**老的 `/transcribe` 链路**增长。

#### 2.4 🔴 App 使用时长（2026-10-11，M9）

**两张新表**（住在同一个 `app.db` 里，字段由 `backend\app\db.py` 的 `SCHEMA` 定义）：

| 表 | 字段 | 键 / 索引 |
| --- | --- | --- |
| **`usage_records`** | `id`(TEXT, `usage_`+uuid) · `device_id`(TEXT) · `package_name`(TEXT) · `app_label`(TEXT, 可空) · `start_time`(TEXT, ISO8601 UTC) · `end_time`(TEXT) · `duration_seconds`(INTEGER) · `created_at` · `updated_at` | 🔴 **唯一** `ux_usage_seg(device_id, package_name, start_time)`；另有 `idx_usage_start(start_time)` |
| **`usage_pending`** | `device_id`(TEXT) · `package_name`(TEXT) · `start_time`(TEXT) · `updated_at`(TEXT) | **主键** `device_id`（每设备最多一条 = 当前未闭合的段） |

🔴 **读它的三个要点**：

1. **`start_time` / `end_time` 是 ISO8601 UTC 文本**，格式与 `records.created_at` **完全相同**
   （`2026-10-10T02:00:00.000000Z`）。**定宽 ⇒ 可以直接当字符串比大小**，不用解析。
   ⚠️ **但"哪一天"是本地概念**：`date=2026-10-10` 指的是**服务器本地时区**的那一天。
2. **一条记录 = 一个"段"，不是一次事件**。手机上报的是**原始事件流**，段是后端配出来的。
3. **`package_name` 是包名**（`com.tencent.mm`），可读名在 `app_label` 里 ——
   **只有在手机上报的那一批里出现过的包名才有名字**。

🔴 **`usage_pending` 是"进行中的一段"，读它没有业务意义**（它就是"还没结束的当前段"）。
它唯一的存在理由是**跨批续段**：App 被杀留下的断档段，靠它活过两次上报之间。

---

### 三、怎么取

> **只读打开 / HTTP GET / 读文件** —— 按"池子"分。

#### 3.1 HTTP API —— HTTP GET/POST

| 项 | 值 |
| --- | --- |
| **前缀** | `/api/v1` |
| **鉴权** | 除 `/health` 外，全部校验 **`X-Token` 头** |
| **取法** | 公网 `https://catcher.xbywebwork.xyz/api/v1/...`，或直接 `http://<host>:8000/api/v1/...` |
| **响应** | 统一信封 + `RecordDTO` + 7 个错误码，见 §2.1 |

#### 3.2 生产数据库 —— 只读打开

**边界一 · 🔴 `app.db` 是活的，但它现在是【一致的快照】**

| 实测（2026-10-04） | |
| --- | --- |
| `app.db` | **385,024 B**，mtime **2026-10-04 02:08:17** |
| **`app.db-wal`** | 🔴 **0 B** |
| `app.db-shm` | 32,768 B |

🔴 **`-wal` = 0 字节 = 无待写事务** —— 所有写入都已 checkpoint 进主库。
**⇒ 此刻只复制 `app.db` 就是一份完整一致的快照。**
⚠️ **但这只对"此刻"成立** —— WAL 一有写入，这条就不成立。
**⇒ 要拿快照，先看 `-wal` 是不是 0 字节。**

> **判据的来源**：`video-workbench\PROGRESS.md` 用的是同一条判据。**两处独立记录一致。**

**边界二 · 🔴 `dist\` 不是"构建输出目录"，是【部署目录】**

`backend\dist\` 里同时住着：

1. **正在跑的 `backend.exe`**；
2. **活的生产数据库**（`data\app.db`）；
3. **运行期资源**（`.cache\` 模型 · `tools\ffmpeg.exe`）。

🔴 **删它 = 应用和用户数据一起没。**
**⇒ 它是本工程里"按名字看像垃圾、按内容看是心脏"的最典型的一个目录。**
（`build.bat` 只覆盖 `backend.exe` + `_internal\`，**不碰 `data\`** —— 这是刻意的。）

**边界三 · 🔴 `git status` 干净 ≠ 有备份**

本项目有 `.git`（工作区干净 / 已推 `origin`）—— **本工程 git 状态最健康的之一**。
**但用户数据一个字节都不在 git 里**：`.gitignore` 挡住了 `dist/` · `backend/data/` · `backend/backups/` · `backend/tools/*.exe`。

| 通道 | 覆盖到生产数据了吗 |
| --- | --- |
| `.git` | ❌ |
| `backend\backups\` | 🟠 只有一份 **2026-09-21** 的 `app.db.bak` |
| `_rollback\catcher_before_20261003\` | ✅ 332 文件 / 60.596 MB，**标了「永久保留」** |
| 项目外 | ❌ |

#### 3.3 音频 —— 读文件

`backend\dist\data\audio\` · `wav\`，按文件名直取（见 §2.3）。

#### 3.4 借出去的二进制 —— 只引用路径，不复制、不修改

**边界五 · 🔴 `backend\tools\ffmpeg.exe` 的位置已冻结**

**三份 `ffmpeg.exe` 字节完全相同**（`backend\tools\` · `backend\dist\tools\` · `_tools\ffmpeg\bin\`），
**但被借用的是 `backend\tools\` 那一份** —— 而它**被 `.gitignore` 挡着**（`backend/tools/*.exe`）。

🔴 **要动它，必须两个项目在同一轮里一起动、改完两边都验证**（`PLAYBOOK` §五 第 1 条）。

#### 3.5 边界四 · 🔴 **本项目有公网入口，而 `X-Token` 是明文默认值**

| 事实 | 实测 / 出处 |
| --- | --- |
| 公网入口 | `catcher.xbywebwork.xyz` → cloudflared → `localhost:8000` |
| Token | `backend\app\config.py`：`TOKEN = os.environ.get("CLIP_TOKEN", "dev-token-...")` —— **明文，且在 git 里，且已推到 GitHub** |
| 改动代价 | `catcher.yaml` L38 原文：「**`CLIP_TOKEN` 必须保持默认值**，否则手机端配对的 token 会失效」 |

🔴 **⇒ 改它 = 手机端立刻 401，必须手工去手机上重配。**
**⇒ 这三件事合起来决定了"哪些数据算敏感"比一个纯本地项目要紧。**

---

### 四、版本

| 项 | 值 |
| --- | --- |
| **槽的格式版本** | 🔴 **无** |
| **HTTP 侧** | 路径里有 `/api/v1/` —— **这是事实上的版本号**，但**不是协商机制**（没有 `Accept` 版本头、没有版本发现） |
| **数据库侧** | 🔴 **无版本表、无版本列** —— schema 只能靠 `PRAGMA table_info` 反读 |
| **后果** | 消费者**无法自证**"我读到的是哪一版"，**上游改 schema 时下游不会知道** |

---

### 五、消费者

> ⚠️ **本表按 §8 是**"关系"，**不是输出槽字段** —— 输出槽本身（§一~§三）**不写这一格**。
> 留在这里供人查阅；**权威来源在 `project_manager\MAP.md`**。

| # | 消费者 | 消费什么 | 引用位置 | 断了怎样 |
| ---: | --- | --- | --- | --- |
| **1** | **`video-workbench`** | `dist\data\app.db`（**只读**） | `vw\config.py:9` | 🔴 **响亮** |
| **2** | **`video-workbench`** | `backend\tools\ffmpeg.exe` | `vw\config.py:19` | 🔴 响亮 |
| **3** | **`video-panel`** | `dist\data\app.db`（**只读**，可用 `CATCHER_DB` 覆盖） | `src-tauri\src\data.rs:53` | 🟢 **降级**（`data.rs:316` 原文：「读不到就返回空表，不报错」） |
| **4** | **`video-panel`** | `backend\tools\ffmpeg.exe` | `src-tauri\src\data.rs:60` | 🔴 响亮 |
| **5** | **`VideoKnowledgeBase`** | `backend\tools\ffmpeg.exe` | `src\media.py:114` | 🟠 **半静默**（`find_ffmpeg()` 候选链**最后一级**，返回 `None` 后调用方报错，**但不会说"是 catcher 那边没了"**） |
| **6** | **`energy-recovery`** | 🔴 **端口 8000** | `docs\run.md:55` · `services.yaml:7` | 🟠 **冲突** —— 它想用 8000，**两者不能同时跑** |
| **7** | 🔴 **本项目自己的安卓端** | **B 组 8 条 HTTP + 1 条 WS**（`/api/v1/lattice/*`） | `android\...\data\api\ApiService.kt` · `ui\remote\*` | 🟠 **只坏"远程"Tab** —— 记录 / 语音 / 文本三个入口**不受影响**。<br>上游（Lattice）断了会**响亮报错**：横幅「后端连不上电脑上的 Lattice：…」 |

#### 🔴 三条"不是项目 ↔ 项目"的边

| 边 | 内容 |
| --- | --- |
| **`service-manager` → 本项目** | **托管者** —— 它全权负责启停 / 健康探测 / 重启 / 日志。**本项目的"自启"不在本项目里。** |
| **公网 → 本项目** | cloudflared |
| **本项目 → 自己的安卓端** | `android\...\ApiConfig.kt` 的 `BASE_URL = "https://catcher.xbywebwork.xyz/"` —— 🔴 **改后端地址要同时改这里** |

> ⚠️ **「除这几家没有别的依赖方」是【推断】，不是穷举证明。** 依据是依赖登记册 + 定向 grep。

---

### 六、`v1` 落地状态

🔴 **本文件是【第一次】给本项目写输出槽（2026-10-04）。**
**⇒ 状态：未落地。** 没有代码读它，它现在**纯粹是给人 / agent 看的声明**。

**但本项目的接口本身是落地的** —— 端点有实现、有错误码表、有 DTO、有状态机，
**这些都写在 `docs\architecture.md` §10**（那份写得极完整）。**本文不重复它，只指过去。**

---

### 七、未解决

| # | 事项 | 说明 |
| ---: | --- | --- |
| **1** | 🔴 **"借出去一个二进制"该算哪一类接口？** | `backend\tools\ffmpeg.exe` 被 3 个项目借用。**它不是数据输出，也不在 `_shared\` 的模型表里。** §8.4 的两列**表达不了"这条边是借的、不是给的"**。<br>🔴 **更根本的**：`_tools\ffmpeg\bin\ffmpeg.exe` 是**生态自己的** ffmpeg，`VideoKnowledgeBase\media.py:112` **两个都在用**（`_tools\` 是候选链上一级）。**⇒ 这条依赖本来就不该指向 `catcher`。** 但那是清理，不是声明。 |
| **2** | 🔴 **同一条边，两个消费者判得不一样** | `video-workbench` **响亮报错**；`video-panel` **静默降级**。**上游没有声明"读不到算什么"，所以两边各猜了一个。** |
| **3** | 🔴 **数字在漂** | 起草时实测 **`records` 364 行 / `tasks` 244 行 / `events` 54 行**；2026-10-11 复核 **`records` 333 行 / `events` 225 行**。**`app.db` 也是活的。** *（数据是活的，文档是快照）* |
| **4** | 🔴 **改了列名 = 静默断两个项目** | `docs\deps.md` 已经写清楚，但**没有任何机制在改 schema 时提醒**。 |
| **5** | **消费者清单不是穷举的** | 见 §五 的注。 |
| **6** | **`energy-recovery` 与 8000 的冲突没有归属** | 是它改，还是本项目改？**两边都没写**。 |

---

### 八、与 `INTERFACES.md` 对应

| `INTERFACES.md` | 本文 |
| --- | --- |
| **§二 服务托管** | 本项目**是托管接口的现行样本** —— `services.yaml` → `pm adopt` → `service-manager\config\projects\catcher.yaml`。**这是全工程唯一一条真跑通的接口链。** |
| **§四 输出槽** | **本文的格式来源** —— 现按 §8.4 收敛成**两列锚点表**（**"借出去的二进制"一处对不上，见 §七 第 1 条**） |
| **§十一 约束一**（项目之间不通过代码耦合） | 🔴 **本项目有 4 条按【路径】依赖别人的边。** 它自己**不引用任何别的 MyProject 项目**（`docs\deps.md` 实测：后端源码零命中），**但它是被引用方。** |
| **§十一 约束二**（必须声明输入和输出） | 🔴 **本文是它欠了很久的那份声明。** |
| **§六 共享** | 🔴 **`ffmpeg.exe` 不在 `_shared\` 里，而在本项目里** —— `_shared\` 只放模型。**⇒ 一个被三方共用的二进制，住在某一个项目里。** |
| **§4.2 判据**（消费者能不能不读源码就理解输出） | 🟡 **API 侧过得很好**（`architecture.md` §10 完整）；**数据库侧不过** —— 消费者得读 `deps.md` 才知道哪些列能用。 |

---

> **本文件由 PM 侧 agent 起草，待项目自己确认。**
> 🔴 **数字全部带日期。`app.db` 是活的，一两天就会漂。**
> **2026-10-11 只重排格式（§8.4），事实与日期未动。**
