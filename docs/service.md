# 常驻进程与托管关系

> 依据：`service-manager\config\projects\catcher.yaml`（**只读**）+ `backend\launcher.py` + 实测进程表。
> 🔴 **本文件的关键结论：这个项目的【自启】不在本项目里。**

---

## 一、常驻进程：只有一个

| 项 | 值 |
| --- | --- |
| **进程** | `backend.exe` —— **FastAPI + SQLite[WAL] + 内嵌 worker 线程** |
| **实测 PID** | **2536** |
| **父进程** | 🔴 **`svcmgr.exe`（PID 3256）** —— 即 `service-manager` |
| **命令行** | `D:\MyProject\catcher\backend\dist\backend.exe --autostart` |
| **启动时间** | 2026-10-01 19:17:19 |
| **监听** | 🔴 **`0.0.0.0:8000`**（实测 State = `Listen`） |
| **单实例** | 靠**端口占用**判定（`launcher.py` 的 `_already_running()` 做 TCP `connect_ex`）—— **不用锁文件** |

**不构成独立服务的**：
SQLite 是**内嵌**（不是独立进程）· FFmpeg 是 **backend 自己调用的工具**（不是服务）· worker 是**线程**（不是进程）。

---

## 二、🔴 托管关系：`service-manager` 全权负责

`service-manager\config\projects\catcher.yaml` 的要点（**本次一字未改**，SHA256 `9A3917D0438BEF7014F5E8E5B7C67D74CF43366336411BC6FDF069497A585BCF`）：

```yaml
metadata:
  id: catcher
  name: 手机剪藏

spec:
  autoStart: true                    # ← 🔴 由服务管理器全权负责开机自启

  services:
    - id: backend
      command: D:\MyProject\catcher\backend\dist\backend.exe
      args: ["--autostart"]          # ← 静默态（端口被占用直接退出，不弹框）
      cwd:    D:\MyProject\catcher\backend\dist
      tags: ["生产", "核心"]
      host: "127.0.0.1"              # 展示/探活用；实际 bind 0.0.0.0:8000
      port: 8000
      env:
        CLIP_NO_AUTOSTART: "1"       # ← 🔴 告诉 launcher 别自己写 HKCU Run
```

### 🔴 `CLIP_NO_AUTOSTART=1` 这一行的来历（`catcher.yaml` 原文注释）

> `true`：由服务管理器全权负责开机自启。
> 配合下面 backend 的 `CLIP_NO_AUTOSTART=1` —— launcher 不再自己写
> `HKCU\...\Run\ClipBackend`，**避免开机时两边同时拉起抢 8000 端口**。
> 注意：如果此前已有 `ClipBackend` 自启项残留，仍需执行一次
> `dist\backend.exe --uninstall-autostart` 把它清掉，否则开机仍会有第二个拉起者。

**✅ 实测已清干净**：`HKCU\Software\Microsoft\Windows\CurrentVersion\Run` 里
**没有 `ClipBackend` 这一项**。

### 🔴 自启链（完整）

```
开机
 └─ service-manager（svcmgr.exe）启动
     └─ 读 config\projects\catcher.yaml
         └─ autoStart: true → 拉起
             D:\MyProject\catcher\backend\dist\backend.exe --autostart
               cwd = D:\MyProject\catcher\backend\dist
               env CLIP_NO_AUTOSTART=1
                 └─ launcher._autostart_muted() == True
                     └─ 跳过写 HKCU\...\Run\ClipBackend   ← 🔴 注册表里始终是空的
                 └─ _already_running()? 端口 8000 已被占 → 直接退出
                 └─ 否则 uvicorn.run(app, 0.0.0.0:8000)
```

---

## 三、健康探测

| 阶段 | 类型 | URL | 间隔 | 超时 | 重试 |
| --- | --- | --- | --- | --- | --- |
| **startup** | http | `http://127.0.0.1:8000/api/v1/health` | 1s | 2s | **30 次** |
| **liveness** | http | 同一个 | **10s** | 2s | **failureThreshold 3** |

**`catcher.yaml` 里两处刻意的决定（原文）**：

> **不写 `readiness`**：本项目只有 `/api/v1/health` 一个探针端点，没有独立的"可接流量"信号，
> **不编造探针**（写 readiness 只会和 liveness 打同一个 URL，纯冗余）。

> **liveness 失败会触发重启。** 转写在 worker 线程里跑，**事件循环不被占住**，
> `/health` 始终是轻量直返，10s 探测安全。

**⚠️ 副作用（实测）**：liveness 每 10s 一次 → **access log 约 1.09 MB/天**。
见 [`run.md`](run.md) 第六节。

---

## 四、重启策略

```yaml
restart:
  policy: on-failure
  maxRetries: 5
  window: 5m
  backoff: 1s
  maxBackoff: 60s
  jitter: true
  minRunTime: 10s
  cooldown: 5m
```

**`minRunTime: 10s` 的理由（原文）**：「`uvicorn.run` 会一直阻塞，**正常退出即异常**，
10s 足够判定"启动失败"」。

