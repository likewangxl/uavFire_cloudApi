<template>
  <div class="leadership-cockpit">
    <section class="cockpit-shell">
      <header class="cockpit-topbar">
        <div class="cockpit-title">
          <h1>森林灭火综合驾驶舱</h1>
          <p>火情识别 / 双光复核 / FC100 投放 / 飞机状态</p>
        </div>
        <div class="cockpit-actions">
          <span class="status-pill default" :title="cockpitLastRefreshText">{{ cockpitRefreshLabel }}</span>
          <span class="status-pill" :class="cockpitDataStatusClass">{{ cockpitDataStatusText }}</span>
        </div>
      </header>

      <section class="summary-grid">
        <article
          v-for="item in cockpitSummary.metrics"
          :key="item.key"
          class="shell-card summary-card"
          :class="item.tone"
          :title="item.source"
        >
          <div class="section-meta">{{ item.label }}</div>
          <div class="summary-value">{{ item.value }}</div>
          <p>{{ item.note }}</p>
        </article>
      </section>

      <section class="content-grid">
      <div class="column">
        <article class="shell-card panel-card">
          <header class="panel-header">
            <div>
              <h3>AI 识别记录</h3>
              <p>来自 dual-stream task events，展示最近识别和复核状态。</p>
            </div>
          </header>

          <div class="ai-risk-alert-header">
            <div>
              <span class="section-meta">AI Fire Recognition</span>
              <h4>AI 风险识别记录</h4>
            </div>
            <span class="status-pill" :class="aiRiskPillClass">{{ aiRiskPillText }}</span>
          </div>

          <div v-if="aiRiskState.error" class="ai-risk-empty error">
            {{ aiRiskState.error }}
          </div>
          <div v-else-if="recentAiRiskEvents.length === 0" class="ai-risk-empty">
            暂无 AI 识别记录
          </div>
          <div v-else class="info-list ai-risk-alert-list">
            <section
              v-for="event in recentAiRiskEvents"
              :key="`${event.sourceTs || 'no-ts'}-${event.analysisChannel || 'unknown'}-${event.fusionScore || 0}`"
              class="info-card ai-risk-card"
              :class="aiRiskCardClass(event)"
            >
              <div class="ai-risk-card-top">
                <div>
                  <strong>{{ formatAiEventTime(event.sourceTs) }}</strong>
                  <span>{{ formatAiChannel(event.analysisChannel) }}</span>
                </div>
                <span class="status-pill" :class="aiRiskLevelClass(event.riskLevel)">
                  {{ event.riskLevel || 'UNKNOWN' }}
                </span>
              </div>
              <div class="ai-risk-scores">
                <span>可见光分数 {{ formatAiScore(event.visibleScore) }}</span>
                <span>融合分数 {{ formatAiScore(event.fusionScore) }}</span>
              </div>
              <div class="ai-risk-review" :class="aiReviewStatusClass(event.reviewStatus)">
                {{ formatAiReviewStatus(event.reviewStatus) }}
              </div>
            </section>
          </div>
        </article>

        <article class="shell-card panel-card">
          <header class="panel-header">
            <div>
              <h3>火情事件队列</h3>
              <p>来自 /api/fire/events，按风险等级和最近更新时间排序。</p>
            </div>
          </header>

          <div v-if="fireEventState.error" class="ai-risk-empty error">
            {{ fireEventState.error }}
          </div>
          <div v-else-if="cockpitSummary.recentFireEvents.length === 0" class="ai-risk-empty">
            暂无火情事件
          </div>
          <div v-else class="info-list">
            <section
              v-for="event in cockpitSummary.recentFireEvents"
              :key="event.eventId"
              class="info-card"
            >
              <div class="info-top">
                <h4>{{ event.fireLevel || 'UNKNOWN' }} · {{ event.eventId }}</h4>
                <span
                  class="status-pill"
                  :class="fireEventLevelClass(event.fireLevel)"
                >
                  {{ event.status }}
                </span>
              </div>
              <p>
                置信度 {{ formatFireConfidence(event.confidence) }} ·
                定位 {{ event.geoQuality || '未知' }} ·
                任务 {{ event.missionNo || '未关联' }}
              </p>
              <p>
                {{ formatFireEventLocation(event) }} ·
                通知版本 {{ event.notificationVersion ?? 1 }}
              </p>
            </section>
          </div>
        </article>
      </div>

      <article class="shell-card panel-card map-panel" :class="{ 'live-mode': activeVisualTab !== 'map' }">
        <header class="panel-header map-header">
          <div>
            <h3>{{ visualPanelTitle }}</h3>
            <p>{{ visualPanelDescription }}</p>
          </div>
          <div class="map-header-actions">
            <div class="visual-tabs">
              <button
                v-for="tab in visualTabs"
                :key="tab.key"
                class="visual-tab"
                :class="{ active: activeVisualTab === tab.key }"
                type="button"
                @click="activeVisualTab = tab.key"
              >
                {{ tab.label }}
              </button>
            </div>
            <span class="status-pill" :class="visualPanelPillClass">
              {{ visualPanelPillText }}
            </span>
          </div>
        </header>

        <div v-if="activeVisualTab === 'map'" class="map-stage">
          <div class="mountain mountain-one"></div>
          <div class="mountain mountain-two"></div>
          <div class="mountain mountain-three"></div>
          <div class="fire-zone fire-major"></div>
          <div class="fire-zone fire-secondary"></div>
          <div class="protection-zone zone-one"></div>
          <div class="protection-zone zone-two"></div>
          <div class="route route-one"></div>
          <div class="route route-two"></div>
          <div class="route route-three"></div>

          <div
            v-for="node in mapNodes"
            :key="node.name"
            class="map-node"
            :style="{ top: node.top, left: node.left }"
          >
            <span class="map-node-dot"></span>
            <span class="map-node-label">{{ node.name }}</span>
          </div>
        </div>

        <div v-else-if="activeVisualTab === 'fire-monitor'" class="livestream-stage dual-stream-stage">
          <div class="dual-stream-shell">
            <div class="dual-stream-stage-head">
              <CockpitAircraftStreamSelector
                v-model:value="selectedFireMonitorTargetKey"
                role="fire-monitor"
                :targets="fireMonitorTargets"
                :loading="dualStreamState.loading"
                @change="handleFireMonitorTargetChange"
              />
              <span class="status-pill" :class="dualStreamPillClass">{{ dualStreamPillText }}</span>
              <button
                class="fire-detect-btn"
                :class="{ active: fireDetectionState.running }"
                :disabled="fireDetectionState.loading"
                @click="onToggleFireDetection"
              >
                {{ fireDetectionState.running ? '停止火情监测' : '开始火情监测' }}
              </button>
            </div>

            <div class="dual-stream-player-stage">
              <div ref="primaryPlayerShell" class="dual-stream-player primary"></div>

              <div v-if="!livePaneState.primary.url" class="dual-stream-overlay">
                <div class="stream-label">{{ primaryPaneMeta.title }}</div>
                <div class="stream-value">{{ primaryPaneMeta.status }}</div>
                <p>{{ primaryPaneMeta.unavailableHint }}</p>
              </div>
              <div v-else-if="primaryPlayerState.loading" class="dual-stream-overlay">
                <div class="stream-label">{{ primaryPaneMeta.title }}</div>
                <div class="stream-value">播放器加载中</div>
                <p>{{ livePaneState.primary.url }}</p>
              </div>
              <div v-else-if="primaryPlayerState.error" class="dual-stream-overlay error">
                <div class="stream-label">{{ primaryPaneMeta.title }}</div>
                <div class="stream-value">播放失败</div>
                <p>{{ primaryPlayerState.error }}</p>
              </div>
              <div v-if="focusSwitching" class="dual-stream-switch-overlay">
                <span class="dual-stream-switch-spinner"></span>
                <strong>{{ focusSwitchLabel }}</strong>
                <small>正在切换直播画面</small>
              </div>

              <div class="live-badge" :class="{ idle: !primaryPlayerState.playing }">
                <span class="live-dot"></span>{{ primaryPaneMeta.badge }}
              </div>

              <div class="dual-stream-hud">
                <span
                  v-for="item in liveHudItems"
                  :key="item"
                  class="dual-stream-hud-chip"
                >
                  {{ item }}
                </span>
              </div>

              <div class="flight-hud-overlay">
                <div class="flight-hud-row mode-row">
                  <span class="mode" :class="{ warn: flightHud.modeWarn }">{{ flightHud.modeText }}</span>
                </div>
                <div class="flight-hud-row">
                  <span class="flight-hud-item battery">⚡ {{ flightHud.battery }}%</span>
                  <span class="flight-hud-item" :class="{ fixed: flightHud.isFixed, unfixed: !flightHud.isFixed }">
                    <span class="dot"></span>{{ flightHud.isFixed ? '定点' : '浮动' }}
                  </span>
                  <span class="flight-hud-item">GPS {{ flightHud.gps }}</span>
                  <span class="flight-hud-item">R {{ flightHud.rtk }}</span>
                </div>
                <div class="flight-hud-row">
                  <span class="flight-hud-item">ASL {{ flightHud.asl }} m</span>
                  <span class="flight-hud-item">H {{ flightHud.height }} m</span>
                  <span class="flight-hud-item">返航点 {{ flightHud.homeDist }} 米</span>
                </div>
                <div class="flight-hud-row">
                  <span class="flight-hud-item">纬度 {{ flightHud.lat }}</span>
                  <span class="flight-hud-item">经度 {{ flightHud.lng }}</span>
                </div>
                <div class="flight-hud-row">
                  <span class="flight-hud-item">H.S {{ flightHud.hSpeed }} m/s</span>
                  <span class="flight-hud-item">V.S {{ flightHud.vSpeed }} m/s</span>
                  <span class="flight-hud-item">W.S {{ flightHud.wSpeed }} m/s</span>
                </div>
              </div>

              <button
                class="dual-stream-preview"
                :class="{ clickable: livePaneState.preview.clickable && !focusSwitching, switching: focusSwitching }"
                type="button"
                :disabled="!livePaneState.preview.clickable || focusSwitching"
                @click="handlePreviewSwap"
              >
                <div ref="previewPlayerShell" class="dual-stream-player preview"></div>

                <div v-if="!livePaneState.preview.url" class="dual-stream-preview-overlay placeholder">
                  <span class="preview-title">{{ previewPaneMeta.title }}</span>
                  <strong v-if="previewPaneMeta.status">{{ previewPaneMeta.status }}</strong>
                  <small>{{ previewPaneMeta.helper }}</small>
                </div>
                <div v-else-if="previewPlayerState.loading" class="dual-stream-preview-overlay">
                  <span class="preview-title">{{ previewPaneMeta.title }}</span>
                  <strong>加载中</strong>
                  <small>{{ previewPaneMeta.helper }}</small>
                </div>
                <div v-else-if="previewPlayerState.error" class="dual-stream-preview-overlay error">
                  <span class="preview-title">{{ previewPaneMeta.title }}</span>
                  <strong>播放失败</strong>
                  <small>{{ previewPlayerState.error }}</small>
                </div>

                <div
                  v-if="livePaneState.preview.url && !previewPlayerState.loading && !previewPlayerState.error"
                  class="dual-stream-preview-label"
                >
                  <span>{{ previewPaneMeta.title }}</span>
                  <small>{{ previewPaneMeta.helper }}</small>
                </div>
              </button>
            </div>

            <div class="dual-stream-reason" v-if="dualStreamSummary.reason">
              <span class="section-meta">当前约束</span>
              <p>{{ dualStreamSummary.reason }}</p>
            </div>
          </div>
        </div>

        <div v-else class="livestream-stage delivery-stage">
          <div class="dual-stream-shell">
            <div class="dual-stream-stage-head">
              <CockpitAircraftStreamSelector
                v-model:value="selectedDeliveryTargetKey"
                role="delivery"
                :targets="deliveryExecutionTargets"
                :loading="deliveryTargetsLoading"
              />
              <span class="status-pill" :class="deliveryPanelPillClass">{{ deliveryPanelPillText }}</span>
            </div>
            <CockpitDeliveryExecutionPanel
              :target="selectedDeliveryTarget"
              :delivery-targets="deliveryExecutionTargets"
              :loading="deliveryTargetsLoading"
              @refresh-targets="loadDeliveryExecutionTargets"
            />
          </div>
        </div>

        <div v-if="activeVisualTab !== 'delivery-execution'" class="map-kpi-grid">
          <section
            v-for="item in visualKpis"
            :key="item.label"
            class="map-kpi"
          >
            <div class="section-meta">{{ item.label }}</div>
            <div class="map-kpi-value">{{ item.value }}</div>
          </section>
        </div>
      </article>

      <div class="column">
        <article class="shell-card panel-card">
          <header class="panel-header">
            <div>
              <h3>飞机与直播状态</h3>
              <p>聚合 MSDK Agent、双光直播和 FC100 投放设备状态。</p>
            </div>
          </header>

          <div v-if="cockpitSummary.aircraftRows.length === 0" class="ai-risk-empty">
            暂无飞机状态，请确认 MSDK Agent 或 FC100 投放平台已接入。
          </div>
          <div v-else class="info-list">
            <section
              v-for="aircraft in cockpitSummary.aircraftRows"
              :key="aircraft.key"
              class="info-card"
            >
              <div class="info-top">
                <h4>{{ aircraft.name }}</h4>
                <span class="status-pill" :class="aircraft.online ? 'safe' : 'danger'">
                  {{ aircraft.role }}
                </span>
              </div>
              <p>{{ aircraft.status }} · 电量 {{ formatAircraftBattery(aircraft.battery) }}</p>
              <p>{{ aircraft.detail }}</p>
            </section>
          </div>
        </article>

        <article class="shell-card panel-card">
          <header class="panel-header">
            <div>
              <h3>FC100 投放与链路状态</h3>
              <p>投放任务来自 delivery 接口；系统链路健康没有统一 health 汇总接口的部分明确标注。</p>
            </div>
          </header>

          <div class="info-list">
            <section
              v-for="task in cockpitSummary.taskRows"
              :key="task.taskId || task.missionId || task.deviceSn"
              class="info-card"
            >
              <div class="info-top">
                <h4>{{ task.taskName || task.taskId || '投放任务' }}</h4>
                <span class="status-pill default">{{ task.status || task.phase || '同步中' }}</span>
              </div>
              <p>阶段 {{ task.phase || '--' }} · 进度 {{ formatTaskProgress(task.progressPercent) }}</p>
              <p>{{ task.message || task.displayMessage || task.reason || '等待投放平台返回任务消息' }}</p>
            </section>
            <section
              v-for="gap in cockpitSummary.dataGaps"
              :key="gap.key"
              class="info-card"
            >
              <div class="info-top">
                <h4>{{ gap.label }}</h4>
                <span class="status-pill default">{{ gap.value }}</span>
              </div>
              <p>{{ gap.note }}</p>
            </section>
          </div>
        </article>
      </div>
      </section>
    </section>
  </div>
