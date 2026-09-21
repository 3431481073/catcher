"""FastAPI 入口。"""
import asyncio
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI

from . import db
from . import transcribe as transcribe_mod
from .errors import register_error_handlers
from .routes import health, records, tasks, transcribe
from .worker import worker

logging.basicConfig(level=logging.INFO)


@asynccontextmanager
async def lifespan(app: FastAPI):
    db.init_db()
    await worker.start()
    # 后台预加载模型（不阻塞启动）；第一个请求若赶在加载完成前，会在 get_model 里等锁
    asyncio.create_task(transcribe_mod.preload())
    yield
    await worker.stop()


app = FastAPI(title="手机剪藏", lifespan=lifespan)
register_error_handlers(app)

app.include_router(health.router)
app.include_router(transcribe.router)
app.include_router(records.router)
app.include_router(tasks.router)
