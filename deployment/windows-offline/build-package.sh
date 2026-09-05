#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
OUTPUT_DIR="${1:-${PROJECT_ROOT}/releases}"
PACKAGE_NAME="UAVFire-Windows-x64-Trial-20260905"
CACHE_DIR="${HOME}/.cache/uavfire-windows-offline"
WORK_DIR="$(mktemp -d)"
STAGE_DIR="${WORK_DIR}/${PACKAGE_NAME}"
trap 'rm -rf "${WORK_DIR}"' EXIT

mkdir -p "${OUTPUT_DIR}" "${CACHE_DIR}" "${STAGE_DIR}"

download() {
  local url="$1"
  local output="$2"
  if [[ ! -s "${output}" ]]; then
    curl -fL --retry 4 --retry-delay 2 -o "${output}.part" "${url}"
    mv "${output}.part" "${output}"
  fi
}

echo '[1/8] Testing and building application'
(cd "${PROJECT_ROOT}/frontend" && node --test \
  scripts/runtime-config.test.mjs \
  src/components/wayline-planner/__tests__/strict-waypoint-turn.test.mjs \
  src/pages/page-web/projects/__tests__/trial-expiration-policy.test.mjs)
if command -v pwsh >/dev/null 2>&1; then
  pwsh -NoLogo -NoProfile -File "${SCRIPT_DIR}/tests/windows-scripts.test.ps1"
fi
(cd "${PROJECT_ROOT}/frontend" && npm run build)
(cd "${PROJECT_ROOT}/backend" && \
  mvn -pl uavfire -Dtest=PlannedWaylineServiceTest,TrialExpirationPolicyTest,TrialExpirationFilterTest test)
(cd "${PROJECT_ROOT}/backend" && mvn -pl uavfire -DskipTests package spring-boot:repackage)

# The Agent and the new Windows installation must share the same credentials.
# These package-specific values are generated per ZIP and are never written to the source tree.
BUNDLED_MQTT_PASSWORD="$(openssl rand -hex 24)"
BUNDLED_WAYLINE_SHARED_SECRET="$(openssl rand -hex 32)"

GIT_COMMON_DIR="$(git -C "${PROJECT_ROOT}" rev-parse --path-format=absolute --git-common-dir)"
PRIMARY_CHECKOUT_ROOT="$(dirname "${GIT_COMMON_DIR}")"
AGENT_JAVA_HOME="${AGENT_JAVA_HOME:-/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
AGENT_ANDROID_SDK="${ANDROID_SDK_ROOT:-${HOME}/Library/Android/sdk}"
AGENT_UXSDK_DIR="${DJI_UXSDK_DIR:-${PRIMARY_CHECKOUT_ROOT}/Mobile-SDK-Android-V5/SampleCode-V5/android-sdk-v5-uxsdk}"
[[ -x "${AGENT_JAVA_HOME}/bin/java" ]] || { echo "Missing Agent JDK 17: ${AGENT_JAVA_HOME}" >&2; exit 1; }
[[ -d "${AGENT_ANDROID_SDK}" ]] || { echo "Missing Android SDK: ${AGENT_ANDROID_SDK}" >&2; exit 1; }
[[ -d "${AGENT_UXSDK_DIR}" ]] || { echo "Missing DJI UXSDK: ${AGENT_UXSDK_DIR}" >&2; exit 1; }
(cd "${PROJECT_ROOT}/rcplus-msdk-agent" && \
  JAVA_HOME="${AGENT_JAVA_HOME}" ANDROID_HOME="${AGENT_ANDROID_SDK}" ANDROID_SDK_ROOT="${AGENT_ANDROID_SDK}" \
  ./gradlew --console=plain \
    -PdjiUxSdkDir="${AGENT_UXSDK_DIR}" \
    -PagentMqttBrokerUsername=JavaServer \
    -PagentMqttBrokerPassword="${BUNDLED_MQTT_PASSWORD}" \
    -PagentWaylineSharedSecret="${BUNDLED_WAYLINE_SHARED_SECRET}" \
    :app:testDebugUnitTest :app:assembleDebug)