</template>

<script lang="ts" setup>
import { computed, h, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { notification } from 'ant-design-vue'
import {
  getDualStreamGroup,
  getDualStreamTaskEvents,
  getLiveCapacity,
  requestDualStreamFocus,
  requestFireDetectionStart,
  requestFireDetectionStop,
  type DualStreamEvent,
  type DualStreamGroup
} from '/@/api/manage'
import { eventApi as fireEventApi } from '/@/api/fire/event'
import { deliveryApi, type DeliveryDeviceDTO } from '/@/api/fire/delivery'
import { listMsdkDevices, type MsdkDeviceState } from '/@/api/msdk-device'
import type { FireEventDTO } from '/@/types/fire/event'
import { useMyStore } from '/@/store'
import { EModeCode } from '/@/types/device'
import CockpitAircraftStreamSelector, { type CockpitStreamTarget } from '/@/components/cockpit/CockpitAircraftStreamSelector.vue'
import CockpitDeliveryExecutionPanel from '/@/components/cockpit/CockpitDeliveryExecutionPanel.vue'
import {
  buildDualStreamCandidateSns,
  buildLivePaneState,
  buildLivePlaybackKey,
  resolveAppliedFocusPreference,
  swapPrimaryPreference
} from './leadership-cockpit-live-layout.mjs'
import { buildCockpitSummary } from './leadership-cockpit-summary.mjs'

const store = useMyStore()
const FIELD_AGENT_AIRCRAFT_SN = (import.meta.env.VITE_AGENT_AIRCRAFT_SN as string | undefined) || '1581F7K3D249E00AM3Q3'

// Flight HUD: 复用 WorkspaceLivestreamPanel 同款 OSD 展示。sn 来源优先级：
// fireDetectionState.droneSn -> store.currentSn -> deviceInfo 第一个可用。
const fmtHud = (v: any, digits = 2) => {
  const n = Number(v)
  return Number.isFinite(n) ? n.toFixed(digits) : '—'
}

function toDeviceOsdFromMsdk (device: MsdkDeviceState) {
  const batteryPercent = device.batteryPercent ?? 0
  return {
    longitude: Number(device.longitude ?? 0),
    latitude: Number(device.latitude ?? 0),
    gear: -1,
    mode_code: device.online ? EModeCode.Manual : EModeCode.Disconnected,
    height: String(device.height ?? 0),
    home_distance: String(device.homeDistance ?? 0),
    horizontal_speed: String(device.horizontalSpeed ?? 0),
    vertical_speed: String(device.verticalSpeed ?? 0),
    wind_speed: String(device.windSpeed ?? 0),
    wind_direction: '--',
    elevation: String(device.elevation ?? 0),
    position_state: {
      gps_number: String(device.gpsCount ?? '--'),
      is_fixed: device.positionFixed === false ? 0 : 1,
      rtk_number: String(device.rtkCount ?? '--')
    },
    battery: {
      capacity_percent: String(batteryPercent),
      landing_power: '0',
      remain_flight_time: 0,
      return_home_power: '0'
    }
  }
}

async function refreshMsdkHudDevices () {
  const res = await listMsdkDevices()
  if (res.code !== 0) return
  const devices = res.data || []
  msdkDeviceSnapshots.value = devices
  for (const device of devices) {
    if (!device.aircraftSn) continue
    store.commit('SET_DEVICE_INFO', {
      sn: device.aircraftSn,
      host: toDeviceOsdFromMsdk(device)
    })
  }
}

const flightHudSn = computed<string | undefined>(() => {
  const fromFire = fireDetectionState?.droneSn
  if (fromFire && store.state.deviceState.deviceInfo[fromFire]) return fromFire
  const cur = store.state.deviceState.currentSn
  if (cur && store.state.deviceState.deviceInfo[cur]) return cur
  const keys = Object.keys(store.state.deviceState.deviceInfo || {})
  return keys.length > 0 ? keys[0] : undefined
})
const flightHudVisible = computed(() => !!flightHudSn.value)
const flightHud = computed(() => {
  const sn = flightHudSn.value
  const osd = sn ? store.state.deviceState.deviceInfo[sn] : undefined
  const hasOsd = !!osd
  const mode = osd?.mode_code
  const modeText = !hasOsd
    ? '等待 OSD 数据'
    : (mode != null && EModeCode[mode] ? EModeCode[mode].replace(/_/g, ' ') : '—')
  const modeWarn = !hasOsd || mode === EModeCode.Disconnected || mode === EModeCode.Forced_Landing
  return {
    sn,
    hasOsd,
    modeText,
    modeWarn,
    battery: osd?.battery?.capacity_percent ?? '—',
    isFixed: osd?.position_state?.is_fixed === 1,
    gps: osd?.position_state?.gps_number ?? '—',
    rtk: osd?.position_state?.rtk_number ?? '—',
    asl: fmtHud(osd?.elevation),
    height: fmtHud(osd?.height),
    homeDist: fmtHud(osd?.home_distance),
    lat: fmtHud(osd?.latitude, 6),
    lng: fmtHud(osd?.longitude, 6),
    hSpeed: fmtHud(osd?.horizontal_speed),
    vSpeed: fmtHud(osd?.vertical_speed),
    wSpeed: fmtHud(osd?.wind_speed),
  }
})

const AI_EVENT_TASK_ID = 'manual-ai-001'

const fireEventState = reactive({
  loading: false,
  error: '',
  events: [] as FireEventDTO[]
})
const deliveryTaskStatuses = ref<any[]>([])
const cockpitLastRefreshAt = ref(0)

const mapNodes = computed(() => {
  const events = cockpitSummary.value.recentFireEvents
  if (events.length === 0) {
    return [{ name: '等待火情定位', top: '48%', left: '48%' }]
  }
  return events.slice(0, 5).map((event, index) => ({
    name: `${event.fireLevel || 'UNKNOWN'} ${event.eventId}`,
    top: `${20 + (index % 3) * 22}%`,
    left: `${24 + (index % 2) * 34}%`
  }))
})

const mapKpis = computed(() => cockpitSummary.value.metrics.slice(0, 4))

const visualTabs = [
  { key: 'map', label: '态势图' },
  { key: 'fire-monitor', label: '火情监测画面' },
  { key: 'delivery-execution', label: '投放执行画面' }
] as const

const activeVisualTab = ref<typeof visualTabs[number]['key']>('map')
const msdkDeviceSnapshots = ref<MsdkDeviceState[]>([])
const selectedFireMonitorTargetKey = ref('')
const deliveryExecutionTargets = ref<CockpitStreamTarget[]>([])
const selectedDeliveryTargetKey = ref('')
const deliveryTargetsLoading = ref(false)
const primaryPlayerShell = ref<HTMLElement | null>(null)
const previewPlayerShell = ref<HTMLElement | null>(null)
const primaryPreference = ref<'visible' | 'thermal'>('visible')
const focusSwitching = ref(false)
const focusSwitchAction = ref<'focus-visible' | 'focus-thermal' | null>(null)

const dualStreamState = reactive({
  loading: false,
  error: '',
  group: null as DualStreamGroup | null
})

const cockpitSummary = computed(() => buildCockpitSummary({
  fireEvents: fireEventState.events,
  aiEvents: aiRiskState.events,
  msdkDevices: msdkDeviceSnapshots.value,
  deliveryTargets: deliveryExecutionTargets.value,
  deliveryTaskStatuses: deliveryTaskStatuses.value,
  dualStreamGroup: dualStreamState.group,
  deliveryTargetsLoading: deliveryTargetsLoading.value,
  fireEventsError: fireEventState.error,
  aiEventsError: aiRiskState.error,
  dualStreamError: dualStreamState.error
}))

const cockpitDataStatusText = computed(() => {
  if (fireEventState.error || aiRiskState.error || dualStreamState.error) return '部分接口异常'
  if (fireEventState.loading || aiRiskState.loading || dualStreamState.loading || deliveryTargetsLoading.value) return '数据同步中'
  return '已接入现有接口'
})

const cockpitDataStatusClass = computed(() => {
  if (fireEventState.error || aiRiskState.error || dualStreamState.error) return 'danger'
  return 'safe'
})

const cockpitRefreshLabel = computed(() => {
  if (!cockpitLastRefreshAt.value) return '等待'
  const seconds = Math.max(0, Math.floor((Date.now() - cockpitLastRefreshAt.value) / 1000))
  return `${seconds}s`
})

const cockpitLastRefreshText = computed(() => (
  cockpitLastRefreshAt.value
    ? `最近火情同步 ${new Date(cockpitLastRefreshAt.value).toLocaleTimeString('zh-CN', { hour12: false })}`
    : '等待后端返回火情数据'
))

const fireMonitorTargets = computed<CockpitStreamTarget[]>(() => {
  const targets = new Map<string, CockpitStreamTarget>()
  for (const device of msdkDeviceSnapshots.value) {
    if (!device.aircraftSn) continue
    const groupForDevice = dualStreamState.group?.droneSn === device.aircraftSn ? dualStreamState.group : null
    targets.set(device.aircraftSn, {
      key: `fire-monitor:${device.aircraftSn}`,
      role: 'fire-monitor',
      deviceSn: device.aircraftSn,
      callsign: device.model || `火情监测 ${device.aircraftSn.slice(-4)}`,
      online: device.online,
      taskStatus: groupForDevice?.sessionState || device.connectionState || device.mode,
      primaryPlayUrl: groupForDevice?.visiblePlayUrl || '',
      thermalPlayUrl: groupForDevice?.thermalPlayUrl || '',
      streamStatus: device.online
        ? (groupForDevice?.visiblePlayUrl ? 'running' : 'idle')
        : 'offline',
      message: groupForDevice?.statusMessage || groupForDevice?.statusReason || device.mode
    })
  }

  const group = dualStreamState.group
  if (group?.droneSn && !targets.has(group.droneSn)) {
    targets.set(group.droneSn, {
      key: `fire-monitor:${group.droneSn}`,
      role: 'fire-monitor',
      deviceSn: group.droneSn,
      callsign: `火情监测 ${group.droneSn.slice(-4)}`,
      online: group.connectionState !== 'offline',
      taskStatus: group.sessionState || group.currentMode,
      primaryPlayUrl: group.visiblePlayUrl || '',
      thermalPlayUrl: group.thermalPlayUrl || '',
      streamStatus: group.visiblePlayUrl ? 'running' : 'idle',
      message: group.statusMessage || group.statusReason
    })
  }

  if (!targets.has(FIELD_AGENT_AIRCRAFT_SN)) {
    targets.set(FIELD_AGENT_AIRCRAFT_SN, {
      key: `fire-monitor:${FIELD_AGENT_AIRCRAFT_SN}`,
      role: 'fire-monitor',
      deviceSn: FIELD_AGENT_AIRCRAFT_SN,
      callsign: `火情监测 ${FIELD_AGENT_AIRCRAFT_SN.slice(-4)}`,
      online: true,
      taskStatus: '默认监测机',
      streamStatus: 'idle',
      message: '默认 Agent 飞机'
    })
  }

  return Array.from(targets.values())
})

const selectedFireMonitorTarget = computed(() => {
  return fireMonitorTargets.value.find(target => target.key === selectedFireMonitorTargetKey.value) ||
    fireMonitorTargets.value[0] ||
    null
})

const selectedDeliveryTarget = computed(() => {
  return deliveryExecutionTargets.value.find(target => target.key === selectedDeliveryTargetKey.value) ||
    deliveryExecutionTargets.value[0] ||
    null
})

const visualPanelTitle = computed(() => {
  if (activeVisualTab.value === 'map') return '森林火场综合态势图'
  if (activeVisualTab.value === 'fire-monitor') return '森林火场火情监测画面'
  return 'FC100 投放执行画面'
})

const visualPanelDescription = computed(() => {
  if (activeVisualTab.value === 'map') {
    return '领导视角聚焦火势范围、保护圈、力量投向、受威胁对象和处置效果。'
  }
  if (activeVisualTab.value === 'fire-monitor') {
    return '选择火情监测飞行器，查看可见光、红外复核、AI 识别和飞行 HUD。'
  }
  return '选择 FC100 投放飞行器，查看投放执行直播、任务阶段、进度和飞行器状态。'
})

const visualPanelPillText = computed(() => {
  if (activeVisualTab.value === 'map') return '空地协同封控中'
  if (activeVisualTab.value === 'fire-monitor') return livestreamStatusPill.value
  return deliveryPanelPillText.value
})

const visualPanelPillClass = computed(() => {
  if (activeVisualTab.value === 'map') return 'safe'
  if (activeVisualTab.value === 'fire-monitor') return dualStreamPillClass.value
  return deliveryPanelPillClass.value
})

const deliveryPanelPillText = computed(() => {
  if (deliveryTargetsLoading.value) return '投放机同步中'
  const target = selectedDeliveryTarget.value
  if (!target) return '等待 FC100 投放机'
  if (target.streamStatus === 'running') return 'FC100 直播在线'
  return target.online ? 'FC100 待播放' : 'FC100 离线'
})

const deliveryPanelPillClass = computed(() => {
  const target = selectedDeliveryTarget.value
  if (!target || !target.online || target.streamStatus === 'error') return 'danger'
  if (target.streamStatus === 'running') return 'safe'
  return 'default'
})

const fireMonitorKpis = computed(() => [
  { label: '播放对象', value: selectedFireMonitorTarget.value?.callsign || '未选择' },
  { label: '可见光状态', value: dualStreamSummary.value.visible },
  { label: '红外状态', value: dualStreamSummary.value.thermal },
  { label: 'AI 识别记录', value: `${recentAiRiskEvents.value.length} 条` }
])

const visualKpis = computed(() => {
  if (activeVisualTab.value === 'map') return mapKpis.value
  return fireMonitorKpis.value
})

const aiRiskState = reactive({
  loading: false,
  error: '',
  events: [] as DualStreamEvent[]
})
const lastSeenAiRiskEventTs = ref(0)
const aiRiskEventsBootstrapped = ref(false)
const AI_RISK_NOTIFY_SUPPRESS_MS = 5 * 60 * 1000
const lastAiRiskNotificationByKey = new Map<string, number>()

const primaryPlayerState = reactive({
  loading: false,
  playing: false,
  error: ''
})

const previewPlayerState = reactive({
  loading: false,
  playing: false,
  error: ''
})

type PlayerRuntimeState = typeof primaryPlayerState

let dualStreamTimer: number | undefined
let msdkHudTimer: number | undefined
let aiRiskEventTimer: number | undefined
let livePlayerRetryTimer: number | undefined
let primaryPlayer: any = null
let previewPlayer: any = null
let zlmClientLoader: Promise<any> | null = null
let lastMirroredFocusCommand = ''

const loadZlmRtcClient = (streamUrl: string) => {
  const existing = (window as any).ZLMRTCClient
  if (existing) {
    return Promise.resolve(existing)
  }
  if (zlmClientLoader) {
    return zlmClientLoader
  }

  const scriptBaseUrl = new URL(streamUrl.replace(/^webrtc:\/\//, 'http://'))
  const scriptUrl = `${scriptBaseUrl.protocol}//${scriptBaseUrl.host}/ZLMRTCClient.js`

  zlmClientLoader = new Promise((resolve, reject) => {
    const script = document.createElement('script')
    script.src = scriptUrl
    script.async = true
    script.onload = () => resolve((window as any).ZLMRTCClient)
    script.onerror = () => reject(new Error(`failed-to-load-zlmrtc-client:${scriptUrl}`))
    document.head.appendChild(script)
  })

  return zlmClientLoader
}

const buildZlmRtcApiUrl = (streamUrl: string) => {
  const url = new URL(streamUrl.replace(/^webrtc:\/\//, 'http://'))
  const segments = url.pathname.split('/').filter(Boolean)
  if (segments.length < 2) {
    throw new Error(`invalid-zlm-stream-url:${streamUrl}`)
  }

  const app = segments[segments.length - 2]
  const stream = segments[segments.length - 1]
  return `${url.protocol}//${url.host}/index/api/webrtc?app=${encodeURIComponent(app)}&stream=${encodeURIComponent(stream)}&type=play`
}

const resetPlayerState = (state: PlayerRuntimeState) => {
  state.loading = false
  state.playing = false
  state.error = ''
}

const destroyPlayerInstance = (
  player: any,
  state: PlayerRuntimeState,
  shell: HTMLElement | null
) => {
  if (player?.close) {
    player.close()
  } else if (player?.destroy) {
    player.destroy()
  }
  resetPlayerState(state)
  if (shell) {
    shell.innerHTML = ''
  }
  return null
}

const applyFullFrameStyles = (video: HTMLVideoElement) => {
  Object.assign(video.style, {
    width: '100%',
    height: '100%',
    objectFit: 'contain',
    objectPosition: '50% 50%',
    transform: ''
  })
}

const mountPlayerInstance = async (
  url: string,
  shell: HTMLElement | null,
  state: PlayerRuntimeState
) => {
  if (!url || activeVisualTab.value !== 'fire-monitor') {
    resetPlayerState(state)
    if (shell) {
      shell.innerHTML = ''
    }
    return null
  }

  await nextTick()
  const mountPoint = shell
  if (!mountPoint) {
    state.loading = false
    state.playing = false
    state.error = 'player-shell-unavailable'
    return null
  }

  const video = document.createElement('video')
  video.autoplay = true
  video.muted = true
  video.playsInline = true
  video.controls = false
  video.className = 'dual-stream-video'
  Object.assign(video.style, {
    display: 'block',
    width: '100%',
    height: '100%',
    minHeight: '100%',
    objectFit: 'contain',
    background: '#02070d'
  })
  applyFullFrameStyles(video)
  mountPoint.appendChild(video)

  state.loading = true
  try {
    const apiUrl = buildZlmRtcApiUrl(url)
    const ZLMRTCClient = await loadZlmRtcClient(url)

    const markPlaying = () => {
      state.loading = false
      state.playing = true
      state.error = ''
    }

    video.addEventListener('loadeddata', markPlaying, { once: true })
    video.addEventListener('playing', markPlaying, { once: true })
    video.addEventListener('error', () => {
      state.loading = false
      if (state.playing) {
        console.warn('[cockpit] ignore non-fatal video error after playback started', url)
        return
      }
      state.error = 'zlm-video-element-error'
    }, { once: true })

    const endpoint = new ZLMRTCClient.Endpoint({
      element: video,
      zlmsdpUrl: apiUrl,
      recvOnly: true,
      useCamera: false,
      audioEnable: false,
      videoEnable: true
    })

    endpoint.on?.(ZLMRTCClient.Events.WEBRTC_ON_CONNECTION_STATE_CHANGE, (connectionState: string) => {
      if (connectionState === 'connected') {
        markPlaying()
        return
      }
      if (connectionState === 'failed' || connectionState === 'disconnected' || connectionState === 'closed') {
        state.loading = false
        if (state.playing) {
          console.warn('[cockpit] ignore transient zlm connection state after playback started', connectionState, url)
          return
        }
        state.error = `zlm-connection-${connectionState}`
      }
    })

    endpoint.on?.(ZLMRTCClient.Events.WEBRTC_OFFER_ANWSER_EXCHANGE_FAILED, (payload: any) => {
      state.loading = false
      if (state.playing) {
        console.warn('[cockpit] ignore late zlm offer/answer error after playback started', payload)
        return
      }
      state.error = payload?.msg || payload?.message || 'zlm-offer-answer-exchange-failed'
    })
    return endpoint
  } catch (error: any) {
    state.loading = false
    state.playing = false
    state.error = error?.message || String(error)
    return null
  }
}

const destroyAllPlayers = () => {
  primaryPlayer = destroyPlayerInstance(primaryPlayer, primaryPlayerState, primaryPlayerShell.value)
  previewPlayer = destroyPlayerInstance(previewPlayer, previewPlayerState, previewPlayerShell.value)
  if (livePlayerRetryTimer != null) {
    window.clearTimeout(livePlayerRetryTimer)
    livePlayerRetryTimer = undefined
  }
}

const syncLivePlayers = async () => {
  if (livePlayerRetryTimer != null) {
    window.clearTimeout(livePlayerRetryTimer)
    livePlayerRetryTimer = undefined
  }
  primaryPlayer = destroyPlayerInstance(primaryPlayer, primaryPlayerState, primaryPlayerShell.value)
  previewPlayer = destroyPlayerInstance(previewPlayer, previewPlayerState, previewPlayerShell.value)

  if (activeVisualTab.value !== 'fire-monitor') {
    return
  }

  if (!primaryPlayerShell.value || !previewPlayerShell.value) {
    livePlayerRetryTimer = window.setTimeout(() => {
      syncLivePlayers()
    }, 120)
    return
  }

  primaryPlayer = await mountPlayerInstance(
    livePaneState.value.primary.url,
    primaryPlayerShell.value,
    primaryPlayerState
  )

  previewPlayer = await mountPlayerInstance(
    livePaneState.value.preview.url,
    previewPlayerShell.value,
    previewPlayerState
  )
}

// 火情识别手动开关 (#4)
const fireDetectionState = reactive({ running: false, loading: false, droneSn: '' })

const resolveFireDetectionDroneSn = async () => {
  const candidateSns = [
    selectedFireMonitorTarget.value?.deviceSn,
    fireDetectionState.droneSn,
    dualStreamState.group?.droneSn,
    FIELD_AGENT_AIRCRAFT_SN,
    store.state.deviceState.currentSn,
    flightHudSn.value,
    ...Object.keys(store.state.deviceState.deviceInfo || {})
  ].filter((sn, index, arr): sn is string => !!sn && sn !== 'RC_PLUS_LOCAL' && arr.indexOf(sn) === index)

  if (candidateSns.length > 0) {
    return candidateSns[0]
  }

  const cap = await getLiveCapacity({} as any)
  const dev: any = (cap.data || [])[0]
  return dev?.sn || ''
}

const onToggleFireDetection = async () => {
  if (fireDetectionState.loading) return
  try {
    fireDetectionState.loading = true
    if (!fireDetectionState.droneSn) {
      fireDetectionState.droneSn = await resolveFireDetectionDroneSn()
    }
    if (!fireDetectionState.droneSn) {
      notification.warning({
        message: '无法启动火情监测',
        description: '未找到可用无人机 SN，请确认遥控器 Agent 已连接并上报状态。'
      })
      return
    }
    if (fireDetectionState.running) {
      const res = await requestFireDetectionStop(fireDetectionState.droneSn)
      if (res.code !== 0) {
        throw new Error(res.message || 'ai-service stop failed')
      }
      fireDetectionState.running = false
    } else {
      const res = await requestFireDetectionStart(fireDetectionState.droneSn)
      if (res.code !== 0) {
        throw new Error(res.message || 'ai-service start failed')
      }
      fireDetectionState.running = true
    }
  } catch (e) {
    console.warn('[cockpit] fire-detection toggle failed', e)
    notification.error({
      message: fireDetectionState.running ? '停止火情监测失败' : '启动火情监测失败',
      description: (e as any)?.message || '请检查后端和 ai-service 是否正常运行。'
    })
  } finally {
    fireDetectionState.loading = false
  }
}

const loadDualStreamState = async () => {
  dualStreamState.loading = true
  try {
    const selectedSn = selectedFireMonitorTarget.value?.deviceSn
    const candidateSns = buildDualStreamCandidateSns({
      flightHudSn: flightHudSn.value,
      agentAircraftSn: selectedSn || FIELD_AGENT_AIRCRAFT_SN,
      currentSn: store.state.deviceState.currentSn,
      fireDetectionSn: fireDetectionState.droneSn
    })

    let lastError: any = null
    let selectedGroup: DualStreamGroup | null = null
    for (const sn of candidateSns) {
      try {
        const response = await getDualStreamGroup(sn)
        if (response.data) {
          selectedGroup = response.data
          break
        }
      } catch (error: any) {
        lastError = error
      }
    }
    dualStreamState.group = selectedGroup
    mirrorAppliedFocusCommand(selectedGroup)
    if (!selectedGroup && lastError) throw lastError
    dualStreamState.error = ''
  } catch (error: any) {
    dualStreamState.error = error?.message || 'dual-stream-state-unavailable'
  } finally {
    dualStreamState.loading = false
  }
}

function handleFireMonitorTargetChange (target: CockpitStreamTarget) {
  fireDetectionState.droneSn = target.deviceSn
  primaryPreference.value = 'visible'
  loadDualStreamState()
}

function toDeliveryTarget (device: DeliveryDeviceDTO): CockpitStreamTarget {
  const onlineText = String(device.online || '').toLowerCase()
  const online = onlineText === 'true' || onlineText === 'online' || onlineText === '1'
  const suffix = device.deviceSn ? device.deviceSn.slice(-4) : '--'
  return {
    key: `delivery:${device.deviceSn}`,
    role: 'delivery',
    deviceSn: device.deviceSn,
    callsign: `FC100 投放 ${suffix}`,
    online,
    taskStatus: device.bindStatus || device.deviceType || '待命',
    streamStatus: online ? 'idle' : 'offline',
    message: device.deviceType || undefined
  }
}

async function loadDeliveryExecutionTargets () {
  deliveryTargetsLoading.value = true
  try {
    const response = await deliveryApi.listDevices()
    const devices = response.data?.data || []
    const targets = devices
      .filter((device) => !!device.deviceSn)
      .map(toDeliveryTarget)

    const enriched = await Promise.all(targets.map(async (target) => {
      try {
        const [liveRes, propsRes] = await Promise.all([
          deliveryApi.deviceLive(target.deviceSn),
          deliveryApi.deviceProps(target.deviceSn).catch(() => null)
        ])
        const live = liveRes.data?.data
        const props = propsRes?.data?.data
        const enrichedTarget: CockpitStreamTarget = {
          ...target,
          online: props?.onlineStatus ?? target.online,
          primaryPlayUrl: live?.playUrl || '',
          streamStatus: live?.streamStatus === 'running'
            ? 'running'
            : (target.online ? 'idle' : 'offline'),
          message: live?.message || target.message
        } as CockpitStreamTarget
        ;(enrichedTarget as any).batteryPercent = props?.batteryPercent
        ;(enrichedTarget as any).taskStatus = props?.aircraftMode != null ? `模式 ${props.aircraftMode}` : enrichedTarget.taskStatus
        return enrichedTarget
      } catch {
        return target
      }
    }))

    deliveryExecutionTargets.value = enriched
    if (!enriched.some(target => target.key === selectedDeliveryTargetKey.value)) {
      selectedDeliveryTargetKey.value = enriched[0]?.key || ''
    }
  } finally {
    deliveryTargetsLoading.value = false
  }
}

const mirrorAppliedFocusCommand = (group: DualStreamGroup | null) => {
  if (!group?.lastCommandAction || group.lastCommandStatus?.toLowerCase() !== 'applied') {
    return
  }
  const commandKey = [
    group.droneSn || '',
    group.lastCommandAction,
    group.lastCommandStatus,
    group.currentMode || '',
    group.visiblePlayUrl || '',
    group.thermalPlayUrl || ''
  ].join('|')
  if (commandKey === lastMirroredFocusCommand) {
    return
  }
  const nextPreference = resolveAppliedFocusPreference({
    currentPreference: primaryPreference.value,
    lastCommandAction: group.lastCommandAction,
    lastCommandStatus: group.lastCommandStatus
  })
  lastMirroredFocusCommand = commandKey
  if (nextPreference !== primaryPreference.value) {
    primaryPreference.value = nextPreference
  }
}

const loadAiRiskEvents = async () => {
  aiRiskState.loading = true
  try {
    const response = await getDualStreamTaskEvents(AI_EVENT_TASK_ID)
    const events = response.data ?? []
    notifyNewAiRiskEvents(events)
    aiRiskState.events = events
    aiRiskState.error = ''
  } catch (error: any) {
    aiRiskState.error = error?.message || 'ai-risk-events-unavailable'
  } finally {
    aiRiskState.loading = false
  }
}

function notifyNewAiRiskEvents (events: DualStreamEvent[]) {
  if (events.length === 0) return

  const maxTs = events.reduce((max, event) => Math.max(max, Number(event.sourceTs) || 0), lastSeenAiRiskEventTs.value)
  if (!aiRiskEventsBootstrapped.value) {
    lastSeenAiRiskEventTs.value = maxTs
    aiRiskEventsBootstrapped.value = true
    return
  }

  const fresh = events
    .filter(event => (Number(event.sourceTs) || 0) > lastSeenAiRiskEventTs.value)
    .filter(event => {
      const level = (event.riskLevel || '').toUpperCase()
      return level === 'LOW' || level === 'MEDIUM' || level === 'HIGH'
    })
    .filter(event => Number(event.fusionScore) > 0)
    .sort((a, b) => (Number(a.sourceTs) || 0) - (Number(b.sourceTs) || 0))

  for (const event of fresh) {
    const level = (event.riskLevel || '').toUpperCase()
    const notifyTs = normalizeAiEventTimestamp(event.sourceTs)
    const notifyKey = buildAiRiskNotificationKey(event)
    const lastNotifiedTs = lastAiRiskNotificationByKey.get(notifyKey)
    if (lastNotifiedTs != null && notifyTs - lastNotifiedTs < AI_RISK_NOTIFY_SUPPRESS_MS) {
      continue
    }
    lastAiRiskNotificationByKey.set(notifyKey, notifyTs)
    const levelLabel = level === 'HIGH' ? '高风险' : level === 'MEDIUM' ? '中等风险' : '低风险'
    notification.warning({
      message: `AI 识别提示：${levelLabel}火情`,
      description: `融合分数 ${formatAiScore(event.fusionScore)}，通道 ${formatAiChannel(event.analysisChannel)}，时间 ${formatAiEventTime(event.sourceTs)}`,
      duration: 8,
      placement: 'topRight'
    })
  }

  lastSeenAiRiskEventTs.value = maxTs
}

function normalizeAiEventTimestamp (sourceTs?: number) {
  const ts = Number(sourceTs) || Date.now()
  return ts > 10_000_000_000 ? ts : ts * 1000
}

function buildAiRiskNotificationKey (event: DualStreamEvent) {
  const level = (event.riskLevel || 'UNKNOWN').toUpperCase()
  return [
    event.taskId || AI_EVENT_TASK_ID,
    event.droneSn || fireDetectionState.droneSn || FIELD_AGENT_AIRCRAFT_SN,
    event.analysisChannel || 'unknown',
    level
  ].join(':')
}

const wait = (durationMs: number) => new Promise(resolve => {
  window.setTimeout(resolve, durationMs)
})

const waitForFocusCommandApplied = async (action: 'focus-visible' | 'focus-thermal') => {
  for (let attempt = 0; attempt < 16; attempt += 1) {
    await loadDualStreamState()
    const group = dualStreamState.group
    const lastCommandAction = group?.lastCommandAction
    const lastCommandStatus = group?.lastCommandStatus
    if (lastCommandAction === action && lastCommandStatus?.toLowerCase() === 'applied') {
      return true
    }
    if (lastCommandAction === action && lastCommandStatus?.toLowerCase() === 'failed') {
      dualStreamState.error = `${action}-failed`
      return false
    }
    await wait(500)
  }
  dualStreamState.error = `${action}-not-applied`
  return false
}

const ensureThermalPreviewMode = async () => {
  // Pilot2 and the web cockpit share the same RC Plus liveview source.
  // Do not auto-switch RC Plus to thermal/PIP just to populate the web preview.
}

// 火情事件弹窗：每 3 秒拉一次 backend /api/fire/events，新出现的 LOW/MEDIUM/HIGH
// 火情弹 antd notification 带带框的标注图缩略图。lastSeenFireEventId 防止首次进
// 页面把历史事件全弹出来。
let fireEventNotifyTimer: number | null = null
const lastSeenFireEventId = ref(0)
const fireEventsBootstrapped = ref(false)
const lastNotifiedFireEventVersions = new Map<number, number>()

async function loadCockpitFireEvents (): Promise<FireEventDTO[]> {
  fireEventState.loading = true
  try {
    const res = await fireEventApi.list()
    const events = res.data.data ?? []
    fireEventState.events = events
    fireEventState.error = ''
    cockpitLastRefreshAt.value = Date.now()
    const missionNos = Array.from(new Set(events.map(event => event.missionNo).filter(Boolean))) as string[]
    const statuses = await Promise.all(missionNos.slice(0, 6).map(async (missionNo) => {
      try {
        const statusRes = await deliveryApi.status(missionNo)
        return statusRes.data?.data || null
      } catch {
        return null
      }
    }))
    deliveryTaskStatuses.value = statuses.filter(Boolean)
    return events
  } catch (e) {
    console.warn('[cockpit] fire event poll failed', e)
    fireEventState.error = (e as any)?.message || 'fire-events-unavailable'
    return []
  } finally {
    fireEventState.loading = false
  }
}

async function loadNewFireEvents (): Promise<void> {
  const events = await loadCockpitFireEvents()
  if (events.length === 0) {
    return
  }
  const maxId = events.reduce((m, e) => (e.id > m ? e.id : m), 0)
  if (!fireEventsBootstrapped.value) {
    lastSeenFireEventId.value = maxId
    for (const evt of events) {
      lastNotifiedFireEventVersions.set(evt.id, evt.notificationVersion ?? 1)
    }
    fireEventsBootstrapped.value = true
    return
  }
  const fresh = events
    .filter((e) => {
      const level = (e.fireLevel || '').toUpperCase()
      return level === 'LOW' || level === 'MEDIUM' || level === 'HIGH'
    })
    .filter(shouldNotifyFireEvent)
    .sort((a, b) => a.id - b.id)
  for (const evt of fresh) {
    const level = (evt.fireLevel || '').toUpperCase()
    const conf = Number(evt.confidence) || 0
    const imageUrl = evt.thermalImageUrl || evt.visibleImageUrl
    const imageKind = evt.thermalImageUrl ? '红外' : '可见光'
    const levelLabel = level === 'HIGH' ? '高' : level === 'MEDIUM' ? '中等' : '低'
    notification.warning({
      message: `检测到${levelLabel}风险火情`,
      description: h('div', { style: 'display:flex;gap:12px;align-items:flex-start' }, [
        imageUrl
          ? h('a', {
            href: imageUrl,
            target: '_blank',
            rel: 'noopener',
            style: 'flex:none; display:block; position:relative'
          }, [
            h('img', {
              src: imageUrl,
              alt: imageKind + '识别图',
              style: 'width:120px;height:68px;object-fit:cover;border:1px solid #555;border-radius:4px;display:block;cursor:zoom-in'
            }),
            h('span', {
              style: 'position:absolute; top:2px; left:2px; padding:1px 6px; font-size:10px; background:rgba(0,0,0,0.6); color:#fff; border-radius:3px'
            }, imageKind)
          ])
          : null,
        h('div', { style: 'font-size:12px;line-height:1.6' }, [
          h('div', `事件: ${evt.eventId}`),
          h('div', `置信度: ${conf.toFixed(2)}`),
          h('div', `位置: ${evt.lat?.toFixed(4) ?? '-'}, ${evt.lng?.toFixed(4) ?? '-'}`),
          h('div', `状态: ${evt.status}`),
          h('div', `通知版本: ${evt.notificationVersion ?? 1}`),
          h('a', {
            href: '/fire-events',
            target: '_blank',
            rel: 'noopener',
            style: 'color:#69b1ff'
          }, '查看完整列表 →')
        ])
      ]),
      duration: 12,
      placement: 'topRight'
    })
    lastNotifiedFireEventVersions.set(evt.id, evt.notificationVersion ?? 1)
  }
  lastSeenFireEventId.value = maxId
}

function shouldNotifyFireEvent (evt: FireEventDTO) {
  const version = evt.notificationVersion ?? 1
  const lastVersion = lastNotifiedFireEventVersions.get(evt.id)
  if (lastVersion == null) {
    return evt.id > lastSeenFireEventId.value
  }
  return version > lastVersion
}

onMounted(async () => {
  refreshMsdkHudDevices()
  loadDualStreamState()
  loadDeliveryExecutionTargets()
  loadAiRiskEvents()
  loadNewFireEvents()
  msdkHudTimer = window.setInterval(refreshMsdkHudDevices, 2000)
  dualStreamTimer = window.setInterval(loadDualStreamState, 5000)
  aiRiskEventTimer = window.setInterval(loadAiRiskEvents, 2000)
  fireEventNotifyTimer = window.setInterval(loadNewFireEvents, 3000)
})

onBeforeUnmount(() => {
  if (msdkHudTimer != null) {
    window.clearInterval(msdkHudTimer)
  }
  if (dualStreamTimer != null) {
    window.clearInterval(dualStreamTimer)
  }
  if (aiRiskEventTimer != null) {
    window.clearInterval(aiRiskEventTimer)
  }
  if (fireEventNotifyTimer != null) {
    window.clearInterval(fireEventNotifyTimer)
  }
  destroyAllPlayers()
})

const dualStreamSummary = computed(() => {
  const group = dualStreamState.group
  return {
    droneSn: group?.droneSn || 'RC_PLUS_LOCAL',
    session: group?.sessionState || (dualStreamState.loading ? 'LOADING' : 'UNKNOWN'),
    connection: group?.connectionState || 'UNKNOWN',
    mode: group?.currentMode || 'IDLE',
    visible: group?.visibleState || 'idle',
    thermal: group?.thermalState || 'idle',
    playbackStatus: group?.playbackStatus || '待媒体地址接入',
    rawVisiblePlayUrl: group?.visiblePlayUrl || '',
    rawThermalPlayUrl: group?.thermalPlayUrl || '',
    visiblePlayUrl: group?.visiblePlayUrl || '未提供',
    thermalPlayUrl: group?.thermalPlayUrl || '未提供',
    thermalCenterTemperatureC: group?.thermalCenterTemperatureC,
    reason: group?.statusReason || dualStreamState.error || '',
    playbackHint: group?.visiblePlayUrl
      ? '驾驶舱已拿到可见光 WebRTC 地址，当前主画面直接从本机 ZLMediaKit 拉流。'
      : '驾驶舱当前只读取 RC Plus runtime 状态；新链路 Web 播放地址尚未由 backend/agent 提供。',
    capability: group == null
      ? '待探测'
      : `visible ${group.visibleSupported ? 'yes' : 'no'} / thermal ${group.thermalSupported ? 'yes' : 'no'}`,
    lastCommand: group?.lastCommandAction
      ? `${group.lastCommandAction} / ${group.lastCommandStatus || 'pending'}`
      : '无'
  }
})

const dualStreamPillText = computed(() => {
  if (dualStreamState.loading) return '状态同步中'
  if (dualStreamState.error) return '状态查询异常'
  if (dualStreamSummary.value.visible === 'running') return 'Visible 主通道在线'
  if (dualStreamSummary.value.session === 'RUNNING') return '双流会话运行中'
  return '等待 RC Plus 运行态'
})

const dualStreamPillClass = computed(() => {
  if (dualStreamState.error) return 'danger'
  if (dualStreamSummary.value.visible === 'running') return 'safe'
  return 'default'
})

const livestreamStatusPill = computed(() => dualStreamPillText.value)

const livePaneState = computed(() => buildLivePaneState({
  visiblePlayUrl: dualStreamSummary.value.rawVisiblePlayUrl,
  thermalPlayUrl: dualStreamSummary.value.rawThermalPlayUrl,
  primaryPreference: primaryPreference.value,
  appliedFocusAction: dualStreamState.group?.lastCommandAction,
  appliedFocusStatus: dualStreamState.group?.lastCommandStatus,
  allowSharedThermalPreview: true
}))

const livePlaybackKey = computed(() => buildLivePlaybackKey({
  primaryKind: livePaneState.value.primary.kind,
  primaryUrl: livePaneState.value.primary.url,
  primaryCrop: livePaneState.value.primary.crop,
  previewKind: livePaneState.value.preview.kind,
  previewUrl: livePaneState.value.preview.url,
  previewCrop: livePaneState.value.preview.crop,
  lastCommandAction: dualStreamState.group?.lastCommandAction,
  lastCommandStatus: dualStreamState.group?.lastCommandStatus,
  currentMode: dualStreamState.group?.currentMode
}))

const primaryPaneMeta = computed(() => {
  const isThermal = livePaneState.value.primary.kind.startsWith('thermal')
  return {
    title: isThermal ? '红外主画面' : '可见光主画面',
    badge: isThermal ? '红外主画面' : '可见光主画面',
    status: isThermal ? dualStreamSummary.value.thermal : dualStreamSummary.value.visible,
    unavailableHint: isThermal
      ? '红外独立播放地址尚未提供，当前无法切为主画面。'
      : dualStreamSummary.value.playbackHint
  }
})

const previewPaneMeta = computed(() => {
  const isThermal = livePaneState.value.preview.kind.startsWith('thermal')
  const isPlaceholder = livePaneState.value.preview.kind.endsWith('placeholder')
  const isClickableThermalPlaceholder = isThermal && isPlaceholder && livePaneState.value.preview.clickable
  return {
    title: isThermal ? '红外画面' : '可见光画面',
    status: isClickableThermalPlaceholder
      ? ''
      : (isThermal ? '红外画面' : dualStreamSummary.value.visible),
    helper: livePaneState.value.preview.clickable
      ? (isThermal ? '点击切换为红外画面' : '点击切换为可见光画面')
      : (isPlaceholder ? '切换入口暂不可用' : '点击切换为主画面')
  }
})

const liveHudItems = computed(() => [
  dualStreamSummary.value.droneSn,
  `模式 ${dualStreamSummary.value.mode}`,
  `主通道 ${primaryPaneMeta.value.status}`,
  `中心温度 ${formatThermalTemperature(dualStreamSummary.value.thermalCenterTemperatureC)}`,
  `播放 ${dualStreamSummary.value.playbackStatus}`
])

const formatThermalTemperature = (value?: number) => {
  const n = Number(value)
  return Number.isFinite(n) ? `${n.toFixed(1)} °C` : '--'
}

const focusSwitchLabel = computed(() => (
  focusSwitchAction.value === 'focus-thermal' ? '红外画面加载中' : '可见光画面加载中'
))

const recentAiRiskEvents = computed(() => aiRiskState.events.slice(-10).reverse())

const aiRiskPillText = computed(() => {
  if (aiRiskState.loading && aiRiskState.events.length === 0) return '同步中'
  if (aiRiskState.error) return '读取异常'
  return `${recentAiRiskEvents.value.length} 条记录`
})

const aiRiskPillClass = computed(() => {
  if (aiRiskState.error) return 'danger'
  return recentAiRiskEvents.value.some(event => ['LOW', 'MEDIUM', 'HIGH'].includes((event.riskLevel || '').toUpperCase()))
    ? 'danger'
    : 'default'
})

const formatAiScore = (score?: number) => {
  if (typeof score !== 'number' || Number.isNaN(score)) {
    return '--'
  }
  return score.toFixed(3)
}

const formatFireConfidence = (confidence: number | string) => {
  const n = Number(confidence)
  return Number.isFinite(n) ? n.toFixed(2) : '--'
}

const formatFireEventLocation = (event: FireEventDTO) => {
  const lat = Number(event.lat)
  const lng = Number(event.lng)
  if (!Number.isFinite(lat) || !Number.isFinite(lng)) return '位置未返回'
  const errorRadius = Number(event.geoErrorRadiusM)
  const errorText = Number.isFinite(errorRadius) ? ` · 误差 ${errorRadius.toFixed(1)}m` : ''
  return `${lat.toFixed(5)}, ${lng.toFixed(5)}${errorText}`
}

const fireEventLevelClass = (level?: string | null) => {
  const normalized = String(level || '').toUpperCase()
  if (normalized === 'HIGH' || normalized === 'MEDIUM') return 'danger'
  if (normalized === 'LOW') return 'default'
  return 'safe'
}

const formatAircraftBattery = (battery?: number) => {
  const n = Number(battery)
  return Number.isFinite(n) ? `${n.toFixed(0)}%` : '--'
}

const formatTaskProgress = (progress?: number | null) => {
  const n = Number(progress)
  return Number.isFinite(n) ? `${n.toFixed(0)}%` : '--'
}

const formatAiEventTime = (sourceTs?: number) => {
  if (!sourceTs) {
    return '--:--:--'
  }
  const timestamp = sourceTs > 10_000_000_000 ? sourceTs : sourceTs * 1000
  return new Date(timestamp).toLocaleTimeString('zh-CN', { hour12: false })
}

const formatAiChannel = (channel?: string) => {
  if (channel === 'thermal') return '红外复核'
  if (channel === 'visible') return '可见光识别'
  if (channel === 'dual') return '双通道融合'
  return '未知通道'
}

const formatAiReviewStatus = (status?: string) => {
  if (status === 'VISIBLE_SUSPECTED') return 'VISIBLE_SUSPECTED 可见光疑似'
  if (status === 'THERMAL_CONFIRMED') return 'THERMAL_CONFIRMED 红外确认'
  if (status === 'THERMAL_REJECTED') return 'THERMAL_REJECTED 红外驳回'
  return status || '未进入复核流程'
}

const aiRiskLevelClass = (riskLevel?: string) => {
  const normalized = (riskLevel || '').toUpperCase()
  if (normalized === 'HIGH' || normalized === 'MEDIUM') return 'danger'
  if (normalized === 'LOW') return 'danger'
  return 'safe'
}

const aiReviewStatusClass = (status?: string) => {
  if (status === 'THERMAL_CONFIRMED' || status === 'VISIBLE_SUSPECTED') return 'danger'
  if (status === 'THERMAL_REJECTED') return 'safe'
  return 'default'
}

const aiRiskCardClass = (event: DualStreamEvent) => {
  const riskLevel = (event.riskLevel || '').toUpperCase()
  if (event.reviewStatus === 'THERMAL_CONFIRMED' || riskLevel === 'HIGH' || riskLevel === 'MEDIUM' || riskLevel === 'LOW') {
    return 'attention'
  }
  if (event.reviewStatus === 'THERMAL_REJECTED') {
    return 'resolved'
  }
  return ''
}

const handlePreviewSwap = async () => {
  if (!livePaneState.value.preview.clickable || focusSwitching.value) {
    return
  }
  const action = livePaneState.value.preview.focusAction || (
    swapPrimaryPreference(primaryPreference.value) === 'thermal' ? 'focus-thermal' : 'focus-visible'
  )
  focusSwitching.value = true
  focusSwitchAction.value = action
  try {
    await requestDualStreamFocus(dualStreamSummary.value.droneSn, action)
    const focusApplied = await waitForFocusCommandApplied(action)
    if (!focusApplied) {
      return
    }
    primaryPreference.value = action === 'focus-thermal' ? 'thermal' : 'visible'
    await loadDualStreamState()
  } catch (error: any) {
    dualStreamState.error = error?.message || `${action}-request-failed`
  } finally {
    focusSwitching.value = false
    focusSwitchAction.value = null
  }
}

watch(
  fireMonitorTargets,
  (targets) => {
    if (!targets.some(target => target.key === selectedFireMonitorTargetKey.value)) {
      selectedFireMonitorTargetKey.value = targets[0]?.key || ''
    }
  },
  { immediate: true }
)

watch(
  selectedFireMonitorTarget,
  (target, previous) => {
    if (!target || target.deviceSn === previous?.deviceSn) return
    fireDetectionState.droneSn = target.deviceSn
    loadDualStreamState()
  }
)

watch(
  activeVisualTab,
  (tab) => {
    if (tab === 'delivery-execution' && deliveryExecutionTargets.value.length === 0) {
      loadDeliveryExecutionTargets()
    }
  }
)

watch(
  [
    activeVisualTab,
    livePlaybackKey
  ],
  () => {
    syncLivePlayers()
    ensureThermalPreviewMode()
  },
  { immediate: true }
)

</script>

<style lang="scss" scoped>
.leadership-cockpit {
  min-height: calc(100vh - 60px);
  padding: 16px;
  background: linear-gradient(180deg, #07111d 0%, #030812 100%);
  color: #f0f6ff;
  overflow: auto;
}

.leadership-cockpit,
.leadership-cockpit * {
  word-break: break-word;
  overflow-wrap: anywhere;
}

.summary-grid,
.content-grid,
.footer-grid,
.small-metric-grid,
.map-kpi-grid {
  display: grid;
  gap: 14px;
}

.cockpit-shell {
  min-height: calc(100vh - 92px);
  padding: 18px;
  border: 1px solid rgba(69, 221, 255, 0.42);
  border-radius: 18px;
  background: linear-gradient(180deg, #071321 0%, #050b14 100%);
  box-shadow:
    0 18px 45px rgba(0, 0, 0, 0.32),
    inset 0 1px 0 rgba(157, 237, 255, 0.08);
}

.cockpit-topbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
  margin-bottom: 14px;
}

.cockpit-title h1 {
  margin: 0;
  color: #e9f8ff;
  font-size: 28px;
  line-height: 1.2;
}

.cockpit-title p {
  margin: 8px 0 0;
  color: #7f9eb7;
  font-size: 13px;
  line-height: 1.5;
}

.cockpit-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 10px;
}

.summary-grid {
  grid-template-columns: repeat(8, minmax(0, 1fr));
  margin-bottom: 14px;
}

.content-grid {
  grid-template-columns: 340px minmax(0, 1fr) 340px;
  align-items: start;
  margin-bottom: 14px;
}

.footer-grid {
  grid-template-columns: 1.2fr 1fr 1fr 1fr;
}

.column {
  display: grid;
  gap: 14px;
  min-width: 0;
}

.shell-card {
  position: relative;
  overflow: hidden;
  background: #0a2130;
  border: 1px solid rgba(69, 221, 255, 0.28);
  box-shadow: inset 0 1px 0 rgba(168, 239, 255, 0.06);
}

.shell-card::after {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(120deg, rgba(103, 184, 255, 0.08), transparent 18%, transparent 84%, rgba(69, 221, 255, 0.06));
  pointer-events: none;
}

.summary-card,
.panel-card,
.footer-card {
  border-radius: 8px;
  padding: 16px;
}

.panel-header h3,
.footer-card h3,
.decision-card h4,
.info-card h4,
.ai-risk-alert-header h4 {
  margin: 0;
}

.eyebrow,
.section-meta {
  color: #45ddff;
  font-size: 12px;
  letter-spacing: 0.2em;
  text-transform: uppercase;
}

.summary-card p,
.panel-header p,
.decision-card p,
.info-card p,
.footer-card p {
  margin: 0;
  color: #8fa6c1;
  font-size: 12px;
  line-height: 1.6;
}

.ai-risk-alert-header,
.ai-risk-card-top,
.ai-risk-scores {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.ai-risk-alert-header {
  margin-bottom: 12px;
}

.ai-risk-alert-header h4 {
  margin-top: 4px;
  color: #f0f6ff;
  font-size: 15px;
  line-height: 1.4;
}

.ai-risk-alert-list {
  display: grid;
  gap: 10px;
  max-height: 260px;
  margin-bottom: 12px;
  overflow-y: auto;
  padding-right: 2px;
}

.ai-risk-card {
  display: grid;
  gap: 10px;
  padding: 12px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.035);
  border: 1px solid rgba(255, 255, 255, 0.06);
}

.ai-risk-card.attention {
  border-color: rgba(255, 97, 114, 0.34);
  background: rgba(255, 97, 114, 0.08);
}

.ai-risk-card.resolved {
  border-color: rgba(66, 226, 157, 0.24);
  background: rgba(66, 226, 157, 0.06);
}

.ai-risk-card-top strong {
  display: block;
  color: #f0f6ff;
  font-size: 13px;
  line-height: 1.4;
}

.ai-risk-card-top span,
.ai-risk-scores span,
.ai-risk-review,
.ai-risk-empty {
  color: #8fa6c1;
  font-size: 12px;
  line-height: 1.5;
}

.ai-risk-scores {
  justify-content: flex-start;
  flex-wrap: wrap;
}

.ai-risk-review {
  width: fit-content;
  max-width: 100%;
  padding: 4px 8px;
  border-radius: 10px;
  background: rgba(103, 184, 255, 0.1);
  color: #cfe7ff;
}

.ai-risk-review.danger {
  background: rgba(255, 97, 114, 0.12);
  color: #ffe1e6;
}

.ai-risk-review.safe {
  background: rgba(66, 226, 157, 0.12);
  color: #e4fff3;
}

.ai-risk-empty {
  padding: 12px;
  margin-bottom: 12px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.035);
  border: 1px dashed rgba(113, 179, 255, 0.16);
}

.ai-risk-empty.error {
  color: #ffe1e6;
  border-color: rgba(255, 97, 114, 0.28);
  background: rgba(255, 97, 114, 0.08);
}

.alert-divider {
  height: 1px;
  margin: 2px 0 12px;
  background: linear-gradient(90deg, rgba(113, 179, 255, 0.2), transparent);
}

.status-pill {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: fit-content;
  max-width: 100%;
  padding: 4px 10px;
  border-radius: 999px;
  border: 1px solid rgba(103, 184, 255, 0.2);
  background: rgba(103, 184, 255, 0.12);
  color: #e2f0ff;
  font-size: 11px;
  line-height: 1.4;
  white-space: normal;
  text-align: center;
}

.status-pill.danger {
  background: rgba(255, 97, 114, 0.12);
  border-color: rgba(255, 97, 114, 0.22);
  color: #ffe1e6;
}

.status-pill.safe {
  background: rgba(66, 226, 157, 0.12);
  border-color: rgba(66, 226, 157, 0.22);
  color: #e4fff3;
}

.status-pill.default {
  background: rgba(103, 184, 255, 0.12);
}

.summary-card {
  min-width: 0;
  min-height: 108px;
}

.summary-value {
  margin: 8px 0;
  color: #e9f8ff;
  font-size: 28px;
  font-weight: 700;
  line-height: 1.1;
}

.summary-card.danger .summary-value {
  color: #ff6978;
}

.summary-card.safe .summary-value {
  color: #72ff6a;
}

.summary-card.default .summary-value {
  color: #ffd866;
}

.panel-card {
  min-width: 0;
}

.panel-header {
  margin-bottom: 14px;
}

.panel-header h3 {
  color: #7ee8ff;
}

.decision-list,
.info-list {
  display: grid;
  gap: 12px;
}

.decision-card,
.info-card,
.small-metric-card,
.map-kpi {
  border-radius: 16px;
  padding: 14px;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.06);
  min-width: 0;
}

.decision-top,
.info-top {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 10px;
  margin-bottom: 8px;
}

.decision-card h4,
.info-card h4 {
  color: #d8f3ff;
  font-size: 15px;
  line-height: 1.4;
}

.small-metric-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
  margin-bottom: 12px;
}

.small-metric-value,
.map-kpi-value {
  margin-top: 8px;
  font-size: 24px;
  font-weight: 700;
  line-height: 1.2;
}

.map-panel {
  display: grid;
  grid-template-rows: auto minmax(420px, 1fr) auto;
}

.map-panel.live-mode {
  grid-template-rows: auto auto auto;
}

.map-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
}

.map-header-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.visual-tabs {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px;
  border-radius: 16px 16px 10px 10px;
  background: rgba(7, 18, 33, 0.62);
  border: 1px solid rgba(113, 179, 255, 0.14);
}

.visual-tab {
  appearance: none;
  border: 0;
  border-radius: 12px 12px 8px 8px;
  padding: 9px 16px;
  background: rgba(26, 59, 92, 0.88);
  color: #8fc8ff;
  font-size: 13px;
  font-weight: 700;
  cursor: pointer;
  transition: background 0.2s ease, color 0.2s ease, transform 0.2s ease;
}

.visual-tab.active {
  background: linear-gradient(180deg, #3296ff 0%, #2174d9 100%);
  color: #ffffff;
  box-shadow: 0 10px 18px rgba(37, 116, 217, 0.24);
}

.visual-tab:hover {
  transform: translateY(-1px);
}

.map-stage {
  position: relative;
  min-height: 420px;
  border-radius: 28px;
  overflow: hidden;
  border: 1px solid rgba(113, 179, 255, 0.14);
  background:
    radial-gradient(circle at 48% 48%, rgba(103, 184, 255, 0.18), transparent 24%),
    linear-gradient(180deg, rgba(7, 18, 33, 0.94), rgba(4, 10, 19, 0.98));
}

.map-stage::before {
  content: '';
  position: absolute;
  inset: 0;
  background:
    linear-gradient(rgba(113, 179, 255, 0.07) 1px, transparent 1px),
    linear-gradient(90deg, rgba(113, 179, 255, 0.07) 1px, transparent 1px);
  background-size: 72px 72px;
  opacity: 0.42;
}

.livestream-stage {
  min-height: 0;
  overflow: visible;
}

.dual-stream-stage {
  display: block;
}

.dual-stream-shell {
  display: grid;
  gap: 14px;
  min-height: 0;
}

.dual-stream-stage-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: center;
}

.fire-detect-btn {
  background: rgba(69, 221, 255, 0.12);
  border: 1px solid rgba(69, 221, 255, 0.35);
  color: #9be7ff;
  padding: 4px 12px;
  border-radius: 999px;
  font-size: 12px;
  cursor: pointer;
  transition: background 0.15s ease;
}
.fire-detect-btn:hover:not(:disabled) {
  background: rgba(69, 221, 255, 0.22);
}
.fire-detect-btn.active {
  background: rgba(255, 99, 71, 0.18);
  border-color: rgba(255, 99, 71, 0.55);
  color: #ffb3a3;
}
.fire-detect-btn:disabled {
  opacity: 0.5;
  cursor: progress;
}

.dual-stream-player-stage {
  position: relative;
  width: 100%;
  aspect-ratio: 16 / 9;
  min-height: 0;
  border-radius: 26px;
  background:
    radial-gradient(circle at top left, rgba(69, 221, 255, 0.18), transparent 34%),
    linear-gradient(180deg, rgba(16, 34, 56, 0.9), rgba(6, 16, 28, 0.96));
  border: 1px solid rgba(69, 221, 255, 0.16);
  overflow: hidden;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.04);
}

