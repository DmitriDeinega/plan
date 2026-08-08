#!/usr/bin/env bash
# One-shot cutover: MongoDB -> PostgreSQL, run ON THE SERVER.
#
# Does the whole thing end to end:
#   1. exports every collection out of the running Mongo container
#   2. starts the Postgres container and waits for it to be healthy
#   3. loads + verifies the data (migrate_mongo_to_pg.py aborts on any mismatch)
#   4. freezes goal snapshots onto the already-closed days
#
# Safe to re-run: the loader TRUNCATEs and reloads, the backfill only touches days
# that have no snapshot yet. Mongo is only ever READ.
#
#   cd /opt/plan/infra && ./migrate_to_postgres.sh
#
# Options:
#   --dump-dir DIR   where to write the mongoexport JSON (default: ./mongo-export)
#   --keep-mongo     leave the Mongo container running afterwards (default: leave it alone)
#   --dry-run        export + verify only; roll back instead of committing
#   --from-ssh USER@HOST --ssh-key PATH
#                    pull the export from a REMOTE host's Mongo container instead of a
#                    local one. Use this when migrating onto a new machine while the old
#                    server still holds the data.
#   --skip-export    reuse the JSON already in --dump-dir (e.g. copied over by hand)
#
# Migrating onto a fresh box while the old server is still up:
#   ./migrate_to_postgres.sh --from-ssh ec2-user@1.2.3.4 --ssh-key ~/.ssh/apps.pem

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"

MONGO_CONTAINER="${PLAN_MONGO_CONTAINER:-plan-mongo}"
MONGO_DB="${PLAN_MONGO_DB:-plan_db}"
PG_CONTAINER="${PLAN_PG_CONTAINER:-plan-postgres}"
PG_DB="${PLAN_PG_DB:-plan_db}"
PG_USER="${PLAN_PG_USER:-plan}"
PG_PASSWORD="${POSTGRES_PASSWORD:-plan}"

DUMP_DIR="$HERE/mongo-export"
DRY_RUN=""
FROM_SSH=""
SSH_KEY=""
SKIP_EXPORT=""

while [ $# -gt 0 ]; do
  case "$1" in
    --dump-dir) DUMP_DIR="$2"; shift 2 ;;
    --dry-run)  DRY_RUN="--dry-run"; shift ;;
    --from-ssh) FROM_SSH="$2"; shift 2 ;;
    --ssh-key)  SSH_KEY="$2"; shift 2 ;;
    --skip-export) SKIP_EXPORT=1; shift ;;
    --keep-mongo) shift ;;   # accepted for clarity; this script never stops Mongo
    -h|--help) sed -n '2,30p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

# Run a command against whichever host holds Mongo.
mongo_host_exec() {
  if [ -n "$FROM_SSH" ]; then
    ssh ${SSH_KEY:+-i "$SSH_KEY"} -o StrictHostKeyChecking=no "$FROM_SSH" "$@"
  else
    bash -c "$@"
  fi
}

say() { printf '\n=== %s ===\n' "$1"; }

# --- 0. sanity -------------------------------------------------------------
say "Checking prerequisites"
command -v docker >/dev/null || { echo "docker not found" >&2; exit 1; }
echo "mongo source    : ${FROM_SSH:-local}/$MONGO_CONTAINER"
echo "target postgres : $PG_CONTAINER / $PG_DB"
echo "dump dir        : $DUMP_DIR"

# --- 1. export from Mongo (read-only) --------------------------------------
mkdir -p "$DUMP_DIR"

if [ -n "$SKIP_EXPORT" ]; then
  say "Skipping export (--skip-export); using the JSON already in $DUMP_DIR"
else
  say "Exporting collections from MongoDB"

  if [ -z "$FROM_SSH" ] && ! docker ps --format '{{.Names}}' | grep -qx "$MONGO_CONTAINER"; then
    echo "Mongo container '$MONGO_CONTAINER' is not running here." >&2
    echo "If the data lives on another machine, use --from-ssh USER@HOST --ssh-key PATH." >&2
    exit 1
  fi

  # Discover collections rather than hardcoding, so any extra days_* backup is picked up.
  COLLECTIONS=$(mongo_host_exec "docker exec $MONGO_CONTAINER mongosh $MONGO_DB --quiet --eval 'db.getCollectionNames().filter(n => !n.startsWith(\"system.\")).join(\"\n\")'")

  [ -n "$COLLECTIONS" ] || { echo "no collections found in $MONGO_DB" >&2; exit 1; }

  for c in $COLLECTIONS; do
    [ -z "$c" ] && continue
    mongo_host_exec "docker exec $MONGO_CONTAINER mongoexport --db $MONGO_DB --collection $c --jsonArray --quiet" > "$DUMP_DIR/$c.json"
    printf '  %-20s %s bytes\n' "$c" "$(wc -c < "$DUMP_DIR/$c.json" | tr -d ' ')"
  done
