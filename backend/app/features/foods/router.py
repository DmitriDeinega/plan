from fastapi import APIRouter, Request
from typing import Optional
from pydantic import BaseModel

from app.api.schemas.response import BaseResponse
from app.core.enums import Status

router = APIRouter(prefix="/foods", tags=["foods"])


class FoodsReplaceIn(BaseModel):
    foods: list[dict]


class ApiResponse(BaseResponse):
    foods: Optional[list] = None


@router.get("", response_model=ApiResponse)
def get_foods(request: Request):
    try:
        foods = request.app.state.bl.get_foods()
        return ApiResponse(foods=foods)
    except Exception as e:
        request.app.state.logger.exception("GET /foods failed")
        return ApiResponse(status=Status.ERROR, errorMessage=str(e))


@router.put("", response_model=BaseResponse)
def replace_foods(request: Request, payload: FoodsReplaceIn):
    try:
        request.app.state.logger.info("PUT /foods count=%s", len(payload.foods or []))
        request.app.state.bl.set_foods(payload.foods)
        return BaseResponse()
    except Exception as e:
        request.app.state.logger.exception("PUT /foods failed")
        return BaseResponse(status=Status.ERROR, errorMessage=str(e))