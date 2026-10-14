"""FastAPI 入口。"""
import asyncio
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI

from . import config
from . import db
from . import lattice
from . import transcribe as transcribe_mod
from .errors import register_error_handlers
from .routes import health, records, tasks, transcribe
from .routes import lattice as lattice_routes
from .routes import usage as usage_routes
from .worker import worker

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("main")


@asynccontextmanager
async def lifespan(app: FastAPI):
    db.init_db()
    await worker.start()
    # 后台预加载模型（不阻塞启动）；第一个请求若赶在加载完成前，会在 get_model 里等锁
    if config.VOICE_ENABLED:
        asyncio.create_task(transcribe_mod.preload())
    else:
        # 这行日志是判定依据：nvidia-smi 无占用无法区分「跳过了加载」和「没走到这段代码」
        logger.info("CLIP_VOICE_ENABLED=0，跳过 whisper 模型预加载（不占显存）")
    yield
    await worker.stop()
    # 转发用的 httpx client 是懒加载的，没被用过就没什么可关的
    await lattice.aclose()


app = FastAPI(title="手机剪藏", lifespan=lifespan)
register_error_handlers(app)

app.include_router(health.router)
app.include_router(transcribe.router)
app.include_router(records.router)
app.include_router(tasks.router)
app.include_router(lattice_routes.router)
app.include_router(usage_routes.router)
# WS 单独一条 router：它**不能**挂 verify_token 的依赖
# （FastAPI 的异常处理器对 WebSocket 不生效，鉴权失败会变成半截连接），
# 所以鉴权在端点里手动做 —— 见 routes/lattice.py 里 ws_router 上方那段注释。
app.include_router(lattice_routes.ws_router)
