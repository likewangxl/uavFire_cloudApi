<template>
  <div class="elevation-profile" v-if="plannerUi.profileOpen && routeWaypoints.length >= 2">
    <div class="ep-head">
      <span>高度剖面 <small class="ep-dim">绿=航线 棕=地形(含植被冠层)</small></span>
      <span class="ep-dim" v-if="baselineLabel">{{ baselineLabel }}</span>
      <span class="ep-dim ep-warn" v-if="terrainMissing">地形数据缺失</span>
      <a-button type="text" size="small" class="ep-toggle" @click="setProfileOpen(false)">收起</a-button>
    </div>
    <svg :viewBox="`0 0 ${W} ${H}`" preserveAspectRatio="none" class="ep-svg">
      <polygon v-if="terrainPath" :points="terrainPolygon" fill="rgba(146,116,77,.3)" />
      <polyline v-if="terrainPath" :points="terrainPath" fill="none" stroke="#92744d" stroke-width="1.5" />
      <polyline v-if="routePath" :points="routePath" fill="none" stroke="#43d675" stroke-width="2" />
      <circle v-for="p in routeDots" :key="p.idx" :cx="p.x" :cy="p.y" r="3" fill="#43d675" />
    </svg>
  </div>
  <a-button
    v-else-if="routeWaypoints.length >= 2"
    class="ep-reopen"
    size="small"
    @click="setProfileOpen(true)">
    高度剖面
  </a-button>
</template>

<script lang="ts" setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { getPlanningStateRaw } from '/@/hooks/use-wayline-planning'
import { setProfileOpen, usePlannerUi } from '/@/hooks/use-planner-ui'
import { getTerrainElevations } from '/@/api/terrain'
// @ts-ignore .mjs 共用模块
import { sampleRoutePoints } from './planner-utils.mjs'

const W = 800
const H = 110
const PAD = 8

const planningState = getPlanningStateRaw()
const plannerUi = usePlannerUi()

// 与覆盖物同口径：预览优先
const routeWaypoints = computed(() =>
  planningState.previewWaypoints.length > 0 ? planningState.previewWaypoints : planningState.waypoints)

interface SamplePoint { wgsLng: number; wgsLat: number; distM: number; waypointIndex: number }

const sampledPoints = ref<SamplePoint[]>([])
const terrainElevations = ref<(number | null)[]>([])
const baselineElev = ref<number | null>(null)
const baselineLabel = ref('')
const terrainMissing = ref(false)
let debounceTimer: number | null = null
let requestSeq = 0

watch(
  () => routeWaypoints.value.map(w => `${w.wgsLng},${w.wgsLat},${w.height}`).join('|'),
  () => {
    if (debounceTimer) window.clearTimeout(debounceTimer)
    debounceTimer = window.setTimeout(refreshTerrain, 500)
  },
  { immediate: true },
)
onBeforeUnmount(() => { if (debounceTimer) window.clearTimeout(debounceTimer) })

async function refreshTerrain () {
  const wps = routeWaypoints.value
  if (wps.length < 2) {
    sampledPoints.value = []
    terrainElevations.value = []
    return
  }
  // 自适应步长保证 ≤499 点，外加 1 个基准点 = 单次请求 ≤500
  const samples: SamplePoint[] = sampleRoutePoints(wps, { maxPoints: 499, minStepM: 30 })
  // 基准点（设计 v2，与 H 标记一致）：优先飞机当前位置，否则 1 号航点正下方地面
  const fp = planningState.flightPosition
  const useAircraft = !!(fp && Number.isFinite(fp.wgsLng) && Number.isFinite(fp.wgsLat))
  const basePoint = useAircraft
    ? { lat: fp!.wgsLat as number, lng: fp!.wgsLng as number }
    : { lat: wps[0].wgsLat, lng: wps[0].wgsLng }
  baselineLabel.value = useAircraft ? '基准: 飞机当前位置(近似)' : '基准: 1号航点地面'
  const seq = ++requestSeq
  try {
    const elevations = await getTerrainElevations([
      basePoint,
      ...samples.map(s => ({ lat: s.wgsLat, lng: s.wgsLng })),
    ])
    if (seq !== requestSeq) return // 过期响应丢弃
    baselineElev.value = elevations[0]
    terrainElevations.value = elevations.slice(1)
    sampledPoints.value = samples
    terrainMissing.value = elevations.slice(1).every(e => e === null)
  } catch {
    if (seq !== requestSeq) return
    baselineElev.value = null
    terrainElevations.value = []
    sampledPoints.value = samples
    terrainMissing.value = true
  }
}

