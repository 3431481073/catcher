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

# FFmpeg 可执行文件路径（项目内静态版）
FFMPEG_PATH = os.environ.get("CLIP_FFMPEG_PATH", str(TOOLS_DIR / "ffmpeg.exe"))

# HuggingFace 模型缓存 + 国内镜像（在 faster_whisper 导入前生效；可用 env 覆盖）
os.environ.setdefault("HF_HOME", str(BASE_DIR / ".cache" / "huggingface"))
os.environ.setdefault("HF_ENDPOINT", "https://hf-mirror.com")
