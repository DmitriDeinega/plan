from fastapi import APIRouter, Request
import logging

from app.api.schemas.response import BaseResponse
from app.core.enums import Status
from app.features.days.schemas import ApiResponse, DayUpdateIn, validate_date_str

router = APIRouter(prefix="/days", tags=["days"])


def _log(request: Request) -> logging.Logger:
    return request.app.state.logger


def _upper(s: str) -> str:
    return (s or "").strip().upper()


def _normalize_food(food: dict) -> dict:
    name = (food.get("name") or "").strip()
    w = food.get("weight", None)
    if w == "":
        w = None
    return {
        "name": name,
        "weight": w,
        "protein": float(food.get("protein", 0) or 0),
        "fat": float(food.get("fat", 0) or 0),
        "calories": float(food.get("calories", 0) or 0),
    }


def _normalize_meal(meal: dict) -> dict:
    foods = meal.get("foods") or []
    return {
        "name": (meal.get("name") or "").strip(),
        "meal_closed": bool(meal.get("meal_closed") or False),
        "foods": [_normalize_food(f) for f in foods],
    }


def _meals_map(meals: list[dict]) -> dict[str, dict]:
    m: dict[str, dict] = {}
    for meal in meals or []:
        nm = _upper(meal.get("name"))
        if nm:
            m[nm] = meal
    return m


@router.get("/open", response_model=ApiResponse)
def get_open_day(request: Request):
    try:
        day = request.app.state.bl.get_open_day()
        return ApiResponse(day=day)
    except Exception as e:
        _log(request).exception("GET /days/open failed")
        return ApiResponse(status=Status.ERROR, errorMessage=str(e))


@router.get("/{date}", response_model=ApiResponse)
def get_day(request: Request, date: str):
    try:
        validate_date_str(date)
        day = request.app.state.bl.get_day(date)
        return ApiResponse(day=day)
    except Exception as e:
        _log(request).exception("GET /days/%s failed", date)
        return ApiResponse(status=Status.ERROR, errorMessage=str(e))


@router.patch("/{date}", response_model=BaseResponse)
def patch_day(request: Request, date: str, payload: DayUpdateIn):
    try:
        validate_date_str(date)
        _log(request).info("PATCH /days/%s weight=%s meals=%s", date, payload.weight, payload.meals)

        bl = request.app.state.bl
        existing = bl.get_day(date)
        if existing is None:
            raise Exception("Day not found")

        if bool(existing.get("day_closed") or False) is True:
            raise Exception("Day is closed")

        groups_doc = bl.get_settings()
        if groups_doc is None:
            raise Exception("Settings missing in DB")

        groups_upper = {_upper(g.get("name")) for g in (groups_doc.get("groups") or []) if g.get("name")}
        incoming = [m.model_dump() for m in (payload.meals or [])]

        for m in incoming:
            if m.get("meal_closed") is None:
                m["meal_closed"] = False

        incoming_map = _meals_map(incoming)

        expected_group_closed: dict[str, bool] = {g: False for g in groups_upper}

        for name_u, meal in incoming_map.items():
            if name_u in groups_upper:
                continue
            if bool(meal.get("meal_closed") or False) is not True:
                continue

            for food in (meal.get("foods") or []):
                food_name_u = _upper(food.get("name"))
                if food_name_u in expected_group_closed:
                    expected_group_closed[food_name_u] = True

        for g_u, should_be_closed in expected_group_closed.items():
            group_meal = incoming_map.get(g_u)
            incoming_closed = bool(group_meal.get("meal_closed") or False) if group_meal else False
            if incoming_closed != should_be_closed:
                if incoming_closed and not should_be_closed:
                    raise Exception(f"Group meal {(group_meal.get('name') if group_meal else g_u)} cannot be signed directly")
                else:
                    raise Exception(f"Group meal {g_u} must be signed because it appears in a signed meal")

        new_day = dict(existing)
        new_day["weight"] = payload.weight
        new_day["meals"] = incoming

        bl.set_day(date, new_day)
        return BaseResponse()

    except Exception as e:
        _log(request).exception("PATCH /days/%s failed", date)
        return BaseResponse(status=Status.ERROR, errorMessage=str(e))


@router.post("/{date}/end", response_model=BaseResponse)
def end_day(request: Request, date: str):
    try:
        validate_date_str(date)
        _log(request).info("POST /days/%s/end", date)
        request.app.state.bl.end_day(date)
        return BaseResponse()
    except Exception as e:
        _log(request).exception("POST /days/%s/end failed", date)
        return BaseResponse(status=Status.ERROR, errorMessage=str(e))


@router.post("/{date}/revert", response_model=BaseResponse)
def revert_day(request: Request, date: str):
    try:
        validate_date_str(date)
        _log(request).info("POST /days/%s/revert", date)
        request.app.state.bl.revert_day(date)
        return BaseResponse()
    except Exception as e:
        _log(request).exception("POST /days/%s/revert failed", date)
        return BaseResponse(status=Status.ERROR, errorMessage=str(e))