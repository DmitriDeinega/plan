"""Postgres data-access layer.

Replaces the previous MongoDB DAL. The public methods return / accept the same dict
shapes the BL and the clients already use (a day is {"date", "weight", "day_closed",
"meals": [{"name", "meal_closed", "foods": [...]}], "nutrition"?}), so the document
model lives on at the API boundary while storage is relational.

Two conversions happen here and nowhere else:
  * date  — DATE column <-> 'DDMMYYYY' wire string
  * macro — NUMERIC column <-> float
"""

from __future__ import annotations

from contextlib import contextmanager
from datetime import date as date_cls
from decimal import Decimal
from pathlib import Path
from typing import Any, Iterable, Optional

import psycopg
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb
from psycopg_pool import ConnectionPool

from app.core.date_utils import format_date, parse_date
from app.core.targets import FORMULA_VERSION

SCHEMA_PATH = Path(__file__).with_name("schema.sql")


class DayClosedError(Exception):
    """Raised when a write loses the race against end_day and targets a now-closed day."""


class DayNotFoundError(Exception):
    """Raised when an operation targets a day that does not exist."""


class SettingsMissingError(Exception):
    """Raised when the single settings row is absent."""


def _num(v: Any) -> float:
    """NUMERIC/Decimal -> float, for JSON responses."""
    if v is None:
        return 0.0
    if isinstance(v, Decimal):
        return float(v)
    return float(v)


def _opt_num(v: Any) -> Optional[float]:
    """Same, but preserves NULL (group-reference rows have no weight)."""
    if v is None:
        return None
    return _num(v)


class InvalidNumberError(Exception):
    """A non-empty value that should be numeric could not be parsed."""


def coerce_num(v: Any, default: Optional[float] = 0.0, *, field: str = "value",
               strict: bool = False) -> Optional[float]:
    """Parse a value that may arrive as a number, a numeric string, '' or None.

    The Excel/VBA client sends every macro and weight as a quoted string, so the API has
    to accept '67.5' as well as 67.5. Empty string means "absent" -> default.

    With strict=True a non-empty unparsable value raises instead of silently becoming the
    default. Used on the foods-catalog write path, where a typo like '67,5' would otherwise
    zero out a food and then propagate into every recomputed day.
    """
    if v is None:
        return default
    if isinstance(v, bool):
        if strict:
            raise InvalidNumberError(f"{field}: expected a number, got boolean {v!r}")
        return default
    if isinstance(v, (int, float, Decimal)):
        return float(v)
    s = str(v).strip()
    if s == "":
        return default
    try:
        return float(s)
    except ValueError:
        if strict:
            raise InvalidNumberError(f"{field}: {v!r} is not a valid number")
        return default


