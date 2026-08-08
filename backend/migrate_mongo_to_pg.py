"""One-shot migration: MongoDB (plan_db) -> Postgres.

Reads mongoexport --jsonArray files and loads them into the Postgres schema, then
verifies by reading every row back and diffing against the source JSON.

Usage:
    python migrate_mongo_to_pg.py --src ./dump --dsn postgresql://plan:plan@localhost:5432/plan

Source files expected in --src:
    settings.json, foods.json, days.json, days_01022026.json, days_10052026.json

The script is idempotent: it TRUNCATEs the target tables before loading, so re-running
produces the same result rather than duplicating rows.
"""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime
from decimal import Decimal
from pathlib import Path
from typing import Any, Optional

import psycopg
from psycopg.rows import dict_row

DATE_FORMAT = "%d%m%Y"

# Mongo collection -> archive label in days_archive
ARCHIVES = {
    "days_01022026": "backup_01022026",
    "days_10052026": "backup_10052026",
}


class MigrationError(Exception):
    pass


def parse_ddmmyyyy(s: str):
    """Strict DDMMYYYY. strptime alone is too permissive: '1012026' (7 digits) parses
    happily as 2026-01-10, so a malformed key would migrate to a plausible wrong date.
    Requiring 8 digits and a clean round-trip makes that fail loudly instead."""
    if not (isinstance(s, str) and len(s) == 8 and s.isdigit()):
        raise MigrationError(f"date {s!r} is not 8 digits in DDMMYYYY form")
    d = datetime.strptime(s, DATE_FORMAT).date()
    if d.strftime(DATE_FORMAT) != s:
        raise MigrationError(f"date {s!r} does not round-trip (got {d.strftime(DATE_FORMAT)})")
    return d


def num(v: Any, field: str, ctx: str, *, allow_null: bool = False) -> Optional[float]:
    """Strict numeric coercion. Unlike the runtime coercion in the DAL, this ABORTS on a
    value it cannot parse — a silent 0 here would corrupt history invisibly."""
    if v is None or (isinstance(v, str) and v.strip() == ""):
        if allow_null:
            return None
        return 0.0
    if isinstance(v, bool):
        raise MigrationError(f"{ctx}: {field} is a bool ({v!r})")
    if isinstance(v, (int, float, Decimal)):
        return float(v)
    if isinstance(v, dict):  # extended JSON, e.g. {"$numberDouble": "1.5"}
        for k in ("$numberDouble", "$numberInt", "$numberLong", "$numberDecimal"):
            if k in v:
                return float(v[k])
        raise MigrationError(f"{ctx}: {field} is an unrecognized object {v!r}")
    try:
        return float(str(v).strip())
    except ValueError as exc:
        raise MigrationError(f"{ctx}: {field} = {v!r} is not numeric") from exc


def mongo_date(v: Any) -> Any:
    """settings.start_date / person.birth_day arrive as {"$date": "..."} or a string."""
    if isinstance(v, dict) and "$date" in v:
        raw = v["$date"]
        if isinstance(raw, dict) and "$numberLong" in raw:
            return datetime.utcfromtimestamp(int(raw["$numberLong"]) / 1000).date()
        return datetime.fromisoformat(str(raw).replace("Z", "+00:00")).date()
    if isinstance(v, str):
        s = v.strip()
        # ISO first: strptime('%d%m%Y') would silently mis-parse '1989-05-03T00:00:00Z'
        # into a garbage date rather than raising, so it must never get the first attempt.
        if "-" in s or "T" in s:
            return datetime.fromisoformat(s.replace("Z", "+00:00")).date()
        if len(s) == 8 and s.isdigit():
            return parse_ddmmyyyy(s)
    raise MigrationError(f"unrecognized date value {v!r}")


def load_json(path: Path) -> list[dict]:
    if not path.exists():
        raise MigrationError(f"missing source file: {path}")
    with path.open(encoding="utf-8") as fh:
        data = json.load(fh)
    return data if isinstance(data, list) else [data]


