from pydantic import BaseModel, Field, field_validator
from typing import List, Optional

from app.api.schemas.response import BaseResponse
from app.core.date_utils import parse_date


class FoodMeal(BaseModel):
    name: str
    protein: float
    fat: float
    calories: float
    weight: Optional[float] = None

    @field_validator("weight", mode="before")
    @classmethod
    def normalize_weight(cls, v):
        if v == "" or v is None:
            return None
        return v


class Meal(BaseModel):
    name: str
    foods: List[FoodMeal] = Field(default_factory=list)
    meal_closed: bool


class DayUpdateIn(BaseModel):
    weight: float
    meals: List[Meal]


class DayOut(BaseModel):
    date: Optional[str] = None
    weight: float
    meals: List[Meal]
    day_closed: Optional[bool] = None


class ApiResponse(BaseResponse):
    day: Optional[DayOut] = None


def validate_date_str(date: str) -> str:
    try:
        parse_date(date)
    except ValueError:
        raise ValueError("date provided in wrong format")
    return date