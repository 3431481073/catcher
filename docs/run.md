# 怎么运行

> 依据：`backend\launcher.py`（4,602 B，**进程级入口**）+ `backend\build.spec` + `service-manager\config\projects\catcher.yaml`。
> **本文件只记已实测的事实。**

---

## 一、三条启动路径

### ① 手动（开发/调试）

```bat
backend\backend-control.bat
```

内容是 `start "" "dist\backend.exe"` —— **不带参数 = 正常启动态**
（若已在跑 → **弹模态框**「后端已在运行」，然后退出）。

配套三个助手脚本：

| 脚本 | 内容 | 说明 |
| --- | --- | --- |
| `backend-control.bat` | `start "" "dist\backend.exe"` | 启动 |
| `status.bat` | `start /wait "" "dist\backend.exe" --status` | 查状态，退出码 0/1 |
| `stop.bat` | `taskkill /IM backend.exe /F` | 🔴 **停 —— 但这个千万别用，见第四节** |

### ② 🔴 服务管理器托管（**当前实际方式**）

```
service-manager\config\projects\catcher.yaml
    command: D:\MyProject\catcher\backend\dist\backend.exe
    args:    ["--autostart"]
    cwd:     D:\MyProject\catcher\backend\dist
    env:     CLIP_NO_AUTOSTART: "1"
```

**实测（2026-10-03）**：`PID 2536`，父进程 `svcmgr.exe (3256)`，启动于 `2026-10-01 19:17:19`。

### ③ 开发态（不打包，直接跑源码）

```bash
cd backend
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

> ⚠️ **开发态的 `data\` 与打包态的 `data\` 是两套** —— `config.py` 里
> frozen 态用 `sys.executable` 的父目录，开发态用 `__file__` 上溯两级。
> **开发态写 `backend\data\`，打包态写 `backend\dist\data\`。** 见 [`data.md`](data.md)。

---

## 二、命令行参数（`launcher.py`，实测）

| 参数 | 行为 | 退出码 |
| --- | --- | --- |
| **（无）** | 正常启动。**若 8000 已被占用 → 弹框「后端已在运行」** 然后退出 | 0 |
| **`--autostart`** | **静默态** —— **端口被占用时直接退出，不弹模态框**。<br>🔴 **服务管理器无人值守拉起必须用这个**（不加的话端口冲突时进程会被对话框卡死） | 0 |
| **`--status`** | 探测 `http://127.0.0.1:8000/api/v1/health` | **0 = 在跑 / 1 = 没跑** |
| **`--uninstall-autostart`** | 删除 `HKCU\...\Run\ClipBackend` | 0（**本就不存在也返回 0**） |

**单实例机制**：`_already_running()` 用 TCP `connect_ex(("127.0.0.1", 8000))` ——
**端口占用即认为已有实例**，不做锁文件。**实测 8000 状态 = Listen，OwningProcess = 2536。**

---

## 三、🔴 端口 8000 是硬编码常量，写进 env 不生效

```python
# backend\launcher.py，模块级常量
HOST = "0.0.0.0"
PORT = 8000
...
uvicorn.run(app, host=HOST, port=PORT, log_config=None)
```

**`PORT` 不读任何环境变量。** `catcher.yaml` 里专门留了一句注释（原文）：

> `PORT` 在 `launcher.py` 里是硬编码常量，**写进 env 不会生效，只会误导**。

**→ 想换端口，必须改代码 + 重新打包。**

🔴 **而换端口会同时断两处**：
① 安卓端 `ApiConfig.kt` 的 `BASE_URL`（云端隧道指向 8000）；
② **`energy-recovery`** —— 它也想用 8000，**两者现在不能同时跑**。

---

## 四、🔴 为什么 `dist\data\backend.log` 会停更（**"日志不动"≠"程序死了"**）

`launcher.py` 的 `_setup_logging()`：

```python
if sys.stderr is None:   # ← 只有 --noconsole 且没有别的东西接管时才成立
    sys.stderr = open(str(log_path), "a", ...)
if sys.stdout is None:
    sys.stdout = open(str(log_path), "a", ...)
```

| 谁拉起它 | `sys.stdout/stderr` | 日志去哪 |
| --- | --- | --- |
| **双击 / `backend-control.bat`** | **`None`**（`--noconsole`） | ✅ **`dist\data\backend.log`** |
| 🔴 **`service-manager` 拉起** | **是管道（非 `None`）** | 🔴 **`service-manager\data\logs\catcher\backend.{out,err}.log`** |

**实测证据**：`dist\data\backend.log` 末行停在 **2026-09-26 11:39:49**，
**而进程启动于 2026-10-01 19:17** —— **对得上**。

