# App 使用时长：外部程序怎么读

> 配套：[`usage-fields.md`](usage-fields.md)（字段是什么意思）· [`usage-sample.json`](usage-sample.json)（一条真实数据）
> 本文只讲**怎么取到**。建立于 **2026-10-12**。

**三种读法，按推荐顺序：**

| # | 方式 | 什么时候用 | 要不要 token |
| --- | --- | --- | --- |
| **①** | **HTTP `GET /api/v1/usage`** | 🔴 **首选** | **要 `X-Token`** |
| **②** | **sqlite3 只读直读库** | 要批量/离线/复杂 SQL，或不想走网络 | 不要 |
| **③** | `GET /api/v1/usage/summary` | 只要"各 App 总时长"排行 | **要 `X-Token`** |

---

## ① HTTP 接口（首选）

**基址**：`http://127.0.0.1:8000`（同机）或 `https://catcher.xbywebwork.xyz`（公网，⚠️ 见 §4）
**鉴权**：**所有 `/api/v1/*` 都要 `X-Token` 头**（`/health` 除外）。**token 在 `backend\app\config.py` 里，是静态默认值**。

### 1.1 按本地某一天查

```bash
curl -H "X-Token: <token>" \
  "http://127.0.0.1:8000/api/v1/usage?date=2026-10-11"
```

`date` 的语义：**服务器本地时区（UTC+8）的那一整天**，自动换算成 UTC 区间去查。

**返回**：
```json
{
  "ok": true,
  "data": {
    "records": [ { …一条段，见下… } ],
    "from": "2026-10-10T16:00:00.000000Z",
    "to":   "2026-10-11T16:00:00.000000Z"
  },
  "error": null
}
```

**`records[]` 每条 9 个字段**（2026-10-12 实测，**和 `usage_records` 的列完全一致**）：
```
id · device_id · package_name · app_label · start_time · end_time · duration_seconds
created_at · updated_at
```

| 后加的两个 | 语义 |
| --- | --- |
| `created_at` | 这条段**第一次被写进库**的时刻（**不是使用时间**） |
| `updated_at` | 这条段**最后一次被改写**的时刻。⚠️ 同一段被重复上报时会**原地覆盖** ⇒ **它会变，而 `start_time`/`end_time` 不变** —— **别拿它当"要不要重新拉"的判据** |

🔴 **`from` / `to` 是接口实际用的 UTC 窗口，务必读它** —— 别自己猜时区，**拿这个窗口去核对**。
（上面这组就是"本地 10-11 一整天" ⇒ UTC 的前一天 16:00 到当天 16:00。）

### 1.2 按区间 / 按包名查

```bash
# 带时区的 ISO8601（无歧义，推荐）
curl -H "X-Token: <token>" \
  "http://127.0.0.1:8000/api/v1/usage?from=2026-10-10T16:00:00Z&to=2026-10-11T16:00:00Z"

# 只看某个 App
curl -H "X-Token: <token>" \
  "http://127.0.0.1:8000/api/v1/usage?package=com.tencent.mm&date=2026-10-11"

# 只看某台设备（目前只有一台）
curl -H "X-Token: <token>" \
  "http://127.0.0.1:8000/api/v1/usage?device_id=2b9067a95b4d2d34"
```

| 参数 | 说明 |
| --- | --- |
| `date` | `YYYY-MM-DD`，**服务器本地时区**的一天 |
| `from` / `to` | ISO8601。**带时区最无歧义**；不带时区按服务器本地解释；**只给日期则整天** |
| `package` | 包名精确匹配 |
| `device_id` | 设备标识 |
| `limit` | 默认 5000，上限 20000 |

**优先级**：`from`/`to` 同时给时**以它们为准**，`date` 被忽略。

### 1.3 汇总（各 App 总时长）

```bash
curl -H "X-Token: <token>" \
  "http://127.0.0.1:8000/api/v1/usage/summary?date=2026-10-11"
```
```json
{
  "ok": true,
  "data": {
    "records": [
      { "package_name": "com.ss.android.ugc.aweme", "app_label": "抖音",
        "total_seconds": 25105, "segments": 173,
        "first_start": "2026-10-10T16:12:03.111000Z",
        "last_end":    "2026-10-11T15:58:44.902000Z" }
    ],
    "total_seconds": 54771,
    "from": "2026-10-10T16:00:00.000000Z",
    "to":   "2026-10-11T16:00:00.000000Z"
  }
}
```
**`records` 已按 `total_seconds` 降序**（就是 App 使用排行）。
⚠️ **`total_seconds` 是 `duration_seconds` 的求和 ⇒ 逐段向下取整的误差会累积**（见 [`usage-fields.md`](usage-fields.md) §6.2）。

### 1.4 错误码

| code | HTTP | 什么时候 |
| --- | --- | --- |
| `UNAUTHORIZED` | 401 | token 不对 |
| `INVALID_PAYLOAD` | 400 | `date` 不是 `YYYY-MM-DD`，或 `from`/`to` 不是合法时间 |
| `SERVER_ERROR` | 500 | 服务端（表不存在等） |

