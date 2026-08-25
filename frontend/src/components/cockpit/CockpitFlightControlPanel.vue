<template>
  <section class="cockpit-flight-panel" :class="{ collapsed: !panelExpanded }">
    <button class="flight-drawer-tab" type="button" @click="panelExpanded = !panelExpanded">
      <span class="tab-title">飞行面板</span>
      <span class="tab-aircraft">{{ target?.callsign || '未选择监测飞机' }}</span>
      <span class="tab-status" :class="{ active: isControllable }">
        {{ isControllable ? 'MSDK 指令可用' : '等待在线' }}
      </span>
      <span class="tab-caret">{{ panelExpanded ? '收起' : '展开' }}</span>
    </button>

    <div v-show="panelExpanded" class="flight-console">
      <div class="console-toolbar">
        <div class="mode-toggles">
          <button
            v-for="mode in viewModes"
            :key="mode.key"
            class="mode-btn"
            :class="{ active: mode.enabled }"
            :disabled="mode.disabled"
            type="button"
            @click="toggleViewMode(mode.key)"
          >
            {{ mode.label }}
          </button>
        </div>
      </div>

      <div class="console-body">
        <div class="console-section movement-section">
          <div class="joystick-shell">
            <button class="axis wing left-wing" type="button" :disabled="!canVirtualStick" @click="runVirtualStick('west')">左旋</button>
            <button class="axis wing right-wing" type="button" :disabled="!canVirtualStick" @click="runVirtualStick('east')">右旋</button>
            <button class="axis wing down-wing" type="button" :disabled="!canVirtualStick" @click="runVirtualStick('down')">下降</button>
            <button class="axis wing up-wing" type="button" :disabled="!canVirtualStick" @click="runVirtualStick('up')">上升</button>
            <div class="joystick-ring">
              <button class="axis north" type="button" :disabled="!canVirtualStick" @click="runVirtualStick('north')">前</button>
              <button class="axis west" type="button" :disabled="!canVirtualStick" @click="runVirtualStick('west')">左</button>
              <button class="axis east" type="button" :disabled="!canVirtualStick" @click="runVirtualStick('east')">右</button>
              <button class="axis south" type="button" :disabled="!canVirtualStick" @click="runVirtualStick('south')">后</button>
              <button class="axis center" type="button" :disabled="!canCommand('hover')" @click="runCommand('hover', 'hover')">H</button>
            </div>
          </div>
          <div class="distance-control">
            <span>位移距离</span>
            <input
              v-model.number="distanceMeters"
              type="number"
              :min="AXIS_DISTANCE_MIN_METERS"
              :max="AXIS_DISTANCE_MAX_METERS"
              step="1"
            >
            <span>m</span>
          </div>
        </div>

        <div class="console-section gimbal-section" title="云台控制：上下调整俯仰，左右调整偏航，H 回中">
          <div class="gimbal-pad">
            <button class="gimbal-axis up" type="button" title="云台上仰" aria-label="云台上仰" :disabled="!canGimbalRotate" @click="runGimbalRotate('up')">⌃</button>
            <button class="gimbal-axis left" type="button" title="云台左转" aria-label="云台左转" :disabled="!canGimbalRotate" @click="runGimbalRotate('left')">‹</button>
            <button class="gimbal-axis center" type="button" title="云台回中" aria-label="云台回中" :disabled="!canGimbalReset" @click="runCommand('gimbal_reset', 'gimbalReset')">H</button>
            <button class="gimbal-axis right" type="button" title="云台右转" aria-label="云台右转" :disabled="!canGimbalRotate" @click="runGimbalRotate('right')">›</button>
            <button class="gimbal-axis down" type="button" title="云台下俯" aria-label="云台下俯" :disabled="!canGimbalRotate" @click="runGimbalRotate('down')">⌄</button>
          </div>
        </div>

        <div class="console-section payload-section">
          <div class="payload-row lens-row">
            <button class="command-btn primary" type="button" :disabled="!canCameraStreamSource" @click="runCameraStreamSource('visible')">广角(1)</button>
            <button class="command-btn" type="button" :disabled="!canCameraStreamSource" @click="runCameraStreamSource('zoom')">变焦(2)</button>
            <button class="command-btn" type="button" :disabled="!canCameraStreamSource" @click="runCameraStreamSource('thermal')">红外(3)</button>
          </div>
          <div class="payload-row cam-actions">
            <button class="command-btn" type="button" :disabled="!canCommand('cameraPhoto')" @click="runCommand('camera_start_photo', 'cameraPhoto')">拍照(F)</button>
            <button class="command-btn" type="button" :disabled="!canCommand('cameraRecord')" @click="runRecordToggle">{{ recording ? '停录(R)' : '录像(R)' }}</button>
            <div class="zoom-stepper">
              <button class="command-btn zoom-btn" type="button" title="缩小焦距倍率" :disabled="!canCommand('cameraZoom') || zoomRatio <= ZOOM_MIN_RATIO" @click="runZoomOut">－</button>
              <span class="zoom-readout">{{ zoomRatio }}x</span>
              <button class="command-btn zoom-btn" type="button" title="放大焦距倍率" :disabled="!canCommand('cameraZoom') || zoomRatio >= ZOOM_MAX_RATIO" @click="runZoomIn">＋</button>
            </div>
          </div>
          <div class="payload-row quality-row">
            <button
              v-for="quality in qualityModes"
              :key="quality.key"
              class="command-btn"
              :class="{ active: activeQuality === quality.key }"
              type="button"
              disabled
              title="画质切换功能后续开放"
            >
              {{ quality.label }}
            </button>
          </div>
          <div class="payload-row telemetry-mini">
            <span>电量 <strong>{{ batteryText }}</strong></span>
            <span>高度 <strong>{{ metric(osd?.height, 'm') }}</strong></span>
            <span>返航点 <strong>{{ metric(osd?.home_distance, 'm') }}</strong></span>
            <span>风速 <strong>{{ metric(osd?.wind_speed, 'm/s') }}</strong></span>
          </div>
        </div>

        <div class="console-section command-section">
          <button class="command-btn danger strong" type="button" :disabled="!canEmergencyStop" @click="runDangerCommand('emergency_stop', 'stop', '确认发送急停指令？')">急停</button>
          <button class="command-btn primary wide" type="button" :disabled="!canCommand('cancelReturnHome')" @click="runDangerCommand('cancel_return_home', 'cancelReturnHome', '确认取消返航？')">取消返航</button>
          <div class="command-row">
            <button class="command-btn" type="button" :disabled="!canCommand('takeoff')" @click="runCommand('takeoff', 'takeoff')">起飞</button>
            <button class="command-btn" type="button" :disabled="!canCommand('land')" @click="runCommand('land', 'land')">降落</button>
          </div>
          <div class="command-row">
            <button class="command-btn" type="button" :disabled="!canCommand('hover')" @click="runCommand('hover', 'hover')">悬停</button>
            <button class="command-btn" type="button" :disabled="!canReturnHome" @click="runDangerCommand('return_home', 'returnHome', '确认让飞机执行返航？')">返航</button>
          </div>
          <button class="command-btn" type="button" :disabled="!canCommand('flyToPoint')" @click="runCommand('stop_fly_to_point', 'flyStop')">停止飞行</button>
        </div>
      </div>

      <div class="target-drawer-row">
        <span class="target-label" :title="controlTip">飞向目标点</span>
        <input v-model.number="flyToPoint.latitude" type="number" step="0.00001" placeholder="纬度">
        <input v-model.number="flyToPoint.longitude" type="number" step="0.00001" placeholder="经度">
        <input v-model.number="flyToPoint.height" type="number" :min="MIN_AIRBORNE_HEIGHT_M" step="1" placeholder="高度 m">
        <input v-model.number="flyToPoint.speed" type="number" min="2" max="15" step="1" placeholder="速度">
        <button class="command-btn primary" type="button" :disabled="!canFlyToPoint" @click="runFlyToPoint">发送目标点</button>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { Modal, notification } from 'ant-design-vue'
