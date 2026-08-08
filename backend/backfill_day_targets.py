"""Backfill frozen goal snapshots onto closed days that predate the feature.

IMPORTANT — what this does and does not do.

It writes the CURRENT settings onto every closed day that has no snapshot. That freezes
history as of now: from this point on, editing tdee_multiplier (or any other target input)
will not move those days any more.

It does NOT recover what the settings actually were on each of those days. That information
was never recorded — the app only ever stored one mutable settings row. Rows written here are
therefore tagged `snapshot_source = 'backfill_current_settings'` so they are always
distinguishable from genuine `end_day` snapshots.

Idempotent: only rows with a NULL snapshot are touched, so re-running is a no-op.

Usage:
    python backfill_day_targets.py --dsn postgresql://plan:plan@127.0.0.1:5432/plan_db
    python backfill_day_targets.py --dsn ... --dry-run
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import psycopg
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb

sys.path.insert(0, str(Path(__file__).parent))

from app.core.date_utils import format_date, parse_date  # noqa: E402
from app.core.targets import (  # noqa: E402
    FORMULA_VERSION,
    TargetError,
    compute_targets,
    snapshot_from_settings,
)

SOURCE = "backfill_current_settings"


def load_settings(conn) -> dict:
    row = conn.execute(
        "SELECT groups, daily, person, start_date, timezone_name FROM settings WHERE id IS TRUE"
    ).fetchone()
    if row is None:
        raise SystemExit("No settings row — nothing to backfill from.")
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


def backfill_table(conn, settings: dict, table: str, dry_run: bool) -> tuple[int, int]:
    """Returns (updated, skipped)."""
    rows = conn.execute(
        f"""
        SELECT id, date, weight FROM {table}
        WHERE day_closed IS TRUE AND settings_snapshot IS NULL
        ORDER BY date
        """
    ).fetchall()

    snapshot = snapshot_from_settings(settings)
    updated = skipped = 0

    for r in rows:
        try:
            targets = compute_targets(r["weight"], settings, r["date"])
        except TargetError as e:
            print(f"    SKIP {format_date(r['date'])}: {e}", file=sys.stderr)
            skipped += 1
            continue

        if not dry_run:
            conn.execute(
                f"""
                UPDATE {table}
                SET settings_snapshot = %s,
                    target_protein = %s, target_fat_calories = %s, target_calories = %s,
                    formula_version = %s, snapshot_source = %s
                WHERE id = %s
                """,
                (
                    Jsonb(snapshot),
                    targets["protein"], targets["fat_calories"], targets["calories"],
                    FORMULA_VERSION, SOURCE, r["id"],
                ),
            )
        updated += 1

    return updated, skipped


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dsn", required=True)
    ap.add_argument("--dry-run", action="store_true", help="report what would change, write nothing")
    args = ap.parse_args()

    with psycopg.connect(args.dsn, row_factory=dict_row) as conn:
        settings = load_settings(conn)
        d = settings["daily"]
        p = settings["person"]
        print("Backfilling with CURRENT settings (not historical — see module docstring):")
        print(f"  tdee_multiplier={d.get('tdee_multiplier')} calories={d.get('calories')} "
              f"protein={d.get('protein')} fat={d.get('fat')} type={d.get('calorie_type')}")
        print(f"  height={p.get('height')} gender={p.get('gender')} birth_day={p.get('birth_day')}")
        print()

        total_u = total_s = 0
        for table in ("days", "days_archive"):
            u, s = backfill_table(conn, settings, table, args.dry_run)
            print(f"  {table}: {u} day(s) {'would be ' if args.dry_run else ''}updated"
                  + (f", {s} skipped" if s else ""))
            total_u += u
            total_s += s

        if args.dry_run:
            conn.rollback()
            print(f"\nDry run — nothing written. {total_u} day(s) would be backfilled.")
        else:
            conn.commit()
            print(f"\nBackfilled {total_u} day(s)." + (f" {total_s} skipped." if total_s else ""))

    return 1 if total_s else 0


if __name__ == "__main__":
    sys.exit(main())
