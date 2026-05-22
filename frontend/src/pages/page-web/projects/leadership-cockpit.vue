<template>
  <div class="leadership-cockpit">
    <section class="hero-grid">
      <article class="shell-card hero-title">
        <p class="eyebrow">Emergency Leadership Cockpit</p>
        <h1>智能集群大载重无人机灭火系统</h1>
        <p class="hero-subtitle">陕西省森林消防应急指挥中心驾驶舱</p>
      </article>

      <article class="shell-card hero-brief">
        <div class="section-meta">处置简报</div>
        <h2>秦岭北坡 2 号山火处于可控压制阶段</h2>
        <p>
          当前主火点 1 处、次生火点 2 处，火线向东南缓慢扩展，已形成空地协同封控圈。
          现场无人机集群、补给、通信和道路管制均保持稳定。
        </p>
        <span class="status-pill danger">一级关注事件</span>
      </article>

      <article class="shell-card hero-clock">
        <div class="section-meta">当前时间</div>
        <div class="clock-value">14:26</div>
        <p>2026-03-30 周一</p>
        <p>指挥值守正常</p>
      </article>
    </section>

    <section class="summary-grid">
      <article
        v-for="item in summaryCards"
        :key="item.label"
        class="shell-card summary-card"
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
              <h3>领导决策摘要</h3>
              <p>面向值班领导的核心结论，不展示飞控级操作细节。</p>
            </div>
          </header>

          <div class="decision-list">
            <section
              v-for="item in decisions"
              :key="item.title"
              class="decision-card"
            >
              <div class="decision-top">
                <h4>{{ item.title }}</h4>
                <span
                  class="status-pill"
                  :class="item.type"
                >
                  {{ item.tag }}
                </span>
              </div>
              <p>{{ item.content }}</p>
            </section>
          </div>
        </article>

        <article class="shell-card panel-card">
          <header class="panel-header">
            <div>
              <h3>现场风险与民生影响</h3>
              <p>突出群众、道路、设施、重点坡向等消防领导最关心的要点。</p>
            </div>
          </header>

          <div class="small-metric-grid">
            <section
              v-for="item in impactMetrics"
              :key="item.label"
              class="small-metric-card"
            >
              <div class="section-meta">{{ item.label }}</div>
              <div class="small-metric-value">{{ item.value }}</div>
            </section>
          </div>

          <div class="info-list">
            <section
              v-for="item in riskItems"
              :key="item.title"
              class="info-card"
            >
              <div class="info-top">
                <h4>{{ item.title }}</h4>
                <span
                  class="status-pill"
                  :class="item.type"
                >
                  {{ item.level }}
                </span>
              </div>
              <p>{{ item.content }}</p>
            </section>
          </div>
        </article>
      </div>

      <article class="shell-card panel-card map-panel" :class="{ 'live-mode': activeVisualTab === 'live' }">
        <header class="panel-header map-header">
          <div>
            <h3>{{ activeVisualTab === 'map' ? '森林火场综合态势图' : '森林火场直播画面' }}</h3>
            <p>{{ activeVisualTab === 'map' ? '领导视角聚焦火势范围、保护圈、力量投向、受威胁对象和处置效果。' : '保留当前直播 HUD，并将直播状态、机组、清晰度和飞行模式集中展示在驾驶舱。' }}</p>
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
            <span class="status-pill" :class="activeVisualTab === 'live' ? dualStreamPillClass : 'safe'">
              {{ activeVisualTab === 'live' ? livestreamStatusPill : '空地协同封控中' }}
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

        <div v-else class="livestream-stage dual-stream-stage">
          <div class="dual-stream-shell">
            <div class="dual-stream-stage-head">
              <span class="section-meta">RC Plus Dual-Stream Runtime</span>
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

        <div v-if="activeVisualTab === 'map'" class="map-kpi-grid">
          <section
            v-for="item in mapKpis"
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
              <h3>重点告警与处置状态</h3>
              <p>只保留对决策有价值的高等级告警和处置结果。</p>
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

          <div class="alert-divider"></div>

          <div class="info-list">
            <section
              v-for="item in alertItems"
              :key="item.title"
              class="info-card"
            >
              <div class="info-top">
                <h4>{{ item.title }}</h4>
                <span
                  class="status-pill"
                  :class="item.type"
                >
                  {{ item.level }}
                </span>
              </div>
              <p>{{ item.content }}</p>
            </section>
          </div>
        </article>

        <article class="shell-card panel-card">
          <header class="panel-header">
            <div>
              <h3>力量与保障资源</h3>
              <p>面向连续作战场景，突出力量、药剂、电池与补能保障状态。</p>
            </div>
          </header>

          <div class="info-list">
            <section
              v-for="item in resourceItems"
              :key="item.title"
              class="info-card"
            >
              <div class="info-top">
                <h4>{{ item.title }}</h4>
                <span
                  class="status-pill"
                  :class="item.type"
                >
                  {{ item.level }}
                </span>
              </div>
              <p>{{ item.content }}</p>
            </section>
          </div>
        </article>
      </div>
    </section>

    <section class="footer-grid">
      <article class="shell-card footer-card">
        <h3>处置成效趋势</h3>
        <p>火场受控比例持续提升，说明当前策略有效。</p>
        <div class="trend-bars">
          <span
            v-for="(height, index) in trendBars"
            :key="index"
            :class="{ active: index >= 3 }"
            :style="{ height }"
          ></span>
        </div>
      </article>

      <article class="shell-card footer-card">
        <h3>火势扩展预测</h3>
        <p>未来 30 分钟整体向东南缓慢扩展，仍处可压制区间。</p>
        <div class="progress-track">
          <i class="progress-danger" style="width: 42%;"></i>
        </div>
      </article>

      <article class="shell-card footer-card">
        <h3>无人机轮换健康度</h3>
        <p>主力机队状态良好，轮换节奏平稳。</p>
        <div class="progress-track">
          <i class="progress-safe" style="width: 86%;"></i>
        </div>
      </article>

      <article class="shell-card footer-card">
        <h3>建议领导关注事项</h3>
        <ul class="focus-list">
          <li v-for="item in focusItems" :key="item">{{ item }}</li>
        </ul>
      </article>
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
import type { FireEventDTO } from '/@/types/fire/event'
import { useMyStore } from '/@/store'
import { EModeCode } from '/@/types/device'
import { buildLivePaneState, swapPrimaryPreference } from './leadership-cockpit-live-layout.mjs'