import { sendMsdkCommand, getMsdkCommand } from '/@/api/msdk-device'
import type { MsdkDeviceState } from '/@/api/msdk-device'
import { EModeCode } from '/@/types/device'
import type { CockpitStreamTarget } from './CockpitAircraftStreamSelector.vue'
import {
  AXIS_DISTANCE_MIN_METERS,
  AXIS_DISTANCE_MAX_METERS,
  getVerticalCommandDurationMs
} from '/@/pages/page-web/projects/axis-displacement-policy.mjs'

type Direction = 'up' | 'down' | 'west' | 'east' | 'north' | 'south'
type GimbalDirection = 'up' | 'down' | 'left' | 'right'
type ViewModeKey = 'tracking' | 'arLabel' | 'gimbalCenter' | 'stealth' | 'nightLight' | 'laserFillLight' | 'referenceLine' | 'measureArea' | 'flv' | 'webrtc' | 'fullScreen'
type BackendViewModeCommand = {
  command: string
  action: string
  capability: string
  buildParams?: (enabled: boolean) => Record<string, unknown>
}

const MIN_AIRBORNE_HEIGHT_M = 3
const ZOOM_MIN_RATIO = 1
const ZOOM_MAX_RATIO = 200
const ZOOM_STEP = 1
const COMMAND_RESULT_POLL_MS = 600
const COMMAND_RESULT_TIMEOUT_MS = 12000
const TERMINAL_COMMAND_STATUSES = new Set(['APPLIED', 'FAILED', 'IGNORED', 'EXPIRED', 'UNKNOWN'])
// MSDK 不提供官方接口、需地面站自绘的功能：点击提示后续开放
const COMING_SOON_MODES: ViewModeKey[] = ['tracking', 'arLabel', 'referenceLine', 'measureArea']
// 纯前端装饰、暂无功能：直接置灰
const DECORATIVE_MODES: ViewModeKey[] = ['stealth']