def strip_id(doc: dict) -> dict:
    return {k: v for k, v in doc.items() if k != "_id"}


# --------------------------------------------------------------------------- load


def load_settings(conn, src: Path) -> None:
    docs = load_json(src / "settings.json")
    if len(docs) != 1:
        raise MigrationError(f"expected exactly 1 settings doc, found {len(docs)}")
    s = strip_id(docs[0])

    person = dict(s.get("person") or {})
    if "birth_day" in person:
        # Store as DDMMYYYY inside the JSONB, which is what every client already parses.
        person["birth_day"] = mongo_date(person["birth_day"]).strftime(DATE_FORMAT)

    daily = dict(s.get("daily") or {})
    for k in ("protein", "fat", "calories", "tdee_multiplier"):
        if k in daily:
            daily[k] = num(daily[k], k, "settings.daily")

    groups = []
    for g in s.get("groups") or []:
        groups.append(
            {
                "name": g.get("name"),
                "new_day_amount": int(num(g.get("new_day_amount"), "new_day_amount", "settings.groups")),
            }
        )

    conn.execute("DELETE FROM settings")
    conn.execute(
        """
        INSERT INTO settings (id, groups, daily, person, start_date, timezone_name)
        VALUES (TRUE, %s, %s, %s, %s, %s)
        """,
        (
            json.dumps(groups),
            json.dumps(daily),
            json.dumps(person),
            mongo_date(s["start_date"]),
            s["timezone_name"],
        ),
    )
    print(f"  settings: 1 row (groups={len(groups)}, tz={s['timezone_name']})")


def load_foods(conn, src: Path) -> int:
    docs = load_json(src / "foods.json")
    rows = []
    seen: dict[str, str] = {}
    for d in docs:
        f = strip_id(d)
        name = (f.get("name") or "").strip()
        if not name:
            raise MigrationError(f"food with empty name: {f!r}")
        key = name.upper()
        if key in seen:
            raise MigrationError(f"duplicate food name (case-insensitive): {name!r} vs {seen[key]!r}")
        seen[key] = name
        ctx = f"food {name!r}"
        rows.append(
            (
                name,
                (f.get("type") or "").strip(),
                (f.get("inner_type") or "").strip(),
                num(f.get("protein"), "protein", ctx),
                num(f.get("fat"), "fat", ctx),
                num(f.get("calories"), "calories", ctx),
                (f.get("available") or "").strip(),
            )
        )

    conn.execute("DELETE FROM foods")
    with conn.cursor() as cur:
        cur.executemany(
            """
            INSERT INTO foods (name, type, inner_type, protein, fat, calories, available)
            VALUES (%s, %s, %s, %s, %s, %s, %s)
            """,
            rows,
        )
    print(f"  foods: {len(rows)} rows")
    return len(rows)