echo '[2/8] Downloading Windows runtimes'
JRE_ARCHIVE="${CACHE_DIR}/OpenJDK17U-jre_x64_windows_hotspot_17.0.20.1_1.zip"
MYSQL_ARCHIVE="${CACHE_DIR}/mysql-8.4.11-winx64.zip"
NGINX_ARCHIVE="${CACHE_DIR}/nginx-1.28.3.zip"
REDIS_ARCHIVE="${CACHE_DIR}/Redis-x64-5.0.14.1.zip"
MOSQUITTO_INSTALLER="${CACHE_DIR}/mosquitto-2.1.2-install-windows-x64.exe"
PYTHON_INSTALLER="${CACHE_DIR}/python-3.11.9-amd64.exe"
VC_REDIST="${CACHE_DIR}/VC_redist.x64.exe"
PLATFORM_TOOLS_ARCHIVE="${CACHE_DIR}/platform-tools-latest-windows.zip"

download 'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jre_x64_windows_hotspot_17.0.20.1_1.zip' "${JRE_ARCHIVE}"
echo 'bc21a93923103cdaac93ee337b0ae4365e739fde36df823dd456bc67c8a9d352  '"${JRE_ARCHIVE}" | shasum -a 256 -c -
download 'https://dev.mysql.com/get/Downloads/MySQL-8.4/mysql-8.4.11-winx64.zip' "${MYSQL_ARCHIVE}"
download 'https://nginx.org/download/nginx-1.28.3.zip' "${NGINX_ARCHIVE}"
download 'https://github.com/tporadowski/redis/releases/download/v5.0.14.1/Redis-x64-5.0.14.1.zip' "${REDIS_ARCHIVE}"
download 'https://mosquitto.org/files/binary/win64/mosquitto-2.1.2-install-windows-x64.exe' "${MOSQUITTO_INSTALLER}"
download 'https://www.python.org/ftp/python/3.11.9/python-3.11.9-amd64.exe' "${PYTHON_INSTALLER}"
download 'https://aka.ms/vs/17/release/vc_redist.x64.exe' "${VC_REDIST}"
download 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip' "${PLATFORM_TOOLS_ARCHIVE}"

echo '[3/8] Downloading AI Windows wheelhouse'
WHEELHOUSE="${CACHE_DIR}/python-wheelhouse-cp311-win_amd64"
mkdir -p "${WHEELHOUSE}"
python3 -m pip download --disable-pip-version-check --only-binary=:all: \
  --platform win_amd64 --implementation cp --python-version 311 --abi cp311 \
  --dest "${WHEELHOUSE}" -r "${SCRIPT_DIR}/requirements-windows.lock.txt"

echo '[4/8] Preparing streaming and storage binaries'
ZLM_CACHE="${CACHE_DIR}/zlmediakit-windows-master-9f90548"
if [[ ! -s "${ZLM_CACHE}/windows/Release/MediaServer.exe" ]]; then
  rm -rf "${ZLM_CACHE}"
  mkdir -p "${ZLM_CACHE}"
  gh run download 33087063372 -R ZLMediaKit/ZLMediaKit -n Windows_master_2026-08-27 -D "${ZLM_CACHE}"
fi
mkdir -p "${CACHE_DIR}/minio"
download 'https://dl.min.io/server/minio/release/windows-amd64/minio.exe' "${CACHE_DIR}/minio/minio.exe"
download 'https://dl.min.io/client/mc/release/windows-amd64/mc.exe' "${CACHE_DIR}/minio/mc.exe"

echo '[5/8] Assembling portable directory'
rsync -a --exclude build-package.sh --exclude database-bootstrap.sql --exclude requirements-windows.lock.txt \
  --exclude 'README-部署说明.md' "${SCRIPT_DIR}/" "${STAGE_DIR}/"