const props = defineProps<{
  target?: CockpitStreamTarget | null
  msdkDevice?: MsdkDeviceState | null
  osd?: any | null
}>()

const actionLoading = ref('')
const distanceMeters = ref(5)
const panelExpanded = ref(false)
const recording = ref(false)
const zoomRatio = ref(ZOOM_MIN_RATIO)
const activeQuality = ref('standard')
const viewState = reactive<Record<ViewModeKey, boolean>>({
  tracking: false,
  arLabel: false,
  gimbalCenter: false,
  stealth: false,
  nightLight: false,
  laserFillLight: false,
  referenceLine: false,
  measureArea: false,
  flv: false,
  webrtc: true,
  fullScreen: false
})
const flyToPoint = reactive({
  latitude: null as number | null,
  longitude: null as number | null,
  height: null as number | null,
  speed: 5
})

const viewModeLabels: Array<{ key: ViewModeKey, label: string }> = [
  { key: 'tracking', label: '视频跟踪' },
  { key: 'arLabel', label: 'AR标签' },
  { key: 'gimbalCenter', label: '云台居中' },
  { key: 'stealth', label: '隐藏模式' },
  { key: 'nightLight', label: '夜航灯' },
  { key: 'laserFillLight', label: '补光灯' },
  { key: 'referenceLine', label: '参考线' },
  { key: 'measureArea', label: '测面' },
  { key: 'flv', label: 'FLV' },
  { key: 'webrtc', label: 'WEBRTC' }
]
const backendViewModeCommands: Partial<Record<ViewModeKey, BackendViewModeCommand>> = {
  gimbalCenter: {
    command: 'gimbal_reset',
    action: 'gimbalReset',
    capability: 'gimbalReset'
  },
  nightLight: {
    command: 'navigation_light',
    action: 'navigationLight',
    capability: 'navigationLight',
    buildParams: enabled => ({ enabled })
  },
  laserFillLight: {
    command: 'laser_fill_light',
    action: 'laserFillLight',
    capability: 'laserFillLight',
    buildParams: enabled => ({ enabled })
  }
}
const qualityModes = [
  { key: 'adaptive', label: '自适应' },
  { key: 'smooth', label: '流畅' },
  { key: 'standard', label: '标清' },
  { key: 'hd', label: '高清' },
  { key: 'uhd', label: '超清' }
]

const aircraftSn = computed(() => props.target?.deviceSn || props.msdkDevice?.aircraftSn || '')
const isControllable = computed(() => Boolean(
  aircraftSn.value &&
  props.msdkDevice?.online &&
  props.osd &&
  props.osd.mode_code !== EModeCode.Disconnected
))
const isAirborne = computed(() => {
  const height = Number(props.osd?.height)
  return isControllable.value && Number.isFinite(height) && height >= MIN_AIRBORNE_HEIGHT_M
})
const controlTip = computed(() => {
  if (!props.target) return '请先选择火情监测飞机。'
  if (!isControllable.value) return '等待飞机在线和 OSD 遥测后可发送 MSDK 指令。'
  return '等待飞机在线和 OSD 遥测后可进入控制；不支持的负载能力会自动禁用。'
})
const batteryText = computed(() => {
  const percent = props.osd?.battery?.capacity_percent
  return percent === undefined || percent === null || percent === '' ? '--' : `${percent}%`
})
const viewModes = computed(() => viewModeLabels.map(mode => ({
  ...mode,
  enabled: viewState[mode.key],
  disabled: Boolean(
    DECORATIVE_MODES.includes(mode.key) ||
    (backendViewModeCommands[mode.key] && !canCommand(backendViewModeCommands[mode.key]!.capability))
  )
})))
const canVirtualStick = computed(() => canCommand('virtualStick'))
const canEmergencyStop = computed(() => canCommand('emergencyStop'))
const canReturnHome = computed(() => canCommand('returnHome') && isAirborne.value)
const canFlyToPoint = computed(() => canCommand('flyToPoint') && isAirborne.value)
const canGimbalRotate = computed(() => canCommand('gimbalRotate'))
const canGimbalReset = computed(() => canCommand('gimbalReset'))
const canCameraStreamSource = computed(() => canCommand('cameraStreamSource'))

watch(aircraftSn, () => {
  flyToPoint.latitude = numberOrNull(props.osd?.latitude)
  flyToPoint.longitude = numberOrNull(props.osd?.longitude)
  flyToPoint.height = numberOrNull(props.osd?.height)
  flyToPoint.speed = 5
  recording.value = false
  zoomRatio.value = ZOOM_MIN_RATIO
})