def load_days(conn, docs: list[dict], *, archive: Optional[str]) -> tuple[int, int, int]:
    """Load day documents into either the live tables or the archive tables."""
    t_days = "days_archive" if archive else "days"
    t_meals = "meals_archive" if archive else "meals"
    t_foods = "meal_foods_archive" if archive else "meal_foods"

    n_days = n_meals = n_foods = 0

    for d in sorted((strip_id(x) for x in docs), key=lambda x: parse_ddmmyyyy(x["date"])):
        date_str = d["date"]
        ctx = f"{archive or 'days'} {date_str}"
        day_date = parse_ddmmyyyy(date_str)
        nutrition = d.get("nutrition") or {}

        if archive:
            row = conn.execute(
                f"""
                INSERT INTO {t_days}
                    (archive, date, weight, day_closed,
                     nutrition_protein, nutrition_fat, nutrition_calories)
                VALUES (%s, %s, %s, %s, %s, %s, %s) RETURNING id
                """,
                (
                    archive, day_date,
                    num(d.get("weight"), "weight", ctx),
                    bool(d.get("day_closed") or False),
                    num(nutrition.get("protein"), "n.protein", ctx, allow_null=True) if nutrition else None,
                    num(nutrition.get("fat"), "n.fat", ctx, allow_null=True) if nutrition else None,
                    num(nutrition.get("calories"), "n.calories", ctx, allow_null=True) if nutrition else None,
                ),
            ).fetchone()
        else:
            row = conn.execute(
                f"""
                INSERT INTO {t_days}
                    (date, weight, day_closed,
                     nutrition_protein, nutrition_fat, nutrition_calories)
                VALUES (%s, %s, %s, %s, %s, %s) RETURNING id
                """,
                (
                    day_date,
                    num(d.get("weight"), "weight", ctx),
                    bool(d.get("day_closed") or False),
                    num(nutrition.get("protein"), "n.protein", ctx, allow_null=True) if nutrition else None,
                    num(nutrition.get("fat"), "n.fat", ctx, allow_null=True) if nutrition else None,
                    num(nutrition.get("calories"), "n.calories", ctx, allow_null=True) if nutrition else None,
                ),
            ).fetchone()
        day_id = row["id"]
        n_days += 1

        for m_pos, meal in enumerate(d.get("meals") or []):
            mrow = conn.execute(
                f"INSERT INTO {t_meals} (day_id, position, name, meal_closed) "
                f"VALUES (%s, %s, %s, %s) RETURNING id",
                (day_id, m_pos, (meal.get("name") or "").strip(),
                 bool(meal.get("meal_closed") or False)),
            ).fetchone()
            n_meals += 1

            frows = []
            for f_pos, food in enumerate(meal.get("foods") or []):
                fctx = f"{ctx} / {meal.get('name')} / {food.get('name')}"
                frows.append(
                    (
                        mrow["id"], f_pos, (food.get("name") or "").strip(),
                        # '' and null both mean "no weight" (group-reference rows).
                        num(food.get("weight"), "weight", fctx, allow_null=True),
                        num(food.get("protein"), "protein", fctx),
                        num(food.get("fat"), "fat", fctx),
                        num(food.get("calories"), "calories", fctx),
                    )
                )
            if frows:
                with conn.cursor() as cur:
                    cur.executemany(
                        f"INSERT INTO {t_foods} (meal_id, position, name, weight, protein, fat, calories) "
                        f"VALUES (%s, %s, %s, %s, %s, %s, %s)",
                        frows,
                    )
                n_foods += len(frows)

    label = archive or "days (live)"
    print(f"  {label}: {n_days} days, {n_meals} meals, {n_foods} foods")
    return n_days, n_meals, n_foods


# ------------------------------------------------------------------------- verify


def _round(v: Optional[float]) -> Optional[float]:
    return None if v is None else round(float(v), 2)


