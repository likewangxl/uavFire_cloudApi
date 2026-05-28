<template>
  <section class="delivery-execution-panel">
    <header class="panel-head">
      <div>
        <p class="panel-kicker">FC100 投放执行画面</p>
        <h3>FC100 直播画面</h3>
      </div>
      <button
        class="refresh-btn"
        type="button"
        :disabled="loading"
        @click="emit('refresh-targets')"
      >
        刷新
      </button>
    </header>

    <div class="delivery-live-frame" :class="{ unavailable: !livePlayUrl || Boolean(playerState.error) }">
      <div v-show="livePlayUrl" ref="playerShell" class="delivery-player-shell"></div>
      <div
        v-if="!livePlayUrl || playerState.loading || playerState.error"
        class="live-overlay"
        :class="{ error: Boolean(errorMessage || playerState.error) }"
      >
        <span v-if="livePlayUrl && playerState.loading" class="live-dot"></span>
        <strong>{{ liveOverlayTitle }}</strong>
        <span>{{ liveOverlayMessage }}</span>
      </div>
    </div>

    <div class="delivery-status-grid">
      <section>
        <span>任务阶段</span>
        <strong>{{ taskPhase }}</strong>
      </section>
      <section>
        <span>执行进度</span>
        <strong>{{ taskProgress }}</strong>
      </section>
      <section>
        <span>飞行器状态</span>
        <strong>{{ aircraftStatus }}</strong>
      </section>
      <section>
        <span>直播来源</span>
        <strong>{{ liveSource }}</strong>
      </section>
    </div>

    <p class="delivery-task-message">{{ taskMessage }}</p>

    <div class="delivery-action-row">
      <span>{{ selectedDeviceSn || '未选择飞行器' }}</span>
      <span>{{ deliveryTargetSummary }}</span>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { deliveryApi } from '/@/api/fire/delivery'
import type {
  DeliveryDeviceLiveDTO,
  DeliveryDeviceProperties,
  DeliveryTaskStatus,
} from '/@/api/fire/delivery'
import type { CockpitStreamTarget } from './CockpitAircraftStreamSelector.vue'

const props = withDefaults(defineProps<{
  target?: CockpitStreamTarget | null
  deliveryTargets?: CockpitStreamTarget[]
  loading?: boolean
}>(), {
  target: null,
  deliveryTargets: () => [],
  loading: false,
})

const emit = defineEmits(['refresh-targets'])

const deviceProps = ref<DeliveryDeviceProperties | null>(null)
const deviceLive = ref<DeliveryDeviceLiveDTO | null>(null)
const taskStatus = ref<DeliveryTaskStatus | null>(null)
const errorMessage = ref('')
const polling = ref(false)
const playerShell = ref<HTMLElement | null>(null)
const playerState = reactive({
  loading: false,
  playing: false,
  error: '',
})
let pollTimer: number | undefined
let requestSeq = 0
let playerInstance: any = null
let currentPlayerUrl = ''
let playerSeq = 0
let zlmClientLoader: Promise<any> | null = null

const targetRecord = computed(() => (props.target || {}) as Record<string, unknown>)

const selectedDeviceSn = computed(() => {
  const target = targetRecord.value
  return stringValue(target.deviceSn) ||
    stringValue(target.sn) ||
    stringValue(target.aircraftSn) ||
    stringValue(target.gatewaySn)
})

const targetMessage = computed(() => stringValue(targetRecord.value.message))

const taskId = computed(() => {
  const explicitTaskId = stringValue(targetRecord.value.taskId)
  if (explicitTaskId) return explicitTaskId

  const match = targetMessage.value.match(/\btask[_-]?id=([A-Za-z0-9_-]+)/i)
  return match?.[1] || ''
})

const targetTaskStatusText = computed(() => stringValue(targetRecord.value.taskStatus))

