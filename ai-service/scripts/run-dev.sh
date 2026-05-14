#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

exec "${PROJECT_ROOT}/.venv/bin/python" -m uvicorn app.main:app --host 0.0.0.0 --port 9000 --reload