def verify(conn, src: Path) -> None:
    """Read every migrated row back and diff it against the source JSON."""
    problems: list[str] = []

    # ---- settings (dates especially: a mis-parsed start_date/birth_day is silent but
    # corrupts the whole age/TDEE calculation, so it is checked explicitly)
    src_settings = strip_id(load_json(src / "settings.json")[0])
    db_s = conn.execute(
        "SELECT groups, daily, person, start_date, timezone_name FROM settings WHERE id IS TRUE"
    ).fetchone()
    if db_s is None:
        problems.append("settings row missing")
    else:
        want_start = mongo_date(src_settings["start_date"])
        if db_s["start_date"] != want_start:
            problems.append(f"settings.start_date {db_s['start_date']} != {want_start}")
        want_birth = mongo_date(src_settings["person"]["birth_day"]).strftime(DATE_FORMAT)
        got_birth = (db_s["person"] or {}).get("birth_day")
        if got_birth != want_birth:
            problems.append(f"settings.person.birth_day {got_birth} != {want_birth}")
        if db_s["timezone_name"] != src_settings["timezone_name"]:
            problems.append("settings.timezone_name mismatch")
        if len(db_s["groups"] or []) != len(src_settings.get("groups") or []):
            problems.append("settings.groups count mismatch")
        for sg, dg in zip(src_settings.get("groups") or [], db_s["groups"] or []):
            if sg.get("name") != dg.get("name") or int(sg.get("new_day_amount")) != int(dg.get("new_day_amount")):
                problems.append(f"settings.groups entry mismatch: {dg} != {sg}")
        for k in ("protein", "fat", "calories", "tdee_multiplier"):
            if float((db_s["daily"] or {}).get(k, 0)) != float((src_settings.get("daily") or {}).get(k, 0)):
                problems.append(f"settings.daily.{k} mismatch")

    # ---- foods
    src_foods = {(strip_id(f)["name"] or "").strip().upper(): strip_id(f) for f in load_json(src / "foods.json")}
    db_foods = conn.execute(
        "SELECT name, type, inner_type, protein, fat, calories, available FROM foods"
    ).fetchall()
    if len(db_foods) != len(src_foods):
        problems.append(f"foods count {len(db_foods)} != source {len(src_foods)}")
    for r in db_foods:
        s = src_foods.get(r["name"].strip().upper())
        if s is None:
            problems.append(f"food {r['name']!r} not in source")
            continue
        for col, key in (("protein", "protein"), ("fat", "fat"), ("calories", "calories")):
            want = _round(float(str(s.get(key) or 0)))
            got = _round(r[col])
            if want != got:
                problems.append(f"food {r['name']!r}.{col}: {got} != {want}")
        if (s.get("type") or "").strip() != r["type"]:
            problems.append(f"food {r['name']!r}.type mismatch")
        if (s.get("inner_type") or "").strip() != r["inner_type"]:
            problems.append(f"food {r['name']!r}.inner_type mismatch")
        if (s.get("available") or "").strip() != r["available"]:
            problems.append(f"food {r['name']!r}.available mismatch")

    # ---- days (live + each archive)
    targets = [("days.json", None, "days", "meals", "meal_foods")]
    for coll, label in ARCHIVES.items():
        targets.append((f"{coll}.json", label, "days_archive", "meals_archive", "meal_foods_archive"))

    for fname, archive, t_days, t_meals, t_foods in targets:
        docs = [strip_id(d) for d in load_json(src / fname)]
        where = "WHERE archive = %s" if archive else ""
        params = (archive,) if archive else ()
        db_days = conn.execute(
            f"SELECT id, date, weight, day_closed, nutrition_protein, nutrition_fat, "
            f"nutrition_calories FROM {t_days} {where}",
            params,
        ).fetchall()
        by_date = {r["date"]: r for r in db_days}

        if len(db_days) != len(docs):
            problems.append(f"{fname}: {len(db_days)} rows != source {len(docs)}")

        for d in docs:
            dt = parse_ddmmyyyy(d["date"])
            r = by_date.get(dt)
            if r is None:
                problems.append(f"{fname}: date {d['date']} missing in DB")
                continue
            ctx = f"{archive or 'live'} {d['date']}"

            if _round(float(d.get("weight") or 0)) != _round(r["weight"]):
                problems.append(f"{ctx}: weight {r['weight']} != {d.get('weight')}")
            if bool(d.get("day_closed") or False) != r["day_closed"]:
                problems.append(f"{ctx}: day_closed mismatch")

            n = d.get("nutrition")
            if n:
                for key, col in (("protein", "nutrition_protein"), ("fat", "nutrition_fat"),
                                 ("calories", "nutrition_calories")):
                    if _round(float(n.get(key) or 0)) != _round(r[col]):
                        problems.append(f"{ctx}: nutrition.{key} {r[col]} != {n.get(key)}")
            elif r["nutrition_calories"] is not None:
                problems.append(f"{ctx}: DB has nutrition but source doesn't")

            # meals + foods, in order
            db_meals = conn.execute(
                f"SELECT id, position, name, meal_closed FROM {t_meals} WHERE day_id = %s ORDER BY position",
                (r["id"],),
            ).fetchall()
            src_meals = d.get("meals") or []
            if len(db_meals) != len(src_meals):
                problems.append(f"{ctx}: {len(db_meals)} meals != source {len(src_meals)}")
                continue
            for m_i, (dbm, sm) in enumerate(zip(db_meals, src_meals)):
                if dbm["name"] != (sm.get("name") or "").strip():
                    problems.append(f"{ctx}[{m_i}]: meal name {dbm['name']!r} != {sm.get('name')!r}")
                if dbm["meal_closed"] != bool(sm.get("meal_closed") or False):
                    problems.append(f"{ctx}[{m_i}] {dbm['name']}: meal_closed mismatch")

                db_foods_rows = conn.execute(
                    f"SELECT position, name, weight, protein, fat, calories FROM {t_foods} "
                    f"WHERE meal_id = %s ORDER BY position",
                    (dbm["id"],),
                ).fetchall()
                src_foods_list = sm.get("foods") or []
                if len(db_foods_rows) != len(src_foods_list):
                    problems.append(
                        f"{ctx}[{m_i}] {dbm['name']}: {len(db_foods_rows)} foods != source {len(src_foods_list)}"
                    )
                    continue
                for f_i, (dbf, sf) in enumerate(zip(db_foods_rows, src_foods_list)):
                    fctx = f"{ctx}[{m_i}] {dbm['name']}[{f_i}]"
                    if dbf["name"] != (sf.get("name") or "").strip():
                        problems.append(f"{fctx}: name {dbf['name']!r} != {sf.get('name')!r}")
                    # '' / null both migrate to NULL
                    sw = sf.get("weight")
                    want_w = None if sw is None or (isinstance(sw, str) and sw.strip() == "") else _round(float(sw))
                    if want_w != _round(dbf["weight"]):
                        problems.append(f"{fctx}: weight {dbf['weight']} != {sw!r}")
                    for key in ("protein", "fat", "calories"):
                        if _round(float(sf.get(key) or 0)) != _round(dbf[key]):
                            problems.append(f"{fctx}: {key} {dbf[key]} != {sf.get(key)}")

    if problems:
        print(f"\nVERIFICATION FAILED — {len(problems)} problem(s):", file=sys.stderr)
        for p in problems[:50]:
            print(f"  - {p}", file=sys.stderr)
        if len(problems) > 50:
            print(f"  ... and {len(problems) - 50} more", file=sys.stderr)
        raise MigrationError("verification failed")

    print("  verification: OK — every row matches the source")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", required=True, type=Path, help="dir with mongoexport .json files")
    ap.add_argument("--dsn", required=True, help="postgresql://user:pass@host:port/db")
    ap.add_argument("--schema", type=Path, default=Path(__file__).parent / "app" / "db" / "schema.sql")
    args = ap.parse_args()

    print(f"Migrating {args.src} -> {args.dsn.rsplit('@', 1)[-1]}")

    with psycopg.connect(args.dsn, row_factory=dict_row) as conn:
        print("Applying schema...")
        conn.execute(args.schema.read_text(encoding="utf-8"))

        print("Clearing target tables...")
        conn.execute(
            "TRUNCATE days, meals, meal_foods, days_archive, meals_archive, "
            "meal_foods_archive, foods, settings RESTART IDENTITY CASCADE"
        )

        print("Loading...")
        load_settings(conn, args.src)
        load_foods(conn, args.src)
        load_days(conn, load_json(args.src / "days.json"), archive=None)
        for coll, label in ARCHIVES.items():
            load_days(conn, load_json(args.src / f"{coll}.json"), archive=label)

        print("Verifying...")
        verify(conn, args.src)

        conn.commit()

    print("\nMigration complete.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except MigrationError as exc:
        print(f"\nMIGRATION ABORTED: {exc}", file=sys.stderr)
        sys.exit(1)