function numberOrNull (value: unknown) {
  const n = Number(value)
  return Number.isFinite(n) ? n : null
}

function metric (value: unknown, unit: string) {
  if (value === undefined || value === null || value === '') return '--'
  return `${value} ${unit}`
}

function hasCapability (capability: string) {
  const caps = props.msdkDevice?.capabilities || {}
  const snake = capability.replace(/[A-Z]/g, letter => `_${letter.toLowerCase()}`)
  return caps[capability] === true || caps[snake] === true
}

function canCommand (capability: string) {
  return isControllable.value && hasCapability(capability)
}

// 元素全屏时，弹窗/通知必须挂到全屏元素内部，否则会被全屏画面盖住、看不到也点不到。
function popupContainer (): HTMLElement {
  return (document.fullscreenElement as HTMLElement) || document.body
}

function notifySuccess (message: string) {
  notification.success({ message, duration: 2.5, getContainer: popupContainer })
}

function notifyWarning (message: string) {
  notification.warning({ message, duration: 3, getContainer: popupContainer })
}

function notifyError (message: string) {
  notification.error({ message, duration: 4, getContainer: popupContainer })
}

async function toggleViewMode (key: ViewModeKey) {
  if (DECORATIVE_MODES.includes(key)) {
    return
  }
  if (COMING_SOON_MODES.includes(key)) {
    notifyWarning('该功能后续开放，敬请期待。')
    return
  }
  const nextEnabled = !viewState[key]
  const backendCommand = backendViewModeCommands[key]
  if (!backendCommand) {
    viewState[key] = nextEnabled
    return
  }
  if (!canCommand(backendCommand.capability)) {
    notifyWarning('当前设备/负载不支持该能力。')
    return
  }
  const sent = await runBackendViewMode(key, nextEnabled)
  if (sent) {
    viewState[key] = nextEnabled
  }
}

async function runBackendViewMode (key: ViewModeKey, enabled: boolean) {
  const backendCommand = backendViewModeCommands[key]
  if (!backendCommand) return false
  return await runCommand(
    backendCommand.command,
    backendCommand.action,
    backendCommand.buildParams ? backendCommand.buildParams(enabled) : {}
  )
}

function delay (ms: number) {
  return new Promise(resolve => setTimeout(resolve, ms))
}

// 轮询后端指令记录，等待 agent 回执真实执行结果（APPLIED/FAILED/...），而不是只确认下发。
async function awaitCommandResult (sn: string, commandId: string): Promise<{ status: string, message?: string } | null> {
  const deadline = Date.now() + COMMAND_RESULT_TIMEOUT_MS
  while (Date.now() < deadline) {
    await delay(COMMAND_RESULT_POLL_MS)
    try {
      const res = await getMsdkCommand(sn, commandId)
      const cmd = res?.data
      const status = String(cmd?.status || '').toUpperCase()
      if (cmd && TERMINAL_COMMAND_STATUSES.has(status)) {
        return { status, message: cmd.message }
      }
    } catch {
      // 单次查询失败时继续轮询，由整体超时兜底
    }
  }
  return null
}

async function withAircraftAction (action: string, task: () => Promise<any>, successText: string): Promise<boolean> {
  if (!aircraftSn.value || !isControllable.value) {
    notifyWarning('飞机离线，或遥测数据尚未就绪。')
    return false
  }
  const sn = aircraftSn.value
  actionLoading.value = action
  try {
    const response = await task()
    if (response?.code !== 0) {
      notifyWarning(response?.message || '指令下发失败。')
      return false
    }
    const commandId = response?.data?.command_id || response?.data?.commandId
    if (!commandId) {
      // 后端未返回指令编号，退回到仅确认下发的旧行为
      notifySuccess(successText)
      return true
    }
    const result = await awaitCommandResult(sn, commandId)
    if (!result) {
      notifyWarning(`${successText.replace(/已发送。?$/, '')}已下发，但未在 ${Math.round(COMMAND_RESULT_TIMEOUT_MS / 1000)} 秒内收到飞机回执，请确认飞机状态。`)
      return false
    }
    if (result.status === 'APPLIED') {
      notifySuccess(successText)
      return true
    }
    notifyError(result.message ? `飞机未执行该指令：${result.message}` : '飞机未执行该指令。')
    return false
  } catch (error: any) {
    notifyError(error?.message || '指令执行失败。')
    return false
  } finally {
    actionLoading.value = ''
  }
}

async function runCommand (command: string, action: string, params: Record<string, unknown> = {}): Promise<boolean> {
  const capability = commandCapability(command)
  if (capability && !canCommand(capability)) {
    notifyWarning('当前设备/负载不支持该能力。')
    return false
  }
  return await withAircraftAction(action, () => sendMsdkCommand(aircraftSn.value, command, params), `${commandLabel(command)}指令已发送。`)
}