fi

# The loader expects these five. Extra days_* backups are reported but not loaded,
# because each archive needs an explicit label in ARCHIVES.
for required in settings.json foods.json days.json; do
  [ -s "$DUMP_DIR/$required" ] || { echo "missing/empty export: $required" >&2; exit 1; }
done

EXTRA=$(ls "$DUMP_DIR" | grep -E '^days_.*\.json$' || true)
if [ -n "$EXTRA" ]; then
  echo "  archives found:"
  echo "$EXTRA" | sed 's/^/    /'
  echo "  (only the archives listed in migrate_mongo_to_pg.py ARCHIVES are loaded)"
fi

# --- 2. bring up Postgres --------------------------------------------------
say "Starting PostgreSQL"
cd "$HERE"
docker compose up -d postgres
printf 'waiting for healthy'
for _ in $(seq 1 60); do
  status=$(docker inspect -f '{{.State.Health.Status}}' "$PG_CONTAINER" 2>/dev/null || echo starting)
  [ "$status" = "healthy" ] && { printf ' ok\n'; break; }
  printf '.'; sleep 2
done
[ "$(docker inspect -f '{{.State.Health.Status}}' "$PG_CONTAINER")" = "healthy" ] \
  || { echo "postgres did not become healthy" >&2; docker logs --tail 30 "$PG_CONTAINER" >&2; exit 1; }

# --- 3. load + verify ------------------------------------------------------
# Run the loader inside a throwaway python container on the compose network, so the
# host needs no python/psycopg of its own.
NETWORK=$(docker inspect -f '{{range $k,$v := .NetworkSettings.Networks}}{{$k}}{{end}}' "$PG_CONTAINER")
DSN="postgresql://${PG_USER}:${PG_PASSWORD}@${PG_CONTAINER}:5432/${PG_DB}"

say "Loading into PostgreSQL (with full verification)"
docker run --rm --network "$NETWORK" \
  -v "$REPO/backend:/app:ro" -v "$DUMP_DIR:/dump:ro" -w /app \
  python:3.12-slim bash -c "
    pip install --quiet 'psycopg[binary]>=3.1,<4.0' >/dev/null &&
    python migrate_mongo_to_pg.py --src /dump --dsn '$DSN'
  "

# --- 4. freeze goal snapshots on historical days ---------------------------
say "Backfilling frozen goal snapshots"
docker run --rm --network "$NETWORK" \
  -v "$REPO/backend:/app:ro" -w /app \
  python:3.12-slim bash -c "
    pip install --quiet 'psycopg[binary]>=3.1,<4.0' >/dev/null &&
    python backfill_day_targets.py --dsn '$DSN' $DRY_RUN
  "

# --- 5. summary ------------------------------------------------------------
say "Result"
docker exec "$PG_CONTAINER" psql -U "$PG_USER" -d "$PG_DB" -t -c "
  SELECT 'live days      : '||count(*)||'  (open: '||count(*) FILTER (WHERE NOT day_closed)||')' FROM days
  UNION ALL SELECT 'archive days   : '||count(*) FROM days_archive
  UNION ALL SELECT 'foods          : '||count(*) FROM foods
  UNION ALL SELECT 'frozen closed  : '||count(*) FROM days WHERE settings_snapshot IS NOT NULL
  UNION ALL SELECT 'closed w/o snap: '||count(*) FROM days WHERE day_closed AND settings_snapshot IS NULL;"

cat <<'DONE'

Migration complete.

Next:
  1. set PG_DSN in backend/app/.env  (see backend/app/.env.example)
  2. docker compose up -d --build backend
  3. check it serves:  curl -s localhost:2100/settings
  4. once happy, retire Mongo. The compose file no longer defines it, so stop the
     container directly and keep the volume until you are sure:
         docker stop plan-mongo && docker rm plan-mongo
     (the plan_mongo_data volume survives; delete it only when confident)

Mongo was only read; its data is untouched.
DONE
