#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if ! command -v npm >/dev/null 2>&1; then
  echo "npm was not found. Install Node.js 22 or use Dev Container." >&2
  exit 1
fi

PYTHON_BIN="${PYTHON_BIN:-python3}"
if ! command -v "$PYTHON_BIN" >/dev/null 2>&1; then
  PYTHON_BIN="python"
fi

if ! command -v "$PYTHON_BIN" >/dev/null 2>&1; then
  echo "python was not found. Install Python 3.11 or use Dev Container." >&2
  exit 1
fi

npm --prefix apps/web ci
pushd apps/web >/dev/null
if command -v apt-get >/dev/null 2>&1 && command -v sudo >/dev/null 2>&1; then
  npx playwright install --with-deps chromium
else
  npx playwright install chromium
fi
popd >/dev/null

"$PYTHON_BIN" -m venv .venv
. .venv/bin/activate

python -m pip install --upgrade pip
python -m pip install -r tools/requirements.txt -r apps/pc-agent/requirements.txt
python --version

echo "Development dependencies installed."