function runDangerCommand (command: string, action: string, title: string) {
  Modal.confirm({
    title,
    content: props.target?.callsign || aircraftSn.value,
    okText: '确认发送',
    cancelText: '取消',
    getContainer: popupContainer,
    onOk: () => runCommand(command, action)
  })
}

async function runVirtualStick (direction: Direction) {
  if (!canVirtualStick.value) {
    notifyWarning('当前设备/负载不支持该能力。')
    return
  }
  const meters = Number(distanceMeters.value)
  if (!Number.isFinite(meters) || meters < AXIS_DISTANCE_MIN_METERS || meters > AXIS_DISTANCE_MAX_METERS) {
    notifyWarning(`距离必须在 ${AXIS_DISTANCE_MIN_METERS}-${AXIS_DISTANCE_MAX_METERS} 米之间。`)
    return
  }
  const keyMap: Record<Direction, string> = {
    up: 'ArrowUp',
    down: 'ArrowDown',
    west: 'KeyA',
    east: 'KeyD',
    north: 'KeyW',
    south: 'KeyS'
  }
  await withAircraftAction(
    direction,
    () => sendMsdkCommand(aircraftSn.value, 'virtual_stick', {
      key: keyMap[direction],
      duration_ms: getVerticalCommandDurationMs(meters)
    }),
    `${directionLabel(direction)} ${meters} 米指令已发送。`
  )
}

function directionLabel (direction: Direction) {
  const labels: Record<Direction, string> = {
    up: '上升',
    down: '下降',
    west: '向左',
    east: '向右',
    north: '向前',
    south: '向后'
  }
  return labels[direction]
}

function runGimbalRotate (direction: GimbalDirection) {
  const map: Record<GimbalDirection, Record<string, number>> = {
    up: { pitch: 8 },
    down: { pitch: -8 },
    left: { yaw: -8 },
    right: { yaw: 8 }
  }
  runCommand('gimbal_rotate', 'gimbalRotate', map[direction])
}

function runCameraStreamSource (source: 'visible' | 'zoom' | 'thermal') {
  runCommand('camera_stream_source', 'cameraStreamSource', { source })
}

async function runCameraZoom (ratio: number) {
  const clamped = Math.min(ZOOM_MAX_RATIO, Math.max(ZOOM_MIN_RATIO, Math.round(ratio)))
  const sent = await runCommand('camera_zoom', 'cameraZoom', { ratio: clamped })
  if (sent) {
    zoomRatio.value = clamped
  }
}

function runZoomIn () {
  runCameraZoom(zoomRatio.value + ZOOM_STEP)
}

function runZoomOut () {
  runCameraZoom(zoomRatio.value - ZOOM_STEP)
}

async function runRecordToggle () {
  const nextCommand = recording.value ? 'camera_stop_record' : 'camera_start_record'
  const sent = await runCommand(nextCommand, 'cameraRecord')
  if (sent) {
    recording.value = !recording.value
  }
}

function commandCapability (command: string) {
  const map: Record<string, string> = {
    takeoff: 'takeoff',
    land: 'land',
    hover: 'hover',
    virtual_stick: 'virtualStick',
    emergency_stop: 'emergencyStop',
    return_home: 'returnHome',
    cancel_return_home: 'cancelReturnHome',
    stop_fly_to_point: 'flyToPoint',
    fly_to_point: 'flyToPoint',
    gimbal_reset: 'gimbalReset',
    gimbal_rotate: 'gimbalRotate',
    camera_start_photo: 'cameraPhoto',
    camera_start_record: 'cameraRecord',
    camera_stop_record: 'cameraRecord',
    camera_stream_source: 'cameraStreamSource',
    camera_zoom: 'cameraZoom',
    night_scene: 'nightScene',
    navigation_light: 'navigationLight',
    laser_fill_light: 'laserFillLight'
  }
  return map[command]
}

function commandLabel (command: string) {
  const map: Record<string, string> = {
    takeoff: '起飞',
    land: '降落',
    hover: '悬停',
    virtual_stick: '飞行控制',
    emergency_stop: '急停',
    return_home: '返航',
    cancel_return_home: '取消返航',
    stop_fly_to_point: '停止飞向目标点',
    fly_to_point: '飞向目标点',
    gimbal_reset: '云台回中',
    gimbal_rotate: '云台旋转',
    camera_start_photo: '拍照',
    camera_start_record: '开始录像',
    camera_stop_record: '停止录像',
    camera_stream_source: '镜头切换',
    camera_zoom: '变焦',
    night_scene: '夜景模式',
    navigation_light: '航行灯',
    laser_fill_light: '补光灯'
  }
  return map[command] || '控制'
}

