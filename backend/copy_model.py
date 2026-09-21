"""build.bat 用：把 medium 模型复制到 dist/.cache，保留 symlink。

robocopy /E /SL 在非提权下会报 1314「所需特权未持有」，而 venv 的 python 能建 symlink
（huggingface_hub 缓存就是它建的），所以这里用 shutil.copytree(symlinks=True)。
"""
import os
import shutil

BASE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(BASE, ".cache", "huggingface", "hub", "models--Systran--faster-whisper-medium")
DST_CACHE = os.path.join(BASE, "dist", ".cache")
DST = os.path.join(DST_CACHE, "huggingface", "hub", "models--Systran--faster-whisper-medium")

if os.path.exists(DST_CACHE):
    shutil.rmtree(DST_CACHE)
os.makedirs(os.path.dirname(DST), exist_ok=True)

try:
    shutil.copytree(SRC, DST, symlinks=True)
    print("medium 模型已复制（保留 symlink）")
except OSError as e:
    print(f"symlink 复制失败，回退实体复制: {e}")
    shutil.copytree(SRC, DST, symlinks=False)
    print("medium 模型已复制（实体化，体积翻倍）")
