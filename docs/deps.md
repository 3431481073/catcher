# 依赖与对外契约

> 🔴 **本文件是六件套里最要紧的两份之一。**
> **`catcher` 是本工程里被依赖最广的项目之一 —— 它不是一个项目，是四个项目的上游。**

---

## 一、🔴 对外契约表（它是被依赖方）

**依赖方 = 别人。被依赖方 = `catcher`。**

| # | 契约项 | 依赖方 | 具体内容 | 断了会怎样 | 当前状态 |
| --- | --- | --- | --- | --- | --- |
| **1** | **`dist\data\app.db`** | **`video-workbench`**<br>`vw\config.py:9` `CATCHER_DB` | `D:\MyProject\catcher\backend\dist\data\app.db`<br>**只读**（代码一律 `mode=ro` 打开，**无写路径**） | 🔴 **响亮** —— 读不到就报错 | ✅ 存在，364,544 B |
| **2** | **`dist\data\app.db`** | **`video-panel`**<br>`src-tauri\src\data.rs:53` | 同上。**且支持 `CATCHER_DB` 环境变量覆盖**（`data.rs:50`） | 🟢 **降级** —— `data.rs:316` 原文：<br>「**读不到就返回空表，不报错 —— 面板不该因为 catcher 换位置或没生成就整个打不开**」 | ✅ 存在 |
| **3** | **🔥 `backend\tools\ffmpeg.exe`** | **`video-workbench`**<br>`vw\config.py:19` `CATCHER_FFMPEG` | 🔴 **注意是 `backend\tools\`，不是 `dist\tools\`**<br>**借二进制**（只引用，不复制不修改） | 🔴 响亮 | ✅ 存在，**102,856,192 B** |
| **4** | **🔥 `backend\tools\ffmpeg.exe`** | **`video-panel`**<br>`src-tauri\src\data.rs:60` | 同上 | 🔴 响亮 | ✅ 同上 |
| **5** | **🔥 `backend\tools\ffmpeg.exe`** | **`VideoKnowledgeBase`**<br>`src\media.py:114` | **`find_ffmpeg()` 候选链的最后一级** | 🟠 **半静默** —— 返回 `None`，调用方**明确报错**，但**不会说"是 catcher 那边没了"** | ✅ 同上 |
| **6** | **端口 8000** | **`energy-recovery`**<br>`docs\run.md:55` · `services.yaml:7` | 它想用 `127.0.0.1:8000`，**而 8000 是 catcher 后端的端口** | 🟠 **冲突，两者不能同时跑** | 🔴 **被 catcher 占着（PID 2536）** |

> ⚠️ **本表 6 条边是实测的**（每条有文件:行号）。
> **「除此之外没有别的依赖方」是推断的** —— 依据是依赖登记册 + 项目地图 + 定向 grep，
> **不是穷举全扫的证明**。若将来在别的项目里发现第七条，以那条为准。

**🔴 「断了会怎样」汇总**：**响亮 3 条 · 半静默 1 条 · 降级 1 条 · 冲突 1 条。**

**🔴 真正危险的不是"找不到文件"，而是【列名】。**
`video-workbench` 与 `video-panel` 都在读 `records` / `tasks` 两张表：

| 表 | 被外部逐字引用的列 |
| --- | --- |
| **`records`** | `id` · `record_id`(*tasks 侧*) · `type` · `title` · `platform` · `tags` · `status` · `deleted` |
| **`tasks`** | `id` · `record_id` · `type` · `status` |

> **→ 改列名 = 断两个项目，而且是静默的。** 改 schema 之前先读本表。

### 🔴 另有三条「不是项目 ↔ 项目」的边

| 边 | 内容 |
| --- | --- |
| **`service-manager` → `catcher`** | **托管者**。`config\projects\catcher.yaml` 全权负责启停、健康探测、重启、日志。见 [`service.md`](service.md) |
| **公网 → `catcher`** | `catcher.xbywebwork.xyz` ← cloudflared → `localhost:8000` |
| **`catcher` → 自己的安卓端** | `android\...\config\ApiConfig.kt` 的 `BASE_URL = "https://catcher.xbywebwork.xyz/"` —— **改后端地址要同时改这里** |
| 🔴 **`catcher` → `Lattice`（**出向**，2026-10-05 新增）** | 后端**吃** Lattice 的数据口（`~\.lattice\api\current.json` 拿端口 → `http://127.0.0.1:<port>/v1/*`），再转发给手机的「远程」Tab。<br>**方向与上表相反** —— 上表是"别人吃 catcher"，这条是"catcher 吃别人"。<br>🔴 **断了只坏「远程」Tab**，记录/语音/文本不受影响。详见 [`INPUT-SLOT.md`](INPUT-SLOT.md)。 |

