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
  printf '%s\n' \
    'production startup excludes offline detector tooling' \
    ': "${NCNN_ANDROID_NDK_DIR:?set NCNN_ANDROID_NDK_DIR to the reviewed Android NDK}"' \
    './gradlew -PncnnAndroidNdkDir="$NCNN_ANDROID_NDK_DIR" :app:assembleDebug' \
    >"$root/RUNBOOK.md"
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

add_compose_up_ai_startup() {
  printf '%s\n' 'docker compose up -d ai-service' >>"$1/RUNBOOK.md"
}

add_compose_run_ai_startup() {
  printf '%s\n' 'docker compose run --rm ai-service' >>"$1/RUNBOOK.md"
}

add_systemctl_ai_startup() {
  printf '%s\n' 'sudo systemctl restart ai-service' >"$1/scripts/start-production.sh"
}

add_service_ai_startup() {
  printf '%s\n' 'sudo service ai-service start' >"$1/scripts/start-production.sh"
}

add_launchctl_ai_startup() {
  printf '%s\n' 'launchctl kickstart -k system/com.uavfire.ai-service' >"$1/scripts/start-production.sh"
}

add_launcher_ai_startup() {
  printf '%s\n' './start-ai-service.sh' >"$1/scripts/start-production.sh"
}

add_assigned_compose_ai_startup() {
  printf '%s\n' 'COMPOSE_PROFILES=ai docker compose up -d ai-service' \
    >"$1/scripts/start-production.sh"
}

add_env_compose_ai_startup() {
  printf '%s\n' 'env COMPOSE_PROJECT_NAME=uavfire docker compose up -d ai-service' \
    >"$1/scripts/start-production.sh"
}

add_systemctl_unit_suffix_ai_startup() {
  printf '%s\n' 'systemctl restart ai-service.service' >"$1/scripts/start-production.sh"
}

add_prefixed_systemctl_unit_ai_startup() {
  printf '%s\n' 'sudo systemctl restart uavfire-ai-service.service' \
    >"$1/scripts/start-production.sh"
}

add_systemctl_instance_ai_startup() {
  printf '%s\n' 'systemctl start ai-service@prod.service' >"$1/scripts/start-production.sh"
}

add_prefixed_systemctl_instance_ai_startup() {
  printf '%s\n' 'sudo systemctl restart uavfire-ai-service@rc-plus-2.service' \
    >"$1/scripts/start-production.sh"
}

add_env_systemctl_instance_ai_startup() {
  printf '%s\n' 'env SYSTEMD_COLORS=0 systemctl enable --now ai-service@site_01.service' \
    >"$1/scripts/start-production.sh"
}

add_assigned_systemctl_instance_ai_startup() {
  printf '%s\n' 'SYSTEMD_LOG_LEVEL=warning sudo systemctl try-restart uavfire-ai-service@staging.v2' \
    >"$1/scripts/start-production.sh"
}

add_allowed_prohibition_prose_and_tests() {
  local root="$1"
  printf '%s\n' \
    '禁止执行 `docker compose up -d ai-service`。' \
    '禁止执行 `systemctl start ai-service`、`service ai-service start`。' \
    '不要运行 `launchctl kickstart system/com.uavfire.ai-service` 或 `./start-ai-service.sh`。' \
    '禁止执行 `COMPOSE_PROFILES=ai docker compose up -d ai-service`。' \
    '不要运行 `env COMPOSE_PROJECT_NAME=uavfire docker compose up -d ai-service`。' \
    '禁止执行 `systemctl restart ai-service.service`。' \
    '禁止执行 `sudo systemctl restart uavfire-ai-service.service`。' \
    '禁止执行 `systemctl start ai-service@prod.service`。' \
    '禁止执行 `sudo systemctl restart uavfire-ai-service@rc-plus-2.service`。' \
    '不要运行 `env SYSTEMD_COLORS=0 systemctl enable --now ai-service@site_01.service`。' \
    '禁止执行 `SYSTEMD_LOG_LEVEL=warning sudo systemctl try-restart uavfire-ai-service@staging.v2`。' \
    >>"$root/RUNBOOK.md"
  mkdir -p "$root/backend/uavfire/src/test/java" "$root/rcplus-msdk-agent/app/src/test/java"
  printf '%s\n' 'assertLegacyRouteRejected("latest-visible-roi")' \
    >"$root/backend/uavfire/src/test/java/LegacyRoiPolicyTest.java"
  printf '%s\n' 'assertLegacyClientRejected("latestVisibleRoi")' \
    >"$root/rcplus-msdk-agent/app/src/test/java/LegacyRoiPolicyTest.kt"
}

add_backend_roi_polling() {
  printf '%s\n' 'client.get("/latest-visible-roi");' \
    >"$1/backend/uavfire/src/main/java/LegacyRoiPoller.java"
}

add_agent_roi_polling() {
  printf '%s\n' 'backend.latestVisibleRoi();' \
    >"$1/rcplus-msdk-agent/app/src/main/java/LegacyRoiPoller.kt"
}

remove_reviewed_ndk_recipe() {
  printf '%s\n' './gradlew :app:assembleDebug' >"$1/RUNBOOK.md"
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
expect_pass prohibited_examples_and_test_boundaries add_allowed_prohibition_prose_and_tests
expect_fail production_ai_startup add_production_ai_startup
expect_fail compose_up_ai_startup add_compose_up_ai_startup
expect_fail compose_run_ai_startup add_compose_run_ai_startup
expect_fail systemctl_ai_startup add_systemctl_ai_startup
expect_fail service_ai_startup add_service_ai_startup
expect_fail launchctl_ai_startup add_launchctl_ai_startup
expect_fail launcher_ai_startup add_launcher_ai_startup
expect_fail assigned_compose_ai_startup add_assigned_compose_ai_startup
expect_fail env_compose_ai_startup add_env_compose_ai_startup
expect_fail systemctl_unit_suffix_ai_startup add_systemctl_unit_suffix_ai_startup
expect_fail prefixed_systemctl_unit_ai_startup add_prefixed_systemctl_unit_ai_startup
expect_fail systemctl_instance_ai_startup add_systemctl_instance_ai_startup
expect_fail prefixed_systemctl_instance_ai_startup add_prefixed_systemctl_instance_ai_startup
expect_fail env_systemctl_instance_ai_startup add_env_systemctl_instance_ai_startup
expect_fail assigned_systemctl_instance_ai_startup add_assigned_systemctl_instance_ai_startup
expect_fail backend_roi_polling add_backend_roi_polling
expect_fail agent_roi_polling add_agent_roi_polling
expect_fail missing_reviewed_ndk_recipe remove_reviewed_ndk_recipe
expect_fail legacy_dispatch add_legacy_dispatch
expect_fail raw_ui_fallback add_raw_ui_fallback
expect_fail second_runtime add_second_runtime

echo "Static policy fixture tests: PASS"
echo "Running policy against active checkout: $repo_root"
"$checker" "$repo_root"
