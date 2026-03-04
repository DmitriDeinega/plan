from __future__ import annotations

import logging
from pymongo import ASCENDING

from app.core.date_utils import parse_date, format_date
from app.core.enums import Collection
from app.core.utils import (
    choice_times,
    next_day,
    prev_day,
    today,
    tomorrow,
    yesterday,
)
from app.db.dal import DAL


def date_key(date: str):
    return {"date": date}


class BL:
    def __init__(
            self,
            mongo_uri: str,
            mongo_db: str,
            logger: logging.Logger
    ):
        self.logger = logger
        self.dal = DAL(mongo_uri, mongo_db)
        self.logger.info("BL initialized mongo_db=%s", mongo_db)

    # Public API used by routers

    def get_foods(self):
        sort = [
            ("type", ASCENDING),
            ("inner_type", ASCENDING),
        ]

        return self.dal.find_all(
            collection=Collection.FOODS.value,
            key={},
            columns={"_id": 0},
            sort=sort,
        )

    def set_foods(self, foods: list[dict]):
        self.logger.info("Replace foods count=%s", len(foods or []))
        self.dal.remove_all(collection=Collection.FOODS.value)
        self.dal.insert_many(
            collection=Collection.FOODS.value,
            documents=foods,
        )

    def get_day(self, date: str):
        key = date_key(date)
        day = self.get_day_dal(key)

        dt = parse_date(date).date()
        if day is None and dt == today().date():
            self.logger.info("Auto end previous day because today requested and day missing date=%s", date)
            self.end_day_dal(format_date(prev_day(parse_date(date))))
            day = self.get_day_dal(key)

        return day

    def set_day(self, date: str, day_doc: dict):
        key = date_key(date)
        self.validate_day_update(key)

        self.logger.info("Set day date=%s", date)
        update = {"$set": day_doc}

        self.dal.update_one(
            collection=Collection.DAYS.value,
            key=key,
            data=update,
        )

    def revert_day(self, open_day_date: str):
        self.logger.info("Revert day requested date=%s", open_day_date)

        open_day = self.get_day_dal({"day_closed": False})
        if open_day is None:
            raise Exception("No open day found")

        actual_open_date = open_day.get("date")
        if not actual_open_date:
            raise Exception("Open day has no date")

        if actual_open_date != open_day_date:
            raise Exception("Day is not open")

        if actual_open_date != format_date(tomorrow()):
            raise Exception("Revert allowed only when open day is tomorrow")

        prev_date = format_date(prev_day(parse_date(actual_open_date)))

        prev_doc = self.get_day_dal(date_key(prev_date))
        if prev_doc is None:
            raise Exception("Previous day not found")

        self.dal.delete_one(
            collection=Collection.DAYS.value,
            key=date_key(actual_open_date),
        )

        self.dal.update_one(
            collection=Collection.DAYS.value,
            key=date_key(prev_date),
            data={
                "$set": {"day_closed": False},
                "$unset": {"nutrition": ""},
            },
        )

        self.logger.info("Revert day done open_deleted=%s prev_reopened=%s", actual_open_date, prev_date)

    def get_settings(self):
        return self.dal.find_one(
            collection=Collection.SETTINGS.value,
            key={},
            columns={"_id": 0},
        )

    def end_day(self, date: str):
        self.logger.info("End day requested date=%s", date)
        if date == format_date(tomorrow()):
            raise Exception("Cannot End Tomorrow")
        self.end_day_dal(date)

    def get_open_day(self):
        open_day = self.get_day_dal({"day_closed": False})
        if open_day is None:
            return None

        yesterday_day = format_date(yesterday())
        if yesterday_day == open_day.get("date"):
            self.logger.info("Auto end open day because it equals yesterday date=%s", yesterday_day)
            self.end_day_dal(yesterday_day)

        return self.get_day_dal({"day_closed": False})

    def get_history(self):
        return self.dal.find_all(
            collection=Collection.DAYS.value,
            key={},
            columns={"_id": 0},
        )

    def get_weights(self):
        return self.dal.find_all(
            collection=Collection.DAYS.value,
            key={},
            columns={
                "date": 1,
                "weight": 1,
                "_id": 0,
            },
        )

    # Helpers

    def get_day_dal(self, key):
        columns = {
            "date": 1,
            "meals": 1,
            "weight": 1,
            "day_closed": 1,
            "_id": 0,
        }

        return self.dal.find_one(
            collection=Collection.DAYS.value,
            key=key,
            columns=columns,
        )

    def end_day_dal(self, date: str):
        self.logger.info("End day start date=%s", date)

        key = date_key(date)
        self.validate_day_update(key)

        settings = self.get_settings()
        if settings is None:
            raise Exception("Settings missing in DB")

        groupsDict: dict[str, int] = {}
        for group in settings["groups"]:
            groupsDict[group["name"]] = group["new_day_amount"]

        tomorrowMeals = []
        current_day = self.get_day_dal(key)
        if current_day is None:
            raise Exception("Day not found")

        proteinSum = 0.0
        fatSum = 0.0
        caloriesSum = 0.0

        for meal in current_day["meals"]:
            if meal.get("meal_closed") is None:
                meal["meal_closed"] = False

            if meal["name"] in groupsDict:
                newDayAmount = groupsDict[meal["name"]]
                if newDayAmount > 0:
                    keyFoods = {
                        "inner_type": meal["name"],
                        "available": "Y",
                    }

                    columns = {"name": 1}
                    groupFoods = self.dal.find_all(
                        collection=Collection.FOODS.value,
                        key=keyFoods,
                        columns=columns,
                    )

                    randomFoods = choice_times(groupFoods, newDayAmount)

                    meal["foods"] = []
                    for food in randomFoods:
                        meal["foods"].append(
                            {
                                "name": food["name"],
                                "protein": 0,
                                "fat": 0,
                                "calories": 0,
                                "weight": 0,
                            }
                        )
            else:
                for food in meal["foods"]:
                    proteinSum += float(food["protein"])
                    fatSum += float(food["fat"])
                    caloriesSum += float(food["calories"])

                    if food["name"] in groupsDict:
                        if groupsDict[food["name"]] > 0:
                            food["protein"] = 0
                            food["fat"] = 0
                            food["calories"] = 0
                            food["weight"] = None

            meal_copy = dict(meal)
            meal_copy["meal_closed"] = False
            tomorrowMeals.append(meal_copy)

        self.logger.info(
            "Nutrition computed date=%s protein=%.2f fat=%.2f calories=%.2f",
            date,
            proteinSum,
            fatSum,
            caloriesSum,
        )

        update = {
            "$set": {
                "nutrition": {
                    "protein": proteinSum,
                    "fat": fatSum,
                    "calories": caloriesSum,
                },
                "day_closed": True,
            }
        }
        self.dal.update_one(
            collection=Collection.DAYS.value,
            key=key,
            data=update,
        )

        tomorrow_date = format_date(next_day(parse_date(date)))
        tomorrow_day = date_key(tomorrow_date) | {
            "meals": tomorrowMeals,
            "weight": 0,
            "day_closed": False,
        }
        self.dal.insert_one(
            collection=Collection.DAYS.value,
            document=tomorrow_day,
        )

        self.logger.info("End day done date=%s tomorrow_created=%s", date, tomorrow_date)

    def validate_day_update(self, day_key):
        columns = {"day_closed": 1}
        day = self.dal.find_one(
            collection=Collection.DAYS.value,
            key=day_key,
            columns=columns,
        )

        if day is not None and day.get("day_closed") is True:
            raise Exception("Day Is Closed")