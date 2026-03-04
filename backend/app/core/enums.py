from enum import Enum


class Status(str, Enum):
    SUCCESS = "SUCCESS"
    ERROR = "ERROR"


class Collection(str, Enum):
    DAYS = "days"
    FOODS = "foods"
    SETTINGS = "settings"