#!/usr/bin/env bash
set -euo pipefail

JAR="${RIKKAWEB_JAR:-${RIKKAHUB_JAR:-./rikkaweb.jar}}"
DATA_DIR="${RIKKAWEB_DATA_DIR:-${RIKKAHUB_DATA_DIR:-./data}}"
HOST="${RIKKAWEB_HOST:-${RIKKAHUB_HOST:-0.0.0.0}}"
PORT="${RIKKAWEB_PORT:-${RIKKAHUB_PORT:-11001}}"
JWT_ENABLED="${RIKKAWEB_JWT_ENABLED:-${RIKKAHUB_JWT_ENABLED:-true}}"
ACCESS_PASSWORD="${RIKKAWEB_ACCESS_PASSWORD:-${RIKKAHUB_ACCESS_PASSWORD:-}}"

if [[ -z "$ACCESS_PASSWORD" ]]; then
  echo "Missing RIKKAHUB_ACCESS_PASSWORD"
  echo "Example: RIKKAHUB_ACCESS_PASSWORD=rikka1125 $0"
  exit 2
fi

mkdir -p "$DATA_DIR"

exec java -jar "$JAR" \
  --host "$HOST" \
  --port "$PORT" \
  --data-dir "$DATA_DIR" \
  --jwt-enabled "$JWT_ENABLED" \
  --access-password "$ACCESS_PASSWORD"