> 🔴 **这是最容易误判的一处**：新装好的人会去看 `dist\data\backend.log`，
> 发现它几天没动，**以为后端挂了** —— 其实它在正常跑，只是日志被托管者接走了。
>
> **看日志请去** `service-manager\data\logs\catcher\` （**当前 err 文件约 1 MB/天，见下**）。

---

## 五、🔴 `stop.bat` 不要用

```bat
taskkill /IM backend.exe /F
```

**它按【镜像名】杀，会连带杀掉非本管理器拉起的所有 `backend.exe` 实例。**
`catcher.yaml` 里专门写了这一条（原文）：

> **注意 `stop.bat` 里的 `taskkill /IM backend.exe` 不要拿来当 stop 命令 —— 它按镜像名杀，
> 会连带杀掉非本管理器拉起的所有 `backend.exe` 实例。**

**正确停法**：走 `service-manager`（默认路径 `taskkill /T`，超时 2s）。

---

## 六、日志增长速率（实测）

| 项 | 值 |
| --- | --- |
| **谁在写** | **`service-manager` 的 liveness 探针，每 10 秒一次** `GET /api/v1/health` |
| **写进哪** | `service-manager\data\logs\catcher\backend.err.log` |
| **实测速率** | **2,267 行 / 6.3 小时 ≈ 1.09 MB/天** |
| **上限** | `maxSizeMB: 50` × `maxBackups: 5` → **最多约 300 MB** |
| ⚠️ **文件名叫 err，内容却全是 INFO** | **uvicorn 把 access log 写 stderr**，而 `out.log` 实测 **0 字节** |

---

## 七、⚠️ 版本不一致（未修正，只记录）

| 出处 | 说是什么 |
| --- | --- |
| `CLAUDE.md`「技术栈」一节 | **Python 3.12** |
| **`backend\.venv` 实测** | 🔴 **3.13.7** |
| `backend\build.spec` 生成的 `.pyc` | `cpython-313` |
| `requirements.txt` | **不声明 Python 版本** |

**→ 实物是 3.13.7，文档写 3.12。** 本次**未改任何一方**（代码零改动）。
**→ 已列进 [`log.md`](log.md) 的未解决。**

---

## 八、App 使用时长采集（手机端）：怎么授权、已知限制

> 需求来自 `snapshot`。后端侧怎么跑不变（还是 `backend.exe` 在 8000），
> **这一节只管手机端那一半**。架构见 [`architecture.md`](architecture.md) §10.8。

### 8.1 🔴 怎么授权（这段可以**照着念给用户**）

它要的不是普通权限，是「**使用情况访问**」—— 一个**特殊权限**，**没有授权弹窗**：

1. App 里**引导页第 3 项**（或记录页顶部那条提示）点「**去开启**」；
2. 跳到系统的「**使用情况访问**」页面（不同 ROM 位置不同，通常在
   *设置 → 应用 → 特殊应用权限 → 使用情况访问*）；
3. 在列表里找到「**剪藏**」，**打开开关**；
4. 退回 App —— **不用重启**，回来那一刻就生效（`MainActivity.onResume` 会重查）。

**如果点了「去开启」跳不出去**：部分 ROM 没有实现这个系统页面，App 会自动退到
「应用详情」页，从那里按上面第 2 步的网络位置自己找。这件事会记在
`Android/data/com.example.clip/files/crash.log` 里（`Permissions` 那一行）。

**不开会怎样**：**剪藏本身完全正常**（录音、剪藏、远程都不受影响），
只是**一条使用时长都不采集**。记录页顶部会一直挂一条提示 —— 不想要的话点「**不再提示**」，
**那是永久的，之后即使撤销授权也不会再出现**。

### 8.2 🔴 已知限制：**App 8 天没跑，中间那段拿不回来**

| | |
| --- | --- |
| **现象** | 手机装了 App 但**超过 7~8 天没打开过** → 中间那几天的使用时长，后端一条都不会有 |
| **原因** | Android 的 `UsageStatsManager.queryEvents` **只保留最近 1~7 天**（各 ROM 不同）。**不是本项目的缺陷**，是系统的保留策略 |
| **能不能补** | ❌ 不能。数据在系统那边已经被丢了 |
| **怎么避免** | App 只要**跑起来**就会采：开机自启、每次回前台、每 15 分钟一次的周期任务。**只要手机没把它彻底冻结/杀掉，就不会断** |

**同类的两条**（都不是缺陷，记在这里免得下次当成 bug 查）：

- **采集不是实时的**：周期任务最短 15 分钟，而且 **Doze 下会被推迟**。
  但**推迟不影响完整性** —— 拉取区间永远是「上次扫到的时间 − 5 分钟 到 now」，
  被推迟 3 小时就多拉 3 小时，**中间不漏**。
- **"开始用某个 App"到"后端能看到"之间有延迟**：手机上要等下一次采集 + 上报，
  后端还要等这一段**结束**（换 App / 锁屏）才会落成一条。**正在用的那个 App 看不到实时值。**
