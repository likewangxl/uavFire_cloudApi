#!/usr/bin/env bash
# switch-dev-ip.sh —— 一键把仓库里 6 个配置文件的 dev LAN IP 切到当前网卡 IP。
# 用法:
#   ./scripts/switch-dev-ip.sh                # 自动检测 en0/en1 当前 IP
#   ./scripts/switch-dev-ip.sh 192.168.x.x    # 显式指定
#   ./scripts/switch-dev-ip.sh --dry-run      # 只看不改
#   ./scripts/switch-dev-ip.sh --skip-apk     # 改完不重建 APK
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

DRY_RUN=false
SKIP_APK=false
NEW_IP=""

for arg in "$@"; do
  case "$arg" in
    --dry-run)  DRY_RUN=true ;;
    --skip-apk) SKIP_APK=true ;;
    -h|--help)
      sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      if [[ -n "$NEW_IP" ]]; then
        echo "❌ 多余参数: $arg" >&2; exit 1
      fi
      NEW_IP="$arg"
      ;;
  esac
done

if [[ -z "$NEW_IP" ]]; then
  NEW_IP="$(ipconfig getifaddr en0 2>/dev/null || true)"
  [[ -z "$NEW_IP" ]] && NEW_IP="$(ipconfig getifaddr en1 2>/dev/null || true)"
fi

if [[ -z "$NEW_IP" ]]; then
  echo "❌ 无法自动检测 IP（en0/en1 都没有），请显式传入: $0 192.168.x.x" >&2
  exit 1
fi

FILES=(
  "backend/uavfire/src/main/resources/application.yml"
  "deployment/zlmediakit/config/config.ini"
  "deployment/zlmediakit/.env"
  "frontend/env/.env"
  "frontend/src/api/http/config.ts"
  "rcplus-msdk-agent/gradle.properties"
)

for f in "${FILES[@]}"; do
  if [[ ! -f "$f" ]]; then
    echo "❌ 文件不存在: $f" >&2; exit 1
  fi
done

if ! [[ "$NEW_IP" =~ ^[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}$ ]]; then
  echo "❌ 非法 IP 格式: $NEW_IP" >&2
  exit 1
fi

OLD_IP="$(grep -E '^agentMediaHost=' rcplus-msdk-agent/gradle.properties | sed -E 's/^agentMediaHost=//')"
OLD_IPS=()

add_old_ip() {
  local ip="$1"
  [[ -z "$ip" || "$ip" == "127.0.0.1" || "$ip" == "$NEW_IP" ]] && return
  for existing in ${OLD_IPS[@]+"${OLD_IPS[@]}"}; do
    [[ "$existing" == "$ip" ]] && return
  done
  OLD_IPS+=("$ip")
}

add_old_ip "$OLD_IP"

# Collect stale host IPs from the concrete dev settings. Avoid broad file-wide
# matching because config examples and CIDR/range allow-lists also contain IPs.
while IFS= read -r ip; do
  add_old_ip "$ip"
done < <(
  {
    grep -E '^agent(BackendBaseUrl|MediaHost|MqttBrokerUrl)=' rcplus-msdk-agent/gradle.properties || true
    grep -Ev '^[[:space:]]*#' backend/uavfire/src/main/resources/application.yml | sed -E 's/[[:space:]]+#.*$//' || true
    grep -E '^externIP=' deployment/zlmediakit/config/config.ini || true
    grep -E '^ZLM_PUBLIC_HOST=' deployment/zlmediakit/.env || true
    grep -E '^VITE_APP_APIGATEWAY_BACKEND_HOST=' frontend/env/.env || true
    grep -E '^(const backendHost|[[:space:]]+rtmpURL:)' frontend/src/api/http/config.ts | sed -E 's#// Example:.*##' || true
  } | grep -Eo '([0-9]{1,3}\.){3}[0-9]{1,3}' || true
)