class DAL:
    def __init__(self, dsn: str, min_size: int = 1, max_size: int = 8):
        self.pool = ConnectionPool(
            dsn,
            min_size=min_size,
            max_size=max_size,
            kwargs={"row_factory": dict_row},
            open=True,
        )

    def close(self) -> None:
        self.pool.close()

    @contextmanager
    def connection(self):
        """A pooled connection wrapped in a transaction (commit on success, rollback on error)."""
        with self.pool.connection() as conn:
            yield conn

    def ensure_schema(self) -> None:
        ddl = SCHEMA_PATH.read_text(encoding="utf-8")
        with self.pool.connection() as conn:
            conn.execute(ddl)

    # ---------------------------------------------------------------- settings

    @staticmethod
    def _settings_from_row(row: dict) -> dict:
        # start_date / person.birth_day stay as `date` objects: the settings response model
        # declares them as datetimes and does the DDMMYYYY serialization itself. Formatting
        # them here too would make Pydantic re-parse the string and silently produce 1970.
        person = dict(row["person"] or {})
        birth = person.get("birth_day")
        if isinstance(birth, str) and len(birth) == 8 and birth.isdigit():
            person["birth_day"] = parse_date(birth)
        return {
            "groups": row["groups"],
            "daily": row["daily"],
            "person": person,
            "start_date": row["start_date"],
            "timezone_name": row["timezone_name"],
        }

    def get_settings(self) -> Optional[dict]:
        with self.pool.connection() as conn:
            row = conn.execute(
                "SELECT groups, daily, person, start_date, timezone_name FROM settings WHERE id IS TRUE"
            ).fetchone()
        if row is None:
            return None
        return self._settings_from_row(row)

    def get_timezone_name(self) -> Optional[str]:
        with self.pool.connection() as conn:
            row = conn.execute(
                "SELECT timezone_name FROM settings WHERE id IS TRUE"
            ).fetchone()
        return row["timezone_name"] if row else None

    # ------------------------------------------------------------------- foods

    def get_foods(self) -> list[dict]:
        with self.pool.connection() as conn:
            rows = conn.execute(
                """
                SELECT name, type, inner_type, protein, fat, calories, available
                FROM foods
                ORDER BY type, inner_type, id
                """
            ).fetchall()
        return [
            {
                "name": r["name"],
                "type": r["type"],
                "inner_type": r["inner_type"],
                "protein": _num(r["protein"]),
                "fat": _num(r["fat"]),
                "calories": _num(r["calories"]),
                "available": r["available"],
            }
            for r in rows
        ]

    def replace_foods(self, foods: Iterable[dict]) -> int:
        """Atomically swap the whole catalog (mirrors the old rename-collection trick).

        Runs in one transaction: if any row is rejected the live catalog is untouched.
        """
        rows = []
        for f in foods:
            name = (f.get("name") or "").strip()
            # strict: this replaces the whole catalog, and every open day's macros are
            # recomputed from it — a silently-zeroed value would spread far beyond this call.
            rows.append(
                (
                    name,
                    (f.get("type") or "").strip(),
                    (f.get("inner_type") or "").strip(),
                    coerce_num(f.get("protein"), 0.0, field=f"{name}.protein", strict=True),
                    coerce_num(f.get("fat"), 0.0, field=f"{name}.fat", strict=True),
                    coerce_num(f.get("calories"), 0.0, field=f"{name}.calories", strict=True),
                    (f.get("available") or "").strip(),
                )
            )
        with self.pool.connection() as conn:
            with conn.cursor() as cur:
                cur.execute("DELETE FROM foods")
                cur.executemany(
                    """
                    INSERT INTO foods (name, type, inner_type, protein, fat, calories, available)
                    VALUES (%s, %s, %s, %s, %s, %s, %s)
                    """,
                    rows,
                )
        return len(rows)

    def get_group_food_names(self, inner_type: str) -> list[dict]:
        """Available foods in a group — the pool end_day draws the next day's picks from."""
        with self.pool.connection() as conn:
            rows = conn.execute(
                "SELECT name FROM foods WHERE inner_type = %s AND available = 'Y' ORDER BY id",
                (inner_type,),
            ).fetchall()
        return [{"name": r["name"]} for r in rows]

    # -------------------------------------------------------------------- days

    def _day_from_rows(self, day_row: dict, meal_rows: list[dict], food_rows: list[dict]) -> dict:
        foods_by_meal: dict[int, list[dict]] = {}
        for fr in food_rows:
            foods_by_meal.setdefault(fr["meal_id"], []).append(
                {
                    "name": fr["name"],
                    "weight": _opt_num(fr["weight"]),
                    "protein": _num(fr["protein"]),
                    "fat": _num(fr["fat"]),
                    "calories": _num(fr["calories"]),
                }
            )

        day = {
            "date": format_date(day_row["date"]),
            "weight": _num(day_row["weight"]),
            "day_closed": day_row["day_closed"],
            "meals": [
                {
                    "name": m["name"],
                    "meal_closed": m["meal_closed"],
                    "foods": foods_by_meal.get(m["id"], []),
                }
                for m in meal_rows
            ],
        }
        if day_row.get("nutrition_calories") is not None:
            day["nutrition"] = {
                "protein": _num(day_row["nutrition_protein"]),
                "fat": _num(day_row["nutrition_fat"]),
                "calories": _num(day_row["nutrition_calories"]),
            }
        # Frozen goals. All-or-none is enforced by a CHECK, so one probe is enough.
        if day_row.get("settings_snapshot") is not None:
            day["settings_snapshot"] = day_row["settings_snapshot"]
            day["targets"] = {
                "protein": _num(day_row["target_protein"]),
                "fat_calories": _num(day_row["target_fat_calories"]),
                "calories": _num(day_row["target_calories"]),
            }
        return day

    def _load_day(self, conn, day_row: Optional[dict]) -> Optional[dict]:
        if day_row is None:
            return None
        meal_rows = conn.execute(
            "SELECT id, name, meal_closed FROM meals WHERE day_id = %s ORDER BY position",
            (day_row["id"],),
        ).fetchall()
        if meal_rows:
            food_rows = conn.execute(
                """
                SELECT mf.meal_id, mf.name, mf.weight, mf.protein, mf.fat, mf.calories
                FROM meal_foods mf
                WHERE mf.meal_id = ANY(%s)
                ORDER BY mf.meal_id, mf.position
                """,
                ([m["id"] for m in meal_rows],),
            ).fetchall()
        else:
            food_rows = []
        return self._day_from_rows(day_row, meal_rows, food_rows)

    _DAY_COLS = (
        "id, date, weight, day_closed, nutrition_protein, nutrition_fat, nutrition_calories, "
        "settings_snapshot, target_protein, target_fat_calories, target_calories, "
        "formula_version, snapshot_source"
    )

    # Reopening a day must drop every frozen value with it: the day becomes editable again, so
    # its targets go back to tracking live settings. A CHECK also forbids an open day from
    # holding a snapshot, which would turn a missed clear here into a hard error.
    _CLEAR_FROZEN = """
        day_closed = FALSE,
        nutrition_protein = NULL, nutrition_fat = NULL, nutrition_calories = NULL,
        settings_snapshot = NULL,
        target_protein = NULL, target_fat_calories = NULL, target_calories = NULL,
        formula_version = NULL, snapshot_source = NULL
    """

    # A day is read with three statements (day, meals, foods). At READ COMMITTED each
    # statement takes a fresh snapshot, so a concurrent PATCH — which deletes and reinserts
    # the whole meal graph — could slip between them and yield meals with empty food lists.
    # REPEATABLE READ pins one snapshot for all three.
    @contextmanager
    def _snapshot(self):
        with self.pool.connection() as conn:
            conn.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ")
            yield conn

    def get_day(self, date_str: str) -> Optional[dict]:
        with self._snapshot() as conn:
            row = conn.execute(
                f"SELECT {self._DAY_COLS} FROM days WHERE date = %s",
                (parse_date(date_str),),
            ).fetchone()
            return self._load_day(conn, row)

    def get_open_day(self) -> Optional[dict]:
        with self._snapshot() as conn:
            row = conn.execute(
                f"SELECT {self._DAY_COLS} FROM days WHERE day_closed IS FALSE"
            ).fetchone()
            return self._load_day(conn, row)

    def day_exists(self, date_str: str) -> bool:
        with self.pool.connection() as conn:
            row = conn.execute(
                "SELECT 1 FROM days WHERE date = %s", (parse_date(date_str),)
            ).fetchone()
        return row is not None

    def is_day_closed(self, date_str: str) -> Optional[bool]:
        """None when the day doesn't exist."""
        with self.pool.connection() as conn:
            row = conn.execute(
                "SELECT day_closed FROM days WHERE date = %s", (parse_date(date_str),)
            ).fetchone()
        return None if row is None else row["day_closed"]

    def _write_meals(self, conn, day_id: int, meals: list[dict], *, table_meals="meals",
                     table_foods="meal_foods") -> None:
        """Replace a day's meal/food graph. Delete-then-insert keeps `position` densely
        packed and matches the clients' whole-day PATCH semantics."""
        conn.execute(f"DELETE FROM {table_meals} WHERE day_id = %s", (day_id,))
        for m_pos, meal in enumerate(meals or []):
            meal_row = conn.execute(
                f"""
                INSERT INTO {table_meals} (day_id, position, name, meal_closed)
                VALUES (%s, %s, %s, %s) RETURNING id
                """,
                (
                    day_id,
                    m_pos,
                    (meal.get("name") or "").strip(),
                    bool(meal.get("meal_closed") or False),
                ),
            ).fetchone()
            meal_id = meal_row["id"]

            food_rows = []
            for f_pos, food in enumerate(meal.get("foods") or []):
                food_rows.append(
                    (
                        meal_id,
                        f_pos,
                        (food.get("name") or "").strip(),
                        # None (not 0) for group-reference rows — the column is nullable
                        # precisely so "no weight" stays distinct from "weighs 0".
                        coerce_num(food.get("weight"), None),
                        coerce_num(food.get("protein"), 0.0),
                        coerce_num(food.get("fat"), 0.0),
                        coerce_num(food.get("calories"), 0.0),
                    )
                )
            if food_rows:
                with conn.cursor() as cur:
                    cur.executemany(
                        f"""
                        INSERT INTO {table_foods}
                            (meal_id, position, name, weight, protein, fat, calories)
                        VALUES (%s, %s, %s, %s, %s, %s, %s)
                        """,
                        food_rows,
                    )

    def save_day(self, date_str: str, day_doc: dict) -> None:
        """Upsert a whole day (weight + meals). Nutrition is written by close_day only.

        The whole save is one transaction and the UPDATE is guarded by `day_closed = FALSE`.
        Without that guard a PATCH that passed its open-day check could still land *after* a
        concurrent end_day closed the day, rewriting meals/weight while the stored nutrition
        totals kept the old values — silently corrupting closed history.
        """
        d = parse_date(date_str)
        with self.pool.connection() as conn:
            row = conn.execute(
                """
                INSERT INTO days (date, weight, day_closed)
                VALUES (%s, %s, %s)
                ON CONFLICT (date) DO UPDATE SET weight = EXCLUDED.weight
                WHERE days.day_closed IS FALSE
                RETURNING id
                """,
                (
                    d,
                    coerce_num(day_doc.get("weight"), 0.0),
                    bool(day_doc.get("day_closed") or False),
                ),
            ).fetchone()
            if row is None:
                # ON CONFLICT ... WHERE didn't match -> the day exists and is closed.
                raise DayClosedError("Day Is Closed")
            if isinstance(day_doc.get("meals"), list):
                self._write_meals(conn, row["id"], day_doc["meals"])

    def insert_day(self, date_str: str, weight: float, day_closed: bool, meals: list[dict]) -> None:
        d = parse_date(date_str)
        with self.pool.connection() as conn:
            row = conn.execute(
                "INSERT INTO days (date, weight, day_closed) VALUES (%s, %s, %s) RETURNING id",
                (d, coerce_num(weight, 0.0), day_closed),
            ).fetchone()
            self._write_meals(conn, row["id"], meals)

    def end_day_atomic(self, date_str: str, tomorrow_date_str: str, compute_fn) -> dict:
        """Close a day and open the next one, entirely inside ONE transaction.

        Everything the close depends on is read AFTER the row locks are taken, so a concurrent
        PATCH cannot slip in between the read and the write and leave the closed day holding
        nutrition/targets that disagree with its own stored meals.

        `compute_fn(day, settings, group_foods)` must be PURE — it may not touch the DAL,
        because a second connection would sit outside this transaction. Everything it needs
        (the day, the settings, and the per-group available-food pools) is passed in.
        It returns {"nutrition": {...}, "targets": {...}, "snapshot": {...},
                    "tomorrow_meals": [...]}.
        """
        d = parse_date(date_str)
        tomorrow_d = parse_date(tomorrow_date_str)

        with self.pool.connection() as conn:
            # 1. Lock the day and the settings row before reading anything.
            day_row = conn.execute(
                f"SELECT {self._DAY_COLS} FROM days WHERE date = %s FOR UPDATE", (d,)
            ).fetchone()
            if day_row is None:
                raise DayNotFoundError(f"Day {date_str} not found")
            if day_row["day_closed"]:
                raise DayClosedError("Day Is Closed")

            settings_row = conn.execute(
                "SELECT groups, daily, person, start_date, timezone_name "
                "FROM settings WHERE id IS TRUE FOR UPDATE"
            ).fetchone()
            if settings_row is None:
                raise SettingsMissingError("Settings missing in DB")

            # 2. Read the day's meals and the group pools under that lock.
            day = self._load_day(conn, day_row)
            settings = self._settings_from_row(settings_row)

            group_foods: dict[str, list[dict]] = {}
            for g in (settings.get("groups") or []):
                gname = g.get("name")
                if not gname:
                    continue
                rows = conn.execute(
                    "SELECT name FROM foods WHERE inner_type = %s AND available = 'Y' ORDER BY id",
                    (gname,),
                ).fetchall()
                group_foods[gname] = [{"name": r["name"]} for r in rows]

            # 3. Pure computation.
            result = compute_fn(day, settings, group_foods)
            nutrition = result["nutrition"]
            targets = result["targets"]
            snapshot = result["snapshot"]
            tomorrow_meals = result["tomorrow_meals"]

            # 4. Guarded close. day_closed=FALSE in the predicate means a racing end_day that
            #    got here first makes this one a no-op rather than a silent overwrite.
            closed = conn.execute(
                """
                UPDATE days
                SET day_closed = TRUE,
                    nutrition_protein = %s, nutrition_fat = %s, nutrition_calories = %s,
                    settings_snapshot = %s,
                    target_protein = %s, target_fat_calories = %s, target_calories = %s,
                    formula_version = %s, snapshot_source = 'end_day'
                WHERE date = %s AND day_closed IS FALSE
                """,
                (
                    nutrition["protein"], nutrition["fat"], nutrition["calories"],
                    Jsonb(snapshot),
                    targets["protein"], targets["fat_calories"], targets["calories"],
                    FORMULA_VERSION,
                    d,
                ),
            ).rowcount
            if closed != 1:
                raise DayClosedError("Day Is Closed")

            # 5. Create tomorrow in the same transaction. ON CONFLICT DO NOTHING covers the
            #    three paths that can reach end_day; RETURNING tells us whether we inserted,
            #    so an existing tomorrow never has its meals overwritten.
            new_row = conn.execute(
                """
                INSERT INTO days (date, weight, day_closed) VALUES (%s, 0, FALSE)
                ON CONFLICT (date) DO NOTHING
                RETURNING id
                """,
                (tomorrow_d,),
            ).fetchone()
            if new_row is not None:
                self._write_meals(conn, new_row["id"], tomorrow_meals)

            return {"created_tomorrow": new_row is not None}

    def reopen_day(self, date_str: str) -> None:
        """Clear closed state, totals and frozen goals."""
        with self.pool.connection() as conn:
            conn.execute(
                f"UPDATE days SET {self._CLEAR_FROZEN} WHERE date = %s",
                (parse_date(date_str),),
            )

    def revert_open_day(self, open_date_str: str, prev_date_str: str) -> None:
        """Delete the open day and reopen the previous one, atomically.

        These two writes MUST share a transaction: between them the table momentarily has
        zero open days, and a crash in that window would leave the app with no open day at
        all (and tomorrow already deleted). The DELETE must still come first — `uniq_open_day`
        is a plain partial unique index, not DEFERRABLE, so it is checked per-statement.
        """
        open_d = parse_date(open_date_str)
        prev_d = parse_date(prev_date_str)
        with self.pool.connection() as conn:
            # Lock both rows up front so a concurrent end/revert can't interleave.
            conn.execute(
                "SELECT id FROM days WHERE date IN (%s, %s) FOR UPDATE",
                (open_d, prev_d),
            )
            deleted = conn.execute("DELETE FROM days WHERE date = %s", (open_d,)).rowcount
            if deleted != 1:
                raise RuntimeError(f"revert: expected to delete 1 open day, deleted {deleted}")
            reopened = conn.execute(
                f"UPDATE days SET {self._CLEAR_FROZEN} WHERE date = %s",
                (prev_d,),
            ).rowcount
            if reopened != 1:
                # Rolls back the DELETE too — the day is never lost.
                raise RuntimeError(f"revert: expected to reopen 1 day, updated {reopened}")

    def delete_day(self, date_str: str) -> None:
        with self.pool.connection() as conn:
            conn.execute("DELETE FROM days WHERE date = %s", (parse_date(date_str),))

    def get_weights(self) -> list[dict]:
        with self.pool.connection() as conn:
            rows = conn.execute(
                "SELECT date, weight FROM days ORDER BY date"
            ).fetchall()
        return [{"date": format_date(r["date"]), "weight": _num(r["weight"])} for r in rows]
