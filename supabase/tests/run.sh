#!/usr/bin/env bash
# Builds the schema from nothing in a throwaway container and runs every
# *_test.sql against it. Never talks to a real project: the only database this
# knows about is the one it starts and removes.
#
# The base schema (videos, rally_clips, rally_annotations) lives in the web
# repo, so its migrations are applied first. Point BASE_MIGRATIONS at them if the
# two repos are not checked out side by side.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
BASE_MIGRATIONS="${BASE_MIGRATIONS:-$HERE/../../../badminton-tracker/supabase/migrations}"
IMAGE="${SUPABASE_PG_IMAGE:-public.ecr.aws/supabase/postgres:17.6.1.156}"
NAME="rally-dbtest-$$"

[ -d "$BASE_MIGRATIONS" ] || { echo "base migrations not found at $BASE_MIGRATIONS" >&2; exit 2; }

cleanup() { docker rm -f "$NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT

docker run -d --name "$NAME" -e POSTGRES_PASSWORD=postgres "$IMAGE" >/dev/null
for _ in $(seq 1 60); do
    docker exec "$NAME" pg_isready -U postgres -h localhost >/dev/null 2>&1 && break
    sleep 1
done
# pg_isready answers during the image's own init restart; wait for the real one.
for _ in $(seq 1 60); do
    docker exec -e PGPASSWORD=postgres "$NAME" psql -U supabase_admin -h localhost -d postgres \
        -Atc "select 1 from pg_roles where rolname = 'authenticated'" 2>/dev/null | grep -q 1 && break
    sleep 1
done

run_sql() {
    docker exec -i -e PGPASSWORD=postgres -e PGOPTIONS "$NAME" \
        psql -U supabase_admin -h localhost -d postgres -v ON_ERROR_STOP=1 -q -f - < "$1" >/dev/null
}
# The migrations are idempotent, so a first run is a wall of "does not exist,
# skipping". Errors still come through.
export PGOPTIONS="-c client_min_messages=warning"

run_sql "$HERE/00_storage_stub.sql"
for f in "$BASE_MIGRATIONS"/*.sql "$HERE"/../migrations/*.sql; do
    run_sql "$f" || { echo "FAILED applying $f" >&2; exit 1; }
done

status=0
for t in "$HERE"/*_test.sql; do
    if out=$(docker exec -i -e PGPASSWORD=postgres -e PGOPTIONS "$NAME" \
        psql -U supabase_admin -h localhost -d postgres -v ON_ERROR_STOP=1 -q -f - < "$t" 2>&1); then
        echo "ok    $(basename "$t")"
    else
        echo "FAIL  $(basename "$t")"; echo "$out" | sed 's/^/      /'; status=1
    fi
done
exit $status