.dual-stream-player {
  height: 100%;
}

.dual-stream-player.primary {
  position: absolute;
  inset: 0;
  min-height: 0;
}

.dual-stream-player.preview {
  position: absolute;
  inset: 0;
  min-height: 100%;
}

.dual-stream-video {
  display: block;
  width: 100%;
  height: 100%;
  min-height: 100%;
  object-fit: contain;
  background: #02070d;
}

.dual-stream-overlay {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  gap: 10px;
  padding: 22px;
  background:
    linear-gradient(180deg, rgba(5, 14, 24, 0.28) 0%, rgba(5, 14, 24, 0.88) 100%);
}

.dual-stream-overlay.error {
  inset: auto 24px 24px auto;
  width: min(420px, calc(100% - 48px));
  min-height: 0;
  justify-content: flex-start;
  padding: 16px 18px;
  border: 1px solid rgba(255, 112, 112, 0.28);
  border-radius: 12px;
  background:
    linear-gradient(180deg, rgba(47, 10, 17, 0.86) 0%, rgba(25, 8, 11, 0.94) 100%);
  box-shadow: 0 18px 36px rgba(0, 0, 0, 0.34);
}

.live-badge {
  position: absolute;
  top: 18px;
  left: 18px;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 8px 14px;
  border-radius: 999px;
  background: rgba(8, 22, 38, 0.78);
  border: 1px solid rgba(255, 255, 255, 0.08);
  color: #f7fbff;
  font-size: 13px;
  font-weight: 700;
  z-index: 3;
}

