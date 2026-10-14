"""X-Token 鉴权依赖。"""
from fastapi import Header

from . import config
from .errors import AppError


def token_ok(token: str | None) -> bool:
    """"什么算有效 token"的**唯一出处**。

    HTTP 走下面的 `verify_token`，WebSocket 走它自己 —— 两条路径的鉴权规则必须一致，
    所以比较写在这里一处，两边都调。
    """
    return token is not None and token == config.TOKEN


async def verify_token(x_token: str = Header(default=None)):
    if not token_ok(x_token):
        raise AppError("UNAUTHORIZED", "Token 无效", 401)
    return x_token