---

## 二、🔴 `ffmpeg.exe` 有三份，字节完全相同

| 路径 | 大小 | SHA256 |
| --- | --- | --- |
| **`backend\tools\ffmpeg.exe`** | **102,856,192 B** | `72A489ECCD008C2EC2C0A5856C5C75BC3D8BBFA90166C4566865C246445E6AA3` |
| `backend\dist\tools\ffmpeg.exe` | 102,856,192 B | **同上** |
| `backend\tools\ffmpeg-9.0.1-essentials_build\bin\ffmpeg.exe` | 102,856,192 B | **同上** |

**→ 三份字节完全相同（实测 SHA256）。**

**ffmpeg 总占 608.9 MB，而唯一载荷只有 98.1 MB：**

| 组成 | 大小 |
| --- | --- |
| 三份 `ffmpeg.exe` | 294 MB |
| `ffmpeg-9.0.1-essentials_build\`（解压态，45 文件） | 306.63 MB |
| `ffmpeg.zip` | 106.10 MB |

**🔴 关键：被三个项目借用的是 `backend\tools\ffmpeg.exe`**（契约 #3 #4 #5）——
**而它被 `.gitignore` 挡着**（`backend/tools/*.exe`）。
**`backend\dist\tools\ffmpeg.exe` 是 `build.bat` 第 [4/4] 步复制过去的，供运行期的 `config.py` 用。**

> 🔴 **它的位置已冻结。** 要动它，必须**两个项目在同一轮里一起动，且改完两边都验证**
> —— 这是 `PLAYBOOK.md` 五、已知的坑第 1 条明令禁止单独动的典型。

---

## 三、Python 依赖

### 形态：**有清单，但用 `>=`（本工程已见三种形态里的第 ② 种）**

`backend\requirements.txt`（**7 条，全部是 `>=`**）：

```
fastapi>=0.115
uvicorn>=0.30
python-multipart>=0.0.9
faster-whisper>=1.0
pytest>=8.0
httpx>=0.27
zhconv>=1.4
```

### 🔴 实测 `backend\.venv\` 里有 **101 个包**

**→ 7 条声明撑起 101 个包，差额全是传递依赖。**

| 包 | 大小 | 在清单里吗 |
| --- | --- | --- |
| **`nvidia\`** | 🔴 **2,017.4 MB** | ❌ **完全没有** |
| `ctranslate2` | 795.8 MB | ❌（是 `faster-whisper` 的传递依赖） |
| `av.libs` | 62.6 MB | ❌ |
| `onnxruntime` | 43.9 MB | ❌ |
| `numpy` + `numpy.libs` | 51.0 MB | ❌ |
| 其余 ~95 个包 | 约 91 MB | 大部分 ❌ |

**`backend\.venv\` 合计 3,061.75 MB / 9,504 文件。**

> 🔴 **`nvidia\` 那 2 GB 是 CUDA 运行库**（`cublas` / `cudnn` / `cuda_runtime` / `nvrtc`）。
> `build.bat` 第 [1/4] 步会把三个 DLL 从 `site-packages\nvidia\` **复制进 `ctranslate2\`**，
> 否则打包出来的 exe 找不到它们。
> **→ 换句话说：`build.bat` 依赖的是"venv 里恰好有 `nvidia\` 这个目录"，而这一点没有任何清单声明。**

**→ `requirements.txt` 说"要装什么"，但**装完之后能不能构建，取决于 101 个包里的某几个**。

---

## 四、其他依赖

| 项 | 值 |
| --- | --- |
| **Python 版本** | **3.13.7**（实测 `.venv`）—— ⚠️ **`CLAUDE.md` 写的是 3.12，不一致** |
| **模型** | `faster-whisper` **medium**（默认）+ small（备用）。`HF_HOME=<BASE_DIR>\.cache\huggingface`，镜像 `HF_ENDPOINT=https://hf-mirror.com` |
| **模型路由** | **零贡献** —— 与 `_shared\models\` / `_shared\routes.yaml` 无关 |
| **FFmpeg** | **项目内静态版**（`config.py` 的 `FFMPEG_PATH`，可用 `CLIP_FFMPEG_PATH` 覆盖） |
| **Android 侧** | Gradle 8.7（**项目内的 `.tools\gradle-8.7\`**）· Room · Retrofit · Compose · WorkManager |
| **硬编码绝对路径** | ✅ **后端源码零命中** —— 路径全部由 `config.py` 的 `BASE_DIR` 推算（`sys.executable` / `__file__`） |
| **引用其他项目** | 🔴 **已不成立（2026-10-05 起）** —— 后端现在**走 `Lattice` 的数据口**（`http://127.0.0.1:<随机高位端口>/v1/*`）。<br>**形态是「走接口」，不是「读文件」**：不 import 它的包、不读它的源码、不碰它的数据文件。<br>唯一读的实体文件是它自己公布的**端口发现文件** `~/.lattice/api/current.json`。<br>🔴 **完整声明见 [`INPUT-SLOT.md`](INPUT-SLOT.md)** —— 这是本项目**唯一的对外输入**。 |

### 🔴 Android 端的本机 ASR 模型（2026-10-11 新增）

**这三个模型是【下载来的外部资源】，不是本项目产出的** —— 迁移 / 备份 / 审计时必须知道。

| 用了什么 | 在哪 | 从哪来 | 大小 | SHA256 |
| --- | --- | --- | --- | --- |
| **Paraformer-small**（中文非流式 ASR，int8） | `android\app\src\main\assets\paraformer-small\model.int8.onnx` | `huggingface.co/csukuangfj/sherpa-onnx-paraformer-zh-small-2024-03-09`<br>（下载走 `hf-mirror.com` 镜像） | **78.04 MB** | `3EF6C19369B912F7CAF3CEF8E545C5CCD1A33D9D7EC792A46668DC41C4B229EC` |
| 同上 · 词表 | `…\paraformer-small\tokens.txt` | 同一个仓库 | 0.07 MB | `4B2D964E18B9CF139B473003B6698FB2ED9A2A5EC55B93DAA677B28F578897AA` |
| **Silero VAD** | `android\app\src\main\assets\silero_vad.onnx` | `github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx` | 0.614 MB（643,854 B） | `9E2449E1087496D8D4CABA907F23E0BD3F78D91FA552479BB9C23AC09CBB1FD6` |
| **标点恢复 · CT-Transformer**（中英混合，int8） | `android\app\src\main\assets\punct-ct-transformer-zh\model.int8.onnx` | `github.com/k2-fsa/sherpa-onnx/releases/download/punctuation-models/sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8.tar.bz2`<br>（原始出处：ModelScope `iic/punc_ct-transformer_zh-cn-common-vocab272727-pytorch`） | 75,519,198 B（**72.02 MB**） | `65A3FB9F5AD7BFB96BF69E0DC4481DF97F6EE60513C1D94CE981BA6EFFD524B1`<br>**tar 包**的 SHA256 `c0d5aa5f8eeb686032345e180bedf39319dc2e0556781c6264bcadba8328a6e1` **已比对该 release 官方的 `checksum.txt` 通过** |

- **用途**：手机本地语音转写（`asr/SherpaAsr.kt` + `recorder/LocalAsrRecorder.kt`）。
  🔴 **音频不出手机** —— 后端不参与识别，只收到识别后的文本（`type=voice` 的记录）。
- **架构**：**VAD 负责"在哪儿断句"，Paraformer 负责"这段是什么字"，CT-Transformer 负责"标点"** ——
  VAD 切段换来 ① 没有音频长度上限 ② 单次解码的输入长度可控。
  🔴 **标点是【每段独立】做的**（在解码线程里，开销分摊在录音过程中，点停止后的等待只含最后一段）。
  代价：VAD 在"停顿"处切段、而停顿 ≠ 句号，所以**停顿处可能被塞句号**。
  真机上若太多，改回"拼接后统一标点"只需把 `punctuate` 从解码线程挪到录音线程末尾，约 5 行。
- **形态**：三个模型都**打进 APK 的 assets**（配合 `androidResources { noCompress += "onnx" }`），
  **运行时不下载、不落磁盘** —— sherpa 的 `newFromAsset` 直接读 AssetManager。
- **标点引擎的线程约束**：🔴 `OfflinePunctuation` 内部是**一个 native 指针**，`addPunctuation` **不是线程安全的**。
  当前唯一调用点是 `LocalAsrRecorder.decodeLoop()`（单线程）→ 天然安全。**别处要调先想清楚。**
- **标点引擎的降级**：加载失败或调用异常都**原样返回文本**（削标点、不削功能）。
  **验收时"没标点"要先看 `SherpaAsr` 那行日志**，别以为模型没用上。
- **选型经过**（2026-10-11）：先试 `streaming-zipformer-zh-14M`（流式，25MB）→ **真机实测精度不够**；
  再评估 `SenseVoice-Small`（int8 228MB）→ 精度够但**有 30 秒/次硬上限**。
  最终 Paraformer-small + VAD：本地离线 · 精度够 · **没有时长上限**。
- ⚠️ **三个模型都不是本项目产出的**，删了要重下
  （Paraformer 从 hf-mirror 约 1 分钟；标点模型必须走**官方 tar + 校验和**，约 2.4 分钟）。

### 🔴 Android 端的「App 使用时长」采集（2026-10-11 新增，M9）

**需求来源**：**`snapshot` 项目** —— 它要按 App 算使用时长，而**这份数据只有手机拿得到**
（电脑上看不见手机在用哪个 App）。所以本项目扩了一个采集入口，数据从这里出去。

**用了什么外部资源：没有新增模型、没有新增二进制、没有新增服务。**
用的是 Android 系统自带的 `UsageStatsManager`（`android.app.usage`，API 21+）——
**不是下载来的东西**，跟着系统走。

| 项 | 值 |
| --- | --- |
| **系统 API** | `android.app.usage.UsageStatsManager.queryEvents(begin, end)` —— 返回**原始事件流** |
| **依赖的系统权限（两条，都只影响手机端）** | **`PACKAGE_USAGE_STATS`** —— 🔴 **特殊权限**，走 **AppOps**，**不在普通权限列表里**：`requestPermissions` 对它无效，声明它也不弹框，**只能在系统设置里手动打开**。查询用 `AppOpsManager.unsafeCheckOpNoThrow`（不是 `checkPermission`，那个对它永远返回 DENIED）。<br>**`QUERY_ALL_PACKAGES`** —— Android 11+ 靠它才能 `getApplicationInfo` 到**别的** App（拿可读名）。**没有它包名照样拿得到**（包名来自使用事件），只是没有中文名。⚠️ Google Play 对上架应用限制它 —— 本项目**不发布到 Play**，不受影响。 |
| **不用什么** | ❌ 不用无障碍服务（`AccessibilityService`）—— 那是"看屏幕内容"，本需求只要"哪个 App 在前台待了多久"，`UsageStats` 足够且权限更轻 |
| **设备标识** | `Settings.Secure.ANDROID_ID` 的 **SHA256 前 16 位**（不发明文；见 `collector/DeviceId.kt`） |

🔴 **一个必须知道的系统限制**：`queryEvents` 一般**只保留最近 1~7 天**。
**App 8 天没跑，中间那段拿不回来** —— 这是系统的保留策略，不是本项目的缺陷。
另见 [`run.md`](run.md) 的已知限制。

🔴 **音频/内容一律不出手机**（与上面 ASR 那条同一原则）：上报的是
**`{事件类型, 时间戳, 包名}`** 三元组，**没有 App 内容、没有屏幕文字、没有音频**。

### ⚠️ `config.py` 的路径基准（`PLAYBOOK` 已知的坑第 10 条）

```python
if getattr(sys, "frozen", False):
    BASE_DIR = Path(sys.executable).resolve().parent      # 打包态：exe 所在目录
else:
    BASE_DIR = Path(__file__).resolve().parent.parent     # 开发态：backend\
```

**→ 它是"算出来的"，不是"写死的"** —— **全项目 grep `D:\` 零命中**。
**判定：现在算出来的结果是对的，不要碰。**

---

## 五、🔴 凭据

| 项 | 值 |
| --- | --- |
| **`.env` / `.pem` / `.key` / `credentials*`** | ✅ **全目录零命中** |
| **云端 API key** | ✅ **没有**（不调任何大模型 API） |
| 🔴 **共享密钥** | **有，明文硬编码在源码里** —— `backend\app\config.py`：<br>`TOKEN = os.environ.get("CLIP_TOKEN", "dev-token-0123456789-abcdefghijklmnopqrstuvwx")` |

**🔴 关于这个 token，三条已实测的事实：**

1. **它在 git 里**，且**已经推到 `github.com/3431481073/catcher`**；
2. **这个项目有公网入口**（`catcher.xbywebwork.xyz`）；
3. **`catcher.yaml` L38 明确写着**：「`CLIP_TOKEN` **必须保持默认值**，否则手机端配对的 token 会失效」。

**→ 改它 = 手机端立刻 401，必须手工去手机上重配。**
**→ 本次整理【一个字没改】，只报告。** 见 [`log.md`](log.md) 的未解决。

**Token 的其他事实**（`architecture.md` §五）：
形态是**静态配置**（后端生成一次，前端首次启动由用户手动填入）·
安卓端存 DataStore（**明文可接受**）· 轮换是手动的 · 长度 ≥ 32 字符。
