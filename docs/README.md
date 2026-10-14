# catcher —— 安卓信息捕获器

> **整理于 2026-10-03**（第十六站）。**2026-10-09 更新过一次**（补两个槽 + 远程功能）。
> **代码零改动。**
> **这是一个「被四方依赖」的项目** —— 读本文之前先看下面三条最要紧的事实。

---

## 一、它是什么

**你只管刷、说、转、贴；系统负责存、转写、分析、找回。**

三个入口：**语音**（点→说→粘贴）· **视频**（转发→可选补一句→结束）· **文本**（复制→粘贴→存）。
一个出口：**剪贴板**（前台自动，后台通知点击）。
一个原则：**不确认、不选标签、不等处理、不管技术。**

> ⚠️ **语音功能已于 2026-09-26 停用**（`CLIP_VOICE_ENABLED=0`），**代码与历史数据全部保留**。
> 恢复只需给服务管理器 YAML 加一行 `CLIP_VOICE_ENABLED: "1"`，不必重新打包。

### 🔴 但它已经不只是一个「捕获器」了 —— 它还有一个「远程」Tab

**2026-10-05 起，App 里多了第二个 Tab：「远程」。**

```
手机（4G）→ Cloudflare Tunnel → catcher 后端 → 127.0.0.1:<Lattice 端口> → Lattice
```

**用来看 / 操作电脑上正在跑的 Claude Code**：看输出、发指令、切窗格、答确认框。
**这是本项目唯一一条「向外吃数据」的边**（吃 Lattice）—— 见 [`INPUT-SLOT.md`](INPUT-SLOT.md)。

| 事实 | 值 |
| --- | --- |
| **协议** | 🔴 **对手机是 WebSocket**（后端把 Lattice 的 SSE 转成 WS）—— 因为 SSE 穿隧道会被 10~100 秒掐断，实测过 |
| **Lattice 一行没改** | 走的是它自己公布的数据口 |
| **断了会怎样** | 🟠 **只坏「远程」Tab** —— 记录 / 语音 / 文本三个入口**一点不受影响** |
| **安卓版本** | `versionCode 51 / 0.10.3`（2026-10-09） |

---

## 二、形态

| 项 | 值 |
| --- | --- |
| **栈** | **Kotlin（Android）+ Python（FastAPI）双栈** |
| **安卓端** | `android\` —— Jetpack Compose + Room + Retrofit + WorkManager |
| **后端** | `backend\` —— FastAPI + SQLite[WAL] + 内嵌 worker |
| **入口** | `backend\launcher.py`（**进程级入口，不碰 `app\` 业务代码**） |
| **常驻进程** | **1 个** —— `backend.exe`，**监听 `0.0.0.0:8000`** |
| **托管** | 🔴 **由 `service-manager` 托管**（`autoStart: true`） |
| **公网入口** | 🔴 **有** —— `catcher.xbywebwork.xyz` → cloudflared → `localhost:8000` |
| **git** | ✅ **有**（8 commit / 工作区干净 / 已推 `origin`）—— **本工程 git 状态最健康的项目之一** |
| **体量** | **10,273.68 MB / 19,431 文件**，其中 **85.3% 是 `backend\`** |
| **不可再生** | **约 60 MB（0.59%）** |

---

## 三、🔴 三条最要紧的事实

### ① 它是被四方依赖的 —— 6 条边

| 依赖方 | 依赖什么 |
| --- | --- |
| **`video-workbench`** | `dist\data\app.db`（只读）+ `backend\tools\ffmpeg.exe` |
| **`video-panel`** | `dist\data\app.db`（只读，**读不到就降级**）+ `backend\tools\ffmpeg.exe` |
| **`VideoKnowledgeBase`** | `backend\tools\ffmpeg.exe`（**候选链最后一级，断了是半静默**） |
| **`energy-recovery`** | **端口 8000** —— 它想用，**两者不能同时跑** |

**→ `catcher` 是这台机器上的「ffmpeg 提供者」。**

**详见** [`deps.md`](deps.md) —— **整理本项目的第一件事是读它。**

### ② 它正在被 `service-manager` 托管

```
service-manager\config\projects\catcher.yaml
    autoStart: true
    command: D:\MyProject\catcher\backend\dist\backend.exe
    args:    ["--autostart"]
    env:     CLIP_NO_AUTOSTART: "1"
    restart.policy: on-failure
