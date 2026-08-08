from __future__ import annotations

import logging
import math
import sys

from app.core.date_utils import (
    parse_date,
    format_date,
    next_day,
    prev_day,
    today,
    tomorrow,
    yesterday,
)
from app.core.errors import BusinessError
from app.core.targets import TargetError, compute_targets, snapshot_from_settings
from app.core.utils import choice_times
from app.db.dal import (
    DAL,
    DayClosedError,
    DayNotFoundError,
    InvalidNumberError,
    SettingsMissingError,
)


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
    def __init__(self, pg_dsn: str, logger: logging.Logger):
        self.logger = logger
        self.dal = DAL(pg_dsn)
        self.dal.ensure_schema()
        self.logger.info("BL initialized (postgres)")

    def close(self):
        self.dal.close()

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
        return self.dal.get_foods()

    def set_foods(self, foods: list[dict]):
        # Refuse to wipe the catalog. The replace runs in a single transaction, so a failure
        # part-way leaves the live catalog untouched (the Postgres equivalent of the old
        # build-in-a-temp-collection-then-rename trick).
        if not foods:
            raise BusinessError("Refusing to replace foods with an empty list")
        self.logger.info("Replace foods count=%s", len(foods))
        try:
            self.dal.replace_foods(foods)
        except InvalidNumberError as e:
            # Surface the offending food/field to the user instead of storing a silent 0.
            raise BusinessError(str(e))

    def get_day(self, date: str):
        day = self.dal.get_day(date)

        dt = parse_date(date)
        timezone_name = self.get_timezone_name()

        if day is None and dt == today(timezone_name):
            self.logger.info("Auto end previous day because today requested and day missing date=%s", date)
            self.end_day_dal(format_date(prev_day(parse_date(date))))
            day = self.dal.get_day(date)

        return day

    def set_day(self, date: str, day_doc: dict):
        self.validate_day_update(date)

        # Server-authoritative nutrition: recompute each food's macros from the catalog so the
        # stored numbers never depend on the client. Only ever applied here (the save path),
        # which set_day's validate_day_update guarantees is an OPEN day — closed/historical days
        # are never re-saved, so this never rewrites past logs with a since-edited catalog.
        if isinstance(day_doc.get("meals"), list):
            self._recompute_meals_nutrition(day_doc["meals"])

        self.logger.info("Set day date=%s", date)
        try:
            self.dal.save_day(date, day_doc)
        except DayClosedError:
            # Lost the race against a concurrent end_day: same user-facing error as the
            # up-front check, so the client behaves identically either way.
            raise BusinessError("Day Is Closed")

    def revert_day(self, open_day_date: str):
        self.logger.info("Revert day requested date=%s", open_day_date)

        open_day = self.dal.get_open_day()
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

        if not self.dal.day_exists(prev_date):
            raise BusinessError("Previous day not found")

        # One transaction: deleting the open day and reopening the previous one must not be
        # separately committed, or a crash between them leaves zero open days with tomorrow
        # already gone.
        self.dal.revert_open_day(actual_open_date, prev_date)

        self.logger.info("Revert day done open_deleted=%s prev_reopened=%s", actual_open_date, prev_date)

    def get_settings(self):
        return self.dal.get_settings()

    def end_day(self, date: str):
        self.logger.info("End day requested date=%s", date)

        timezone_name = self.get_timezone_name()
        if date == format_date(tomorrow(timezone_name)):
            raise BusinessError("Cannot End Tomorrow")
        self.end_day_dal(date)

    def get_open_day(self):
        open_day = self.dal.get_open_day()
        if open_day is None:
            return None

        timezone_name = self.get_timezone_name()
        yesterday_day = format_date(yesterday(timezone_name))
        if yesterday_day == open_day.get("date"):
            self.logger.info("Auto end open day because it equals yesterday date=%s", yesterday_day)
            self.end_day_dal(yesterday_day)

        return self.dal.get_open_day()

    def get_weights(self):
        return self.dal.get_weights()

    # Helpers

    @staticmethod
    def _end_day_compute(day: dict, settings: dict, group_foods: dict, date: str) -> dict:
        """Pure: given the locked day, settings and group pools, produce everything the close
        needs. No DB access — a second connection here would sit outside end_day_atomic's
        transaction and reintroduce the race it exists to close."""
        groupsDict: dict[str, int] = {}
        for group in settings["groups"]:
            groupsDict[group["name"]] = group["new_day_amount"]

        tomorrowMeals = []
        proteinSum = 0.0
        fatSum = 0.0
        caloriesSum = 0.0

        for meal in day["meals"]:
            if meal.get("meal_closed") is None:
                meal["meal_closed"] = False

            if meal["name"] in groupsDict:
                newDayAmount = groupsDict[meal["name"]]
                if newDayAmount > 0:
                    randomFoods = choice_times(group_foods.get(meal["name"], []), newDayAmount)

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

        # Freeze the goals alongside the totals, so editing settings later can never rewrite
        # what this day was measured against.
        targets = compute_targets(day.get("weight"), settings, parse_date(date))

        return {
            "nutrition": {"protein": proteinSum, "fat": fatSum, "calories": caloriesSum},
            "targets": targets,
            "snapshot": snapshot_from_settings(settings),
            "tomorrow_meals": tomorrowMeals,
        }

    def end_day_dal(self, date: str):
        self.logger.info("End day start date=%s", date)

        tomorrow_date = format_date(next_day(parse_date(date)))

        # One transaction owns the whole thing: locking, reading, computing and writing. See
        # DAL.end_day_atomic. Tomorrow is created in the same transaction and only when it
        # doesn't already exist (end_day is reachable from three paths: manual End, auto-end
        # in get_open_day, auto-end-prev in get_day).
        try:
            result = self.dal.end_day_atomic(
                date,
                tomorrow_date,
                lambda day, settings, group_foods: self._end_day_compute(
                    day, settings, group_foods, date
                ),
            )
        except DayNotFoundError:
            raise BusinessError("Day not found")
        except SettingsMissingError:
            raise BusinessError("Settings missing in DB")
        except DayClosedError:
            raise BusinessError("Day Is Closed")
        except TargetError as e:
            raise BusinessError(str(e))

        self.logger.info(
            "End day done date=%s tomorrow=%s created=%s",
            date, tomorrow_date, result.get("created_tomorrow"),
        )

    def get_timezone_name(self):
        tz = self.dal.get_timezone_name()
        if tz is None:
            raise BusinessError("Settings missing in DB")
        return tz

    def validate_day_update(self, date: str):
        closed = self.dal.is_day_closed(date)
        if closed is True:
            raise BusinessError("Day Is Closed")
