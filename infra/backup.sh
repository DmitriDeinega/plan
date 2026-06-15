#!/usr/bin/env bash
# Nightly MongoDB backup for Plan. Streams a gzipped archive of the DB to BACKUP_DIR and
# keeps only the most recent $KEEP archives. Intended to run from cron on the prod host
# (as a user that can run docker, e.g. root):
#
#   0 3 * * * /opt/plan/infra/backup.sh >> /var/log/plan-backup.log 2>&1
#
set -euo pipefail

BACKUP_DIR="${PLAN_BACKUP_DIR:-/opt/plan-backups}"
KEEP="${PLAN_BACKUP_KEEP:-14}"
CONTAINER="${PLAN_MONGO_CONTAINER:-plan-mongo}"
DB="${PLAN_MONGO_DB:-plan_db}"

mkdir -p "$BACKUP_DIR"
ts="$(date +%Y%m%d_%H%M%S)"
out="$BACKUP_DIR/${DB}_${ts}.archive.gz"

# Stream the gzipped archive out of the container to the host.
docker exec "$CONTAINER" mongodump --db "$DB" --archive --gzip > "$out"

# Rotate: keep the newest $KEEP archives, delete older ones.
ls -1t "$BACKUP_DIR"/${DB}_*.archive.gz 2>/dev/null | tail -n +$((KEEP + 1)) | xargs -r rm -f

echo "$(date -Is) backup ok: $out ($(du -h "$out" | cut -f1)); $(ls -1 "$BACKUP_DIR"/${DB}_*.archive.gz | wc -l) kept"
