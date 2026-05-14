<template>
  <div class="workspace-livestream-panel" :class="`variant-${props.variant}`">
    <div v-if="props.showHeader" class="header">直播画面</div>

    <div class="panel-body">
      <div class="player-frame">
        <div class="player-aspect">
          <div id="workspace-player" class="player-surface"></div>
          <div v-if="state.configError" class="player-overlay error">
            <span>{{ state.configError }}</span>
          </div>
          <div v-else-if="!state.configLoaded" class="player-overlay">
            <span>直播播放入口未启用</span>
          </div>
          <div v-else-if="!state.remotePlaying" class="player-overlay idle">
            <span>正在等待视频流。请选择无人机与相机后点击开始播放。</span>
          </div>
          <div v-if="state.remotePlaying" class="live-badge">
            <span class="live-dot"></span>直播中<span v-if="!livePara.liveState" class="live-source"> · 来自 Pilot 发起</span>
          </div>

          <div v-if="hudVisible" class="hud-overlay">
            <div class="hud-row mode-row">
              <span class="mode" :class="{ warn: hud.modeWarn }">{{ hud.modeText }}</span>
            </div>
            <div class="hud-row">
              <span class="hud-item battery">⚡ {{ hud.battery }}%</span>
              <span class="hud-item" :class="{ fixed: hud.isFixed, unfixed: !hud.isFixed }">
                <span class="dot"></span>{{ hud.isFixed ? '定点' : '浮动' }}
              </span>
              <span class="hud-item">GPS {{ hud.gps }}</span>
              <span class="hud-item">R {{ hud.rtk }}</span>
            </div>
            <div class="hud-row">
              <span class="hud-item">ASL {{ hud.asl }} m</span>
              <span class="hud-item">H {{ hud.height }} m</span>
              <span class="hud-item">返航点 {{ hud.homeDist }} 米</span>
            </div>
            <div class="hud-row">
              <span class="hud-item">H.S {{ hud.hSpeed }} m/s</span>
              <span class="hud-item">V.S {{ hud.vSpeed }} m/s</span>
              <span class="hud-item">W.S {{ hud.wSpeed }} m/s</span>
            </div>
          </div>
        </div>
      </div>

      <div class="controls">
        <div class="row">
          <a-select
            style="width: 200px"
            placeholder="请选择无人机"
            v-model:value="dronePara.droneSelected"
            :disabled="!state.configLoaded || livePara.liveState"
            @change="onDroneChange"
          >
            <a-select-option
              v-for="item in dronePara.droneList"
              :key="item.value"
              :value="item.value"
            >{{ item.label }}</a-select-option>
          </a-select>

          <a-select
            class="ml10"
            style="width: 180px"
            placeholder="请选择相机"
            v-model:value="dronePara.cameraSelected"
            :disabled="livePara.liveState"
            @change="onCameraChange"
          >
            <a-select-option
              v-for="item in dronePara.cameraList"
              :key="item.value"
              :value="item.value"
            >{{ item.label }}</a-select-option>
          </a-select>

          <a-select
            class="ml10"
            style="width: 160px"
            placeholder="请选择清晰度"
            v-model:value="dronePara.claritySelected"
            @select="onClaritySelect"
          >
            <a-select-option
              v-for="item in clarityList"
              :key="item.value"
              :value="item.value"
            >{{ item.label }}</a-select-option>
          </a-select>
        </div>

        <div class="row lens-row" v-if="livePara.liveState && dronePara.isDockLive">
          <span class="lens-label">镜头：</span>
          <a-radio-group v-model:value="dronePara.lensSelected" button-style="solid">
            <a-radio-button v-for="lens in dronePara.lensList" :key="lens" :value="lens">{{ lens }}</a-radio-button>
          </a-radio-group>
          <a-button class="ml10" type="primary" size="small" @click="onSwitchLens">切换镜头</a-button>
        </div>

        <div class="row actions">
          <a-button
            type="primary"
            :disabled="!state.configLoaded || livePara.liveState"
            @click="onStart"
          >开始播放</a-button>
          <a-button class="ml10" :disabled="!livePara.liveState" @click="onStop">停止直播</a-button>
          <a-button class="ml10" :disabled="!livePara.liveState" @click="onUpdateQuality">应用清晰度</a-button>
          <a-button class="ml10" :disabled="livePara.liveState" @click="onRefresh">刷新直播能力</a-button>
        </div>

        <div class="status" v-if="state.statusMessage">{{ state.statusMessage }}</div>
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { message } from 'ant-design-vue'
import { computed, onBeforeUnmount, onMounted, reactive, watch } from 'vue'
import {
  getLiveCapacity,
  stopLivestream
} from '/@/api/manage'
import { useMyStore } from '/@/store'
import { EModeCode } from '/@/types/device'