---

## ② sqlite3 只读直读（不要走网络时用）

**库路径（唯一）**：
```
D:\MyProject\catcher\backend\dist\data\app.db
```

**🔴 必须用只读方式打开。** 这个库同时被**正在运行的后端**写（WAL 模式），**直读绝对不能加锁、更不能写**：

```python
import sqlite3
conn = sqlite3.connect("file:D:/MyProject/catcher/backend/dist/data/app.db?mode=ro", uri=True)
```
```powershell
# PowerShell / 命令行
sqlite3 "file:D:/MyProject/catcher/backend/dist/data/app.db?mode=ro" "SELECT COUNT(*) FROM usage_records;"
```

**WAL 注意事项**：库是 **WAL 模式**，`-wal` 里可能有还没 checkpoint 的**最新**数据。
**⇒ 只复制 `app.db` 一个文件可能拿到旧快照。** 要复制就**连 `app.db-wal` / `app.db-shm` 一起复制**，或者**先确认 `-wal` 是 0 字节**。

### 2.1 直读时的时区换算

**库里全是 UTC**，而"某一天"是本地概念。**直读必须自己换**：

```sql
-- 本地(UTC+8) 的 2026-10-11 一整天
SELECT package_name, app_label, start_time, end_time, duration_seconds
FROM usage_records
WHERE start_time >= '2026-10-10T16:00:00.000000Z'   -- 本地 10-11 00:00
  AND start_time <  '2026-10-11T16:00:00.000000Z';  -- 本地 10-12 00:00
```

🔴 **ISO 串是定宽的**（29 字符）⇒ **字符串比较等价于时间比较**，不用解析、不用 `datetime()`。

**要转成本地时间显示**（SQLite 内，**已实测可用**）：

```sql
SELECT package_name,
       datetime(end_time, '+8 hours') AS local_end
FROM usage_records LIMIT 5;
```

**实测（本机 SQLite，2026-10-12）**：
```
datetime('2026-10-11T19:34:14.207000Z')                  → '2026-10-11 19:34:14'
datetime('2026-10-11T19:34:14.207000Z', '+8 hours')      → '2026-10-12 03:34:14'   ✅ 换算正确
strftime('%Y-%m-%d %H:%M', '…207000Z', '+8 hours')       → '2026-10-12 03:34'
```
**⇒ SQLite 完全认「微秒 + Z」，`+8 hours` 也按预期把 UTC 推成 UTC+8。**
（`datetime()` **丢掉微秒**、精确到秒 —— 要对秒以下精度就在程序里用 `datetime.fromisoformat()`。）

### 2.2 按 App 排行（直读版）

```sql
SELECT package_name,
       MAX(app_label)          AS app_label,
       SUM(duration_seconds)   AS total_seconds,
       COUNT(*)                AS segments
FROM usage_records
WHERE start_time >= '2026-10-10T16:00:00.000000Z'
  AND start_time <  '2026-10-11T16:00:00.000000Z'
GROUP BY package_name
ORDER BY total_seconds DESC;
```

---

## ③ 两者的取舍

| | HTTP 接口 | 直读 sqlite |
| --- | --- | --- |
| 鉴权 | 要 token | 不要 |
| 时区 | **接口帮你换算**（并回显 `from`/`to`） | **自己换算** |
| 复杂 SQL | ❌ 只能按 `package` 过滤 | ✅ 随便写 |
| 依赖 | 后端要在跑 | 只要文件在 |
| 一致性 | 走 WAL 正常读，**总是最新** | 复制文件时可能拿到旧快照 |
| 🔴 风险 | 无 | **写错会锁库/改库** |

**⇒ 日常用 ①；要复杂统计用 ②，但只读打开。**

---

## ④ 公网入口的现状（2026-10-12 实测，**读之前请先看这条**）

`https://catcher.xbywebwork.xyz` 走 Cloudflare 隧道。**实测这条链路现在很差**：

| 目标 | 结果 |
| --- | --- |
| 本机 `http://127.0.0.1:8000` | **15~25 ms** ✅ |
| 公网 `https://catcher.xbywebwork.xyz` | **TLS 握手 1.4~8.7 秒；8 连发里 3 次完全超时** 🔴 |
| 对照：本机到国内站 | 0.07 秒 ✅ |

**⇒ 同机消费请直接打 `127.0.0.1:8000`，不要绕公网。**
（这是链路问题，不是接口问题 —— 后端到 Lattice 实测 5~42 ms。）

---

## ⑤ 还有两个接口（不是给消费者读的）

| 接口 | 干什么 |
| --- | --- |
| `POST /api/v1/usage` | **手机上报原始事件用**。返回 `{accepted, duplicates, unknown, oversized_skipped, pending_stale_rejected}` |
| `POST /api/v1/usage/trigger` | **让手机立刻采一次**。返回 `{delivered}` —— **0 = 手机没连着 WS**（只在它开着「远程」Tab 时连着） |

---

> **本文描述的是 `2026-10-12` 的实际行为。** 接口契约见 [`architecture.md`](architecture.md) §10.8。
