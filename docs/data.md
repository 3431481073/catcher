# 数据在哪、怎么流、有没有备份

> 🔴 **本文件是六件套里最要紧的两份之一。** 整理本项目**第一件该读的就是它**。

---

## 一、🔴 最重要的一句：这个项目的数据**全在项目目录内**，而**它们一处 git 副本都没有**

**`catcher` 的 git 状态是本工程最健康的之一** ——
8 个 commit、工作区干净（0 未跟踪）、已推 `origin`、有 MIT LICENSE。

**但它的不可再生数据，全部被 `.gitignore` 挡在 git 外面。**

> 🔴 **`git status` 干净 ≠ 有备份。** 干净只说明"没有未提交的改动"，
> **不代表"数据被管着"** —— 被 ignore 掉的东西**根本不会出现在 `git status` 里**。

---

## 二、`.gitignore` 挡住了什么（逐条）

`catcher\.gitignore`（549 B / 28 行，**本次一字未改**）：

```
# ---- Python ----
__pycache__/  *.py[cod]  .venv/  .pytest_cache/  .cache/          ← 🔴 模型缓存

# ---- 构建产物 ----
build/  dist/  .build_onedir/  dist_onefile_backup/               ← 🔴 🔴 部署目录（含活库）
backend/tools/ffmpeg-*/  backend/tools/ffmpeg.zip  backend/tools/*.exe   ← 🔴 三方在借的 ffmpeg

# ---- 运行数据（数据库、音频、日志）----
backend/data/  backend/backups/  backend/*.log  *.err.log  *.out.log    ← 🔴 🔴 生产数据

# ---- Android / Gradle ----
.gradle/  android/.gradle/  android/**/build/  android/local.properties  *.aar
.tmp_aar/  .tmp_foundation/  .tools/
backend/test.webm
```

**→ 逐条问"这条挡住的是什么"，答案见下表。**

| git 里**有** | git 里**没有** |
| --- | --- |
| `backend\app\`（12 个 `.py`）· `launcher.py` · `build.bat` · `build.spec` · `requirements.txt` · `pytest.ini` | 🔴 **`backend\dist\data\app.db`（352 条记录）** |
| `android\` 全部源码（65 文件） | 🔴 **`backend\dist\data\audio\`（91 段原始音频）** |
| `README.md` · `LICENSE` · `CLAUDE.md` · `PROGRESS.md` · `docs\` | 🔴 **`backend\dist\data\wav\`（91 段转码）** |
| `.gitignore` | 🔴 **`backend\tools\ffmpeg.exe`**（**三个项目在借**） |
| | 🔴 `backend\.cache\` · `dist\.cache\`（模型）· `backend\data\`（开发态库）· `backend\backups\` |

---

## 三、🔴 生产数据库：`backend\dist\data\app.db`

| 项 | 值 |
| --- | --- |
| **路径** | `D:\MyProject\catcher\backend\dist\data\app.db` |
| **大小** | **364,544 B** |
| **实测 SHA256** | `0E68A64156D8ADF098A03719953CE016B0FB1DBA5C9E7905C113CB88AEA27C97` |
| **最后写入** | **2026-10-03 04:00:44** |
| **是不是活的** | 🔴 **是。** `app.db-shm`（32,768 B）与 `app.db-wal`（**0 B**）的 mtime 是 06:28 —— **说明进程正持有它** |

**实测 schema 与内容（`mode=ro` 只读打开）**：

```sql
records (id, local_id, type, title, content, note, url, platform, status, error,
         raw_audio, wav_audio, analysis, deleted, created_at, updated_at, tags)   352 行
tasks   (id, record_id, type, status, progress, result, error,
         created_at, updated_at)                                                  233 行
events  (id, record_id, event_type, created_at)                                    42 行
```

> 🔴 **`app.db-wal` = 0 字节 = 无待写事务** —— 所有写入都已 checkpoint 进主库。
> **所以只复制 `app.db` 就是一份完整一致的快照。**
> （判据来源：`video-workbench\PROGRESS.md` 用的是同一条。）

### 另有两份 `app.db`，**都不是活的**

| 路径 | 大小 | 时间 | 是什么 |
| --- | --- | --- | --- |
| `backend\data\app.db` | 57,344 B | 2026-09-26 | **开发态库**（`uvicorn app.main:app` 时写这个） |
| `backend\backups\app.db.2026-09-21_230846.bak` | 323,584 B | 2026-09-21 | 🔴 **唯一一份备份，而且是 9/21 的** |

> ⚠️ **这两份都在 git 里吗？** —— **不在**（`backend/data/` 和 `backend/backups/` 都被 ignore）。

---

## 四、数据怎么从安卓流到后端

```
手机（Kotlin）
  ├─ 用户动作：刷 / 说 / 转 / 贴
  ├─ 先写本地 Room（乐观 UI）
  └─ 异步上传
        │  HTTPS + X-Token
        ▼
  Cloudflare Tunnel  →  catcher.xbywebwork.xyz
        │
        ▼
  localhost:8000  →  FastAPI（X-Token 中间件）
        ├─ POST /api/v1/records   或   POST /api/v1/transcribe
        ├─ 落 SQLite[WAL] + 建 task
        └─ worker 线程领 task（转码 FFmpeg → 16k 单声道 WAV）
        │
        ▼
  手机轮询 GET /api/v1/tasks/{id}
        │
        ▼
  手机写剪贴板  →  更新本地 Room
