"""统一响应 + 错误码。"""
import logging

from fastapi import Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

logger = logging.getLogger("app")


class AppError(Exception):
    def __init__(self, code: str, message: str, http_status: int = 500):
        super().__init__(message)
        self.code = code
        self.message = message
        self.http_status = http_status


def ok(data):
    return {"ok": True, "data": data, "error": None}


def _error_response(code: str, message: str, status: int):
    return JSONResponse(
        status_code=status,
        content={"ok": False, "data": None, "error": {"code": code, "message": message}},
    )


def register_error_handlers(app):
    @app.exception_handler(AppError)
    async def app_error_handler(request: Request, exc: AppError):
        return _error_response(exc.code, exc.message, exc.http_status)

    @app.exception_handler(RequestValidationError)
    async def validation_error_handler(request: Request, exc: RequestValidationError):
        # FastAPI 默认校验错误是 422，契约里没有 422，统一映射成 400「请求格式错」
        return _error_response("INVALID_PAYLOAD", "请求格式错", 400)

    @app.exception_handler(Exception)
    async def unexpected_error_handler(request: Request, exc: Exception):
        logger.exception("未处理异常")
        return _error_response("SERVER_ERROR", "服务端错误", 500)
