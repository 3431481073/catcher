# -*- mode: python ; coding: utf-8 -*-
"""PyInstaller 打包配置：入口 launcher.py，**onedir（目录）** + 无控制台。

为什么不是 onefile（2026-09-21 改）：
  onefile 每次启动都要把 ~1GB 解包到 %TEMP%\\_MEIxxxxx。被强杀时 bootloader 不清理，
  而服务管理器的 stop 走 taskkill 强杀 —— 实测每次启停都留 0.95GB，38 次启动堆了 35GB 塞满 C 盘。
  onedir 不产生临时目录（不止 C 盘，任何盘都不长），启动也不再等解包：5~15s → <1s。

输出：<distpath>/backend/backend.exe + <distpath>/backend/_internal/
部署：build.bat 把它拷成 dist\\backend.exe + dist\\_internal\\ —— 与 onefile 时代的布局一致，
      所以 config.py 的 BASE_DIR（= exe 所在目录）、服务管理器 YAML、bat 助手都不需要改。
      数据目录 data\\ / tools\\ / .cache\\ 始终在 dist\\ 下、**不在构建输出目录内**，
      这样 --noconfirm 删除输出目录时永远不会碰到 app.db。
"""
from PyInstaller.utils.hooks import collect_all

datas = []
binaries = []
hiddenimports = []

# 原生库 / 含二进制资源的包显式 collect，防止丢 dll/pyd
#
# 🔴 websockets 也在这一批里，理由和别的不一样：它不是"含二进制资源"，而是
#    **uvicorn 用 importlib.import_module 按名字动态找的**（protocols/websockets/auto.py）。
#    PyInstaller 只追静态 import，追不到这条 ⇒ 不加的话本地跑得好好的、
#    打包出来 WS 端点静默失效（握手阶段直接失败，且没有任何报错线索）。
for pkg in ("av", "ctranslate2", "tokenizers", "huggingface_hub", "faster_whisper", "zhconv", "websockets"):
    d, b, h = collect_all(pkg)
    datas += d
    binaries += b
    hiddenimports += h

# uvicorn 的 loop/protocol 是动态导入，显式包含
hiddenimports += [
    "uvicorn.logging",
    "uvicorn.loops",
    "uvicorn.loops.auto",
    "uvicorn.loops.asyncio",
    "uvicorn.protocols",
    "uvicorn.protocols.http",
    "uvicorn.protocols.http.auto",
    "uvicorn.protocols.http.h11_impl",
    "uvicorn.protocols.websockets",
    "uvicorn.protocols.websockets.auto",
    "uvicorn.lifespan",
    "uvicorn.lifespan.on",
]

# httpx 及其后端：转发到 Lattice 用（app/lattice.py）。
# `import httpx` 是静态的，PyInstaller 通常能自动收全；但 httpcore 的后端选择
# （httpcore._backends.auto 按同步/异步挑 sync/async）有动态成分，历史上有漏收记录。
# 这几条是**防御性**的 —— 多收几个纯 Python 模块，代价是几 KB。
hiddenimports += [
    "httpx",
    "httpcore",
    "httpcore._backends",
    "httpcore._backends.auto",
    "httpcore._backends.base",
    "httpcore._backends.sync",
    "httpcore._backends.anyio",
    "h11",
    "certifi",
    "idna",
    "sniffio",
    "anyio",
    "anyio._backends",
    "anyio._backends._asyncio",
]

a = Analysis(
    ["launcher.py"],
    pathex=["."],
    binaries=binaries,
    datas=datas,
    hiddenimports=hiddenimports,
    # torch/torchaudio 必须排除：本项目的 Python **一处都没 import 它们**
    # （faster-whisper 走 ctranslate2，不走 torch）。但 venv 里装了 torch 之后，
    # PyInstaller 会顺着某条 import 图把它连同 CUDA 全家桶一起收进来 ——
    # 实测 _internal 从 970 MB 涨到 7.97 GB（torch 4.1 GB + torch_cuda.dll 774 MB
    # + cublasLt64_12.dll 643 MB + cusparse64_12.dll 362 MB + cudnn 系列）。
    # 排除后回到 970 MB 量级，且与 2026-09-26 那次可用构建的文件集一致。
    excludes=["tkinter", "pytest", "torch", "torchaudio", "torchvision"],
    noarchive=False,
)
pyz = PYZ(a.pure)

# onedir：exe 只装 bootloader + PYZ，二进制/数据交给 COLLECT 放到目录里
exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name="backend",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=False,  # 无窗口
    disable_windowed_traceback=False,
)

coll = COLLECT(
    exe,
    a.binaries,
    a.datas,
    strip=False,
    upx=False,
    name="backend",
)