cp "${SCRIPT_DIR}/README-部署说明.md" "${STAGE_DIR}/README-WINDOWS.md"
printf '{\n  "mqttPassword": "%s",\n  "waylineAgentSharedSecret": "%s"\n}\n' \
  "${BUNDLED_MQTT_PASSWORD}" "${BUNDLED_WAYLINE_SHARED_SECRET}" \
  > "${STAGE_DIR}/config/agent-bootstrap-secrets.json"
mkdir -p "${STAGE_DIR}/app/backend" "${STAGE_DIR}/app/frontend" "${STAGE_DIR}/app/ai-service" \
  "${STAGE_DIR}/agent" \
  "${STAGE_DIR}/runtime" "${STAGE_DIR}/runtime/prerequisites" "${STAGE_DIR}/runtime/minio" \
  "${STAGE_DIR}/runtime/python-wheelhouse" "${STAGE_DIR}/runtime/android-platform-tools" "${STAGE_DIR}/database"
cp "${PROJECT_ROOT}/backend/uavfire/target/uavfire-1.10.0.jar" "${STAGE_DIR}/app/backend/"
rsync -a "${PROJECT_ROOT}/frontend/dist/" "${STAGE_DIR}/app/frontend/"
cp "${SCRIPT_DIR}/config/runtime-config.js.template" "${STAGE_DIR}/app/frontend/runtime-config.js"
cp "${PROJECT_ROOT}/rcplus-msdk-agent/app/build/outputs/apk/debug/app-debug.apk" \
  "${STAGE_DIR}/agent/UAVFire-Agent-v0.1.24-trial.apk"
rsync -a --exclude '.venv' --exclude '__pycache__' --exclude 'data' --exclude '.pytest_cache' \
  "${PROJECT_ROOT}/ai-service/app" "${PROJECT_ROOT}/ai-service/weights" "${STAGE_DIR}/app/ai-service/"
cp "${SCRIPT_DIR}/requirements-windows.lock.txt" "${STAGE_DIR}/app/ai-service/"

mkdir -p "${STAGE_DIR}/runtime/jre" "${STAGE_DIR}/runtime/mysql" "${STAGE_DIR}/runtime/nginx" \
  "${STAGE_DIR}/runtime/redis" "${STAGE_DIR}/runtime/mosquitto" "${STAGE_DIR}/runtime/zlmediakit"
unzip -q "${JRE_ARCHIVE}" -d "${WORK_DIR}/jre"
rsync -a "${WORK_DIR}/jre/"*/ "${STAGE_DIR}/runtime/jre/"
unzip -q "${MYSQL_ARCHIVE}" -d "${WORK_DIR}/mysql"
rsync -a "${WORK_DIR}/mysql/"*/ "${STAGE_DIR}/runtime/mysql/"
unzip -q "${NGINX_ARCHIVE}" -d "${WORK_DIR}/nginx"
rsync -a "${WORK_DIR}/nginx/"*/ "${STAGE_DIR}/runtime/nginx/"
unzip -q "${REDIS_ARCHIVE}" -d "${STAGE_DIR}/runtime/redis"
unzip -q "${PLATFORM_TOOLS_ARCHIVE}" -d "${WORK_DIR}/android-platform-tools"
rsync -a "${WORK_DIR}/android-platform-tools/platform-tools/" "${STAGE_DIR}/runtime/android-platform-tools/"
7zz x -y -o"${STAGE_DIR}/runtime/mosquitto" "${MOSQUITTO_INSTALLER}" >/dev/null
cp "${ZLM_CACHE}/windows/Release/MediaServer.exe" "${ZLM_CACHE}/windows/Release/config.ini" \
  "${ZLM_CACHE}/windows/Release/default.pem" "${STAGE_DIR}/runtime/zlmediakit/"