async function runFlyToPoint () {
  if (!canFlyToPoint.value) {
    notifyWarning(`飞机需已起飞且高度不低于 ${MIN_AIRBORNE_HEIGHT_M} 米，并已进入 MSDK 控制通道。`)
    return
  }
  const { latitude, longitude, height, speed } = flyToPoint
  if (latitude == null || longitude == null || height == null) {
    notifyWarning('纬度、经度和高度均为必填项。')
    return
  }
  if (!Number.isFinite(latitude) || latitude < -90 || latitude > 90) {
    notifyWarning('纬度超出范围 [-90, 90]。')
    return
  }
  if (!Number.isFinite(longitude) || longitude < -180 || longitude > 180) {
    notifyWarning('经度超出范围 [-180, 180]。')
    return
  }
  if (!Number.isFinite(height) || height < MIN_AIRBORNE_HEIGHT_M) {
    notifyWarning(`目标高度必须不低于 ${MIN_AIRBORNE_HEIGHT_M} 米。`)
    return
  }
  const safeSpeed = Number.isFinite(speed) && speed > 0 ? Math.min(speed, 15) : 5
  await withAircraftAction(
    'flyManual',
    () => sendMsdkCommand(aircraftSn.value, 'fly_to_point', {
      speed: safeSpeed,
      latitude,
      longitude,
      height
    }),
    '手动目标点指令已发送。'
  )
}
</script>

<style scoped lang="scss">
.cockpit-flight-panel {
  position: absolute;
  right: 18px;
  bottom: 14px;
  left: 18px;
  z-index: 8;
  min-width: 0;
  color: #d8f3ff;
  pointer-events: none;
}

.cockpit-flight-panel > * {
  pointer-events: auto;
}

.flight-drawer-tab {
  display: grid;
  width: 100%;
  min-width: 0;
  grid-template-columns: auto minmax(0, 1fr) auto auto;
  align-items: center;
  gap: 12px;
  height: 34px;
  padding: 0 12px;
  color: #dff7ff;
  border: 2px solid rgba(95, 165, 255, 0.48);
  border-bottom-width: 1px;
  border-radius: 12px 12px 0 0;
  background:
    linear-gradient(180deg, rgba(10, 31, 52, 0.54), rgba(5, 18, 31, 0.38)),
    radial-gradient(circle at 12% 0%, rgba(91, 213, 255, 0.14), transparent 36%);
  box-shadow: inset 0 1px 0 rgba(210, 248, 255, 0.12), 0 10px 26px rgba(0, 0, 0, 0.18);
  backdrop-filter: blur(6px);
  cursor: pointer;
}

.flight-drawer-tab:focus-visible,
.mode-btn:focus-visible,
.command-btn:focus-visible {
  outline: 1px solid rgba(74, 204, 255, 0.76);
  outline-offset: -2px;
}

.collapsed .flight-drawer-tab {
  border-radius: 12px;
  border-bottom-width: 2px;
}

.tab-title {
  position: relative;
  padding-left: 12px;
  color: #effbff;
  font-size: 13px;
  font-weight: 800;
}

.tab-title::before {
  position: absolute;
  left: 0;
  top: 3px;
  width: 3px;
  height: 14px;
  border-radius: 999px;
  background: #35b8ff;
  content: '';
}

