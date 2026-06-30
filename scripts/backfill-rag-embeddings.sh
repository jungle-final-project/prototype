#!/usr/bin/env bash
set -euo pipefail

API_BASE_URL="${API_BASE_URL:-http://localhost:8080}"
ADMIN_EMAIL="${ADMIN_EMAIL:-admin@example.com}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-passw0rd!}"
LIMIT="${LIMIT:-200}"

PYTHON_BIN="${PYTHON_BIN:-python3}"
if ! command -v "$PYTHON_BIN" >/dev/null 2>&1; then
  PYTHON_BIN="python"
fi

if ! command -v "$PYTHON_BIN" >/dev/null 2>&1; then
  echo "python was not found. Install Python 3.11 or run the PowerShell script on Windows." >&2
  exit 1
fi

health_json="$(curl -fsS "$API_BASE_URL/api/health")"
health_status="$(printf '%s' "$health_json" | "$PYTHON_BIN" -c 'import json,sys; print(json.load(sys.stdin).get("status",""))')"
if [ "$health_status" != "UP" ]; then
  echo "API health is not UP. Current response: $health_json" >&2
  exit 1
fi

login_json="$(
  curl -fsS -X POST "$API_BASE_URL/api/auth/login" \
    -H "Content-Type: application/json" \
    -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}"
)"

access_token="$(printf '%s' "$login_json" | "$PYTHON_BIN" -c 'import json,sys; print(json.load(sys.stdin).get("accessToken",""))')"
if [ -z "$access_token" ]; then
  echo "Admin login succeeded but accessToken was not returned." >&2
  exit 1
fi

curl -fsS -X POST "$API_BASE_URL/api/admin/rag-embeddings/backfill" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $access_token" \
  -d "{\"limit\":$LIMIT}" \
  | "$PYTHON_BIN" -m json.tool
