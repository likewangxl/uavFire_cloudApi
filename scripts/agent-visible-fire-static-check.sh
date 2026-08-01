#!/usr/bin/env bash
set -euo pipefail

repo_root="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
cd "$repo_root"

if ! command -v rg >/dev/null 2>&1; then
  echo "ERROR: agent visible-fire policy requires rg" >&2
  exit 2
fi

violations=0

existing_paths() {
  local path
  for path in "$@"; do
    [[ -e "$path" ]] && printf '%s\n' "$path"
  done
}

reject_matches() {
  local label="$1"
  local pattern="$2"
  shift 2
  local -a paths=()
  while IFS= read -r path; do paths+=("$path"); done < <(existing_paths "$@")
  [[ ${#paths[@]} -eq 0 ]] && return 0
  local output
  output=$(rg -n -i --hidden --glob '!**/*test*' --glob '!agent-visible-fire-static-check.sh' \
    --glob '!agent-visible-fire-static-check.test.sh' -e "$pattern" "${paths[@]}" 2>/dev/null || true)
  if [[ -n "$output" ]]; then
    echo "POLICY_VIOLATION[$label]" >&2
    echo "$output" >&2
    violations=$((violations + 1))
  fi
}

require_match() {
  local label="$1"
  local pattern="$2"
  local path="$3"
  if [[ ! -f "$path" ]] || ! rg -q -e "$pattern" "$path"; then
    echo "POLICY_VIOLATION[$label]: missing '$pattern' in $path" >&2
    violations=$((violations + 1))
  fi
}

production_docs=(
  README.md
  RUNBOOK.md
  HANDOFF_2026-07-28_VISIBLE_ONLY_FIRE_DETECTION.md
  deployment/zlmediakit/README.md
  docs/runbooks
)
production_config=(
  backend/uavfire/src/main/resources
  rcplus-msdk-agent/app/build.gradle.kts
  rcplus-msdk-agent/gradle.properties
  frontend/env
  deployment/zlmediakit
)
production_scripts=(scripts)
backend_main=(backend/uavfire/src/main)
agent_main=(rcplus-msdk-agent/app/src/main)
frontend_main=(frontend/src)

# Operational signatures only. Plain statements that ai-service is offline and
# forbidden in production remain legal documentation.
reject_matches "production-ai-service-operations" \
  'AI_SERVICE_[A-Z0-9_]+|ai-service:[[:space:]]*$|ai[-_ ]service[^\n]{0,40}(base[-_ ]?url|healthz|tunnel|隧道)|uvicorn[[:space:]]+app\.main:app|uavfire-ai(-tunnel|\.service)|https?://[^[:space:]]*:9000/(healthz|api/v1/tasks)|/api/v1/tasks/(start|stop|query)' \
  "${production_docs[@]}" "${production_config[@]}" "${production_scripts[@]}"

# Match command-shaped lines, not prose that quotes forbidden commands for
# operator guidance. Service definitions remain covered by the signature above.
reject_matches "production-ai-service-launch-command" \
  '^[[:space:]]*((sudo|env)[[:space:]]+)?(docker([[:space:]]+compose|-compose)[[:space:]][^#]*(up|run|start|restart)[^#]*ai[-_]service([[:space:]]|$)|(systemctl[[:space:]][^#]*(start|restart|enable|try-restart)[^#]*ai[-_]service([[:space:]]|$))|(service[[:space:]]+ai[-_]service[[:space:]]+(start|restart)([[:space:]]|$))|(launchctl[[:space:]][^#]*(load|bootstrap|kickstart|start)[^#]*ai[-_]service([^[:alnum:]_]|$))|((bash|sh)[[:space:]]+)?([^[:space:]]*/)?(start|run|restart)[-_]ai[-_]service(\.sh)?([[:space:]]|$))' \
  "${production_docs[@]}" "${production_config[@]}" "${production_scripts[@]}"

reject_matches "backend-ai-service-client" \
  'AiServiceClient|ai[-_ ]service[^\n]{0,40}(base[-_ ]?url|healthz)|/api/v1/tasks/(start|stop|query)' \
  "${backend_main[@]}"

reject_matches "frontend-ai-service-lifecycle" \
  'ai-service|AI_SERVICE|/api/v1/tasks/(start|stop|query)' \
  "${frontend_main[@]}"

reject_matches "production-roi-polling" \
  'latest-visible-roi|latestVisibleRoi|LatestVisibleRoi' \
  "${backend_main[@]}" "${agent_main[@]}"

reject_matches "legacy-backend-flight-dispatch" \
  'visible-fire-hold|visible-fire-laser-measure|fire-confirmation-mission' \
  "${backend_main[@]}" "${agent_main[@]}"

fire_ui=(
  frontend/src/pages/page-web/projects/leadership-cockpit.vue
  frontend/src/pages/page-web/projects/fire
  frontend/src/components/fire
)
reject_matches "raw-fire-ui-status-fallback" \
  '\{\{[[:space:]]*[A-Za-z0-9_?]+\.(status|phase|locationStatus|flightStatus|detectionStatus)[[:space:]]*(\?\?|\|\|)' \
  "${fire_ui[@]}"
require_match "unknown-ui-status-label" "未知状态" \
  frontend/src/pages/page-web/projects/fire/fire-event-status.mjs

manifest=rcplus-msdk-agent/app/src/main/assets/fire-detection/model-manifest.json
require_match "detector-default-off" '"enabledByDefault"[[:space:]]*:[[:space:]]*false' "$manifest"
require_match "device-gate-not-claimed" '"visible960GatePassed"[[:space:]]*:[[:space:]]*false' "$manifest"
require_match "single-runtime-ncnn" '"name"[[:space:]]*:[[:space:]]*"ncnn"' "$manifest"
require_match "approved-model-version" '"modelVersion"[[:space:]]*:[[:space:]]*"visible-fire-wechat-best2-20260728"' "$manifest"
require_match "production-arming-default-off" 'VISIBLE_FIRE_DETECTION_ENABLED"[[:space:]]*,[[:space:]]*"false"' \
  rcplus-msdk-agent/app/build.gradle.kts
require_match "production-runbook-reviewed-ndk-preflight" \
  'NCNN_ANDROID_NDK_DIR:\?[^}]*reviewed Android NDK' RUNBOOK.md
require_match "production-runbook-reviewed-ndk-property" \
  '-PncnnAndroidNdkDir="\$NCNN_ANDROID_NDK_DIR"' RUNBOOK.md

reject_matches "alternate-production-runtime" \
  'org\.tensorflow|tensorflow-lite|tensorflowlite|onnxruntime|pytorch_android|libtorch|\.tflite(["[:space:]]|$)|\.onnx(["[:space:]]|$)' \
  rcplus-msdk-agent/app/src/main rcplus-msdk-agent/app/build.gradle.kts

asset_dir=rcplus-msdk-agent/app/src/main/assets/fire-detection
if [[ -d "$asset_dir" ]]; then
  detector_assets=$(rg --files "$asset_dir" | sed "s#^$asset_dir/##" | LC_ALL=C sort | tr '\n' ' ' | sed 's/[[:space:]]*$//')
  expected_assets="model-manifest.json visible-fire-960.ncnn.bin visible-fire-960.ncnn.param"
  if [[ "$detector_assets" != "$expected_assets" ]]; then
    echo "POLICY_VIOLATION[single-production-model]: expected '$expected_assets', got '$detector_assets'" >&2
    violations=$((violations + 1))
  fi
else
  echo "POLICY_VIOLATION[single-production-model]: missing $asset_dir" >&2
  violations=$((violations + 1))
fi

if (( violations > 0 )); then
  echo "Agent visible-fire static policy: FAIL ($violations categories)" >&2
  exit 1
fi

echo "Agent visible-fire static policy: PASS"
