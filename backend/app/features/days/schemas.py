from pydantic import BaseModel, Field, field_validator
from typing import List, Optional

from app.api.schemas.response import BaseResponse
from app.core.date_utils import parse_date


class FoodMeal(BaseModel):
    name: str
    protein: float = 0
    fat: float = 0
    calories: float = 0
    weight: Optional[float] = None

    @field_validator("protein", "fat", "calories", mode="before")
    @classmethod
    def normalize_nutrient(cls, v):
        if v == "" or v is None:
            return 0
        return v

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


class NutritionOut(BaseModel):
    """Day totals, frozen at End Day. Absent on an open day."""
    protein: float = 0
    fat: float = 0
    calories: float = 0


class TargetsOut(BaseModel):
    """The "Needed" figures as computed when the day was closed. Absent on an open day,
    where clients compute them live from current settings."""
    protein: float = 0
    fat_calories: float = 0
    calories: float = 0


class SnapshotDaily(BaseModel):
    protein: float = 0
    fat: float = 0
    calories: float = 0
    calorie_type: str = ""
    tdee_multiplier: float = 0


class SnapshotPerson(BaseModel):
    height: float = 0
    gender: str = ""
    birth_day: str = ""


class SettingsSnapshotOut(BaseModel):
    """The settings that were in force when the day was closed. Clients use its
    `calorie_type` so a closed day keeps its original deficit/surplus interpretation
    (and therefore its colours) even after the live setting changes."""
    daily: SnapshotDaily = SnapshotDaily()
    person: SnapshotPerson = SnapshotPerson()


class DayOut(BaseModel):
    date: Optional[str] = None
    weight: Optional[float] = None
    meals: List[Meal]
    day_closed: Optional[bool] = None
    # Previously omitted from this model, so the server's frozen totals were computed, stored,
    # and then silently dropped on the way out. Clients now render them for closed days.
    nutrition: Optional[NutritionOut] = None
    targets: Optional[TargetsOut] = None
    settings_snapshot: Optional[SettingsSnapshotOut] = None


class ApiResponse(BaseResponse):
    day: Optional[DayOut] = None


def validate_date_str(date: str) -> str:
    try:
        parse_date(date)
    except ValueError:
        raise ValueError("date provided in wrong format")
    return date