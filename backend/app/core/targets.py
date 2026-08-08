"""Canonical daily-target formula.

This module is the SINGLE SOURCE OF TRUTH for the "Needed" figures (protein, fat-calories,
calories). The three clients (Android, web, Excel) each render their own copy of the numbers,
so any divergence here shows up as three apps disagreeing about the same day.

Before this module they DID disagree: Excel used the correct Mifflin-St Jeor sex term
(+5 / -161) while Android and web hardcoded +5; Android and web approximated age as
elapsed_ms / 365.2425 days; and web multiplied the UNROUNDED calorie target when deriving
fat-calories while the others multiplied the rounded one.

Arithmetic is Decimal, not float, and every Decimal is built from a STRING — Decimal(0.1)
inherits the binary-float error that ROUND_HALF_UP was chosen to avoid.
"""

from __future__ import annotations

from datetime import date
from decimal import Decimal, ROUND_HALF_UP
from typing import Any

# Bump only when the formula itself changes. Stored per day for auditing; it is NOT a licence
# to recompute closed days — frozen targets are a record of what was shown.
FORMULA_VERSION = 1

SEX_TERM = {"M": Decimal("5"), "F": Decimal("-161")}


class TargetError(Exception):
    """The settings cannot produce a target (bad gender, unparsable number)."""


def _dec(v: Any, field: str) -> Decimal:
    """Decimal from anything the clients might send, via str() so no binary float sneaks in."""
    if v is None or (isinstance(v, str) and v.strip() == ""):
        return Decimal("0")
    if isinstance(v, bool):
        raise TargetError(f"{field}: expected a number, got boolean {v!r}")
    try:
        return Decimal(str(v).strip())
    except Exception as exc:
        raise TargetError(f"{field}: {v!r} is not a valid number") from exc


def round2(d: Decimal) -> Decimal:
    """Half-up to 2dp. Matches the clients' round2 for the non-negative values used here."""
    return d.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)


def compute_age_years(birth: date, on: date) -> int:
    """Whole elapsed years — calendar-exact, not elapsed_days/365.2425.

    The birthday counts on the day itself. A 29-Feb birthday has no anniversary in a common
    year, so it is treated as having occurred by 1 March (the `(3, 1)` comparison below), which
    matches Excel's DATEDIF "Y".
    """
    had_birthday = (on.month, on.day) >= (
        (3, 1) if (birth.month, birth.day) == (2, 29) and not _is_leap(on.year)
        else (birth.month, birth.day)
    )
    return on.year - birth.year - (0 if had_birthday else 1)


def _is_leap(y: int) -> bool:
    return y % 4 == 0 and (y % 100 != 0 or y % 400 == 0)


def sex_term(gender: Any) -> Decimal:
    """Mifflin-St Jeor sex constant. Normalised, and an unknown value is an ERROR: silently
    treating anything non-"M" as female would quietly shift every target by 166 kcal."""
    g = str(gender or "").strip().upper()
    if g not in SEX_TERM:
        raise TargetError(f"person.gender must be 'M' or 'F', got {gender!r}")
    return SEX_TERM[g]


def compute_targets(weight: Any, settings: dict, day: date) -> dict:
    """The canonical targets for `day` at body-weight `weight` under `settings`.

    Returns plain floats (JSON-friendly); callers that persist them hand the values straight
    to NUMERIC(10,2) columns.

        age            = whole years from person.birth_day to `day`
        bmr            = 10*weight + 6.25*height - 5*age + sex
        tdee           = bmr * tdee_multiplier
        calories       = round2(surplus ? tdee + daily.calories : tdee - daily.calories)
        fat_calories   = round2(ROUNDED calories * daily.fat)
        protein        = round2(daily.protein * weight)
    """
    daily = settings.get("daily") or {}
    person = settings.get("person") or {}

    w = _dec(weight, "weight")
    height = _dec(person.get("height"), "person.height")
    multiplier = _dec(daily.get("tdee_multiplier"), "daily.tdee_multiplier")
    daily_cals = _dec(daily.get("calories"), "daily.calories")
    daily_fat = _dec(daily.get("fat"), "daily.fat")
    daily_protein = _dec(daily.get("protein"), "daily.protein")

    birth = person.get("birth_day")
    if not isinstance(birth, date):
        raise TargetError(f"person.birth_day must be a date, got {birth!r}")
    age = Decimal(compute_age_years(birth, day))

    calorie_type = str(daily.get("calorie_type") or "").strip().lower()
    if calorie_type not in ("deficit", "surplus"):
        raise TargetError(f"daily.calorie_type must be 'deficit' or 'surplus', got {calorie_type!r}")

    bmr = (Decimal("10") * w) + (Decimal("6.25") * height) - (Decimal("5") * age) + sex_term(person.get("gender"))
    tdee = bmr * multiplier

    calories = round2(tdee + daily_cals if calorie_type == "surplus" else tdee - daily_cals)
    # Deliberately the ROUNDED calories: Excel's C6 references C7, and Android multiplies its
    # already-rounded value. Web used the unrounded one and is corrected to match.
    fat_calories = round2(calories * daily_fat)
    protein = round2(daily_protein * w)

    return {
        "protein": float(protein),
        "fat_calories": float(fat_calories),
        "calories": float(calories),
    }


def snapshot_from_settings(settings: dict) -> dict:
    """The subset of settings that determines the targets, in WIRE shape.

    `birth_day` becomes its DDMMYYYY string here: the value goes into a JSONB column, and a
    Python date is not JSON-serialisable.
    """
    from app.core.date_utils import format_date

    daily = settings.get("daily") or {}
    person = settings.get("person") or {}
    birth = person.get("birth_day")

    return {
        "daily": {
            "protein": float(_dec(daily.get("protein"), "daily.protein")),
            "fat": float(_dec(daily.get("fat"), "daily.fat")),
            "calories": float(_dec(daily.get("calories"), "daily.calories")),
            "calorie_type": str(daily.get("calorie_type") or "").strip().lower(),
            "tdee_multiplier": float(_dec(daily.get("tdee_multiplier"), "daily.tdee_multiplier")),
        },
        "person": {
            "height": float(_dec(person.get("height"), "person.height")),
            "gender": str(person.get("gender") or "").strip().upper(),
            "birth_day": format_date(birth) if isinstance(birth, date) else birth,
        },
    }