const livePlayUrl = computed(() => deviceLive.value?.playUrl || '')
const liveTitle = computed(() => {
  const status = deviceLive.value?.streamStatus || 'running'
  return `FC100 ${status}`
})
const liveSource = computed(() => deviceLive.value?.source || '未上报')
const liveUnavailableMessage = computed(() =>
  errorMessage.value ||
  deviceLive.value?.message ||
  (selectedDeviceSn.value ? '当前设备未返回可播放地址' : '请选择投放飞行器')
)
const liveOverlayTitle = computed(() => {
  if (playerState.error) return '直播连接失败'
  if (errorMessage.value) return '直播不可用'
  if (livePlayUrl.value && playerState.loading) return '正在连接 FC100 直播'
  return '等待直播信号'
})
const liveOverlayMessage = computed(() => {
  if (playerState.error) return playerState.error
  if (livePlayUrl.value && playerState.loading) return livePlayUrl.value
  return liveUnavailableMessage.value
})

const taskPhase = computed(() => {
  if (!taskId.value) return targetTaskStatusText.value ? '未关联任务' : '待命'
  return taskStatus.value?.phase || taskStatus.value?.status || targetTaskStatusText.value || '查询中'
})

const taskProgress = computed(() => {
  if (!taskId.value) return '--'
  const progress = taskStatus.value?.progressPercent
  return typeof progress === 'number' ? `${Math.round(progress)}%` : '--'
})

const aircraftStatus = computed(() => {
  if (polling.value && !deviceProps.value) return '刷新中'
  if (!selectedDeviceSn.value) return '未选择'
  if (!deviceProps.value) return '未上报'
  const online = deviceProps.value.onlineStatus === true ? '在线' : '离线'
  const flying = deviceProps.value.flying === true ? '飞行中' : '地面'
  const battery = typeof deviceProps.value.batteryPercent === 'number'
    ? `${Math.round(deviceProps.value.batteryPercent)}%`
    : '--'
  return `${online} / ${flying} / 电量 ${battery}`
})

const taskMessage = computed(() => {
  if (!taskId.value && targetTaskStatusText.value) return '检测到任务状态字段，但未发现任务 ID，已跳过任务详情查询。'
  return taskStatus.value?.displayMessage ||
    taskStatus.value?.message ||
    deviceLive.value?.message ||
    targetMessage.value ||
    '暂无投放任务消息'
})

const deliveryTargetSummary = computed(() => {
  const count = props.deliveryTargets.length
  return count > 0 ? `可选投放目标 ${count} 架` : '无可选投放目标'
})

watch(selectedDeviceSn, () => {
  deviceProps.value = null
  deviceLive.value = null
  destroyPlayer()
  resetPolling()
}, { immediate: true })

watch(taskId, () => {
  taskStatus.value = null
  resetPolling()
})

watch(livePlayUrl, () => {
  syncLivePlayer().catch(() => undefined)
}, { flush: 'post' })

onBeforeUnmount(() => {
  if (pollTimer) window.clearInterval(pollTimer)
  pollTimer = undefined
  requestSeq += 1
  playerSeq += 1
  destroyPlayer()
})

function stringValue (value: unknown): string {
  return typeof value === 'string' ? value.trim() : ''
}

function resetPolling () {
  if (pollTimer) window.clearInterval(pollTimer)
  pollTimer = undefined
  refreshPanel().catch(() => undefined)
  pollTimer = window.setInterval(() => {
    refreshPanel().catch(() => undefined)
  }, 5000)
}

function resetPlayerState () {
  playerState.loading = false
  playerState.playing = false
  playerState.error = ''
}

function destroyPlayer () {
  if (playerInstance?.close) {
    playerInstance.close()
  } else if (playerInstance?.destroy) {
    playerInstance.destroy()
  }
  playerInstance = null
  currentPlayerUrl = ''
  resetPlayerState()
  if (playerShell.value) {
    playerShell.value.innerHTML = ''
  }
}

