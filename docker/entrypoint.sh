#!/usr/bin/env bash
set -euo pipefail

HOST="${RIKKAHUB_HOST:-0.0.0.0}"
PORT="${RIKKAHUB_PORT:-${PORT:-11001}}"
DATA_DIR="${RIKKAHUB_DATA_DIR:-/data}"
JWT_ENABLED="${RIKKAHUB_JWT_ENABLED:-true}"
ACCESS_PASSWORD="${RIKKAHUB_ACCESS_PASSWORD:-}"

JAVA_OPTS="${JAVA_OPTS:-}"

if [[ "$JWT_ENABLED" == "true" && -z "$ACCESS_PASSWORD" ]]; then
  echo "Missing RIKKAHUB_ACCESS_PASSWORD (required when RIKKAHUB_JWT_ENABLED=true)" >&2
  exit 2
fi

args=(
  --host "$HOST"
  --port "$PORT"
  --data-dir "$DATA_DIR"
  --jwt-enabled "$JWT_ENABLED"
)

if [[ -n "$ACCESS_PASSWORD" ]]; then
  args+=(--access-password "$ACCESS_PASSWORD")
fi

exec java $JAVA_OPTS -jar /app/rikkaweb.jar "${args[@]}" "$@"