---

## 五、🔴 停止：**不要用 `stop.bat`**

```yaml
stop:
  timeout: 2s
```

**没有写 `stop.command`**（原文理由）：

> 后端没有 HTTP shutdown 端点，也没有可靠的优雅停止方式，
> 走管理器默认路径（`taskkill /T`，超时后强杀进程树）；**SQLite 是 WAL 模式，强杀不坏库**。

> 🔴 **注意 `stop.bat` 里的 `taskkill /IM backend.exe` 不要拿来当 stop 命令 ——
> 它按镜像名杀，会连带杀掉非本管理器拉起的所有 `backend.exe` 实例。**

**`timeout: 2s` 的理由（原文）**：「默认 10s 纯属空等：后端不会自己退出，优雅期只是浪费。
**实测 stop 从 11.1s 降到 2.5s 左右。**」

**🔴 对本工程的后果**：因为 `policy: on-failure` + 管理员拉起，
**`PLAYBOOK` 步骤 11 说过的那条在这里同样成立** ——
**按 PID 杀掉会被拉回来**（`remote-access` 的先例：4 秒内重新拉起）。
**→ 想停它，得先停 `service-manager` 或改它的配置。**

---

## 六、日志

```yaml
logs:
  stdout: data/logs/catcher/backend.out.log     # 相对 <service-manager>\data 解析
  stderr: data/logs/catcher/backend.err.log
  maxSizeMB: 50
  maxBackups: 5
  timestamp: true
```

**实测路径**：`D:\MyProject\service-manager\data\logs\catcher\`

| 文件 | 实测大小 |
| --- | --- |
| `backend.err.log` | 285,680 B |
| `backend.err.log.1` ~ `.5` | 各约 1.03~1.10 MB |
| **`backend.out.log`** | 🔴 **0 B** |

⚠️ **文件名叫 `err`，内容却是 INFO 访问日志** —— **uvicorn 把 access log 写 stderr**。
**`out.log` 是空的。**

---

## 七、🔴 公网入口：cloudflared

**`service-manager\config\projects\infra.yaml`**：

```yaml
metadata:
  id: infra
  name: 基础设施
spec:
  autoStart: true
  services:
    - id: cloudflared
      command: D:\cloudflared\cloudflared.exe
      args: ["tunnel", "--config", "C:\\Users\\asus\\.cloudflared\\config.yml", "run"]
      cwd: D:\cloudflared
```

**头两行（原文）**：

> **基础设施 —— 公网隧道。**
> 本项目只有一个常驻进程：**cloudflared（`catcher.xbywebwork.xyz` → `http://localhost:8000`）**。

**为什么把它从自启项搬进管理器（原文）**：

> 原来它走 `HKCU\...\Run\CloudflaredTunnel` … `cloudflared` 是控制台子系统程序，
> `start` 会给它单独分配一个控制台窗口；套 `/min` 只是把它最小化，任务栏上仍然留一个条目。
> **交给管理器后由 `internal/process` 用 `CREATE_NO_WINDOW` 启动，控制台窗口根本不建。**

🔴 **对本工程的后果**：

| | |
| --- | --- |
| **它是本工程第一个已知有公网入口的项目** | 安卓端 `ApiConfig.kt` 的 `BASE_URL` 指向它 |
| **它不在本项目里** | 在 `service-manager` 的 `infra.yaml` 里 —— **本项目内 grep 找不到** |
| **它决定了"哪些数据算敏感"** | **`CLIP_TOKEN` 是明文默认值 + 已上 GitHub + 已上公网** |
| ⚠️ **本轮未核实 cloudflared 是否真在跑** | 见 [`log.md`](log.md) 的未解决 |

---

## 八、🔴 自启的失效模式：**静默**

**`PLAYBOOK.md` 五、已知的坑第 15 条要求**：每处外部持久状态都要标注它的失效模式（响亮 / 静默）。

| | |
| --- | --- |
| **本项目的自启** | `service-manager\config\projects\catcher.yaml` 的 `autoStart: true` |
| **失效模式** | 🔴 **静默** |
| **具体表现** | `catcher.yaml` 若丢了 / 被改名 / `autoStart` 被改成 false → **开机时什么都不发生**。**不报错、不弹框、没有提示。** |
| **用户会看到什么** | 🟠 **只是"今天手机发来的记录没进来"** —— **他不会想到去查 `service-manager` 的配置** |
| **自愈方式** | 恢复 `catcher.yaml`；或手工 `dist\backend.exe` 跑一次（**但它不会自写注册表了**，因为 `CLIP_NO_AUTOSTART=1`） |

> ⚠️ **这条比 `voice_tool` / `Workbench` 那两处更隐蔽** ——
> 那两处的自启至少还在**本项目注册表里**；**这个项目的自启已经搬到别的项目里去了。**
> **→ 这是第三种形态，`PLAYBOOK` 已知的坑第 15 条原先没有覆盖。**
