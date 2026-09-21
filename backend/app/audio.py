"""音频校验与落盘。"""
from . import config
from .errors import AppError

_SAFE_EXT = {"webm", "opus", "m4a", "mp3", "wav", "aac", "ogg", "mp4", "3gp", "amr"}


def save_audio(record_id: str, filename: str, content: bytes) -> str:
    """校验大小并保存原始音频到 data/audio/，返回绝对路径。"""
    if not content:
        raise AppError("INVALID_PAYLOAD", "音频为空", 400)
    if len(content) > config.MAX_AUDIO_BYTES:
        raise AppError("INVALID_PAYLOAD", "音频超过 50MB 上限", 400)

    ext = filename.rsplit(".", 1)[-1].lower() if "." in filename else "bin"
    if ext not in _SAFE_EXT:
        ext = "bin"
    path = config.AUDIO_DIR / f"{record_id}.{ext}"
    path.write_bytes(content)
    return str(path)