function relTerrain (): (number | null)[] {
  const base = baselineElev.value
  if (base == null) return terrainElevations.value.map(() => null)
  return terrainElevations.value.map(e => (e == null ? null : e - base))
}

const scale = computed(() => {
  const samples = sampledPoints.value
  const totalM = samples.length ? samples[samples.length - 1].distM : 1
  const heights = routeWaypoints.value.map(w => w.height)
  const terrainRel = relTerrain().filter((v): v is number => v !== null)
  const all = [...heights, ...terrainRel, 0]
  const maxY = Math.max(...all) + 10
  const minY = Math.min(...all) - 5
  return {
    x: (distM: number) => PAD + (distM / (totalM || 1)) * (W - 2 * PAD),
    y: (val: number) => H - PAD - ((val - minY) / (maxY - minY || 1)) * (H - 2 * PAD),
  }
})

// 航点投影到采样点的里程轴（waypointIndex>=0 的采样点即航点本身）
const routePath = computed(() => {
  const wps = routeWaypoints.value
  const out: string[] = []
  sampledPoints.value.forEach(s => {
    if (s.waypointIndex >= 0) {
      const wp = wps[s.waypointIndex]
      if (wp) out.push(`${scale.value.x(s.distM)},${scale.value.y(wp.height)}`)
    }
  })
  return out.join(' ')
})

const routeDots = computed(() =>
  sampledPoints.value
    .filter(s => s.waypointIndex >= 0)
    .map(s => ({
      idx: s.waypointIndex,
      x: scale.value.x(s.distM),
      y: scale.value.y(routeWaypoints.value[s.waypointIndex]?.height ?? 0),
    })))

const terrainPath = computed(() => {
  const rel = relTerrain()
  if (!rel.length || rel.every(v => v === null)) return ''
  return sampledPoints.value
    .map((s, i) => (rel[i] == null ? null : `${scale.value.x(s.distM)},${scale.value.y(rel[i] as number)}`))
    .filter(Boolean)
    .join(' ')
})

const terrainPolygon = computed(() => {
  if (!terrainPath.value || !sampledPoints.value.length) return ''
  const first = sampledPoints.value[0]
  const last = sampledPoints.value[sampledPoints.value.length - 1]
  return `${scale.value.x(first.distM)},${H - PAD} ${terrainPath.value} ${scale.value.x(last.distM)},${H - PAD}`
})
</script>

<style lang="scss" scoped>
.elevation-profile {
  position: absolute;
  left: 50%;
  transform: translateX(-50%);
  width: 680px; // 固定宽度居中，不顶到两侧面板；航点多时横轴自动压缩
  max-width: calc(100% - 24px);
  bottom: 64px; // 避开底部工具栏
  height: 140px;
  background: rgba(13, 17, 23, 0.93);
  border: 1px solid #2c3a4f;
  border-radius: 6px;
  padding: 6px 10px;
  pointer-events: auto;
  color: #cfd8e3;
  z-index: 25;
  .ep-head {
    display: flex;
    justify-content: space-between;
    align-items: center;
    gap: 10px;
    font-size: 12px;
    color: #e8eef6;
  }
  .ep-dim {
    color: #7d8ca0;
    font-size: 11px;
  }
  .ep-warn {
    color: #d4a017;
  }
  .ep-toggle {
    color: #8ca0b8;
    padding: 0 4px;
  }
  .ep-svg {
    width: 100%;
    height: calc(100% - 24px);
  }
}
.ep-reopen {
  position: absolute;
  right: 12px;
  bottom: 64px;
  pointer-events: auto;
  z-index: 25;
}
</style>
