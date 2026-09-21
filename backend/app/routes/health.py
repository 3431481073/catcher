"""GET /health（免 token）。"""
from fastapi import APIRouter

from ..errors import ok

router = APIRouter()


@router.get("/api/v1/health")
async def health():
    return ok({"status": "ok"})