const props = withDefaults(defineProps<{
  showHeader?: boolean
  variant?: 'default' | 'cockpit'
}>(), {
  showHeader: true,
  variant: 'default'
})

const emit = defineEmits(['state-change'])

const clarityList = [
  { value: 0, label: '自适应' },
  { value: 1, label: '流畅' },
  { value: 2, label: '标准' },
  { value: 3, label: '高清' },
  { value: 4, label: '超清' }
]

interface SelectOption {
  value: any,
  label: string,
  more?: any
}

const nonSwitchable = 'normal'

const dronePara = reactive({
  livestreamSource: [] as any[],
  droneList: [] as SelectOption[],
  cameraList: [] as SelectOption[],
  videoList: [] as SelectOption[],
  droneSelected: undefined as string | undefined,
  cameraSelected: undefined as string | undefined,
  videoSelected: undefined as string | undefined,
  claritySelected: 0 as number,
  lensList: [] as string[],
  lensSelected: undefined as string | undefined,
  isDockLive: false
})

const livePara = reactive({
  url: '',
  videoId: '',
  liveState: false
})

const state = reactive({
  configLoaded: false,
  configError: '',
  statusMessage: '',
  remotePlaying: false,
  // 独立于 remoteUsers 事件的"正在播"信号 — 通过轮询 #workspace-player 下的 <video> 节点确认
  videoAttached: false
})

const store = useMyStore()

const fmt = (v: any, digits = 2) => {
  const n = Number(v)
  return Number.isFinite(n) ? n.toFixed(digits) : '—'
}

// HUD 的 sn 源：优先用户选中的飞机，其次 store 里的 currentSn，再次 deviceInfo 里任意一个
const hudSn = computed<string | undefined>(() => {
  if (dronePara.droneSelected) return dronePara.droneSelected
  const cur = store.state.deviceState.currentSn
  if (cur && store.state.deviceState.deviceInfo[cur]) return cur
  const keys = Object.keys(store.state.deviceState.deviceInfo || {})
  return keys.length > 0 ? keys[0] : undefined
})

const hudVisible = computed(() => !!hudSn.value)

const hud = computed(() => {
  const sn = hudSn.value
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
    asl: fmt(osd?.elevation),
    height: fmt(osd?.height),
    homeDist: fmt(osd?.home_distance),
    hSpeed: fmt(osd?.horizontal_speed),
    vSpeed: fmt(osd?.vertical_speed),
    wSpeed: fmt(osd?.wind_speed)
  }
})

// "视频正在播"以 DOM 为准：#workspace-player 下存在 <video> 即视为在播
const streamActive = computed(() => state.videoAttached || state.remotePlaying)

const summaryState = computed(() => ({
  configLoaded: state.configLoaded,
  configError: state.configError,
  remotePlaying: streamActive.value,
  liveState: livePara.liveState,
  statusMessage: state.statusMessage,
  selectedDrone: dronePara.droneSelected || '',
  selectedCamera: dronePara.cameraSelected != null ? String(dronePara.cameraSelected) : '',
  qualityLabel: clarityList[dronePara.claritySelected]?.label || '自适应',
  hudMode: hud.value.modeText
}))

const setStatus = (msg: string) => {
  state.statusMessage = msg
}

const disableLegacyPlaybackConfig = async () => {
  state.configLoaded = false
  state.configError = '旧版播放入口已下线，请使用 WebRTC/ZLM 直播面板'
  return false
}

const onRefresh = async () => {
  dronePara.droneList = []
  dronePara.cameraList = []
  dronePara.videoList = []
  dronePara.droneSelected = undefined
  dronePara.cameraSelected = undefined
  dronePara.videoSelected = undefined
  try {
    const res = await getLiveCapacity({})
    if (res.code !== 0 || !res.data) {
      setStatus('未获取到直播能力')
      return
    }
    dronePara.livestreamSource = res.data
    console.log('[直播能力] 原始返回：', JSON.parse(JSON.stringify(res.data)))
    dronePara.livestreamSource.forEach((ele: any) => {
      console.log('[直播能力] 无人机：', ele.sn, '相机列表：', ele.cameras_list)
      dronePara.droneList.push({ label: (ele.name || '无人机') + '-' + ele.sn, value: ele.sn, more: ele.cameras_list })
    })
    setStatus('直播能力已刷新，无人机数量：' + dronePara.droneList.length)
    // 默认自动选中第一架在线飞机，同时级联填 camera / video / lens
    if (dronePara.droneList.length > 0) {
      dronePara.droneSelected = dronePara.droneList[0].value
      onDroneChange(dronePara.droneSelected)
    }
  } catch (err: any) {
    setStatus('获取直播能力失败：' + (err?.message || err))
  }
}