.live-badge.idle {
  background: rgba(8, 22, 38, 0.62);
}

.live-dot {
  width: 8px;
  height: 8px;
  border-radius: 999px;
  background: #ff6172;
  box-shadow: 0 0 12px rgba(255, 97, 114, 0.72);
}

.stream-label {
  color: #8fa6c1;
  font-size: 12px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.stream-value {
  margin: 18px 0 10px;
  font-size: 38px;
  line-height: 1.05;
  font-weight: 700;
}

.dual-stream-reason p {
  margin: 0;
  color: #8fa6c1;
  font-size: 13px;
  line-height: 1.7;
}

.dual-stream-hud {
  position: absolute;
  left: 18px;
  top: 72px;
  right: 240px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  z-index: 3;
}

.dual-stream-hud-chip {
  display: inline-flex;
  align-items: center;
  padding: 8px 12px;
  border-radius: 999px;
  background: rgba(8, 22, 38, 0.9);
  border: 1px solid rgba(95, 165, 255, 0.22);
  color: #f3f8ff;
  font-size: 12px;
  line-height: 1.4;
  box-shadow: 0 8px 18px rgba(0, 0, 0, 0.24);
}

/* 左下角飞行 HUD（与 WorkspaceLivestreamPanel 同款），不拦截点击 */
.flight-hud-overlay {
  position: absolute;
  left: 18px;
  bottom: 18px;
  z-index: 5;
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px 14px;
  background: rgba(0, 0, 0, 0.32);
  border-radius: 6px;
  color: #e6e9ef;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
  text-shadow: 0 1px 3px rgba(0, 0, 0, 0.72);
  pointer-events: none;
  max-width: 85%;

  .flight-hud-row {
    display: flex;
    gap: 14px;
    align-items: center;
    flex-wrap: wrap;
  }

  .mode-row .mode {
    color: #53d492;
    font-weight: 600;
    letter-spacing: 0.3px;

    &.warn {
      color: #ff7875;
    }
  }

  .flight-hud-item {
    display: inline-flex;
    align-items: center;
    gap: 4px;

    &.battery {
      color: #ffd666;
    }

    &.fixed .dot {
      background: #52c41a;
    }

    &.unfixed .dot {
      background: #ff4d4f;
    }

    .dot {
      width: 8px;
      height: 8px;
      border-radius: 50%;
      display: inline-block;
    }
  }
}

.dual-stream-preview {
  position: absolute;
  top: 18px;
  right: 18px;
  width: 210px;
  height: 132px;
  padding: 0;
  border: 2px solid rgba(95, 165, 255, 0.42);
  border-radius: 18px;
  overflow: hidden;
  background: rgba(6, 16, 28, 0.28);
  box-shadow: 0 12px 28px rgba(0, 0, 0, 0.22);
  cursor: default;
  z-index: 4;
}

.dual-stream-preview.clickable {
  cursor: pointer;
  border-color: rgba(95, 165, 255, 0.58);
}

.dual-stream-preview.switching {
  cursor: wait;
}

.dual-stream-preview:disabled {
  opacity: 1;
}

.dual-stream-switch-overlay {
  position: absolute;
  inset: 0;
  z-index: 5;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  background: rgba(3, 10, 18, 0.68);
  color: #f5f9ff;
  text-align: center;
  backdrop-filter: blur(2px);
}

.dual-stream-switch-overlay strong {
  font-size: 20px;
  line-height: 1.2;
}

.dual-stream-switch-overlay small {
  color: #b7c7d9;
  font-size: 12px;
}

.dual-stream-switch-spinner {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  border: 3px solid rgba(143, 200, 255, 0.28);
  border-top-color: #8fc8ff;
  animation: dual-stream-spin 0.8s linear infinite;
}

@keyframes dual-stream-spin {
  to {
    transform: rotate(360deg);
  }
}

.dual-stream-preview-overlay,
.dual-stream-preview-label {
  position: absolute;
  left: 0;
  right: 0;
}

.dual-stream-preview-overlay {
  inset: 0;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
  gap: 4px;
  padding: 12px;
  background: linear-gradient(180deg, rgba(5, 14, 24, 0.08) 0%, rgba(5, 14, 24, 0.42) 100%);
  color: #f4f8ff;
  text-shadow: 0 1px 3px rgba(0, 0, 0, 0.76);
}

.dual-stream-preview-overlay.error {
  background: linear-gradient(180deg, rgba(47, 10, 17, 0.32) 0%, rgba(25, 8, 11, 0.92) 100%);
}

.dual-stream-preview-overlay.placeholder {
  background:
    radial-gradient(circle at 50% 20%, rgba(255, 122, 122, 0.08), transparent 35%),
    linear-gradient(180deg, rgba(38, 25, 28, 0.18) 0%, rgba(15, 12, 16, 0.5) 100%);
  border: 1px solid rgba(255, 122, 122, 0.14);
}

.dual-stream-preview-overlay strong {
  font-size: 18px;
  line-height: 1.1;
}

.dual-stream-preview-overlay.placeholder strong {
  color: #fff0f0;
}

.dual-stream-preview-overlay small,
.dual-stream-preview-label small {
  color: #b7c7d9;
  font-size: 11px;
  line-height: 1.4;
}

.preview-title {
  color: #9fb4ca;
  font-size: 11px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.dual-stream-preview-label {
  bottom: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 10px 12px;
  background: linear-gradient(180deg, rgba(5, 14, 24, 0) 0%, rgba(5, 14, 24, 0.46) 100%);
  color: #ffffff;
  text-align: left;
  text-shadow: 0 1px 3px rgba(0, 0, 0, 0.76);
  pointer-events: none;
}

.dual-stream-reason {
  border-radius: 18px;
  padding: 14px 16px;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.06);
}

.mountain,
.protection-zone,
.fire-zone,
.route {
  position: absolute;
}

.mountain {
  inset: 12% 10% 18% 10%;
  border-radius: 46% 54% 48% 52% / 54% 48% 52% 46%;
  border: 1px dashed rgba(103, 184, 255, 0.18);
}

.mountain-two {
  inset: 20% 18% 24% 18%;
  opacity: 0.65;
}

.mountain-three {
  inset: 28% 25% 30% 25%;
  opacity: 0.4;
}

.fire-zone {
  border-radius: 999px;
  background: radial-gradient(circle, rgba(255, 211, 107, 0.96), rgba(255, 97, 114, 0.72) 56%, rgba(255, 97, 114, 0.06) 78%);
  box-shadow: 0 0 40px rgba(255, 97, 114, 0.34);
}

.fire-major {
  width: 190px;
  height: 190px;
  top: 24%;
  left: 49%;
}

.fire-secondary {
  width: 110px;
  height: 110px;
  top: 56%;
  left: 30%;
  opacity: 0.78;
}

.protection-zone {
  border: 2px dashed rgba(69, 221, 255, 0.7);
  border-radius: 50% 52% 46% 50%;
  opacity: 0.85;
}

.zone-one {
  width: 280px;
  height: 180px;
  top: 46%;
  left: 46%;
  transform: rotate(-12deg);
}

.zone-two {
  width: 250px;
  height: 150px;
  top: 26%;
  left: 18%;
  transform: rotate(18deg);
}

.route {
  border-top: 2px dashed rgba(69, 221, 255, 0.72);
  transform-origin: left center;
}

.route-one {
  width: 220px;
  top: 28%;
  left: 18%;
  transform: rotate(18deg);
}

.route-two {
  width: 200px;
  top: 63%;
  left: 20%;
  transform: rotate(-15deg);
}

.route-three {
  width: 150px;
  top: 28%;
  left: 64%;
  transform: rotate(120deg);
}

.map-node {
  position: absolute;
  display: inline-flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.map-node-dot {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: radial-gradient(circle at 35% 35%, #ffffff, #45ddff);
  box-shadow: 0 0 18px rgba(69, 221, 255, 0.75);
  border: 2px solid rgba(255, 255, 255, 0.28);
}

.map-node-label {
  max-width: 112px;
  color: #e1efff;
  font-size: 11px;
  line-height: 1.4;
  text-align: center;
}

.map-kpi-grid {
  grid-template-columns: repeat(4, minmax(0, 1fr));
  margin-top: 14px;
}

.footer-card h3 {
  font-size: 15px;
  line-height: 1.4;
  margin-bottom: 8px;
}

.trend-bars {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  height: 86px;
  margin-top: 12px;
}

.trend-bars span {
  flex: 1;
  min-width: 0;
  border-radius: 10px 10px 0 0;
  background: linear-gradient(180deg, rgba(255, 171, 74, 0.9), rgba(255, 97, 114, 0.32));
}

.trend-bars span.active {
  background: linear-gradient(180deg, rgba(69, 221, 255, 0.88), rgba(103, 184, 255, 0.22));
}

.progress-track {
  margin-top: 14px;
  height: 8px;
  border-radius: 999px;
  overflow: hidden;
  background: rgba(255, 255, 255, 0.08);
}

.progress-track i {
  display: block;
  height: 100%;
  border-radius: inherit;
}

.progress-danger {
  background: linear-gradient(90deg, #ffab4a, #ff6172);
}

.progress-safe {
  background: linear-gradient(90deg, #42e29d, #45ddff);
}

.focus-list {
  margin: 12px 0 0;
  padding-left: 18px;
  color: #8fa6c1;
  font-size: 12px;
  line-height: 1.7;
}

@media (max-width: 1680px) {
  .summary-grid {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }

  .content-grid {
    grid-template-columns: 300px minmax(0, 1fr) 300px;
  }

  .footer-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 1280px) {
  .content-grid,
  .footer-grid {
    grid-template-columns: 1fr;
  }

  .summary-grid,
  .map-kpi-grid,
  .small-metric-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .cockpit-topbar {
    flex-direction: column;
  }

  .map-header {
    flex-direction: column;
    align-items: flex-start;
  }

  .map-header-actions {
    flex-direction: column;
    align-items: flex-start;
  }

  .dual-stream-preview {
    width: 172px;
    height: 108px;
  }

  .dual-stream-hud {
    top: 68px;
    right: 188px;
  }
}
</style>
