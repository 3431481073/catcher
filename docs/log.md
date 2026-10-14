# 整理记录 · 2026-10-03

> 依据 `project_manager/docs/PLAYBOOK.md`。
> **代码零改动。配置零改动。** **未运行任何代码**（`py_compile` 除外）。
> **它自己全程在跑（PID 2536），没有被中断。**

---

## 一、背景

`catcher` 是保留项目 —— 用户说它是「**非常重要的工具**」。

摸底定下的四件事：

1. 🔴 **它是被四方依赖的** —— **4 个依赖方、6 条边**（比摸底前的"两个项目"多一倍）；
2. 🔴 **它正在被 `service-manager` 托管** —— **自启不在本项目里**；
3. 🔴 **它有公网入口**（`catcher.xbywebwork.xyz` ← cloudflared）—— **本工程第一个**；
4. **10.03 GB 里，真正不可再生的只有 60.3 MB（0.59%）** —— **源码只占 0.08 MB**。

**用户拍板**：建 `docs\`（扩容）+ `misc\` + `services.yaml` · `_inspect_report.json` → `misc\` ·
**D2 体量清理整轮不动** · `PROGRESS.md` 留根 · `dist_onefile_backup\` 留 · 代码零改动 ·
**不建 `src\` / `data\`** · 新建 `PROVIDERS.md`。

---

## 二、结构前后对照

| # | 动作 | 内容 |
| --- | --- | --- |
| 1 | **新建** | `catcher\misc\` |
| 2 | **移动** | `backend\dist\data\_inspect_report.json`（241,837 B）→ **`misc\_inspect_report.json`**（SHA256 前后一致） |
| 3 | **新建** | `docs\` **五件套**（`README` / `run` / `data` / `deps` / `service`） |
| 4 | **新建** | `services.yaml` |

### 明确**未动**的（**这是本方案的主体**）

| 位置 | 为什么 |
| --- | --- |
| 🔴 **`backend\dist\`（除 `_inspect_report.json`）** | **正在跑的 exe + 活的生产库 + 运行期模型/工具**（四类全中） |
| 🔴 **`backend\tools\ffmpeg.exe`** | **三个别的项目直接指向它** |
| 🔴 **`dist\data\audio\` · `wav\`** | **用户数据**（禁止项明令） |
| 🔴 **`dist_onefile_backup\backend.exe`** | **用户拍板：留**（它不可复现，但不需要第二份） |
| `.venv\` · `.cache\` · `dist\.cache\` · `build\` · `.gradle\` · `.tools\` · `android\app\build\` | **D2 决定：整轮不动**（清理需要停进程，收益与风险不成比例） |
| **`backend\tools\ffmpeg-9.0.1-essentials_build\`** · `ffmpeg.zip` | 同上 |
| **`docs\architecture.md`** · **`docs\reference\v12-prototype.html`** | **原样保留**（前者写得极完整） |
| **`CLAUDE.md`** · **`PROGRESS.md`** · **`README.md`** · **`LICENSE`** | 🔴 **位置即语义** —— 用户明确拍板留根 |
| **`.gitignore`** | **它现在是对的** |
| `android\` 全部源码 | **第二个源码根**，不塞进 `src\` |
| **`HKCU\...\Run`** | **里面本来就没有 `ClipBackend`** —— 只读查询 |
| **`service-manager\config\projects\catcher.yaml`** | **不属于 catcher** —— 只读核 SHA256 |

### 体积对照

| | 整理前 | 整理后 |
| --- | --- | --- |
| **总计** | **10,273.68 MB / 19,431 文件** | **10,273.76 MB / 19,440 文件** |
| `docs\` | 0.045 MB / 2 文件 | **0.093 MB / 8 文件**（`97,113 B`） |
| `misc\` | 无 | **0.231 MB / 1 文件** |
| 根下文件 | 5 文件 | **6 文件 / 0.098 MB**（+`services.yaml`） |
| **D 盘剩余** | **20.077 GB** | **19.999 GB** |

> ⚠️ **表格里"整理后"的数字是【最后】量的** —— 我第一版写的是 0.07 MB / 7 文件，
> **那是 `docs\log.md` 自己还没生成时的读数**。**已按最终状态改正。**

> 🔴 **⚠️ 本轮【没有释放空间】—— 它是【消耗】了约 78 MB。**
> **因为备份 60.6 MB 落进了 `_rollback`**（其余是本轮新建的文档），
> 而本轮**一个字节的构建产物都没删**（D2 决定整轮不动）。
> **→ 本轮的收益不在空间，在"把 6 条对外边写下来"。**
>
> ⚠️ **两个基数会漂移**：① **`dist\data\` 是活的** —— 观测期间它的文件数变过（185 → 187 → 184）；
> ② `catcher` 总文件数从 19,431 → 19,439，其中 **6 个是本轮新建的文档/配置**，
> **差额那 2 个是手机端发来的新记录**（进程一直在写）。**→ 这个项目没有"静止的快照"。**

---

## 三、代码改动清单

> # **代码改动 = 无。**
>
> **一个 `.py` / `.kt` / `.kts` / `.json` / `.bat` / `.spec` 都没有改。**
> 只搬了一个文件、新建了五份文档 + `services.yaml`。

**`py_compile` 是本轮唯一"碰到代码"的动作**（用户授权），结果：

| 项 | 值 |
| --- | --- |
| **目标文件** | **18 个**（`backend\app\` 全部 17 个 `.py` + `launcher.py`） |
| **退出码** | ✅ **0** |
| **新增 `.pyc`** | ✅ **0 个** |
| **被刷新的 `.pyc`** | **1 个** —— `backend\__pycache__\launcher.cpython-313.pyc`，**7,796 → 7,817 B（+21 B）** |

> ⚠️ **那个 +21 B 是如实记的**：`app\__pycache__`（12 文件 / 39,738 B）与
> `app\routes\__pycache__`（5 文件 / 9,567 B）**与编译前逐字节一致**，
> 只有 `launcher` 那个变了 —— **判断是它此前就是个"陈旧的产物"**（源码没动，git 干净）。
> **→ 未做任何回改**（`.pyc` 本来就可再生）。

---

## 四、🔴 对外契约（本项目最要紧的产出）

**6 条边 + 3 条非项目边，已全部写进 [`deps.md`](deps.md)。**

| 依赖方 | 依赖什么 | 断了的严重度 |
| --- | --- | --- |
| `video-workbench` | `dist\data\app.db`（`vw\config.py:9`）+ `backend\tools\ffmpeg.exe`（`:19`） | 🔴 响亮 ×2 |
| `video-panel` | `dist\data\app.db`（`data.rs:53`）+ `ffmpeg.exe`（`:60`） | 🟢 降级 · 🔴 响亮 |
| `VideoKnowledgeBase` | `ffmpeg.exe`（`src\media.py:114`） | 🟠 **半静默** |
| `energy-recovery` | **端口 8000**（冲突） | 🟠 二者不能同时跑 |

**并已同步到 `project_manager` 层**：

| 动作 | 落点 |
| --- | --- |
| 第 1、2 行的「待核实」→ **实测结果** | `DEPENDENCY-CONTRACTS.md` 第三节 |
| **新增「三·五」小节**登记两条原先缺的边 | 同上 |
| 第四节 #7 从「**必须先冻结**」→ **✅ 已冻结** | 同上 |
| **新建 `PROVIDERS.md`** —— `catcher` = ffmpeg 提供者 | `project_manager\docs\` |

---

## 五、模型路由

> # 对 `_shared/models/` 与 `_shared/routes.yaml` **零贡献**。

**它用的是 faster-whisper 的 HF 格式**（`HF_HOME=<BASE_DIR>\.cache\huggingface`），
**与 `_shared` 的 `routes.yaml` 体系无关**。

**⚠️ 但它有一处值得记的重复**：
**medium 模型在本项目内【两份】且各自自足** ——
`backend\.cache\`（1,457 MB）+ `backend\dist\.cache\`（1,457 MB）；
**再加 `small`（461 MB）已无用但未删**。
**→ `medium` 模型在本工程里至少 5 份**（`catcher` ×2 · `voice_tool` · `video-workbench` · `D:\models\`）。
**跨项目去重是别的轮次的事，本轮只登记。**

**跳过也是一种结论。**

---

## 六、验证结果表

> ⚠️ **纯静态整理 —— 没跑任何代码（`py_compile` 除外）、没联网、没杀进程、没写注册表。**

| # | 验证项 | 方法 | 证伪标准 | 结果 |
| --- | --- | --- | --- | --- |
| V-1 | **备份完整** | 332 文件逐份 SHA256 | 任一不符 | ✅ **332 / 332 逐字节一致** |
| V-2 | **备份布局镜像源结构** | 逐目录比对 | 有平铺/撞名 | ✅ **镜像**（⚠️ **第一版平铺时撞过名：`android\build.gradle.kts` 被 `android\app\build.gradle.kts` 覆盖**，**已重建**） |
| V-3 | 备份里没有排除项 | 存在性 | 有 | ✅ 无 `.venv` / `.cache` / `dist`（除 `data`）/ `dist_onefile_backup` / `.gradle` / `.tools` / `build` / `android\app\build` |
| V-4 | **`_inspect_report.json` 搬前无锁** | `Open/ReadWrite/None` | 打不开 | ✅ **无锁** |
| V-5 | **搬迁无损** | SHA256 前后 | 变化 | ✅ `462C5E5A4190AD08…EBD9` **前后一致** |
| V-6 | **搬迁后源已消失** | 存在性 | 还在 | ✅ **False** |
| V-7 | 🔴 **`app.db` 未动** | SHA256 vs 备份 | 变化 | ✅ `0E68A64156D8ADF0…7C97` **与备份一致** |
| V-8 | **`app.db-wal` = 0 B** | 大小 | >0 | ✅ **无待写事务** → 只备 `app.db` 即完整快照 |
| V-9 | `dist\data\audio\` 完好 | 计数 | 减少 | ✅ **91 文件**（88 `.ogg` + 3 `.webm`） |
| V-10 | `dist\data\wav\` 完好 | 计数 | 减少 | ✅ **91 文件** |
| V-11 | 🔴 **`dist\backend.exe` 完好** | 存在性 + 大小 | 变化 | ✅ **12,601,518 B** |
| V-12 | 🔴 **`backend\tools\ffmpeg.exe` 完好** | 存在性 + 大小 | 变化 | ✅ **102,856,192 B** |
| V-13 | `android\app\src` 未动 | 计数 | 变化 | ✅ **65 文件** |
| V-14 | **`py_compile` 全过** | 18 个目标 | 非 0 | ✅ **退出码 0** |
| V-15 | **未新增 `.pyc`** | 编译前后计数 | 新增 | ✅ **0 新增**（1 个刷新，见第三节） |
| V-16 | 🔴 **`HKCU\...\Run` 无 `ClipBackend`** | 只读查询 | 有 | ✅ **无**（本来就无） |
| V-17 | 🔴 **`catcher.yaml` 未动** | SHA256 | 变化 | ✅ `9A3917D0438BEF70…5BCF`（只读核） |
| V-18 | 🔴 **进程未中断** | `Win32_Process` | PID 变化 | ✅ **PID 2536 仍在跑**，命令行 `…\dist\backend.exe --autostart` |
| V-19 | 🔴 **8000 仍在听** | `Get-NetTCPConnection` | 状态变化 | ✅ **`0.0.0.0:8000` = Listen，OwningProcess = 2536** |
| V-20 | **无 git 写操作** | `git status --porcelain` | 出现意外条目 | ✅ **只有 `?? misc/`** —— **恰好是本轮新建的那一个** |
| V-21 | 四份根文档未动 | SHA256 vs 备份 | 变化 | ✅ `README.md` · `LICENSE` · `CLAUDE.md` · `PROGRESS.md` 全部一致 |
| V-22 | `.gitignore` 未动 | SHA256 vs 备份 | 变化 | ✅ `ACEE485422B795AE…` 一致 |
| V-23 | `docs\architecture.md` 未动 | SHA256 vs 备份 | 变化 | ✅ 一致 |
| V-24 | 编码未写坏 | 逐份 UTF-8 + 查 BOM | BOM / 非法序列 | ✅ 6 个新文件全部合法 UTF-8、**无 BOM** |

---

## 七、未解决与拿不准

1. **🔴 `CLIP_TOKEN` 明文默认值 —— 只报告，没改。**
   `config.py` 里 `TOKEN = os.environ.get("CLIP_TOKEN", "dev-token-0123456789-abcdefghijklmnopqrstuvwx")`。
   **它在 git 里、已推 GitHub、而这个项目有公网入口。**
   **但 `catcher.yaml` L38 明写「必须保持默认值，否则手机端配对的 token 会失效」** ——
   **改它 = 手机端立刻 401。** → **留给人判断。**

2. **🔴 `_inspect_report.json` 是什么生成的，没查出来。**
   内容是 `records` 表的 JSON 导出（**含用户原文**），日期 9/22。
   **全库 grep 不到生成它的代码**（推测是打包前的一个临时脚本，已删）。
   **本轮处置：搬进 `misc\`（不改内容）+ 在 `docs\data.md` 点名它含用户数据。**

3. **`dist_onefile_backup\backend.exe`（623 MB）—— 用户拍板留，但它不可复现。**
   `build.spec` 已改成 onedir，**现有工具链再也产不出 onefile**。
   **→ 它不是"构建产物"，是"一份旧版本"。** 留着。

4. **`backend\.cache\` 里的 `small` 模型（461 MB）—— 未删。**
   默认模型是 `medium` 且语音功能已停用，
   **但 `CLIP_WHISPER_MODEL` 可以用环境变量覆盖** —— **"当前用不到"≠"永远用不到"。**

5. **`.gradle\daemon\`（20.6 MB / 12 文件）里有没有活着的 GradleDaemon —— 没查。**
   将来要删 `.gradle\` 之前必须补这一步。

6. **cloudflared 当前是否真在跑 —— 没核。**
   `infra.yaml` 声明 `autoStart: true`，**但本轮只查了含 `catcher` 字样的命令行，而 cloudflared 的命令行里没有。**
   **→ "声明开着"≠"真开着"。**

7. **`.venv` 是 Python 3.13.7，而 `CLAUDE.md` 写 3.12 —— 哪个对，没有依据。**
   venv 是实物，所以大概率文档笔误。**但"大概率"不是证据。** **未改任何一方。**

8. **`dist\data\backend.log`（218,972 B）停更在 2026-09-26** —— **已解释**（被 svcmgr 拉起时不写它），
   但**它现在是一份"僵尸日志"**：既不再增长，又被 `.gitignore` 挡着。
   **要不要留，没决定。**

9. **本轮【不读安卓端源码内容】。**
   只读了目录树与统计（65 个源文件 / 197 KB）。
   `OverlayCard.kt`（21,991 B，全项目最大的 Kotlin 文件）与 `FloatingBallWindow.kt`（18,640 B）
   **一行都没打开** —— **所以本方案不对安卓端做任何结构判断。**

10. **「只有这 6 条边」是推断的，不是穷举证明的。**
    依据是依赖登记册 + 项目地图 + 定向 grep。
    **一次全工程 `Select-String` 超时被掐断**（已写进 PLAYBOOK 已知的坑第 18 条），
    **所以"没有第七条"这句话没有跑完的全扫支撑。** → **已在 `docs\deps.md` 的表下如实标注。**

---

## 八、未改动清单

**以下内容本次一行未动（实测佐证见第六节）：**

- 🔴 **`backend\dist\`** —— 除 `_inspect_report.json` 外一个文件没动
  （含 `backend.exe` · `data\app.db` · `data\audio\` · `data\wav\` · `tools\ffmpeg.exe` · `.cache\` · `_internal\`）
- 🔴 **`backend\tools\ffmpeg.exe`** + `ffmpeg-9.0.1-essentials_build\` + `ffmpeg.zip`
- 🔴 **`dist_onefile_backup\backend.exe`** · `backend\.cache\` · `backend\.venv\` · `backend\build\`
- **`.gradle\` · `.tools\` · `android\.gradle\` · `android\app\build\`**（D2 决定）
- **`android\` 全部源码**（65 文件）· `local.properties` · `gradlew` · 4 个 `.gradle.kts`
- **`backend\app\`**（34 文件）· `launcher.py` · `build.bat` · `build.spec` · `requirements.txt` · `pytest.ini`
- 🔴 **四份根文档**：`README.md` · `LICENSE` · `CLAUDE.md` · `PROGRESS.md`
- **`.gitignore`** · `.git\`（**零 git 写操作**）· `docs\architecture.md` · `docs\reference\`
- **`HKCU\...\Run`** —— **只读查询，没有写/删**
- **`service-manager\config\projects\catcher.yaml`** —— **只读核 SHA256，没有写**
- **`catcher` 以外的任何项目** —— 未碰 `_shared` / `shared_models` / `_archive` /
  `video-workbench` / Lattice 发布侧 / `_rollback` 里已有的十四份备份
  （**只新建了 `catcher_before_20261003\`**，并更新了 `_rollback\README.md`）
- **未运行任何代码（`py_compile` 除外），未联网，未杀任何进程**

---
---

# 补齐两个槽 · 2026-10-09

> 依据 `D:\MyProject\CLAUDE.md`（§1 六件套 / §7 两个槽）。
> 🔴 **本轮只写文档。零代码改动、零数据改动、零删除。**

---

## 一、为什么有这一轮

`catcher` 的六件套**在 2026-10-03 就建好了**（README / run / data / deps / service / log），
`OUTPUT-SLOT.md` 由 PM 侧在 2026-10-04 起草。

**但两件事没做**：

1. 🔴 **`INPUT-SLOT.md` 从来没写过** —— §7 明确要求两侧都要有；
2. 🔴 **10-04 之后本项目加了一整个「远程」功能**（手机看/操作电脑上的 Claude Code），
   而 `OUTPUT-SLOT.md` 停在「9 个端点」—— **B 组那 9 条一条都没写进去。**

---

## 二、改了什么

| # | 动作 | 文件 | 内容 |
| ---: | --- | --- | --- |
| 1 | **新建** | `docs\INPUT-SLOT.md` | 🔴 **第一次给本项目写输入槽。** 结论：**只有一条对外输入 —— `Lattice` 的数据口**。含发现文件机制、9 条上游接口、超时两套、`enter` 拆分、5 条假设 |
| 2 | **新建** | `docs\runtime-log.md` | 日志落在哪。**三处会让人找错地方**：① 后端日志其实在 `service-manager\data\logs\` ② 本项目内那份 `backend.log` 是僵尸 ③ 安卓端日志在**手机上** |
| 3 | **更新** | `docs\OUTPUT-SLOT.md` | ① §2.1 从「9 个端点」→ **17 条 HTTP + 1 条 WS**（补 B 组 Lattice 转发）<br>② 错误码 5 个 → **7 个**（补 `CONFLICT`(409) / `LATTICE_UNAVAILABLE`(503)）<br>③ 消费者表补**第 7 条**：本项目自己的安卓端「远程」Tab |
| 4 | **更新** | `docs\deps.md` | ① §四「引用其他项目：✅ **零**」→ 🔴 **已不成立**（现在走 Lattice 的数据口）<br>② 「三条非项目边」→ **四条**，补 `catcher → Lattice`（**出向**） |
| 5 | **更新** | `docs\README.md` | ① 「六件套索引」→「**文档地图**」（六件套 + 两个槽 + 原样保留两份）<br>② §一 补一段「**它已经不只是一个捕获器了**」—— 远程 Tab |

**未动的**：`architecture.md` · `reference\v12-prototype.html` · `data.md` · `run.md` · `service.md`
（`data.md` / `run.md` / `service.md` 的内容**本轮没发现与 10-09 事实冲突的地方** —— 见 §四）。

---

## 三、怎么验的

> ⚠️ **本轮是纯文档轮，没有可跑的验收命令。** 下面是"我凭什么说这些是真的"。

| # | 断言 | 依据 |
| ---: | --- | --- |
| 1 | 现在有 **8 条 `/api/v1/lattice/*` HTTP + 1 条 WS** | `grep '^@router\.\|^@ws_router\.' backend\app\routes\lattice.py` —— **实测输出 9 行** |
| 2 | Lattice 的端口来自发现文件、可被 `LATTICE_HOME` 覆盖 | `backend\app\config.py` 第 50~60 行（只读） |
| 3 | 后端**每次请求都重读**发现文件 | `app\lattice.py` 的 `forward()` 里 `read_discovery()` 在循环内 |
| 4 | 只认 `http://127.0.0.1:` 开头 | `app\lattice.py` 的 `read_discovery()` 里的 `startswith` 判断 |
| 5 | 事件流对手机是 **WS**、对上游是 **SSE** | `routes\lattice.py` 的 `ws_router` + `lattice.open_event_stream()` |
| 6 | SSE 穿隧道会被掐 | **前几轮实测**：SSE 两轮 88.8s / 96.9s 被掐；同一隧道 WS 两轮挂满 299.3s / 299.8s |
| 7 | 安卓端 `FileLogger` 落在 `getExternalFilesDir(null)\crash.log` | `util\FileLogger.kt` 第 20~21 行（只读） |
| 8 | `docs\` 下现有 11 个条目 | glob `docs\**\*` |

---

## 四、还剩什么（未解决）

| # | 事项 | 说明 |
| ---: | --- | --- |
| **1** | 🔴 **`runtime-log.md` 里"安卓端 crash.log 当前存不存在"没在真机核过** | Android 11+ 对 `Android\data\` 的访问收紧了，**部分机型文件管理器打不开**。只核了代码路径，没核实物 |
| **2** | 🔴 **`INPUT-SLOT` 的"只有一条输入"是【推断】** | 依据是 `deps.md` 那轮的后端源码 grep（零命中）。**那次全扫曾超时被掐**，所以"没有第二条"没有跑完的全扫支撑 —— **与 `OUTPUT-SLOT` §七 第 5 条同一条限制** |
| **3** | 🟠 **`OUTPUT-SLOT` 里的数字仍是 10-04 的** | 它写着 `records` 364 行 / `app.db` 385,024 B —— **数据是活的，现在肯定变了**。**本轮没去重量**（量了明天又漂）。它自己 §七 第 3 条已声明"数字在漂" |
| **4** | 🟠 **`data.md` / `service.md` 里的 PID（2536）早过时** | 实测现在是 `40464`（且每次换服务都会变）。**本轮没改** —— PID 是"某一刻的读数"，不是契约。**但如果有人拿它当判据，就会误判** |
| **5** | 🟠 **`README` §二 的体量数字（10,273.68 MB / 19,431 文件）是 10-03 的** | 同上。**本轮没重量** |
| **6** | 🔴 **`deps.md` 的"6 条边"没有把 Lattice 那条算进【入向】表** | 因为那条是**出向**（catcher 吃 Lattice），**方向不同**，所以单独列在"非项目边"里。**如果将来有人只看 §一 的表，会以为本项目没有对外输入** —— 已在 §一 表下加了指引 |
| **7** | **`§8 禁止项`「不硬编码别人的路径」是否被破** | 读 `%USERPROFILE%\.lattice\api\current.json` —— **不是硬编码**（用 `Path.home()` 展开，且 `LATTICE_HOME` 可覆盖），且那是 Lattice **自己公布的发现机制**。**判定：没破。** 但这条依赖**没有写进 `project_manager` 的依赖登记册** |

---

## 五、明确没做的

- ❌ **没改任何代码**（`.py` / `.kt` / `.kts` / `.spec` / `.bat` / `.json` 全未动）
- ❌ **没动任何数据**（`app.db` / `audio\` / `wav\` 未碰）
- ❌ **没删任何东西**（本轮只新增 2 份、更新 4 份文档）
- ❌ **没动别的项目**（`Lattice` / `service-manager` / `project_manager` 一律只读）
- ❌ **没重启服务、没改配置、没碰注册表**

---

# 两个槽改成 §8.4 格式 · 2026-10-11

> 依据 `D:\MyProject\CLAUDE.md` **§8.4**「槽的固定格式 —— 程序只读这一块」。
> 🔴 **本轮只重排文档格式。零代码改动、零数据改动、零删除。**

---

## 一、为什么有这一轮

`CLAUDE.md` 新增 **§8.4**：三份槽里**只有「固定标题下面那张两列表」是给程序读的**
（`pm map` 靠它反查锚点、推边）。

而本项目两份槽用的是**旧的散文式表格**（INPUT 是「四格」、OUTPUT 是「我放到哪/放了什么/怎么取」三格）——
**程序认不出固定标题**，按 §8.4 原话是 **「静默丢边」**。

---

## 二、改了什么

| # | 文件 | 改动 |
| ---: | --- | --- |
| 1 | `docs\INPUT-SLOT.md` | 顶部加固定标题 **`## 我从哪拿`** + 两列表（锚点 / 拿什么），**1 行**；原 §零~§四 **一字未删**，整体移入 **`## 详情`** |
| 2 | `docs\OUTPUT-SLOT.md` | 顶部加固定标题 **`## 我往外给`** + 两列表（锚点 / 是什么），**5 行**；原 §一~§八 **一字未删**，整体移入 **`## 详情`** |

**锚点怎么定的（本轮唯一需要判断的地方）**：

| 槽 | 锚点 | 为什么是它 |
| --- | --- | --- |
| INPUT | `%USERPROFILE%\.lattice\api\current.json` | 🔴 **不能写端口** —— Lattice 的数据口是**随机高位端口**（44770 起 12 次尝试），**每次启动都变**。这个发现文件**就是它的寻址方式**，所以"池子的锚点"是文件、不是端口 |
| OUTPUT | `127.0.0.1:8000` | §8.4 只认 `127.0.0.1:xxxx` 这一种端口写法。**实际监听是 `0.0.0.0:8000`**、公网入口是 `catcher.xbywebwork.xyz` —— 两者都**不是**合法锚点形态，写在 `## 详情` |
| OUTPUT | `…\backend\dist\data\app.db` · `…\data\audio` · `…\data\wav` · `…\backend\tools\ffmpeg.exe` | 绝对路径、反斜杠、**不带项目名**（谁给的，看这个锚点在谁的 `OUTPUT-SLOT.md` 里） |

**顺带更正的事实 —— 🔴 这几处【超出了"只改格式"】，特此标出来**：

| # | 位置 | 原来写的 | 改成 | 依据 |
| ---: | --- | --- | --- | --- |
| 1 | OUTPUT §2.1 `POST /records` | 「文本 / 视频」 | 加上 **`voice`**（`type` ∈ text / video / **voice**） | 2026-10-11 后端 `schemas.py` 已加 `voice` |
| 2 | OUTPUT §2.1 A 组 | 「9 条」 | **「契约 9 条，实测只实现了 7 条」**，`DELETE /records/{id}` 与 `/tasks/{id}/retry` 标 ⚠️ | **实测 grep `routes\`：这两条没有** |
| 3 | OUTPUT §2.1 `RecordDTO` | 「14 个字段」 | **16 个字段**（补 `tags`） | **原文自己列了 15 项却写 14 —— 原文就错**；对着 `schemas.py` 数是 16 |
| 4 | OUTPUT §2.2 / §七 | 只有 10-04 的数字 | 补 **2026-10-11 复核**：`records` 333 行 / `events` 225 行 | 本轮实测 |
| 5 | INPUT §三 第 1 条 | 「需要同步更新」 | 标 **✅ 2026-10-11 已改** | 只读 `deps.md`，那一行确实已更新 |

---

## 三、怎么验的

> 本轮是纯文档轮，**没有可跑的验收命令**。下面是"我凭什么说这些是真的"。

| # | 断言 | 依据 |
| ---: | --- | --- |
| 1 | 两份槽的固定标题**一字不差** | `grep '^## (我从哪拿\|我往外给)$' docs\` → **命中 2 处**：`INPUT-SLOT.md:8` · `OUTPUT-SLOT.md:17` |
| 2 | 标题下面**紧接**两列表 | 读文件前 22 行确认：表头是 `\| 锚点 \| 拿什么 \|` / `\| 锚点 \| 是什么 \|`，**数据行每行正好 2 列** |
| 3 | `DELETE /records/{id}` 与 `/tasks/{id}/retry` **确实没实现** | `grep '@router\.(get\|post\|patch\|delete)' backend\app\routes\` —— **输出里没有这两条** |
| 4 | `RecordDTO` 是 **16 个字段** | `backend\app\schemas.py` 的 `RecordDTO`（只读） |
| 5 | 内容没丢 | 两份文件正文**逐段搬运**，只加标题、只改归位 |

---

## 四、还剩什么（未解决）

| # | 事项 | 说明 |
| ---: | --- | --- |
| **1** | 🔴 **"借出去的二进制"这个语义表达不了** | `backend\tools\ffmpeg.exe` 被 3 个项目借用。§8.4 的两列（锚点 / 是什么）**没有"这条边是借的、不是给的"这一格**。已在该行描述里写明"借出去的二进制"，原问题留在 §七 第 1 条 |
| **2** | 🔴 **公网入口进不了锚点表** | `catcher.xbywebwork.xyz` 既是**别的机器唯一能取到的池子**，又是对外总入口 —— 但 §8.4 只认「绝对路径」和 `127.0.0.1:xxxx`。**只能写在 `## 详情`** |
| **3** | 🟠 **`0.0.0.0:8000` 与锚点 `127.0.0.1:8000` 不等价** | 反查能匹配上（归一化里有 `localhost → 127.0.0.1`），但**"它其实监听在所有网卡"这个事实在表里体现不出来** |
| **4** | 🟠 **`energy-recovery` 也声明用 8000** | 按 §8.4 规则会扫到**两条指向同一端口的边** —— 那是**冲突**，不是共享。**规则里没有"冲突"这个语义** |
| **5** | 🔴 **本项目没有 `TRIGGER-SLOT.md`** | 三份槽只写了两份（INPUT / OUTPUT）。**"我怎么被启动"没声明** —— `service-manager` 托管那条链只写在 `OUTPUT-SLOT` §五 的"非项目边"里。**本轮不在范围内，未补** |
| **6** | 🟠 **数字仍在漂** | 本轮补的 333 / 225 也是**某一刻的读数**，明天就变 |

---

## 五、明确没做的

- ❌ **没改任何代码**（`.py` / `.kt` / `.kts` / `.spec` / `.bat` / `.json` 全未动）
- ❌ **没动任何数据**（`app.db` / `audio\` / `wav\` 未碰）
- ❌ **没删任何东西**（两份槽整份重写，**正文一字未删**）
- ❌ **没动别的项目**（`Lattice` / `service-manager` / `project_manager` 一律只读）
- ❌ **没写 `TRIGGER-SLOT.md`** —— 见 §四 第 5 条，本轮不在范围
- ❌ **没碰 `OUTPUT-SLOT.md` §五 消费者表** —— §8 说"输出槽不写谁在用"，但那是**既有内容**，本轮**只搬不删**，仍留在 `## 详情` 并保留原注

---

# 锚点表按四条禁令复核 · 2026-10-11（同日二次）

> §8.4 在上一轮之后**又加了两块**：**四条禁令**（都是实测踩出来的）+ **归一化说明**。
> 按新规矩把两张锚点表**逐条过了一遍**。
> 🔴 **仍然只动文档。零代码、零数据、零删除。**

---

## 一、按四条禁令逐条复核的结果

| 禁令 | INPUT-SLOT | OUTPUT-SLOT |
| --- | --- | --- |
| **1** 声明不许只写在 `## 详情` | ✅ 唯一的输入已在表里 | 🔴 **补了一行** —— `services.yaml` 原来只写在详情里 |
| **2** 不许写到项目根 / 生态根 | ✅ 具体文件 | ✅ 1 端口 + 4 文件 + 2 目录，全是具体东西 |
| **3** 项目外路径统一用 `~` | 🔴 **改了写法** | ✅ 锚点全在项目内，无项目外路径 |
| **4** 读什么写什么 | ✅ 读文件写文件 | ✅ 文件写文件、目录写目录 |

---

## 二、改了什么（两处）

| # | 文件 | 改动 | 为什么 |
| ---: | --- | --- | --- |
| 1 | `docs\INPUT-SLOT.md` | 锚点 **`%USERPROFILE%\.lattice\api\current.json` → `~/.lattice/api/current.json`**（表 + 详情里另外 2 处一并改） | 🔴 **禁令 3**。而且**这条正是实测里点名的那条**：`catcher` 写 `%USERPROFILE%`、`Lattice` 写 `~/.lattice/...` —— **同一个东西两种写法，`catcher ← Lattice` 这条真边直接丢了** |
| 2 | `docs\OUTPUT-SLOT.md` | 表里**补一行** `D:\MyProject\catcher\services.yaml`；详情 §一 新增 **「C. 服务声明」** 一小节 | 🔴 **禁令 1**。它原来只写在详情"谁启动它"那一行里 —— **内容没丢 ≠ 声明没丢**，程序只读表 |

**没有改的**：`127.0.0.1:8000` · `app.db` · `audio` · `wav` · `ffmpeg.exe` 五个锚点 —— 逐条过了禁令 2/3/4，**本来就对**。

**故意没加的一行**：`backend\dist\backend.exe`。它是**服务本体**，被 `service-manager` 按 `services.yaml` 里的 `command` **拉起** ——
那是**控制流**（TRIGGER），不是"别人读的数据"。§8 说的"二进制工具"指 `ffmpeg.exe` 那种**给别人用的**工具。

---

## 三、怎么验的

| # | 断言 | 依据 |
| ---: | --- | --- |
| 1 | 两份槽的固定标题仍**一字不差** | 读文件确认：`## 我从哪拿`（INPUT:9）· `## 我往外给`（OUTPUT:18） |
| 2 | 两张表仍**正好两列** | 逐行读：INPUT **1 行**、OUTPUT **6 行**，每行 2 列 |
| 3 | `services.yaml` 确实在项目根 | 读 `D:\MyProject\catcher\services.yaml` —— `services:` 一条 `catcher-backend`，`port: 8000` |
| 4 | `INPUT-SLOT.md` 里的 `%USERPROFILE%` 已清零 | grep `USERPROFILE` → **该文件只剩 2 处，且都在"改动说明"那段里引用旧写法**（改动前 3 处都是真锚点/真路径）<br>🔴 **另外发现一处漏网并已改**：`docs\deps.md:125` 也写着 `%USERPROFILE%\.lattice\api\current.json` —— **同一个路径、同一个坑**，一并改成 `~` 写法 |
| 5 | 内容没丢 | 两处改动**只改写法 / 只加行**，没有删除任何段落 |

---

## 四、还剩什么（未解决）

| # | 事项 | 说明 |
| ---: | --- | --- |
| **1** | 🟠 **`0.0.0.0:8000` 与锚点 `127.0.0.1:8000` 不等价** | 反查能匹配上（归一化里 `localhost → 127.0.0.1`），但**"它其实监听在所有网卡"这个事实在表里体现不出来**。§8.4 只认 `127.0.0.1:xxxx` 这一种端口写法 |
| **2** | 🟠 **公网入口进不了表** | `catcher.xbywebwork.xyz` 是**别的机器唯一能取到的入口**，但既不是路径也不是回环端口，**只能写在 `## 详情`** |
| **3** | 🟠 **`energy-recovery` 也声明用 8000** | 按 §8.4 会扫到**两条指向同一端口的边** —— 那是**冲突**，不是共享。**规则里没有"冲突"这个语义** |
| **4** | 🔴 **本项目仍没有 `TRIGGER-SLOT.md`** | 三份槽只写了两份。**服务怎么被启动**现在靠 `services.yaml` 那一行锚点撑着（`service-manager` 读得到），但**按 §8 本应有独立一份**。**本轮不在范围，未补** |
| **5** | 🟠 **`audio\` / `wav\` 没有具名消费者** | 表里列了，但 `## 详情` 的作者表里**没有谁在读它们**。按 §8"输出槽不写谁在用"这不算错，但**这两行可能永远推不出边** |

---

## 五、明确没做的

- ❌ **没改任何代码 / 数据 / 配置**（`services.yaml` 本身**一个字节没动**，只是把它**声明进了表**）
- ❌ **没删任何东西**（两份槽只改写法、只加行）
- ❌ **没动别的项目**（`Lattice` / `service-manager` / `project_manager` 一律只读）
- ❌ **没写 `TRIGGER-SLOT.md`** —— 见 §四 第 4 条

---

# App 使用时长采集 · 2026-10-11（同日三次）

> 需求来自 **`snapshot`**：它要按 App 算使用时长，而**这份数据只有手机拿得到**。
> 🔴 **分工的硬边界：手机只做「拉 / 存 / 发」，配对、去重、跨批全在后端。**
> **本轮只动 `catcher`，没碰别的项目。**

---

## 一、做了什么

**后端（5 个文件，其中 2 个新增）**

| 文件 | 改动 |
| --- | --- |
| `app\usage.py` | **新增** —— `pair()` **纯函数**（无 IO 无 DB，单测主力）· `ingest()` 一次上报的完整处理 · `list_segments()` / `summarize()` |
| `app\routes\usage.py` | **新增** —— 4 条端点 |
| `app\ws_hub.py` | **新增** —— 进程内 `set[WebSocket]` + `broadcast()`，让**后端自己**也能往那条 WS 推东西 |
| `app\db.py` | `SCHEMA` 加 `usage_records`（+ 唯一索引 + 时间索引）与 `usage_pending` |
| `app\schemas.py` | `+ UsageEventItem` / `UsageReportRequest`（`events` 有 `max_length=20000`） |
| `app\routes\lattice.py` | 🔴 **三行**：import + `accept()` 后 `register` + `finally` 里 `unregister`。**转发逻辑一行没动** |
| `app\main.py` | `include_router(usage_routes.router)` |

**安卓端（6 个新增 + 14 个修改）**

| 新增 | 内容 |
| --- | --- |
| `collector\DeviceId.kt` | `ANDROID_ID` → SHA256 前 16 位（读失败退**常量**兜底，不是随机 —— 随机的话后端会当成一群新设备） |
| `collector\UsageCollector.kt` | **纯逻辑**：`collectIncremental` / `backfillIfNeeded` / `lastScanMs`。只拉和存 |
| `collector\UsagePrefs.kt` | DataStore `usage` 分区的**唯一所有者**（游标 + `usage_hint_dismissed`） |
| `collector\AppLabels.kt` | 包名 → 可读名（只有手机做得了） |
| `data\db\entity\UsageEventEntity.kt` · `dao\UsageEventDao.kt` | Room 新增一张表 + 两个索引 |
| `service\UsageCollectWorker.kt` | 采集的调度壳：周期 15 分钟 / 回溯 7 天 |

| 修改 | 内容 |
| --- | --- |
| `AppDatabase.kt` | `version 2→3` + `UsageEventEntity` + `MIGRATION_2_3` + 🔴 **`exportSchema = true`**（见下） |
| `QueueEntity.kt` | `+ kind`（`@ColumnInfo(defaultValue = "record")`） |
| `QueueItem.kt` · `RecordRepositoryImpl.kt` | `kind` 在 domain ↔ entity 之间的映射 |
| `UploadWorker.kt` | 🔴 **顶部按 `queue.kind` 分叉** → `doUsageUpload` / `retryUsage` |
| `UploadQueue.kt` | `USAGE_SUBJECT` · `enqueueUsageIfPending()` · 唯一工作名带 kind |
| `Recovery.kt` | `resetSyncing()` + `enqueueUsageIfPending()` |
| `ClipApp.kt` | 15 分钟周期 + 启动/回前台各采一次 |
| `RemoteViewModel.kt` | `handleEvent` **加一个 case**（`trigger_usage`） |
| `FloatingBallService.kt` | `onTaskRemoved` 退出前尽力采集（`runBlocking` + 3 秒上限） |
| `Permissions.kt` · `PermissionGuideScreen.kt` · `LogListScreen.kt` · `MainScreen.kt` | 第 3 项权限卡 + 记录页提示条（带「不再提示」） |
| `AndroidManifest.xml` | `+ PACKAGE_USAGE_STATS` · `+ QUERY_ALL_PACKAGES` |
| `ApiModels.kt` · `ApiService.kt` | `POST api/v1/usage` 的 DTO 与接口 |
| `build.gradle.kts` | `ksp { room.schemaLocation }` |

## 二、关键决定

| # | 决定 | 理由 |
| ---: | --- | --- |
| 1 | **配对全在后端** | 配对要跨批、要看锁屏和换 App 的顺序 —— 手机上存不下那个状态 |
| 2 | **5 分钟重叠** | "正好跨在两次扫描之间"的段会被截断。**宁可重复（两端都去重），不能漏** |
| 3 | **15 分钟周期不是完整性的前提** | 拉取区间永远是「上次扫到 − 5 分钟 到 now」，Doze 推迟 3 小时也只是多拉 3 小时 |
| 4 | 🔴 **跨天/跨批的段用 `usage_pending` 表，不用内存** | 后端会被服务管理器重启，放内存里一重启就丢 |
| 5 | **`date`/`from`/`to` 按【段起点】过滤** | 每段只归一天，**不会跨天重复计数** |
| 6 | **`queue` 加一列 `kind` 而不是新建表** | 复用同一个调度循环 / 退避 / 401 门 / 网络恢复重投 |
| 7 | 🔴 **usage 失败到顶时【删】队列行，不标 FAILED** | `enqueueUsageIfPending` 是"没有行才补" —— 标 FAILED 会让那批**永久发不出去** |
| 8 | 🔴 **标了 SYNCING 的行在任何失败路径都要放回 PENDING** | `takePending` 只取 PENDING，不放回就**再也捞不到**（进程被杀那种由 `Recovery` 兜底） |
| 9 | **不自建常驻 WS** | 拍板：只在「远程」Tab 开着时能收 `trigger_usage`；周期采集才是主路径 |
| 10 | 🔴 **打开 `exportSchema`** | 见 §三 第 2 条 —— 没有它，`queue.kind` 的 `DEFAULT` 写法只能靠真机试 |

## 三、怎么验的

| # | 验的东西 | 结果 |
| ---: | --- | --- |
| 1 | **`pytest`（后端）** | **75 passed**（新增 `tests\test_usage.py` **22 条**：配对 8 · 落库/去重/覆盖/跨批/label 7 · WS 触发 2 · 常量回归 1 …） |
| 2 | 🔴 **Room 迁移 vs Room 自己的期望 schema** | 手写的 `MIGRATION_2_3` 与 `exportSchema` 导出的 `3.json` **逐字比对全 OK**（表 / 两个索引名 / `queue.kind` 的 `DEFAULT 'record'`）。**这一步逮住了"默认值到底要不要带引号"** |
| 3 | **`curl` 冒烟（源码态 uvicorn :8001 + 隔离库副本）** | 配对 ✅ / 去重（同批重发段数不变）✅ / 跨批续段（20 秒）✅ / 未知事件类型计数 ✅ / `date` 窗口 = 本地整天 ✅ / `trigger` 返回 `delivered` ✅ / 边界 400 ✅ |
| 4 | **`assembleDebug`** | ✅ 多轮通过。APK **250.1 MB**，`versionCode` **仍 56**（第 10 步才动） |
| 5 | **清单合并** | 两条新权限确实进了 `merged_manifest\...\AndroidManifest.xml` |

🔴 **没有验的**：**真机**（`adb devices` 空）。**安卓端这批代码一次都没在运行时执行过**，
包括那条 Room 迁移 —— 它要到真机**第一次打开旧库**时才真正被验证。

## 四、还剩什么（未解决）

| # | 事项 | 说明 |
| ---: | --- | --- |
| **1** | 🔴 **正式后端还是旧版** | 跑在 8000 的 `dist\backend.exe` **没有重打包** ⇒ **`POST /api/v1/usage` 现在会 404**。本轮只在**源码态**验过。**要用必须先重打 `backend.exe`** |
| **2** | 🔴 **安卓端未上真机** | 见 §三 的末注。`versionCode` 也还没动 |
| **3** | 🟠 **`usage_events` 的唯一索引对 NULL 包名不去重** | SQLite 里 NULL 互不相等，屏幕/锁屏事件（无包名）在 5 分钟重叠里会重复插入。**影响很小**（后端对它们只当断段边界，重复完全幂等；且上传成功就删）。要修就是手机侧写 `""`，一行 |
| **4** | 🟠 **`trigger_usage` 只在「远程」Tab 开着时有效** | 拍板点 1。要常驻得单开一条 WS |
| **5** | 🟠 **M9 没写进 `CLAUDE.md` §七 的里程碑清单** | 那一节仍停在 M0~M6 |
| **6** | 🟠 **`snapshot` 那侧怎么读这两张表，本项目没确认** | 按 §8 输出槽不写"谁在用"，所以这里也不写 |

## 五、明确没做的

- ❌ **没动 `records` / `tasks` 的任何列**（`video-workbench` / `video-panel` 在逐字引用，见 `deps.md` §一）
- ❌ **没动 Lattice 转发逻辑**（`lattice_ws` 只加了 register/unregister 两行）、**没改 WS 协议**
- ❌ **没动录音与 ASR**
- ❌ **没重打 `backend.exe`** —— 见 §四 第 1 条，**这条是当前最要紧的**