const onDroneChange = (value: any) => {
  dronePara.cameraList = []
  dronePara.videoList = []
  dronePara.lensList = []
  dronePara.cameraSelected = undefined
  dronePara.videoSelected = undefined
  dronePara.lensSelected = undefined
  const item = dronePara.droneList.find(d => d.value === value)
  if (!item || !item.more) return
  item.more.forEach((ele: any) => {
    dronePara.cameraList.push({ label: ele.name || ele.index, value: ele.index, more: ele.videos_list })
  })
  // 只有一个相机直接自动选上，避免用户再点一次
  if (dronePara.cameraList.length === 1) {
    dronePara.cameraSelected = dronePara.cameraList[0].value
    onCameraChange(dronePara.cameraSelected)
  }
}

const onCameraChange = (value: any) => {
  dronePara.videoSelected = undefined
  dronePara.lensSelected = undefined
  dronePara.videoList = []
  dronePara.lensList = []
  const item = dronePara.cameraList.find(c => c.value === value)
  if (!item || !item.more) return
  item.more.forEach((ele: any) => {
    dronePara.videoList.push({ label: ele.type, value: ele.index, more: ele.switch_video_types })
  })
  if (dronePara.videoList.length === 0) return
  const firstVideo: SelectOption = dronePara.videoList[0]
  dronePara.videoSelected = firstVideo.value
  dronePara.lensList = firstVideo.more
  dronePara.lensSelected = firstVideo.label
  dronePara.isDockLive = (dronePara.lensList?.length || 0) > 0
}

const onClaritySelect = (val: any) => {
  dronePara.claritySelected = val
}

const onStart = async () => {
  if (!state.configLoaded) {
    message.error('旧版播放入口已下线，请使用 WebRTC/ZLM 直播面板')
    return
  }
  if (dronePara.droneSelected == null || dronePara.cameraSelected == null) {
    message.warn('请先选择无人机和相机')
    return
  }

  livePara.videoId =
    dronePara.droneSelected + '/' +
    dronePara.cameraSelected + '/' +
    (dronePara.videoSelected || nonSwitchable + '-0')

  setStatus('旧版播放入口已下线，请使用 WebRTC/ZLM 直播面板')
}

const onStop = async () => {
  if (!livePara.videoId) {
    return
  }
  try {
    const res = await stopLivestream({ video_id: livePara.videoId })
    if (res.code === 0) {
      livePara.liveState = false
      dronePara.lensSelected = ''
      setStatus('直播已停止')
    } else {
      setStatus('停止直播被拒绝：' + (res.message || '代码 ' + res.code))
    }
  } catch (err: any) {
    setStatus('停止直播出错：' + (err?.message || err))
  }
}

const onUpdateQuality = async () => {
  if (!livePara.liveState) {
    message.info('请先启动直播')
    return
  }
  message.info('旧版播放入口已下线，请使用 WebRTC/ZLM 直播面板')
}

const onSwitchLens = async () => {
  message.info('旧版播放入口已下线，请使用 WebRTC/ZLM 直播面板')
}

let playbackPoll: ReturnType<typeof setInterval> | null = null

onMounted(async () => {
  await disableLegacyPlaybackConfig()
  await onRefresh()
  // 保留 DOM 轮询，避免 Pilot 发起的旧播放器节点状态无法及时刷新。
  playbackPoll = setInterval(async () => {
    state.remotePlaying = state.videoAttached
  }, 1000)
})

watch(summaryState, value => {
  emit('state-change', value)
}, { immediate: true })

onBeforeUnmount(async () => {
  if (playbackPoll) {
    clearInterval(playbackPoll)
    playbackPoll = null
  }
  if (livePara.liveState && livePara.videoId) {
    try {
      await stopLivestream({ video_id: livePara.videoId })
    } catch (e) {
      // best-effort cleanup
    }
  }
})
</script>