function loadZlmRtcClient (streamUrl: string) {
  const existing = (window as any).ZLMRTCClient
  if (existing) return Promise.resolve(existing)
  if (zlmClientLoader) return zlmClientLoader

  const scriptBaseUrl = new URL(streamUrl.replace(/^webrtc:\/\//, 'http://'))
  const scriptUrl = `${scriptBaseUrl.protocol}//${scriptBaseUrl.host}/ZLMRTCClient.js`

  zlmClientLoader = new Promise((resolve, reject) => {
    const script = document.createElement('script')
    script.src = scriptUrl
    script.async = true
    script.onload = () => resolve((window as any).ZLMRTCClient)
    script.onerror = () => reject(new Error(`加载 ZLMRTCClient 失败：${scriptUrl}`))
    document.head.appendChild(script)
  })

  return zlmClientLoader
}

function buildZlmRtcApiUrl (streamUrl: string) {
  const url = new URL(streamUrl.replace(/^webrtc:\/\//, 'http://'))
  const segments = url.pathname.split('/').filter(Boolean)
  if (segments.length < 2) {
    throw new Error(`FC100 直播地址格式不正确：${streamUrl}`)
  }

  const app = segments[segments.length - 2]
  const stream = segments[segments.length - 1]
  return `${url.protocol}//${url.host}/index/api/webrtc?app=${encodeURIComponent(app)}&stream=${encodeURIComponent(stream)}&type=play`
}

async function syncLivePlayer () {
  const url = livePlayUrl.value
  const seq = ++playerSeq

  if (!url) {
    destroyPlayer()
    return
  }

  if (url === currentPlayerUrl && playerInstance) return

  destroyPlayer()
  currentPlayerUrl = url
  playerState.loading = true

  await nextTick()
  if (seq !== playerSeq) return
  const mountPoint = playerShell.value
  if (!mountPoint) {
    playerState.loading = false
    playerState.error = '播放器容器未就绪'
    return
  }

  const video = document.createElement('video')
  video.autoplay = true
  video.muted = true
  video.playsInline = true
  video.controls = false
  video.className = 'delivery-live-video'
  Object.assign(video.style, {
    display: 'block',
    width: '100%',
    height: '100%',
    minHeight: '100%',
    objectFit: 'contain',
    background: '#02070d',
  })
  mountPoint.appendChild(video)

  const markPlaying = () => {
    if (seq !== playerSeq) return
    playerState.loading = false
    playerState.playing = true
    playerState.error = ''
  }

  try {
    const apiUrl = buildZlmRtcApiUrl(url)
    const ZLMRTCClient = await loadZlmRtcClient(url)
    if (seq !== playerSeq) return

    video.addEventListener('loadeddata', markPlaying, { once: true })
    video.addEventListener('playing', markPlaying, { once: true })
    video.addEventListener('error', () => {
      if (seq !== playerSeq || playerState.playing) return
      playerState.loading = false
      playerState.error = '视频元素播放失败'
    }, { once: true })

    playerInstance = new ZLMRTCClient.Endpoint({
      element: video,
      zlmsdpUrl: apiUrl,
      recvOnly: true,
      useCamera: false,
      audioEnable: false,
      videoEnable: true,
    })

    playerInstance.on?.(ZLMRTCClient.Events.WEBRTC_ON_CONNECTION_STATE_CHANGE, (connectionState: string) => {
      if (seq !== playerSeq) return
      if (connectionState === 'connected') {
        markPlaying()
        return
      }
      if (connectionState === 'failed' || connectionState === 'disconnected' || connectionState === 'closed') {
        playerState.loading = false
        if (!playerState.playing) playerState.error = `ZLM WebRTC ${connectionState}`
      }
    })

    playerInstance.on?.(ZLMRTCClient.Events.WEBRTC_OFFER_ANWSER_EXCHANGE_FAILED, (payload: any) => {
      if (seq !== playerSeq || playerState.playing) return
      playerState.loading = false
      playerState.error = payload?.msg || payload?.message || 'ZLM SDP 交换失败'
    })
  } catch (error: any) {
    if (seq !== playerSeq) return
    playerState.loading = false
    playerState.playing = false
    playerState.error = error?.message || String(error)
  }
}

async function refreshPanel () {
  const deviceSn = selectedDeviceSn.value
  const currentTaskId = taskId.value
  const seq = ++requestSeq

  if (!deviceSn) {
    deviceProps.value = null
    deviceLive.value = null
    taskStatus.value = null
    errorMessage.value = ''
    return
  }

  polling.value = true
  errorMessage.value = ''

  try {
    const requests: [
      ReturnType<typeof deliveryApi.deviceProps>,
      ReturnType<typeof deliveryApi.deviceLive>,
      ReturnType<typeof deliveryApi.waylineTaskStatus> | Promise<null>,
    ] = [
      deliveryApi.deviceProps(deviceSn),
      deliveryApi.deviceLive(deviceSn),
      currentTaskId ? deliveryApi.waylineTaskStatus(currentTaskId) : Promise.resolve(null),
    ]
    const [propsRes, liveRes, taskRes] = await Promise.all(requests)
    if (seq !== requestSeq) return

    deviceProps.value = propsRes.data?.data || null
    deviceLive.value = liveRes.data?.data || null
    taskStatus.value = taskRes?.data?.data || null
  } catch (error) {
    if (seq !== requestSeq) return
    errorMessage.value = error instanceof Error ? error.message : '查询投放状态失败'
  } finally {
    if (seq === requestSeq) polling.value = false
  }
}
</script>

<style scoped lang="scss">
.delivery-execution-panel {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-height: 100%;
  padding: 16px;
  color: #e8f3ff;
  border: 1px solid rgba(87, 180, 255, 0.22);
  border-radius: 8px;
  background: rgba(8, 18, 31, 0.88);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.05);
}

.panel-head,
.delivery-action-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.panel-head h3 {
  margin: 4px 0 0;
  font-size: 18px;
  font-weight: 700;
}

.panel-kicker,
.delivery-status-grid span,
.delivery-action-row {
  color: rgba(206, 226, 244, 0.68);
  font-size: 12px;
}

.panel-kicker {
  margin: 0;
}

.refresh-btn {
  min-width: 64px;
  height: 32px;
  color: #d9f0ff;
  border: 1px solid rgba(100, 191, 255, 0.45);
  border-radius: 6px;
  background: rgba(28, 94, 145, 0.32);
  cursor: pointer;
}

.refresh-btn:disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

.delivery-live-frame {
  position: relative;
  min-height: 220px;
  overflow: hidden;
  border: 1px solid rgba(97, 203, 255, 0.24);
  border-radius: 8px;
  background:
    linear-gradient(135deg, rgba(24, 65, 91, 0.65), rgba(7, 15, 26, 0.92)),
    repeating-linear-gradient(0deg, rgba(255, 255, 255, 0.04) 0 1px, transparent 1px 4px);
}

.delivery-live-frame.unavailable {
  background: rgba(7, 14, 24, 0.92);
}

.delivery-player-shell {
  position: absolute;
  inset: 0;
  z-index: 1;
  width: 100%;
  height: 100%;
  background: #02070d;
}

.delivery-player-shell :deep(video) {
  width: 100%;
  height: 100%;
  object-fit: contain;
}

.live-overlay {
  position: absolute;
  inset: 0;
  z-index: 2;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-width: 0;
  padding: 20px;
  text-align: center;
  pointer-events: none;
  background:
    radial-gradient(circle at center, rgba(9, 24, 38, 0.62), rgba(4, 10, 18, 0.92)),
    repeating-linear-gradient(0deg, rgba(255, 255, 255, 0.035) 0 1px, transparent 1px 4px);
}

.live-overlay span,
.delivery-task-message {
  max-width: 100%;
  overflow-wrap: anywhere;
  color: rgba(222, 238, 250, 0.72);
  font-size: 12px;
  line-height: 1.55;
}

.live-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #39e58c;
  box-shadow: 0 0 16px rgba(57, 229, 140, 0.85);
}

.live-overlay.error strong {
  color: #ff9c8a;
}

.delivery-status-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.delivery-status-grid section {
  min-width: 0;
  padding: 10px;
  border: 1px solid rgba(121, 187, 255, 0.16);
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.04);
}

.delivery-status-grid strong {
  display: block;
  min-height: 22px;
  margin-top: 6px;
  overflow-wrap: anywhere;
  font-size: 14px;
  font-weight: 650;
}

.delivery-task-message {
  min-height: 36px;
  margin: 0;
  padding: 10px 12px;
  border-left: 3px solid rgba(75, 195, 255, 0.7);
  border-radius: 6px;
  background: rgba(24, 54, 79, 0.42);
}

.delivery-action-row {
  padding-top: 2px;
}

@media (max-width: 920px) {
  .delivery-status-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
