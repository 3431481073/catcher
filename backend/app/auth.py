"""X-Token 鉴权依赖。"""
from fastapi import Header

from . import config
from .errors import AppError


async def verify_token(x_token: str = Header(default=None)):
    if x_token != config.TOKEN:
        raise AppError("UNAUTHORIZED", "Token 无效", 401)
    return x_token
