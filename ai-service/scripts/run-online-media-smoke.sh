#!/usr/bin/env bash
set -euo pipefail

AI_BASE_URL="${AI_BASE_URL:-http://127.0.0.1:9000}"
BACKEND_BASE_URL="${BACKEND_BASE_URL:-http://127.0.0.1:6789}"
BACKEND_USERNAME="${BACKEND_USERNAME:-adminPC}"
BACKEND_PASSWORD="${BACKEND_PASSWORD:-adminPC}"
BACKEND_LOGIN_FLAG="${BACKEND_LOGIN_FLAG:-1}"
MEDIA_DIR="${MEDIA_DIR:-/tmp/uavfire-online-media}"

FIRE_URL="https://upload.wikimedia.org/wikipedia/commons/4/42/Fire_fire_flames.jpg"
FOREST_URL="https://upload.wikimedia.org/wikipedia/commons/3/34/Amazon_green_forest.jpg"
FIRE_VIDEO_URL="https://upload.wikimedia.org/wikipedia/commons/8/89/Fire_flames_9652_Nevit.ogv"

mkdir -p "${MEDIA_DIR}"

download_media() {
  local url="$1"
  local output="$2"
  if [[ ! -s "${output}" ]]; then
    curl -L "${url}" -o "${output}"
  fi
}

backend_token() {
  curl -s -X POST "${BACKEND_BASE_URL}/manage/api/v1/login" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"${BACKEND_USERNAME}\",\"password\":\"${BACKEND_PASSWORD}\",\"flag\":${BACKEND_LOGIN_FLAG}}" \
    | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p'
}

wait_for_detection() {
  local task_id="$1"
  local events_file="${MEDIA_DIR}/${task_id}-events.json"
  for _ in {1..10}; do
    curl -s "${AI_BASE_URL}/api/v1/dual-stream/tasks/${task_id}/events" >"${events_file}"
    if grep -q '"event_type":"detection"' "${events_file}"; then
      return 0
    fi
    sleep 0.5
  done
}

run_task() {
  local label="$1"
  local media_file="$2"
  local task_id="online-${label}-$(date +%s)"

  curl -s -X POST "${AI_BASE_URL}/api/v1/dual-stream/tasks" \
    -H "Content-Type: application/json" \
    -d "{\"task_id\":\"${task_id}\",\"drone_sn\":\"AI_ONLINE\",\"visible_stream_url\":\"${media_file}\",\"thermal_stream_url\":\"\"}" \
    >/dev/null

  curl -s -X POST "${AI_BASE_URL}/api/v1/dual-stream/tasks/${task_id}/start" >/dev/null
  wait_for_detection "${task_id}"
  curl -s -X POST "${AI_BASE_URL}/api/v1/dual-stream/tasks/${task_id}/stop" >/dev/null

  echo "TASK ${task_id}"
  echo "AI_EVENTS"
  curl -s "${AI_BASE_URL}/api/v1/dual-stream/tasks/${task_id}/events"
  echo

  local token
  token="$(backend_token)"
  echo "BACKEND_EVENTS"
  curl -s -H "x-auth-token: ${token}" \
    "${BACKEND_BASE_URL}/manage/api/v1/dual-stream/tasks/${task_id}/events"
  echo
  echo "---"
}

download_media "${FIRE_URL}" "${MEDIA_DIR}/fire-fire-flames.jpg"
download_media "${FOREST_URL}" "${MEDIA_DIR}/amazon-green-forest.jpg"
download_media "${FIRE_VIDEO_URL}" "${MEDIA_DIR}/fire-flames-nevit.ogv"

if command -v ffmpeg >/dev/null 2>&1; then
  ffmpeg -y -loglevel error \
    -i "${MEDIA_DIR}/fire-flames-nevit.ogv" \
    -t 5 -an -c:v libx264 -pix_fmt yuv420p \
    "${MEDIA_DIR}/fire-flames-nevit-5s.mp4"
fi

run_task "fire" "${MEDIA_DIR}/fire-fire-flames.jpg"
run_task "forest" "${MEDIA_DIR}/amazon-green-forest.jpg"
if [[ -s "${MEDIA_DIR}/fire-flames-nevit-5s.mp4" ]]; then
  run_task "fire-video" "${MEDIA_DIR}/fire-flames-nevit-5s.mp4"
fi
