from fastapi import APIRouter, Request
from typing import Optional

from pydantic import BaseModel

from app.core.enums import Status
from app.features.settings.schemas import Settings

router = APIRouter(prefix="/settings", tags=["settings"])


class ApiResponse(BaseModel):
    status: Status = Status.SUCCESS
    errorMessage: str = ""
    settings: Optional[Settings] = None


@router.get("", response_model=ApiResponse)
def get_settings(request: Request):
    try:
        settings = request.app.state.bl.get_settings()
        return ApiResponse(settings=settings)
    except Exception as e:
        request.app.state.logger.exception("GET /settings failed")
        return ApiResponse(status=Status.ERROR, errorMessage=str(e))