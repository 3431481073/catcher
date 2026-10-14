# 运行日志：跑起来之后，话都说到哪去了

> 起草 **2026-10-09**（`D:\MyProject\CLAUDE.md` §1 的六件套第 7 格，**可关**）。
> **本文件只记"日志落在哪、怎么看"，不记日志内容。**
> 🔴 **本项目【开着】这一格** —— 因为它有两处**很容易找错地方**的日志。

---

## 一、后端：**别看 `dist\data\backend.log`**（它可能几天没动）

**同一份日志，落在哪取决于【谁把它拉起来的】：**

| 谁拉起 | `sys.stdout/stderr` | 日志实际去哪 |
| --- | --- | --- |
| 双击 / `backend-control.bat` | `None`（`--noconsole`） | `backend\dist\data\backend.log` |
| 🔴 **`service-manager` 拉起**（**当前实际方式**） | **是管道（非 `None`）** | 🔴 **`service-manager\data\logs\catcher\backend.{out,err}.log`** |

**实测**：`dist\data\backend.log` 末行停在 **2026-09-26 11:39:49**，
而进程是从 **2026-10-01** 起一直在跑的 —— **对得上，它没死，只是日志被托管者接走了。**

> 🔴 **这是本项目最容易误判的一处**：新接手的人去看 `dist\data\backend.log`，
> 发现几天没动 → **以为后端挂了**。

**当前该看的地方**：

```
D:\MyProject\service-manager\data\logs\catcher\backend.err.log
```

| 项 | 值 |
| --- | --- |
| **谁在写** | 主要是 `service-manager` 的 **liveness 探针**（每 10 秒一次 `GET /api/v1/health`） |
| **速率** | **约 1.09 MB/天**（实测 2,267 行 / 6.3 小时） |
| **轮转上限** | `maxSizeMB: 50` × `maxBackups: 5` → **最多约 300 MB** |
| ⚠️ **文件名叫 `err`，内容却全是 INFO** | **uvicorn 把 access log 写 stderr**；`out.log` 实测 **0 字节** |

**轮转文件**：`backend.err.log.1` ~ `.5`（各约 1 MB，按天滚）。

> ⚠️ **`README` / `logs` 里那 60 行 `GET /api/v1/health` 不是人在用** ——
> 是探针。**排查问题时要能一眼滤掉**。

---

## 二、Android 端：`FileLogger` → 手机上的一个文件

| 项 | 值 |
| --- | --- |
| **实现** | `android\...\util\FileLogger.kt` |
| **落盘位置** | 🔴 **`getExternalFilesDir(null)\crash.log`** |
| **在手机上就是** | `/sdcard/Android/data/com.example.clip/files/crash.log` |
| **同时** | 也打 logcat |
| **写入时机** | `ClipApp.onCreate` 里 `init()` 一次；之后 `FileLogger.{i,w,e}` 随时写 |
| **为什么落盘** | 🔴 **源码注释原文**：「落盘是为了**用户连不上 adb 时也能把崩溃点发回来**」 |
| **崩溃捕获** | `ClipApp` 设了 `Thread.setDefaultUncaughtExceptionHandler` → 先写 `crash.log` 再转交默认处理 |

### 🔴 它也是"要找对地方"的一类

| 你会想去看 | 实际上 |
| --- | --- |
| `adb logcat` | ✅ 有，**但手机在外面 4G 时你连不上 adb** |
| 🔴 **`/sdcard/Android/data/com.example.clip/files/crash.log`** | ✅ **这个才是设计给"发回来"用的** |

⚠️ **但 Android 11+ 起，文件管理器对 `Android\data\` 的访问被系统收紧了** ——
部分机型要连着数据线用电脑看，或用 `adb pull`。
**本轮没有在真机上核过这个文件当前存不存在、有多大** —— 列进未解决。

---

## 三、不要指望的三处

| 位置 | 为什么不要看 |
| --- | --- |
| `backend\dist\data\backend.log` | 🟠 **僵尸日志** —— 被 svcmgr 拉起时**不再增长**（除非你手工双击启动过） |
| `service-manager\data\logs\catcher\backend.out.log` | 🔴 **0 字节**（uvicorn 全写 stderr） |
| `backend\dev_uvicorn.{out,err}.log` | 开发态调试留下的，2026-09-19 之后再没写过 |

---

## 四、这一格开着的理由

**§1 说 `runtime-log` 可关。本项目选择【开】，因为：**

1. 后端的日志落在**别的项目里**（`service-manager\data\logs\`）—— 不说就会找错；
2. 本项目内还留着一份**同名但停更**的日志 —— 不说就会误判成"挂了";
3. 安卓端的日志在**手机上**，而排查的人通常坐在电脑前 —— 不说就捞不到。

**三条都会让人得出错误结论**，所以这一格不留空。

---

> 🔴 **事实来源带日期（2026-10-09）。日志速率是实测值，会随探针间隔变。**