```

**架构不变量第 1 条（`architecture.md` 原文）**：

> **后端是唯一权威源，前端是缓存 + 采集器。**

🔴 **→ 所以 Android 端不持有任何需要备份的东西 —— 它是纯采集器 + 缓存。**

---

## 五、🔴 要保护的用户数据清单（全在 `dist\data\`）

| 项 | 量 | 别处有吗 |
| --- | --- | --- |
| **`app.db`** | 364,544 B / **352 条记录**（用户自己的语音、视频、文本） | ❌ |
| **`audio\`** | **91 文件 / 8,437 KB**（88 `.ogg` + 3 `.webm`，原始录音/分享音频） | ❌ |
| **`wav\`** | **91 文件 / 50,986 KB**（转码后 WAV，16k 单声道） | ❌ |
| `_inspect_report.json` | 241,837 B | ❌ —— 🔴 **见下节** |
| `backend.log` | 218,972 B | —— 日志，可弃 |
| **合计** | **约 58.8 MB** | **零副本** |

---

## 六、🔴 `_inspect_report.json` —— 含用户原文的一次性导出

| 项 | 值 |
| --- | --- |
| **原位置** | `backend\dist\data\_inspect_report.json` |
| **现位置** | 🔴 **`catcher\misc\_inspect_report.json`**（**本轮搬走**） |
| **大小** | **241,837 B** |
| **SHA256** | `462C5E5A4190AD080BF30AD16BDBD9F52E7A7E20006849ACF309ACE64176EBD9` |
| **生成时间** | 2026-09-22 10:20:44 |
| **内容** | 🔴 **`records` 表的一次性 JSON 导出** —— 样本首条 `content` = `"自开的马克风"` |

⚠️ **生成它的代码在全库 grep 不到**（推测是打包前的一个临时脚本，已删）。
**→ 一个含用户数据的一次性导出，原先静静躺在生产数据目录里。**
**→ 本轮的处置：搬进 `misc\`（不改内容），并在这里点名它含用户原文。**

---

## 七、🔴 备份现状总结

| 通道 | 覆盖到生产数据了吗 |
| --- | --- |
| **`.git`** | ❌ **`dist/`、`backend/data/`、`backend/backups/` 全被 `.gitignore` 挡住** |
| **`backend\backups\`** | 🟠 **只有一份 9/21 的 `app.db.bak`**（323,584 B）—— **比现在的库旧了 12 天** |
| **`_rollback`（本轮新建）** | ✅ **`catcher_before_20261003\backend\dist\data\` —— 332 文件 / 60.596 MB，已标「永久保留」** |
| **项目外** | ❌ 没有 |

**→ 本轮整理之前：生产数据零备份。本轮之后：有一份，且被标成永久保留。**

> 详见 `D:\MyProject\_rollback\README.md` 的 `catcher_before_20261003\` 专节。

---

## 八、模型缓存：两份，各自自足

| 位置 | 大小 | 内容 |
| --- | --- | --- |
| `backend\.cache\huggingface\hub\` | **1,923 MB** | **medium（1,457 MB）+ small（461 MB）** |
| `backend\dist\.cache\huggingface\hub\` | **1,460 MB** | **medium（1,457 MB）** —— **运行期用这份** |

**🔴 实测：`dist\.cache` 里的 `snapshots\` 是【相对 symlink】，指向**它自己的** `blobs\`**：

```
snapshots\08e178d4…\model.bin  ->  ..\..\blobs\9b45e1009dcc4ab601eff815b61d80e60ce3fd8c74c1a14f4a282258286b51ae
```

**→ 两份各自自足，谁都不依赖谁。**（`copy_model.py` 用 `shutil.copytree(symlinks=True)` 复制，
保留的是相对链接，所以复制品自足。）

**⚠️ `small`（461 MB）当前用不到** —— `config.py` 默认 `WHISPER_MODEL = "medium"`，
且**语音功能已停用**。`build.bat` 的注释写着「清掉旧 `.cache`（含 small）」
—— **但它只清 `dist\.cache`，没清 `backend\.cache`**，所以 small 留在了开发侧。

> 🔴 **能不能删 `backend\.cache` 里的 small？** **理论可以，但 `CLIP_WHISPER_MODEL` 可以用环境变量覆盖**
> —— **"当前配置下用不到"不等于"永远用不到"**。**本轮未删，列进未解决。**

**模型路由**：对 `_shared\models\` 与 `_shared\routes.yaml` **零贡献**（**它用的是 faster-whisper 的 HF 格式，与 `_shared` 无关**）。

---

## 九、Web 服务 / 数据口

| 项 | 值 |
| --- | --- |
| **后端 HTTP** | `0.0.0.0:8000`（**实测 Listen，PID 2536**） |
| **健康探针** | `GET /api/v1/health` |
| **公网入口** | 🔴 **`catcher.xbywebwork.xyz`** ← cloudflared（见 [`service.md`](service.md)） |
| **鉴权** | **X-Token** —— 静态配置，值在 `backend\app\config.py` 里 |

---

## 十、⚠️ 为什么**不建** `data\`

**四区模型里的 `data\{input,output,store,cache,logs}` 本项目【不建】。**

**理由：数据已经有三处语义清晰的落点，再建一个只会让人分不清哪个是活的。**

| 已有落点 | 是什么 |
| --- | --- |
| **`backend\dist\data\`** | 🔴 **生产数据**（活库 + 音频） |
| `backend\data\` | 开发态数据（陈旧） |
| `backend\backups\` | 备份（只有一份 9/21 的） |
| `backend\.cache\` · `dist\.cache\` | 模型缓存（可再生，**不是数据**） |

**→ 用户 2026-10-03 拍板：不建 `data\`。**
**→ 但 `misc\` 建了**（放 `_inspect_report.json`）。