```

🔴 **这意味着：它的自启不在本项目里，在 `service-manager` 里。**
**在本项目内 grep `Run` / 计划任务，永远找不到。**

**详见** [`service.md`](service.md)。

### ③ 它有公网入口

```
catcher.xbywebwork.xyz  ←── cloudflared ──  localhost:8000
```

**安卓端的 `ApiConfig.kt` 里 `BASE_URL = "https://catcher.xbywebwork.xyz/"`。**

🔴 **→ 所以"哪些数据算敏感"这件事，比一个纯本地项目要紧。**

---

## 四、文档地图

### 六件套（`D:\MyProject\CLAUDE.md` §1）

| 文件 | 内容 |
| --- | --- |
| [`README.md`](README.md) | 本文件 —— 入口 |
| [`run.md`](run.md) | 怎么启动 · 四个参数 · 🔴 端口硬编码 · 为什么日志会停更 |
| [`data.md`](data.md) | 🔴 **数据在哪、怎么流、有没有备份** —— 本节最关键 |
| [`deps.md`](deps.md) | 🔴 **对外 6 条边 ＋ 🔴 出向 1 条（Lattice）＋ Python 依赖形态** —— 本节最关键 |
| [`service.md`](service.md) | 常驻进程 · 托管关系 · 健康探测 · 🔴 公网入口 |
| [`log.md`](log.md) | 变更记录 —— **只追加** |
| [`runtime-log.md`](runtime-log.md) | 🔴 **日志落在哪** —— 有三处会让人找错地方（见下） |

### 两个槽（`D:\MyProject\CLAUDE.md` §7）

| 文件 | 方向 | 一句话 |
| --- | --- | --- |
| [`OUTPUT-SLOT.md`](OUTPUT-SLOT.md) | **我产出什么** | 17 条 HTTP + 1 条 WS + `app.db` + 借出去的 `ffmpeg.exe` |
| [`INPUT-SLOT.md`](INPUT-SLOT.md) | **我吃什么** | 🔴 **只有一条：`Lattice` 的数据口**（2026-10-05 新增） |

### 原样保留的两份

| 文件 | 是什么 |
| --- | --- |
| [`architecture.md`](architecture.md) | **架构文档（11,421 B）** —— 十条架构不变量 / 系统架构图 / 同步协议 / Token 生命周期 / 容量边界 / 接口契约。**写得极完整，从未改过。** |
| [`reference\v12-prototype.html`](reference/v12-prototype.html) | **「便签 v12」原型**（33,866 B）—— 悬浮窗交互的设计来源 |

---

## 五、⚠️ 两份重要文档**在项目根上**，不在 `docs\` 里

**这个项目的根目录有两份"活文档"，它们的位置是刻意的：**

| 文件 | 是什么 | 为什么留根 |
| --- | --- | --- |
| **`CLAUDE.md`**（11,566 B） | 🔴 **"施工手册"** —— 开头写着「**这份文件是最高约束。所有代码必须服从这里的不变量和契约。架构文档是宪法，这份文件是施工手册。**」 | 🔴 **Claude Code 从项目根读它** —— 位置即语义 |
| **`PROGRESS.md`**（84,290 B） | 🔴 **"施工日志"** —— M0→M7.5 逐轮时间线 + 决策日志 | 同上；**用户 2026-10-03 明确拍板留根** |

**`README.md` / `LICENSE` 同样留根**（GitHub 从仓库根读它们）。

---

## 六、🔴 最容易踩的三个坑

| # | 坑 |
| --- | --- |
| 1 | **`backend\dist\` 不是"构建输出目录"，它是【部署目录】** —— 里面住着**正在跑的 exe** + **活的生产数据库** + **运行期资源**。删它 = 应用和 352 条记录一起没 |
| 2 | **`backend\tools\ffmpeg.exe` 被三个别的项目直接指向** —— 动它就是三处同时断 |
| 3 | **`.gitignore` 把生产数据全挡住了** —— **`git status` 干净 ≠ 有备份**。本项目的不可再生数据**一处 git 副本都没有** |

**详见** [`data.md`](data.md) 与 [`deps.md`](deps.md)。
