from fastapi import APIRouter, Request
from typing import Optional

from app.api.schemas.response import BaseResponse
from app.core.enums import Status

router = APIRouter(prefix="", tags=["history"])


class ApiResponse(BaseResponse):
    days: Optional[list] = None


@router.get("/history", response_model=ApiResponse)
def history(request: Request):
    try:
        days = request.app.state.bl.get_history()
        return ApiResponse(days=days)
    except Exception as e:
        request.app.state.logger.exception("GET /history failed")
        return ApiResponse(status=Status.ERROR, errorMessage=str(e))


@router.get("/weights", response_model=ApiResponse)
def weights(request: Request):
    try:
        days = request.app.state.bl.get_weights()
        return ApiResponse(days=days)
    except Exception as e:
        request.app.state.logger.exception("GET /weights failed")
        return ApiResponse(status=Status.ERROR, errorMessage=str(e))