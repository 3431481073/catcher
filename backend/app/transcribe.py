"""FFmpeg 转码（16k 单声道 WAV）+ faster-whisper 转写。"""
import asyncio
import logging
import subprocess
import time

import zhconv

from . import config

logger = logging.getLogger("transcribe")

# 模型单例：启动时后台加载一次，之后常驻内存，绝不重新加载。
_model = None
_load_lock = asyncio.Lock()


def convert_to_wav(raw_path: str, wav_path: str) -> None:
    """FFmpeg 转 16k 单声道 WAV。"""
    cmd = [
        config.FFMPEG_PATH, "-y", "-i", raw_path,
        "-ar", str(config.SAMPLE_RATE), "-ac", "1",
        wav_path,
    ]
    # CREATE_NO_WINDOW：打包后的 backend.exe 无控制台，不压窗口则每次调 ffmpeg 都会闪一下黑框
    proc = subprocess.run(cmd, capture_output=True, creationflags=subprocess.CREATE_NO_WINDOW)
    if proc.returncode != 0:
        err = proc.stderr.decode(errors="ignore")[-500:]
        raise RuntimeError(f"FFmpeg 转码失败: {err}")


def _load_model_blocking(model_name: str, device: str, compute_type: str):
    """同步加载模型（跑在线程池）。CUDA 失败自动降级 cpu/int8；降级也失败才抛出。"""
    from faster_whisper import WhisperModel

    t0 = time.monotonic()
    try:
        model = WhisperModel(model_name, device=device, compute_type=compute_type)
        logger.info("模型已加载 %s (%s/%s)，耗时 %.1fs", model_name, device, compute_type, time.monotonic() - t0)
        return model
    except Exception as e:
        if device != "cuda":
            raise
        logger.warning("CUDA 加载失败，降级 cpu/int8：%s", e)
        model = WhisperModel(model_name, device="cpu", compute_type="int8")
        logger.info("已降级加载 %s (cpu/int8)，耗时 %.1fs", model_name, time.monotonic() - t0)
        return model


async def preload() -> None:
    """启动时后台预加载模型（不阻塞 uvicorn 启动）。失败只记日志，第一个请求会兜底重试。"""
    global _model
    async with _load_lock:
        try:
            logger.info(
                "开始预加载模型 %s (%s/%s)",
                config.WHISPER_MODEL, config.WHISPER_DEVICE, config.WHISPER_COMPUTE_TYPE,
            )
            _model = await asyncio.to_thread(
                _load_model_blocking,
                config.WHISPER_MODEL, config.WHISPER_DEVICE, config.WHISPER_COMPUTE_TYPE,
            )
        except Exception as e:
            logger.exception("模型预加载失败：%s", e)


async def get_model():
    """请求路径：与预加载共用一把锁，等加载完成后返回常驻模型；模型缺失时同步兜底加载。"""
    global _model
    async with _load_lock:
        if _model is None:
            logger.info("模型尚未就绪，同步加载 %s", config.WHISPER_MODEL)
            _model = await asyncio.to_thread(
                _load_model_blocking,
                config.WHISPER_MODEL, config.WHISPER_DEVICE, config.WHISPER_COMPUTE_TYPE,
            )
        return _model


def transcribe_wav(model, wav_path: str) -> str:
    """对 16k 单声道 WAV 转写，返回简体纯文本。model 为已加载的常驻实例。

    简体化只靠 zhconv 后处理（繁体→简体），不影响英文/其他语言。
    """
    segments, _info = model.transcribe(wav_path)
    text = "".join(seg.text for seg in segments).strip()
    return zhconv.convert(text, "zh-cn")