.tab-aircraft {
  min-width: 0;
  color: #8fc2dd;
  font-size: 12px;
  overflow: hidden;
  text-align: center;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tab-status,
.tab-caret {
  padding: 4px 10px;
  border: 1px solid rgba(132, 213, 255, 0.28);
  border-radius: 999px;
  color: #91d7f3;
  font-size: 12px;
}

.tab-status.active {
  border-color: rgba(77, 255, 170, 0.45);
  color: #74ffb6;
}

.flight-console {
  max-height: min(248px, calc(100vh - 180px));
  min-width: 0;
  overflow: hidden;
  padding: 8px 10px 10px;
  border: 2px solid rgba(95, 165, 255, 0.48);
  border-top: 0;
  border-radius: 0 0 12px 12px;
  background:
    linear-gradient(90deg, rgba(8, 35, 58, 0.38), rgba(4, 18, 31, 0.3) 46%, rgba(8, 33, 56, 0.38)),
    radial-gradient(circle at 15% 50%, rgba(47, 184, 255, 0.12), transparent 30%);
  box-shadow: inset 0 0 18px rgba(95, 165, 255, 0.12), 0 18px 44px rgba(0, 0, 0, 0.2);
  backdrop-filter: blur(8px);
}

.console-toolbar {
  display: grid;
  min-width: 0;
  grid-template-columns: minmax(0, 1fr);
  align-items: center;
  gap: 8px;
  height: 28px;
  margin-bottom: 8px;
}

.mode-toggles,
.payload-row,
.command-row,
.target-drawer-row,
.telemetry-mini {
  display: grid;
  min-width: 0;
  gap: 8px;
}

.mode-toggles {
  display: flex;
  gap: 6px;
  overflow-x: auto;
  overflow-y: hidden;
  scrollbar-width: none;
}

.mode-toggles::-webkit-scrollbar {
  display: none;
}

.mode-btn,
.command-btn {
  min-width: 0;
  height: 26px;
  padding: 0 8px;
  color: #dff4ff;
  border: 1px solid rgba(95, 165, 255, 0.42);
  border-radius: 4px;
  background:
    linear-gradient(180deg, rgba(24, 83, 124, 0.62), rgba(8, 40, 70, 0.58));
  box-shadow: inset 0 0 12px rgba(70, 195, 255, 0.16);
  font-size: 11px;
  font-weight: 700;
  cursor: pointer;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mode-btn {
  flex: 0 0 auto;
  min-width: 62px;
}

.mode-btn.active,
.command-btn.active,
.command-btn.primary {
  color: #f7fff9;
  border-color: rgba(74, 204, 255, 0.72);
  background: linear-gradient(180deg, rgba(27, 159, 231, 0.7), rgba(16, 102, 178, 0.72));
}

.command-btn.danger {
  color: #fff7f2;
  border-color: rgba(255, 111, 89, 0.72);
  background: linear-gradient(180deg, rgba(180, 55, 50, 0.74), rgba(105, 29, 28, 0.68));
}

.command-btn.strong {
  border-color: rgba(255, 78, 53, 0.82);
  background: linear-gradient(180deg, rgba(210, 63, 45, 0.76), rgba(126, 31, 28, 0.72));
}

.command-btn.wide,
.command-section > .command-btn {
  width: 100%;
}

.command-btn:disabled,
.mode-btn:disabled,
.axis:disabled,
.gimbal-axis:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}

.console-body {
  display: grid;
  min-width: 0;
  grid-template-columns: minmax(190px, 1fr) 118px minmax(270px, 1.28fr) minmax(142px, 0.72fr);
  align-items: center;
  gap: 10px;
}

.console-section {
  position: relative;
  min-width: 0;
}

.console-section + .console-section {
  padding-left: 10px;
  border-left: 1px solid rgba(115, 204, 255, 0.28);
}

.movement-section {
  display: grid;
  grid-template-columns: minmax(142px, 1fr) 70px;
  align-items: center;
  gap: 8px;
}

.joystick-shell {
  position: relative;
  width: 166px;
  max-width: 100%;
  height: 118px;
  margin: 0 auto;
}

.joystick-ring,
.gimbal-pad {
  position: relative;
  width: 104px;
  height: 104px;
  margin: 0 auto;
  border: 2px solid rgba(61, 185, 255, 0.78);
  border-radius: 999px;
  background:
    radial-gradient(circle at center, rgba(48, 186, 255, 0.24) 0 20%, transparent 21%),
    conic-gradient(from 45deg, rgba(36, 141, 205, 0.86), rgba(7, 52, 91, 0.86), rgba(36, 141, 205, 0.86));
  box-shadow: inset 0 0 22px rgba(91, 213, 255, 0.28), 0 0 22px rgba(35, 157, 242, 0.28);
}

.joystick-ring {
  position: absolute;
  left: 50%;
  top: 50%;
  transform: translate(-50%, -50%);
}

.axis,
.gimbal-axis {
  position: absolute;
  display: grid;
  place-items: center;
  color: #d8f6ff;
  border: 0;
  border-radius: 10px;
  background: transparent;
  font-weight: 800;
  cursor: pointer;
  touch-action: manipulation;
  -webkit-tap-highlight-color: transparent;
  transition: background 0.12s ease, box-shadow 0.12s ease, filter 0.12s ease;
}

.axis.north,
.axis.south,
.axis.west,
.axis.east,
.gimbal-axis.up,
.gimbal-axis.down,
.gimbal-axis.left,
.gimbal-axis.right {
  width: 36px;
  height: 36px;
  font-size: 15px;
}

.axis:not(:disabled):hover,
.gimbal-axis:not(.center):not(:disabled):hover {
  background: rgba(73, 198, 255, 0.16);
}

.axis:not(.center):not(:disabled):active,
.gimbal-axis:not(.center):not(:disabled):active {
  background: rgba(73, 198, 255, 0.34);
  box-shadow: inset 0 0 0 1px rgba(125, 222, 255, 0.7), 0 0 12px rgba(73, 198, 255, 0.4);
}

.axis.center:not(:disabled):active,
.gimbal-axis.center:not(:disabled):active {
  filter: brightness(1.3);
  box-shadow: 0 0 14px rgba(73, 198, 255, 0.55);
}

.axis.wing:not(:disabled):active {
  filter: brightness(1.35);
}

.axis.north { top: 2px; left: 50%; transform: translateX(-50%); }
.axis.south { bottom: 2px; left: 50%; transform: translateX(-50%); }
.axis.west { left: 2px; top: 50%; transform: translateY(-50%); }
.axis.east { right: 2px; top: 50%; transform: translateY(-50%); }

.axis.center,
.gimbal-axis.center {
  left: 50%;
  top: 50%;
  display: grid;
  width: 32px;
  height: 32px;
  place-items: center;
  transform: translate(-50%, -50%);
  border: 2px solid rgba(73, 198, 255, 0.86);
  border-radius: 999px;
  background: radial-gradient(circle, rgba(14, 109, 166, 0.96), rgba(9, 53, 92, 0.96));
}

.axis.wing {
  position: absolute;
  width: 40px;
  height: 48px;
  border: 1px solid rgba(36, 158, 232, 0.42);
  background: linear-gradient(180deg, rgba(16, 91, 143, 0.46), rgba(8, 48, 86, 0.34));
  clip-path: polygon(0 16%, 100% 0, 100% 100%, 0 84%);
}

.left-wing { left: 0; top: 23px; }
.right-wing {
  right: 0;
  top: 23px;
  clip-path: polygon(0 0, 100% 16%, 100% 84%, 0 100%);
}
.down-wing { left: 0; bottom: 0; }
.up-wing { right: 0; bottom: 0; }

.gimbal-axis.up { top: 2px; left: 50%; transform: translateX(-50%); }
.gimbal-axis.down { bottom: 2px; left: 50%; transform: translateX(-50%); }
.gimbal-axis.left { left: 2px; top: 50%; transform: translateY(-50%); }
.gimbal-axis.right { right: 2px; top: 50%; transform: translateY(-50%); }

.distance-control {
  display: grid;
  gap: 4px;
  color: #9ed5ef;
  font-size: 11px;
}

.distance-control input,
.target-drawer-row input {
  width: 100%;
  min-width: 0;
  height: 26px;
  padding: 0 8px;
  color: #effcff;
  border: 1px solid rgba(95, 165, 255, 0.44);
  border-radius: 4px;
  background: rgba(4, 20, 35, 0.48);
}

.payload-section {
  display: grid;
  gap: 6px;
}

.payload-row {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.payload-row.cam-actions {
  grid-template-columns: repeat(3, minmax(0, 1fr));
  align-items: center;
}

.zoom-stepper {
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  align-items: center;
  gap: 4px;
}

.zoom-btn {
  min-width: 0;
  font-size: 14px;
}

.zoom-readout {
  min-width: 28px;
  color: #eafaff;
  font-size: 11px;
  font-weight: 800;
  text-align: center;
}

.payload-row:nth-of-type(3) {
  grid-template-columns: repeat(5, minmax(0, 1fr));
}

.telemetry-mini {
  grid-template-columns: repeat(4, minmax(0, 1fr));
  color: #9dd6ef;
  font-size: 11px;
}

.telemetry-mini span {
  min-width: 0;
  padding: 4px 6px;
  border: 1px solid rgba(95, 165, 255, 0.22);
  border-radius: 4px;
  background: rgba(2, 18, 32, 0.32);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.telemetry-mini strong {
  color: #f4fdff;
}

.command-section {
  display: grid;
  gap: 6px;
}

.command-row {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.target-drawer-row {
  grid-template-columns: 78px repeat(4, minmax(0, 1fr)) 104px;
  align-items: center;
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px solid rgba(118, 210, 255, 0.22);
  color: #9ed5ef;
  font-size: 11px;
}

.target-label {
  color: #d9f7ff;
  font-weight: 800;
  white-space: nowrap;
}

@media (max-width: 1580px) {
  .cockpit-flight-panel {
    right: 10px;
    left: 10px;
    bottom: 10px;
  }

  .console-body {
    grid-template-columns: minmax(180px, 0.94fr) 108px minmax(240px, 1.2fr) minmax(132px, 0.66fr);
  }

  .command-section {
    grid-template-columns: 1fr;
  }

  .command-section .command-row {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .target-drawer-row {
    grid-template-columns: 70px repeat(4, minmax(0, 1fr)) 94px;
  }
}

@media (max-width: 980px) {
  .flight-drawer-tab {
    grid-template-columns: minmax(0, 1fr) auto;
  }

  .tab-aircraft,
  .tab-status {
    display: none;
  }

  .console-toolbar,
  .console-body,
  .target-drawer-row,
  .command-section {
    grid-template-columns: 1fr;
  }

  .mode-toggles,
  .payload-row,
  .payload-row:nth-of-type(3),
  .telemetry-mini {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .console-section + .console-section,
  .command-section {
    padding-top: 10px;
    padding-left: 0;
    border-top: 1px solid rgba(115, 204, 255, 0.22);
    border-left: 0;
  }

  .movement-section {
    grid-template-columns: 1fr;
  }
}
</style>
