# backend.exe 打包说明（M6 + GPU）

## 产物
- `dist/backend.exe`：单文件、无窗口的后端（内含 faster-whisper + CUDA 库，约 600MB）。
- `dist/tools/ffmpeg.exe`：外部引用的静态 FFmpeg（build.bat 自动复制）。
- `dist/.cache/huggingface/hub/`：medium 模型缓存（约 1.46GB，build.bat 复制）。

## 运行
- 双击 `dist/backend.exe` → 后台运行，监听 `0.0.0.0:8000`。
- 首次运行自动写入开机自启 `HKCU\Software\Microsoft\Windows\CurrentVersion\Run\ClipBackend`，且每次启动都幂等重写（exe 移动后自启仍指向新路径）。
- 已在运行时再次双击 → 弹框「后端已在运行」。

> **首次启动较慢是正常的**：exe 解压 + CUDA 初始化 + medium 模型加载，可能 1-2 分钟。
> 等 `dist\data\backend.log` 出现「模型已加载 medium (cuda/float16)」再使用——此时转写走 GPU。

## 参数
| 参数 | 行为 |
|---|---|
| （无参数） | 正常启动 |
| `--autostart` | 开机自启静默启动；已有实例则静默退出 |
| `--uninstall-autostart` | 删除自启键 |
| `--status` | 检查后端是否在跑，退出码 0/1（用 `status.bat` 查看） |

## 数据与模型
- 数据目录 `dist/data/`（app.db / audio / wav），相对 exe 所在目录，与开发态 `backend/data/` 独立。
- 转写默认走 **medium 模型 + GPU（cuda/float16）**，模型从 `dist/.cache/huggingface/` 读；缺失时首次启动自动下载。
- 若 CUDA 不可用会自动降级 cpu/int8（backend.log 有降级日志），但 medium 在 CPU 上很慢，属兜底非推荐。
