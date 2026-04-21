from typing import Literal
from pydantic import BaseModel, field_serializer
from datetime import datetime
from typing import List
from app.core.date_utils import format_date


class Group(BaseModel):
    name: str
    new_day_amount: int


class Daily(BaseModel):
    protein: float
    fat: float
    calories: float
    calorie_type: Literal["deficit", "surplus"]
    tdee_multiplier: float


class Person(BaseModel):
    height: int
    birth_day: datetime
    gender: str

    @field_serializer("birth_day")
    def serialize_birth(self, value: datetime):
        return format_date(value)


class Settings(BaseModel):
    groups: List[Group]
    daily: Daily
    person: Person
    start_date: datetime
    timezone_name: str

    @field_serializer("start_date")
    def serialize_start(self, value: datetime):
        return format_date(value)