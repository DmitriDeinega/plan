from __future__ import annotations

import logging
import math
import sys
from pymongo import ASCENDING

from app.core.date_utils import (
    parse_date,
    format_date,
    next_day,
    prev_day,
    today,
    tomorrow,
    yesterday,
)
from app.core.enums import Collection
from app.core.errors import BusinessError
from app.core.utils import choice_times
from app.db.dal import DAL


def date_key(date: str):
    return {"date": date}


def _round2(n: float) -> float:
    # Mirror the clients' round2: Math.round((n + Number.EPSILON) * 100) / 100 (half-up, n >= 0).
    return math.floor((n + sys.float_info.epsilon) * 100 + 0.5) / 100


def _is_number(w) -> bool:
    if w is None:
        return False
    try:
        float(w)
        return True
    except (TypeError, ValueError):
        return False


class BL:
    def __init__(
            self,
            mongo_uri: str,
            mongo_db: str,
            logger: logging.Logger
    ):
        self.logger = logger
        self.dal = DAL(mongo_uri, mongo_db)
        self._ensure_indexes()
        self.logger.info("BL initialized mongo_db=%s", mongo_db)

    def _ensure_indexes(self):
        # Enforce the data invariants at the DB level: unique date, and at most one open day.
        # Guarded so a (already-clean) prod can't be crashed by index creation; if existing
        # data ever violated these it would be logged instead of taking the app down.
        try:
            self.dal.create_index(
                Collection.DAYS.value, [("date", ASCENDING)],
                unique=True, name="uniq_date",
            )
            self.dal.create_index(
                Collection.DAYS.value, [("day_closed", ASCENDING)],
                unique=True, name="uniq_open_day",
                partialFilterExpression={"day_closed": False},
            )
        except Exception:
            self.logger.exception("Day index creation failed (continuing) — check for duplicate dates / multiple open days")

    def _recompute_meals_nutrition(self, meals: list[dict]) -> None:
        """Recompute every food's protein/fat/calories from the foods catalog, mirroring the
        clients' math exactly (per-100g x weight/100, round2). Group-reference rows take the
        totals of the matching group meal. Mutates `meals` in place. Verified to reproduce the
        client values to the cent across all stored days."""
        catalog: dict[str, tuple[float, float, float]] = {}
        for f in (self.get_foods() or []):
            try:
                catalog[(f.get("name") or "").strip().upper()] = (
                    float(f.get("protein") or 0),
                    float(f.get("fat") or 0),
                    float(f.get("calories") or 0),
                )
            except (TypeError, ValueError):
                continue

        settings = self.get_settings() or {}
        groups_u = {(g.get("name") or "").strip().upper() for g in (settings.get("groups") or [])}

        # Pass 1 — catalog-based foods (group-reference rows handled in pass 3).
        for meal in meals:
            for food in (meal.get("foods") or []):
                nu = (food.get("name") or "").strip().upper()
                if nu in groups_u:
                    continue
                w = food.get("weight")
                if nu in catalog and _is_number(w):
                    p100, f100, c100 = catalog[nu]
                    wv = float(w)
                    food["protein"] = _round2(p100 * wv / 100.0)
                    food["fat"] = _round2(f100 * wv / 100.0)
                    food["calories"] = _round2(c100 * wv / 100.0)
                else:
                    food["protein"] = 0
                    food["fat"] = 0
                    food["calories"] = 0

        # Pass 2 — group-meal totals (sum of that group meal's now-recomputed foods).
        group_totals: dict[str, tuple[float, float, float]] = {}
        for meal in meals:
            mnu = (meal.get("name") or "").strip().upper()
            if mnu in groups_u:
                p = f = c = 0.0
                for food in (meal.get("foods") or []):
                    p += float(food.get("protein") or 0)
                    f += float(food.get("fat") or 0)
                    c += float(food.get("calories") or 0)
                group_totals[mnu] = (_round2(p), _round2(f), _round2(c))

        # Pass 3 — group-reference rows inherit the matching group meal's totals.
        for meal in meals:
            for food in (meal.get("foods") or []):
                nu = (food.get("name") or "").strip().upper()
                if nu in groups_u:
                    gt = group_totals.get(nu, (0, 0, 0))
                    food["protein"], food["fat"], food["calories"] = gt

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
        # Refuse to wipe the catalog, and never leave it half-replaced: build the new set in a
        # temp collection and atomically rename it over `foods` (renameCollection w/ dropTarget).
        # If validation/insert fails, the live `foods` collection is untouched.
        if not foods:
            raise BusinessError("Refusing to replace foods with an empty list")
        self.logger.info("Replace foods count=%s", len(foods))
        swap = Collection.FOODS.value + "_swap"
        self.dal.drop_collection(swap)
        self.dal.insert_many(collection=swap, documents=foods)
        self.dal.rename_collection(swap, Collection.FOODS.value, drop_target=True)

    def get_day(self, date: str):
        key = date_key(date)
        day = self.get_day_dal(key)

        dt = parse_date(date)
        timezone_name = self.get_timezone_name()

        if day is None and dt == today(timezone_name):
            self.logger.info("Auto end previous day because today requested and day missing date=%s", date)
            self.end_day_dal(format_date(prev_day(parse_date(date))))
            day = self.get_day_dal(key)

        return day

    def set_day(self, date: str, day_doc: dict):
        key = date_key(date)
        self.validate_day_update(key)

        # Server-authoritative nutrition: recompute each food's macros from the catalog so the
        # stored numbers never depend on the client. Only ever applied here (the save path),
        # which set_day's validate_day_update guarantees is an OPEN day — closed/historical days
        # are never re-saved, so this never rewrites past logs with a since-edited catalog.
        if isinstance(day_doc.get("meals"), list):
            self._recompute_meals_nutrition(day_doc["meals"])

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
            raise BusinessError("No open day found")

        actual_open_date = open_day.get("date")
        if not actual_open_date:
            raise BusinessError("Open day has no date")

        if actual_open_date != open_day_date:
            raise BusinessError("Day is not open")

        timezone_name = self.get_timezone_name()

        if actual_open_date != format_date(tomorrow(timezone_name)):
            raise BusinessError("Revert allowed only when open day is tomorrow")

        prev_date = format_date(prev_day(parse_date(actual_open_date)))

        prev_doc = self.get_day_dal(date_key(prev_date))
        if prev_doc is None:
            raise BusinessError("Previous day not found")

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

        timezone_name = self.get_timezone_name()
        if date == format_date(tomorrow(timezone_name)):
            raise BusinessError("Cannot End Tomorrow")
        self.end_day_dal(date)

    def get_open_day(self):
        open_day = self.get_day_dal({"day_closed": False})
        if open_day is None:
            return None

        timezone_name = self.get_timezone_name()
        yesterday_day = format_date(yesterday(timezone_name))
        if yesterday_day == open_day.get("date"):
            self.logger.info("Auto end open day because it equals yesterday date=%s", yesterday_day)
            self.end_day_dal(yesterday_day)

        return self.get_day_dal({"day_closed": False})

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
            raise BusinessError("Settings missing in DB")

        groupsDict: dict[str, int] = {}
        for group in settings["groups"]:
            groupsDict[group["name"]] = group["new_day_amount"]

        tomorrowMeals = []
        current_day = self.get_day_dal(key)
        if current_day is None:
            raise BusinessError("Day not found")

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
        # Only create the next day if it doesn't already exist. end_day_dal is reached from
        # three paths (manual End, auto-end in get_open_day, auto-end-prev in get_day); without
        # this guard any of them running when tomorrow already exists inserts a duplicate-date
        # document, leaving two "open" days and stranding the client on the wrong day.
        existing_tomorrow = self.dal.find_one(
            collection=Collection.DAYS.value,
            key=date_key(tomorrow_date),
            columns={"_id": 1},
        )
        if existing_tomorrow is None:
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
        else:
            self.logger.info(
                "End day done date=%s tomorrow_already_exists=%s", date, tomorrow_date
            )

    def get_timezone_name(self):
        settings = self.dal.find_one(
            collection=Collection.SETTINGS.value,
            key={},
            columns={"timezone_name": 1}
        )

        return settings["timezone_name"]

    def validate_day_update(self, day_key):
        columns = {"day_closed": 1}
        day = self.dal.find_one(
            collection=Collection.DAYS.value,
            key=day_key,
            columns=columns,
        )

        if day is not None and day.get("day_closed") is True:
            raise BusinessError("Day Is Closed")