rsync -a "${ZLM_CACHE}/windows/Release/www" "${STAGE_DIR}/runtime/zlmediakit/"
cp "${CACHE_DIR}/minio/minio.exe" "${CACHE_DIR}/minio/mc.exe" "${STAGE_DIR}/runtime/minio/"
cp "${PYTHON_INSTALLER}" "${VC_REDIST}" "${STAGE_DIR}/runtime/prerequisites/"
rsync -a "${WHEELHOUSE}/" "${STAGE_DIR}/runtime/python-wheelhouse/"

echo '[6/8] Generating clean database schema and seed'
TABLES="$(mysql -h127.0.0.1 -P3306 -ug_byzt -pg_byzt -N -B cloud_sample -e \
  "SELECT table_name FROM information_schema.tables WHERE table_schema='cloud_sample' AND table_name NOT REGEXP '(_bak_|^T[0-9]|^migration_task_(backup|before))' AND table_name <> 'migration_table_verification' ORDER BY table_name" 2>/dev/null | tr '\n' ' ')"
cp "${SCRIPT_DIR}/database-bootstrap.sql" "${STAGE_DIR}/database/schema.sql"
mysqldump -h127.0.0.1 -P3306 -ug_byzt -pg_byzt --set-gtid-purged=OFF --no-tablespaces \
  --skip-comments --no-data cloud_sample ${TABLES} >> "${STAGE_DIR}/database/schema.sql"
mysqldump -h127.0.0.1 -P3306 -ug_byzt -pg_byzt --set-gtid-purged=OFF --no-tablespaces \
  --skip-comments --compact --no-create-info --skip-triggers cloud_sample \
  manage_device_dictionary manage_user manage_workspace map_group > "${STAGE_DIR}/database/seed.sql"

echo '[7/8] Writing manifest and checksums'
SOURCE_BRANCH="$(git -C "${PROJECT_ROOT}" branch --show-current)"
SOURCE_COMMIT="$(git -C "${PROJECT_ROOT}" rev-parse HEAD)"
if [[ -n "$(git -C "${PROJECT_ROOT}" status --porcelain)" ]]; then SOURCE_STATE='with-uncommitted-changes'; else SOURCE_STATE='clean'; fi
cat > "${STAGE_DIR}/PACKAGE-MANIFEST.txt" <<MANIFEST
UAVFire Windows x64 offline bundle
Application source branch: ${SOURCE_BRANCH}
Application source commit: ${SOURCE_COMMIT}
Application source state: ${SOURCE_STATE}
Package build time: $(date '+%Y-%m-%d %H:%M:%S %z')
Trial expiration: 2026-10-01 00:00:00 Asia/Shanghai
MSDK Agent: com.yinxin.uavfir 0.1.24-trial (versionCode 25)
MSDK Agent backend/MQTT endpoint: 192.168.1.2:81
MSDK Agent RTMP endpoint: 192.168.1.2:8089
Java runtime: Eclipse Temurin JRE 17.0.20.1+1
MySQL: 8.4.11 LTS
Nginx: 1.28.3 stable
Redis Windows port: 5.0.14.1 (loopback only)
Mosquitto: 2.1.2
Python: 3.11.9
ZLMediaKit: master 9f90548a67df0a9f1425a1c884186bf48eb7be21, official CI artifact 33087063372
MinIO / mc: official Windows latest fetched at package build time
Android platform-tools: official Windows latest fetched at package build time
MANIFEST
(cd "${STAGE_DIR}" && find . -type f ! -name CHECKSUMS.sha256 -print0 | sort -z | xargs -0 shasum -a 256 > CHECKSUMS.sha256)

echo '[8/8] Creating ZIP archive'
ZIP_PATH="${OUTPUT_DIR}/${PACKAGE_NAME}.zip"
rm -f "${ZIP_PATH}"
(cd "${WORK_DIR}" && COPYFILE_DISABLE=1 zip -qr "${ZIP_PATH}" "${PACKAGE_NAME}")
(cd "${OUTPUT_DIR}" && shasum -a 256 "${PACKAGE_NAME}.zip" > "${PACKAGE_NAME}.zip.sha256")
ls -lh "${ZIP_PATH}" "${ZIP_PATH}.sha256"