const store = useMyStore()
const FIELD_AGENT_AIRCRAFT_SN = (import.meta.env.VITE_AGENT_AIRCRAFT_SN as string | undefined) || '1581F7K3D249E00AM3Q3'

// Flight HUD: 复用 WorkspaceLivestreamPanel 同款 OSD 展示。sn 来源优先级：
// fireDetectionState.droneSn -> store.currentSn -> deviceInfo 第一个可用。
const fmtHud = (v: any, digits = 2) => {
  const n = Number(v)
  return Number.isFinite(n) ? n.toFixed(digits) : '—'
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

const summaryCards = [
  { label: '受控火场面积', value: '68%', note: '较 30 分钟前提升 14%' },
  { label: '受威胁群众点位', value: '2', note: '均已完成提前疏散' },
  { label: '投入无人机力量', value: '12', note: '侦察 4 / 灭火 6 / 中继 2' },
  { label: '累计投送灭火弹', value: '29', note: '有效命中率 92%' },
  { label: '预计扑灭窗口', value: '23', note: '分钟内进入残火清理' },
  { label: '保障资源到位率', value: '96%', note: '电池、药剂、通信均充足' }
]

const decisions = [
  {
    title: '总体判断',
    tag: '态势可控',
    type: 'safe',
    content: '现阶段火势已被主封控圈限制，未出现跨山脊跃迁，建议维持当前空中压制强度。'
  },
  {
    title: '下一步建议',
    tag: '建议批示',
    type: 'default',
    content: '保持 2 个侦察架次持续巡查东南回燃区，同时提前调度补给组进入待命，不建议新增地面冒进扑救。'
  }
]

const impactMetrics = [
  { label: '受威胁村组', value: '1' },
  { label: '重要设施点', value: '3' },
  { label: '道路管制段', value: '2' },
  { label: '需重点盯防坡向', value: '东南坡' }
]

const riskItems = [
  {
    title: '东南坡回燃风险',
    level: '高',
    type: 'danger',
    content: '地表温升回弹明显，若风向继续偏东，20 分钟内有局部复燃可能。'
  },
  {
    title: '通信链路冗余',
    level: '中',
    type: 'default',
    content: '中继链路整体稳定，但建议继续保留专网备份，避免山谷遮挡造成盲区。'
  }
]

const mapNodes = [
  { name: '侦察组 A-01', top: '23%', left: '28%' },
  { name: '投送组 B-02', top: '52%', left: '23%' },
  { name: '中继组 C-01', top: '38%', left: '61%' },
  { name: '封控组 D-03', top: '60%', left: '55%' },
  { name: '补位组 E-02', top: '16%', left: '72%' }
]

const mapKpis = [
  { label: '当前主火点', value: '1' },
  { label: '次生火点', value: '2' },
  { label: '封控圈完整度', value: '91%' },
  { label: '预计稳控时间', value: '23 分钟' }
]

const visualTabs = [
  { key: 'map', label: '态势图' },
  { key: 'live', label: '直播画面' }
] as const

const activeVisualTab = ref<typeof visualTabs[number]['key']>('map')
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

const aiRiskState = reactive({
  loading: false,
  error: '',
  events: [] as DualStreamEvent[]
})

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
let aiRiskEventTimer: number | undefined
let livePlayerRetryTimer: number | undefined
let primaryPlayer: any = null
let previewPlayer: any = null
let zlmClientLoader: Promise<any> | null = null

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
  if (!url || activeVisualTab.value !== 'live') {
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

  if (activeVisualTab.value !== 'live') {
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
    const candidateSns = [
      flightHudSn.value,
      FIELD_AGENT_AIRCRAFT_SN,
      store.state.deviceState.currentSn,
      fireDetectionState.droneSn,
      'RC_PLUS_LOCAL'
    ].filter((sn, index, arr): sn is string => !!sn && arr.indexOf(sn) === index)

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
    if (!selectedGroup && lastError) throw lastError
    dualStreamState.error = ''
  } catch (error: any) {
    dualStreamState.error = error?.message || 'dual-stream-state-unavailable'
  } finally {
    dualStreamState.loading = false
  }
}

const loadAiRiskEvents = async () => {
  aiRiskState.loading = true
  try {
    const response = await getDualStreamTaskEvents(AI_EVENT_TASK_ID)
    aiRiskState.events = response.data ?? []
    aiRiskState.error = ''
  } catch (error: any) {
    aiRiskState.error = error?.message || 'ai-risk-events-unavailable'
  } finally {
    aiRiskState.loading = false
  }
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

// 火情事件弹窗：每 3 秒拉一次 backend /api/fire/events，新出现的 MEDIUM/HIGH
// 火情弹 antd notification 带带框的标注图缩略图。lastSeenFireEventId 防止首次进
// 页面把历史事件全弹出来。
let fireEventNotifyTimer: number | null = null
const lastSeenFireEventId = ref(0)
const fireEventsBootstrapped = ref(false)

async function loadNewFireEvents (): Promise<void> {
  let events: FireEventDTO[] = []
  try {
    const res = await fireEventApi.list()
    events = res.data.data ?? []
  } catch (e) {
    console.warn('[cockpit] fire event poll failed', e)
    return
  }
  if (events.length === 0) return
  const maxId = events.reduce((m, e) => (e.id > m ? e.id : m), 0)
  if (!fireEventsBootstrapped.value) {
    lastSeenFireEventId.value = maxId
    fireEventsBootstrapped.value = true
    return
  }
  const fresh = events
    .filter((e) => e.id > lastSeenFireEventId.value)
    .filter((e) => {
      const level = (e.fireLevel || '').toUpperCase()
      return level === 'MEDIUM' || level === 'HIGH'
    })
    .sort((a, b) => a.id - b.id)
  for (const evt of fresh) {
    const level = (evt.fireLevel || '').toUpperCase()
    const conf = Number(evt.confidence) || 0
    const imageUrl = evt.thermalImageUrl || evt.visibleImageUrl
    const imageKind = evt.thermalImageUrl ? '红外' : '可见光'
    notification.warning({
      message: `检测到${level === 'HIGH' ? '高' : '中等'}风险火情`,
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
  }
  lastSeenFireEventId.value = maxId
}

onMounted(async () => {
  loadDualStreamState()
  loadAiRiskEvents()
  loadNewFireEvents()
  dualStreamTimer = window.setInterval(loadDualStreamState, 5000)
  aiRiskEventTimer = window.setInterval(loadAiRiskEvents, 2000)
  fireEventNotifyTimer = window.setInterval(loadNewFireEvents, 3000)
})

onBeforeUnmount(() => {
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
  allowSharedThermalPreview: true
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
  `播放 ${dualStreamSummary.value.playbackStatus}`
])

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
  return recentAiRiskEvents.value.some(event => ['MEDIUM', 'HIGH'].includes((event.riskLevel || '').toUpperCase()))
    ? 'danger'
    : 'default'
})

const formatAiScore = (score?: number) => {
  if (typeof score !== 'number' || Number.isNaN(score)) {
    return '--'
  }
  return score.toFixed(3)
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
  if (normalized === 'LOW') return 'default'
  return 'safe'
}

const aiReviewStatusClass = (status?: string) => {
  if (status === 'THERMAL_CONFIRMED' || status === 'VISIBLE_SUSPECTED') return 'danger'
  if (status === 'THERMAL_REJECTED') return 'safe'
  return 'default'
}

const aiRiskCardClass = (event: DualStreamEvent) => {
  const riskLevel = (event.riskLevel || '').toUpperCase()
  if (event.reviewStatus === 'THERMAL_CONFIRMED' || riskLevel === 'HIGH' || riskLevel === 'MEDIUM') {
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
  [
    activeVisualTab,
    () => livePaneState.value.primary.kind,
    () => livePaneState.value.primary.url,
    () => livePaneState.value.primary.crop,
    () => livePaneState.value.preview.kind,
    () => livePaneState.value.preview.url,
    () => livePaneState.value.preview.crop
  ],
  () => {
    syncLivePlayers()
    ensureThermalPreviewMode()
  },
  { immediate: true }
)

const resourceItems = [
  {
    title: '空中作战力量',
    level: '充足',
    type: 'safe',
    content: '在线 12 架，可立即补位 2 架，核心灭火力量满足连续两轮压制要求。'
  },
  {
    title: '药剂与投送载荷',
    level: '充足',
    type: 'safe',
    content: '可支持后续 31 次标准投送，满足本次事件全程处置。'
  },
  {
    title: '电池与补能保障',
    level: '可持续',
    type: 'default',
    content: '轮换电池 24 组，现场补能车 1 台，预计可支撑 4 小时连续作战。'
  }
]

const alertItems = [
  {
    title: '东南坡热成像温升回弹',
    level: '需持续关注',
    type: 'danger',
    content: '已安排 2 架侦察无人机轮巡，暂未触发新的扩大蔓延。'
  },
  {
    title: '山谷链路抖动',
    level: '已采取备份',
    type: 'default',
    content: '专网备链已启用，中继高度已调整，未对当前任务造成实质影响。'
  },
  {
    title: '群众点位风险',
    level: '已解除',
    type: 'safe',
    content: '下风向村组已完成疏散和交通管制，目前无人员被困报告。'
  }
]

const trendBars = ['24%', '36%', '48%', '62%', '74%', '86%']

const focusItems = [
  '东南坡复燃风险',
  '保持交通管制',
  '视风向变化决定是否增援'
]
</script>

<style lang="scss" scoped>
.leadership-cockpit {
  min-height: calc(100vh - 60px);
  padding: 16px;
  background:
    radial-gradient(circle at 14% 18%, rgba(69, 221, 255, 0.12), transparent 18%),
    radial-gradient(circle at 88% 12%, rgba(255, 97, 114, 0.12), transparent 16%),
    radial-gradient(circle at 50% 85%, rgba(103, 184, 255, 0.08), transparent 24%),
    linear-gradient(180deg, #081424 0%, #07111d 52%, #030912 100%);
  color: #f0f6ff;
  overflow: auto;
}

.leadership-cockpit,
.leadership-cockpit * {
  word-break: break-word;
  overflow-wrap: anywhere;
}

.hero-grid,
.summary-grid,
.content-grid,
.footer-grid,
.small-metric-grid,
.map-kpi-grid {
  display: grid;
  gap: 14px;
}

.hero-grid {
  grid-template-columns: 420px minmax(0, 1fr) 260px;
  margin-bottom: 14px;
}

.summary-grid {
  grid-template-columns: repeat(6, minmax(0, 1fr));
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
  background: linear-gradient(180deg, rgba(17, 36, 60, 0.84), rgba(5, 15, 27, 0.9));
  border: 1px solid rgba(113, 179, 255, 0.2);
  box-shadow: 0 14px 40px rgba(0, 0, 0, 0.26), inset 0 1px 0 rgba(255, 255, 255, 0.04);
  backdrop-filter: blur(16px);
}

.shell-card::after {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(120deg, rgba(103, 184, 255, 0.08), transparent 18%, transparent 84%, rgba(69, 221, 255, 0.06));
  pointer-events: none;
}

.hero-title,
.hero-brief,
.hero-clock,
.summary-card,
.panel-card,
.footer-card {
  border-radius: 22px;
  padding: 18px 20px;
}

.hero-title h1,
.hero-brief h2,
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

.hero-title h1 {
  margin-top: 8px;
  font-size: 30px;
  line-height: 1.15;
}

.hero-subtitle,
.hero-brief p,
.hero-clock p,
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

.hero-brief {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.hero-brief h2 {
  color: #ffd36b;
  font-size: 18px;
  line-height: 1.4;
}

.hero-clock {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  justify-content: center;
  text-align: right;
}

.clock-value {
  font-size: 34px;
  font-weight: 700;
  line-height: 1.1;
  margin: 10px 0 6px;
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
}

.summary-value {
  margin: 8px 0;
  font-size: 30px;
  font-weight: 700;
  line-height: 1.1;
}

.panel-card {
  min-width: 0;
}

.panel-header {
  margin-bottom: 14px;
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
  background:
    linear-gradient(180deg, rgba(47, 10, 17, 0.38) 0%, rgba(25, 8, 11, 0.92) 100%);
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
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .content-grid {
    grid-template-columns: 300px minmax(0, 1fr) 300px;
  }

  .footer-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 1280px) {
  .hero-grid,
  .content-grid,
  .footer-grid {
    grid-template-columns: 1fr;
  }

  .summary-grid,
  .map-kpi-grid,
  .small-metric-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .hero-clock {
    align-items: flex-start;
    text-align: left;
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
