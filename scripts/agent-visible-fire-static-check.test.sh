#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
checker="$script_dir/agent-visible-fire-static-check.sh"
repo_root=$(cd "$script_dir/.." && pwd)
tmp_root=$(mktemp -d "${TMPDIR:-/tmp}/agent-visible-fire-policy.XXXXXX")
trap 'rm -rf "$tmp_root"' EXIT

new_fixture() {
  local root="$1"
  mkdir -p "$root"/{scripts,docs/runbooks,deployment/zlmediakit,backend/uavfire/src/main/resources,backend/uavfire/src/main/java,frontend/src/pages/page-web/projects/fire,frontend/src/components/fire,frontend/env,rcplus-msdk-agent/app/src/main/assets/fire-detection,rcplus-msdk-agent/app/src/main/java,ai-service,docs/superpowers/specs,docs/superpowers/plans}
  printf '%s\n' 'production backend frontend ZLM Agent only' >"$root/README.md"
  printf '%s\n' 'production startup excludes offline detector tooling' >"$root/RUNBOOK.md"
  printf '%s\n' 'current Agent handoff' >"$root/HANDOFF_2026-07-28_VISIBLE_ONLY_FIRE_DETECTION.md"
  printf '%s\n' 'display only media' >"$root/deployment/zlmediakit/README.md"
  printf '%s\n' '未知状态' >"$root/frontend/src/pages/page-web/projects/fire/fire-event-status.mjs"
  printf '%s\n' 'android.defaultConfig.buildConfigField("boolean", "VISIBLE_FIRE_DETECTION_ENABLED", "false")' >"$root/rcplus-msdk-agent/app/build.gradle.kts"
  cat >"$root/rcplus-msdk-agent/app/src/main/assets/fire-detection/model-manifest.json" <<'JSON'
{"enabledByDefault":false,"visible960GatePassed":false,"runtime":{"name":"ncnn"},"modelVersion":"visible-fire-wechat-best2-20260728"}
JSON
  : >"$root/rcplus-msdk-agent/app/src/main/assets/fire-detection/visible-fire-960.ncnn.bin"
  : >"$root/rcplus-msdk-agent/app/src/main/assets/fire-detection/visible-fire-960.ncnn.param"
}

expect_pass() {
  local name="$1"
  local root="$tmp_root/$name"
  new_fixture "$root"
  shift
  "$@" "$root"
  "$checker" "$root" >/dev/null
}

expect_fail() {
  local name="$1"
  local root="$tmp_root/$name"
  new_fixture "$root"
  shift
  "$@" "$root"
  if "$checker" "$root" >/dev/null 2>&1; then
    echo "expected policy failure: $name" >&2
    exit 1
  fi
}

add_offline_history() {
  local root="$1"
  printf '%s\n' 'AI_SERVICE_BASE_URL=http://127.0.0.1:9000' >"$root/ai-service/.env"
  printf '%s\n' 'uvicorn app.main:app and visible-fire-hold are historical only' >"$root/docs/superpowers/specs/old.md"
  printf '%s\n' 'curl http://127.0.0.1:9000/healthz' >"$root/docs/superpowers/plans/old.md"
}

add_production_ai_startup() {
  printf '%s\n' '.venv/bin/python -m uvicorn app.main:app --port 9000' >>"$1/RUNBOOK.md"
}

add_legacy_dispatch() {
  printf '%s\n' 'dispatch("visible-fire-laser-measure");' >"$1/backend/uavfire/src/main/java/Legacy.java"
}

add_raw_ui_fallback() {
  printf '%s\n' '<span>{{ event.status ?? "-" }}</span>' >"$1/frontend/src/pages/page-web/projects/fire/Bad.vue"
}

add_second_runtime() {
  : >"$1/rcplus-msdk-agent/app/src/main/assets/fire-detection/alternate.tflite"
}

expect_pass compliant true
expect_pass excluded_offline_and_history add_offline_history
expect_fail production_ai_startup add_production_ai_startup
expect_fail legacy_dispatch add_legacy_dispatch
expect_fail raw_ui_fallback add_raw_ui_fallback
expect_fail second_runtime add_second_runtime

echo "Static policy fixture tests: PASS"
echo "Running policy against active checkout: $repo_root"
"$checker" "$repo_root"
