<template>
  <section class="delivery-execution-panel">
    <div class="delivery-live-frame" :class="{ unavailable: !livePlayUrl || Boolean(playerState.error) }">
      <div v-show="livePlayUrl" ref="playerShell" class="delivery-player-shell"></div>

      <div class="delivery-live-badge" :class="{ idle: !playerState.playing }">
        <span class="live-dot"></span>
        FC100 投放主画面
      </div>

      <button
        class="delivery-refresh-btn"
        type="button"
        :disabled="loading"
        @click="emit('refresh-targets')"
      >
        刷新
      </button>

      <div class="delivery-live-hud">
        <span
          v-for="item in deliveryHud.chips"
          :key="item"
          class="delivery-hud-chip"
        >
          {{ item }}
        </span>
      </div>

      <div class="delivery-flight-hud">
        <div
          v-for="(row, rowIndex) in deliveryHud.flightRows"
          :key="`delivery-row-${rowIndex}`"
          class="delivery-flight-row"
          :class="{ 'mode-row': rowIndex === 0 }"
        >
          <span
            v-for="item in row"
            :key="`${rowIndex}-${item.label}`"
            class="delivery-flight-item"
          >
            <span class="delivery-flight-label">{{ item.label }}</span>
            <strong>{{ item.value }}</strong>
          </span>
        </div>
      </div>

      <p v-if="visibleTaskMessage" class="delivery-hud-message">{{ visibleTaskMessage }}</p>
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
import { buildDeliveryExecutionHud } from './delivery-execution-hud.mjs'

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

const deliveryHud = computed(() => buildDeliveryExecutionHud({
  selectedDeviceSn: selectedDeviceSn.value,
  deliveryTargetSummary: deliveryTargetSummary.value,
  taskPhase: taskPhase.value,
  taskProgress: taskProgress.value,
  aircraftStatus: aircraftStatus.value,
  liveSource: liveSource.value,
  taskMessage: taskMessage.value,
}))

const visibleTaskMessage = computed(() =>
  deliveryHud.value.message === '暂无投放任务消息' ? '' : deliveryHud.value.message
)

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
  min-height: 100%;
  color: #e8f3ff;
}

.delivery-live-frame {
  position: relative;
  width: 100%;
  aspect-ratio: 16 / 9;
  min-height: clamp(560px, 64vh, 860px);
  overflow: hidden;
  border: 1px solid rgba(69, 221, 255, 0.16);
  border-radius: 26px;
  background:
    radial-gradient(circle at top left, rgba(69, 221, 255, 0.18), transparent 34%),
    linear-gradient(180deg, rgba(16, 34, 56, 0.9), rgba(6, 16, 28, 0.96));
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.04);
}

.delivery-live-frame.unavailable {
  background:
    radial-gradient(circle at top left, rgba(69, 221, 255, 0.12), transparent 34%),
    linear-gradient(180deg, rgba(16, 34, 56, 0.84), rgba(6, 16, 28, 0.96));
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

.delivery-live-badge {
  position: absolute;
  top: 18px;
  left: 18px;
  z-index: 3;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 8px 14px;
  color: #f7fbff;
  font-size: 13px;
  font-weight: 700;
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 999px;
  background: rgba(8, 22, 38, 0.78);
}

.delivery-live-badge.idle {
  background: rgba(8, 22, 38, 0.62);
}

.delivery-refresh-btn {
  position: absolute;
  top: 18px;
  right: 18px;
  z-index: 4;
  min-width: 76px;
  height: 38px;
  padding: 0 18px;
  color: #dff4ff;
  font-size: 13px;
  font-weight: 700;
  border: 1px solid rgba(97, 203, 255, 0.32);
  border-radius: 999px;
  background: rgba(8, 38, 64, 0.78);
  box-shadow: 0 10px 24px rgba(0, 0, 0, 0.26);
  cursor: pointer;
}

.delivery-refresh-btn:disabled {
  cursor: progress;
  opacity: 0.55;
}

.delivery-live-hud {
  position: absolute;
  top: 72px;
  right: 18px;
  left: 18px;
  z-index: 3;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding-right: 96px;
  pointer-events: none;
}

.delivery-hud-chip {
  display: inline-flex;
  align-items: center;
  max-width: 100%;
  padding: 8px 12px;
  overflow-wrap: anywhere;
  color: #f3f8ff;
  font-size: 12px;
  line-height: 1.4;
  border: 1px solid rgba(95, 165, 255, 0.22);
  border-radius: 999px;
  background: rgba(8, 22, 38, 0.9);
  box-shadow: 0 8px 18px rgba(0, 0, 0, 0.24);
}

.delivery-flight-hud {
  position: absolute;
  left: 18px;
  bottom: 18px;
  z-index: 5;
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-width: min(720px, calc(100% - 36px));
  padding: 10px 14px;
  color: #e6e9ef;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
  text-shadow: 0 1px 3px rgba(0, 0, 0, 0.72);
  pointer-events: none;
  border-radius: 6px;
  background: rgba(0, 0, 0, 0.32);
}

.delivery-flight-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 14px;
}

.delivery-flight-row.mode-row strong {
  color: #53d492;
  font-weight: 700;
  letter-spacing: 0.3px;
}

.delivery-flight-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.delivery-flight-label {
  color: rgba(230, 233, 239, 0.72);
}

.delivery-flight-item strong {
  min-width: 0;
  overflow-wrap: anywhere;
  font-weight: 500;
}

.delivery-hud-message {
  position: absolute;
  right: 14px;
  bottom: 14px;
  z-index: 4;
  max-width: min(440px, calc(100% - 36px));
  min-height: 0;
  margin: 0;
  padding: 12px 14px;
  overflow-wrap: anywhere;
  color: rgba(222, 238, 250, 0.78);
  font-size: 12px;
  line-height: 1.45;
  pointer-events: none;
  border: 1px solid rgba(105, 197, 255, 0.22);
  border-radius: 10px;
  border-left: 3px solid rgba(75, 195, 255, 0.78);
  background: rgba(3, 12, 22, 0.68);
  box-shadow: 0 10px 28px rgba(0, 0, 0, 0.28);
  backdrop-filter: blur(8px);
}

.live-overlay {
  position: absolute;
  inset: 0;
  z-index: 4;
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

.live-overlay span {
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

.live-overlay.error {
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

@media (max-width: 920px) {
  .delivery-live-frame {
    min-height: clamp(460px, 64vh, 700px);
  }

  .delivery-live-badge {
    top: 10px;
    left: 10px;
  }

  .delivery-refresh-btn {
    top: 10px;
    right: 10px;
  }

  .delivery-live-hud {
    top: 62px;
    right: 10px;
    left: 10px;
    padding-right: 0;
  }

  .delivery-flight-hud {
    right: 10px;
    left: 10px;
    max-width: none;
  }

  .delivery-hud-message {
    right: 10px;
    bottom: 10px;
    max-width: calc(100% - 20px);
  }
}
</style>
