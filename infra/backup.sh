#!/usr/bin/env bash
# Nightly PostgreSQL backup for Plan. Streams a gzipped pg_dump of the DB to BACKUP_DIR and
# keeps only the most recent $KEEP dumps. Intended to run from cron on the prod host
# (as a user that can run docker, e.g. root):
#
#   0 3 * * * /opt/plan/infra/backup.sh >> /var/log/plan-backup.log 2>&1
#
set -euo pipefail

BACKUP_DIR="${PLAN_BACKUP_DIR:-/opt/plan-backups}"
KEEP="${PLAN_BACKUP_KEEP:-14}"
CONTAINER="${PLAN_PG_CONTAINER:-plan-postgres}"
DB="${PLAN_PG_DB:-plan_db}"
PGUSER="${PLAN_PG_USER:-plan}"

mkdir -p "$BACKUP_DIR"
ts="$(date +%Y%m%d_%H%M%S)"
out="$BACKUP_DIR/${DB}_${ts}.sql.gz"

# Stream the dump out of the container to the host. Write to a .partial file first and only
# rename on success, so an interrupted run can never leave a truncated dump that looks valid
# to the rotation step below.
docker exec "$CONTAINER" pg_dump -U "$PGUSER" -d "$DB" --clean --if-exists \
  | gzip -c > "$out.partial"
mv "$out.partial" "$out"

# Sanity-check: a dump that doesn't end with PostgreSQL's completion marker is truncated.
if ! gzip -dc "$out" | tail -n 5 | grep -q "PostgreSQL database dump complete"; then
  echo "$(date -Is) backup FAILED: $out is truncated (no completion marker)" >&2
  exit 1
fi

# Rotate: keep the newest $KEEP dumps, delete older ones.
ls -1t "$BACKUP_DIR"/${DB}_*.sql.gz 2>/dev/null | tail -n +$((KEEP + 1)) | xargs -r rm -f

echo "$(date -Is) backup ok: $out ($(du -h "$out" | cut -f1)); $(ls -1 "$BACKUP_DIR"/${DB}_*.sql.gz | wc -l) kept"

# Restore (for reference):
#   gzip -dc plan_db_YYYYmmdd_HHMMSS.sql.gz | docker exec -i plan-postgres psql -U plan -d plan_db
