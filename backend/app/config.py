"""全局配置。所有可调参数集中在这里，运行时用环境变量覆盖。"""
import os
import sys
from pathlib import Path

if getattr(sys, "frozen", False):
    # PyInstaller 打包态：data/tools/.cache 都落在 backend.exe 旁边（不受 cwd 影响）
    BASE_DIR = Path(sys.executable).resolve().parent
else:
    BASE_DIR = Path(__file__).resolve().parent.parent  # backend/

DATA_DIR = BASE_DIR / "data"
AUDIO_DIR = DATA_DIR / "audio"
WAV_DIR = DATA_DIR / "wav"
DB_PATH = DATA_DIR / "app.db"
TOOLS_DIR = BASE_DIR / "tools"

# Token：静态配置（≥32 字符），env CLIP_TOKEN 可覆盖。
# 后端生成一次，M2 手机端手动填入同一个值。
TOKEN = os.environ.get(
    "CLIP_TOKEN",
    "dev-token-0123456789-abcdefghijklmnopqrstuvwx",
)

# 容量边界（架构 §六）
MAX_AUDIO_BYTES = 50 * 1024 * 1024  # 50MB
QUEUE_LIMIT = 5                     # 只用于展示位次，不拒绝超限

# 转码 / 转写
SAMPLE_RATE = 16000                 # 16k 单声道
WHISPER_MODEL = os.environ.get("CLIP_WHISPER_MODEL", "medium")
WHISPER_DEVICE = os.environ.get("CLIP_WHISPER_DEVICE", "cuda")
WHISPER_COMPUTE_TYPE = os.environ.get("CLIP_WHISPER_COMPUTE_TYPE", "float16")

# 语音功能总开关，默认停用（不预加载模型、worker 不领转写任务、/transcribe 直接拒绝）。
# 历史 voice 记录、模型文件、录音/上传代码全部保留，恢复只需给服务管理器 YAML 加
# env CLIP_VOICE_ENABLED: "1"，不必重新打包。
VOICE_ENABLED = os.environ.get("CLIP_VOICE_ENABLED", "0") != "0"

# FFmpeg 可执行文件路径（项目内静态版）
FFMPEG_PATH = os.environ.get("CLIP_FFMPEG_PATH", str(TOOLS_DIR / "ffmpeg.exe"))

# ── Lattice 远程转发（手机 → 本后端 → 127.0.0.1:<Lattice 端口>）─────────────
# Lattice 的数据口端口是随机的（44770+），每次请求都要重读它写的发现文件。
#
# 🔴 用 Path.home() 展开，不写死 C:\Users\asus —— 换启动身份/换机器时不会静默失联。
# 注意这与 service-manager 的 infra.yaml 相反：那里给 cloudflared 写死了路径，
# 并在注释里说明了理由（不依赖 USERPROFILE）。这里没有那个理由，用展开更稳。
#
# LATTICE_HOME 可覆盖是【测试能离线跑的关键】——测试把它指到 tmp_path。
LATTICE_HOME = Path(os.environ.get("LATTICE_HOME") or (Path.home() / ".lattice"))
LATTICE_API_DIR = LATTICE_HOME / "api"
# Lattice 实例的发现文件（"最近启动的那个实例"）。不是独立进程时它就是唯一的实例信息源。
LATTICE_DISCOVERY_FILE = LATTICE_API_DIR / "current.json"

# 超时必须紧：Lattice 卡死时不能让手机的轮询请求堆积。
# connect 短（loopback 被拒是毫秒级）、read 稍长（渲染层要现算窗格状态）。
LATTICE_CONNECT_TIMEOUT = float(os.environ.get("CLIP_LATTICE_CONNECT_TIMEOUT", "2"))
LATTICE_READ_TIMEOUT = float(os.environ.get("CLIP_LATTICE_READ_TIMEOUT", "10"))
LATTICE_WRITE_TIMEOUT = float(os.environ.get("CLIP_LATTICE_WRITE_TIMEOUT", "5"))

# HuggingFace 模型缓存 + 国内镜像（在 faster_whisper 导入前生效；可用 env 覆盖）
os.environ.setdefault("HF_HOME", str(BASE_DIR / ".cache" / "huggingface"))
os.environ.setdefault("HF_ENDPOINT", "https://hf-mirror.com")