<style lang="scss" scoped>
.workspace-livestream-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  width: 100%;
  background: #f6f8fa;

  .header {
    padding: 12px 16px;
    font-size: 16px;
    font-weight: 600;
    border-bottom: 1px solid #e4e7eb;
    background: #ffffff;
  }

  .panel-body {
    flex: 1;
    display: flex;
    flex-direction: column;
    padding: 16px;
    overflow: auto;
  }

  .player-frame {
    display: flex;
    justify-content: center;
    align-items: center;
    margin-bottom: 16px;
  }

  .player-aspect {
    position: relative;
    width: 100%;
    max-width: 960px;
    aspect-ratio: 16 / 9;
    background: #000;
    border-radius: 4px;
    overflow: hidden;
  }

  .player-surface {
    position: absolute;
    inset: 0;
  }

  .player-overlay {
    position: absolute;
    inset: 0;
    display: flex;
    justify-content: center;
    align-items: center;
    color: #d9dde3;
    font-size: 14px;
    background: rgba(0, 0, 0, 0.4);

    &.error {
      color: #ff7875;
    }

    &.idle {
      color: #d9dde3;
    }
  }

  .live-badge {
    position: absolute;
    top: 12px;
    left: 12px;
    display: flex;
    align-items: center;
    padding: 2px 10px;
    background: rgba(0, 0, 0, 0.55);
    color: #fff;
    font-size: 12px;
    font-weight: 600;
    letter-spacing: 0.5px;
    border-radius: 2px;

    .live-dot {
      width: 8px;
      height: 8px;
      background: #ff4d4f;
      border-radius: 50%;
      margin-right: 6px;
      animation: livePulse 1.6s ease-in-out infinite;
    }

    .live-source {
      margin-left: 6px;
      font-weight: 400;
      opacity: 0.8;
    }
  }

  @keyframes livePulse {
    0%, 100% { opacity: 1; }
    50% { opacity: 0.35; }
  }

  .hud-overlay {
    position: absolute;
    left: 12px;
    bottom: 12px;
    z-index: 10;
    display: flex;
    flex-direction: column;
    gap: 4px;
    padding: 8px 12px;
    background: rgba(0, 0, 0, 0.55);
    border-radius: 4px;
    color: #e6e9ef;
    font-size: 12px;
    font-variant-numeric: tabular-nums;
    pointer-events: none;
    max-width: 85%;

    .hud-row {
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

    .hud-item {
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

  .controls {
    display: flex;
    flex-direction: column;
    gap: 8px;

    .row {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px;
    }

    .lens-row {
      padding: 8px 0;
    }

    .lens-label {
      margin-right: 8px;
    }

    .actions {
      padding-top: 8px;
    }

    .status {
      padding: 8px 12px;
      background: #fff;
      border: 1px solid #e4e7eb;
      border-radius: 4px;
      font-size: 12px;
      color: #4d5560;
      min-height: 32px;
    }
  }

  .ml10 {
    margin-left: 10px;
  }

  &.variant-cockpit {
    height: auto;
    min-height: 100%;
    background: transparent;
    color: #eaf5ff;

    .panel-body {
      padding: 0;
      gap: 14px;
      overflow: visible;
    }

    .player-frame {
      margin-bottom: 0;
    }

    .player-aspect {
      max-width: none;
      border-radius: 24px;
      border: 1px solid rgba(113, 179, 255, 0.18);
      box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.04);
      background:
        radial-gradient(circle at 50% 18%, rgba(44, 131, 206, 0.16), transparent 36%),
        linear-gradient(180deg, rgba(7, 18, 33, 0.94), rgba(4, 10, 19, 0.98));
    }

    .player-overlay {
      background: rgba(3, 9, 18, 0.56);
      color: #d7e9ff;

      &.error {
        color: #ff9da6;
      }
    }

    .live-badge {
      top: 16px;
      left: 16px;
      border-radius: 999px;
      background: rgba(7, 18, 33, 0.72);
      border: 1px solid rgba(255, 255, 255, 0.08);
    }

    .hud-overlay {
      left: 16px;
      bottom: 16px;
      border-radius: 14px;
      background: rgba(3, 9, 18, 0.64);
      border: 1px solid rgba(255, 255, 255, 0.08);
    }

    .controls {
      gap: 10px;

      .row {
        gap: 10px;
      }

      .status {
        background: rgba(255, 255, 255, 0.04);
        border-color: rgba(255, 255, 255, 0.08);
        color: #9fb3c7;
      }
    }
  }
}
</style>
