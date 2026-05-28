#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
OLD_IP_PREFIX="192.168.99"
OLD_IP="${OLD_IP_PREFIX}.99"
NEW_IP="172.20.10.7"

mkdir -p \
  "$TMP_DIR/scripts" \
  "$TMP_DIR/backend/uavfire/src/main/resources" \
  "$TMP_DIR/deployment/zlmediakit/config" \
  "$TMP_DIR/deployment/zlmediakit" \
  "$TMP_DIR/frontend/env" \
  "$TMP_DIR/frontend/src/api/http" \
  "$TMP_DIR/rcplus-msdk-agent"

cp "$ROOT/scripts/switch-dev-ip.sh" "$TMP_DIR/scripts/switch-dev-ip.sh"

cat > "$TMP_DIR/backend/uavfire/src/main/resources/application.yml" <<EOF
media:
  host: ${OLD_IP}
  server-url: http://${OLD_IP}:6789 # Example: http://192.168.1.1:6789
EOF
cat > "$TMP_DIR/deployment/zlmediakit/config/config.ini" <<EOF
externIP=${OLD_IP}
EOF
cat > "$TMP_DIR/deployment/zlmediakit/.env" <<EOF
ZLM_PUBLIC_HOST=${OLD_IP}
EOF
cat > "$TMP_DIR/frontend/env/.env" <<EOF
VITE_APP_APIGATEWAY_BACKEND_HOST='http://${OLD_IP}:6789'
EOF
cat > "$TMP_DIR/frontend/src/api/http/config.ts" <<EOF
const backendHost = 'http://${OLD_IP}:6789'
export const baseURL = 'http://${OLD_IP}:6789/' // Example: 'http://192.168.1.1:6789/'
export const rtmpURL = 'rtmp://${OLD_IP}:1935/live/'
EOF
cat > "$TMP_DIR/rcplus-msdk-agent/gradle.properties" <<EOF
agentBackendBaseUrl=http://${OLD_IP}:6789/
agentMediaHost=${OLD_IP}
agentMqttBrokerUrl=tcp://${OLD_IP}:1883
EOF

(cd "$TMP_DIR" && git init -q && git add .)

(cd "$TMP_DIR" && bash scripts/switch-dev-ip.sh "$NEW_IP" --skip-apk >/tmp/switch-dev-ip-test.log)

if grep -R "$OLD_IP" \
  "$TMP_DIR/backend/uavfire/src/main/resources/application.yml" \
  "$TMP_DIR/deployment/zlmediakit/config/config.ini" \
  "$TMP_DIR/deployment/zlmediakit/.env" \
  "$TMP_DIR/frontend/env/.env" \
  "$TMP_DIR/frontend/src/api/http/config.ts" \
  "$TMP_DIR/rcplus-msdk-agent/gradle.properties" >/dev/null; then
  echo "expected stale LAN IPs to be replaced when agentMediaHost already matches new IP" >&2
  cat /tmp/switch-dev-ip-test.log >&2
  exit 1
fi

if ! grep -F "192.168.1.1" "$TMP_DIR/backend/uavfire/src/main/resources/application.yml" >/dev/null; then
  echo "expected example IPs in comments to remain unchanged" >&2
  cat "$TMP_DIR/backend/uavfire/src/main/resources/application.yml" >&2
  exit 1
fi

if ! grep -F "192.168.1.1" "$TMP_DIR/frontend/src/api/http/config.ts" >/dev/null; then
  echo "expected frontend example IPs in comments to remain unchanged" >&2
  cat "$TMP_DIR/frontend/src/api/http/config.ts" >&2
  exit 1
fi

echo "switch-dev-ip mixed-state regression passed"