echo "🔍 检测到新 IP: $NEW_IP"
if [[ ${#OLD_IPS[@]} -eq 0 ]]; then
  echo "✅ 已在 $NEW_IP 上，无需切换"
  exit 0
fi
echo "📌 仓库当前记录的旧 IP: ${OLD_IPS[*]}"

echo ""
echo "📋 计划改动 (旧 IP ${OLD_IPS[*]} -> 新 IP $NEW_IP):"
for f in "${FILES[@]}"; do
  count=0
  for old_ip in "${OLD_IPS[@]}"; do
    n=$(grep -F -c "$old_ip" "$f" || true)
    count=$((count + n))
  done
  echo "  - $f  ($count 处)"
done

if $DRY_RUN; then
  echo ""
  echo "🧪 dry-run 模式，未做任何修改"
  exit 0
fi

echo ""
echo "✏️  替换中..."
for f in "${FILES[@]}"; do
  for old_ip in "${OLD_IPS[@]}"; do
    sed -i '' "s/${old_ip}/${NEW_IP}/g" "$f"
  done
done

if grep -q '^agentMediaHost=127\.0\.0\.1$' rcplus-msdk-agent/gradle.properties; then
  sed -i '' "s#^agentBackendBaseUrl=.*#agentBackendBaseUrl=http://${NEW_IP}:6789/#" rcplus-msdk-agent/gradle.properties
  sed -i '' "s#^agentMediaHost=.*#agentMediaHost=${NEW_IP}#" rcplus-msdk-agent/gradle.properties
  sed -i '' "s#^agentMqttBrokerUrl=.*#agentMqttBrokerUrl=tcp://${NEW_IP}:1883#" rcplus-msdk-agent/gradle.properties
fi

for old_ip in "${OLD_IPS[@]}"; do
  remnant=$(git grep -F "$old_ip" -- "${FILES[@]}" 2>/dev/null || true)
  if [[ -n "$remnant" ]]; then
    echo "❌ 替换后仍发现旧 IP 残留:" >&2
    echo "$remnant" >&2
    exit 1
  fi
done
echo "✅ 6 个配置文件已切到 $NEW_IP"

if $SKIP_APK; then
  echo ""
  echo "⏭  --skip-apk: 跳过 APK 重建"
  echo "💡 之后真机调试前手动跑: cd rcplus-msdk-agent && ./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk"
else
  echo ""
  echo "📱 检查 adb..."
  if ! command -v adb >/dev/null 2>&1; then
    echo "❌ adb 未安装；IP 已改完，请手动重建 APK: cd rcplus-msdk-agent && ./gradlew assembleDebug" >&2
    exit 1
  fi
  device_count=$(adb devices | awk 'NR>1 && $2=="device"' | wc -l | tr -d ' ')
  if [[ "$device_count" -eq 0 ]]; then
    echo "❌ adb 未发现设备；IP 已改完，RC 连上后手动跑: cd rcplus-msdk-agent && ./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk" >&2
    exit 1
  fi

  echo "📦 编译 APK (assembleDebug，AGP 8.5.2 需 JDK 17)..."
  if [[ ! -d /usr/local/opt/openjdk@17 ]]; then
    echo "❌ 找不到 /usr/local/opt/openjdk@17。请 brew install openjdk@17" >&2
    exit 1
  fi
  (cd rcplus-msdk-agent && JAVA_HOME=/usr/local/opt/openjdk@17 ./gradlew assembleDebug)

  APK="rcplus-msdk-agent/app/build/outputs/apk/debug/app-debug.apk"
  if [[ ! -f "$APK" ]]; then
    echo "❌ 未找到 APK: $APK" >&2; exit 1
  fi

  echo "📲 adb install -r ..."
  adb install -r "$APK"
  echo "✅ APK 已重装"

  # adb install 不会自动 launch，必须显式拉起进程，否则 agent 不在跑、RTMP 推不到 ZLM
  echo "🚀 启动 agent (com.yinxin.uavfir)..."
  adb shell monkey -p com.yinxin.uavfir 1 >/dev/null 2>&1 && echo "✅ agent 已 launch" || echo "⚠️ monkey 启动失败，请手动从 RC 桌面打开 uavfir 图标"
fi

echo ""
echo "🔄 还需手动重启的服务（如已在跑）："
echo "   - backend:   JAVA_HOME=/usr/local/opt/openjdk@11 mvn spring-boot:run -pl uavfire"
echo "   - frontend:  cd frontend && npm run serve"
echo "   - ZLM:       docker restart \$(docker ps -qf name=zlm)   # 仅当 ICE externIP 走的是 container env"
echo ""
echo "🎉 IP 切换完成: ${OLD_IPS[*]} -> $NEW_IP"
