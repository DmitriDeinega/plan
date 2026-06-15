from fastapi import APIRouter, Request
from typing import Optional

from pydantic import BaseModel

from app.core.date_utils import format_date, today as today_fn, tomorrow as tomorrow_fn
from app.core.enums import Status
from app.core.errors import safe_error_message
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
        if settings is not None:
            tz = settings.get("timezone_name")
            settings = dict(settings)
            settings["today"] = format_date(today_fn(tz))
            settings["tomorrow"] = format_date(tomorrow_fn(tz))
        return ApiResponse(settings=settings)
    except Exception as e:
        request.app.state.logger.exception("GET /settings failed")
        return ApiResponse(status=Status.ERROR, errorMessage=safe_error_message(e))