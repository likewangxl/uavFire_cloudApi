<template>
  <div class="simulation-bar" v-if="plannerUi.simulating">
    <a-button size="small" type="primary" @click="togglePlay">{{ playing ? '暂停' : '播放' }}</a-button>
    <a-slider
      class="sim-slider"
      :min="0"
      :max="Math.max(1, Math.round(timeline.totalS * 10) / 10)"
      :step="0.1"
      :value="plannerUi.simulationTimeS"
      :tip-formatter="formatT"
      @change="(v: number) => setSimulationTime(v)" />
    <a-select
      size="small"
      :value="plannerUi.simulationSpeedX"
      style="width: 64px"
      @change="(v: any) => setSimulationSpeed(Number(v))">
      <a-select-option v-for="x in [1, 2, 4, 8]" :key="x" :value="x">{{ x }}x</a-select-option>
    </a-select>
    <span class="sim-time">{{ formatT(plannerUi.simulationTimeS) }} / {{ formatT(timeline.totalS) }}</span>
    <a-button size="small" danger @click="onExit">退出预演</a-button>
  </div>
</template>

<script lang="ts" setup>
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { getPlanningStateRaw } from '/@/hooks/use-wayline-planning'
import { setSimulationSpeed, setSimulationTime, stopSimulation, usePlannerUi } from '/@/hooks/use-planner-ui'
// @ts-ignore .mjs 共用模块
import { buildSimulationTimeline } from './planner-utils.mjs'

const planningState = getPlanningStateRaw()
const plannerUi = usePlannerUi()
const playing = ref(false)
let raf = 0
let lastTs = 0

const timeline = computed(() => buildSimulationTimeline(planningState.waypoints, planningState.maxSpeed))

function tick (ts: number) {
  if (!playing.value) return
  const dt = lastTs ? (ts - lastTs) / 1000 : 0
  lastTs = ts
  const next = plannerUi.simulationTimeS + dt * plannerUi.simulationSpeedX
  if (next >= timeline.value.totalS) {
    setSimulationTime(timeline.value.totalS)
    playing.value = false
    return
  }
  setSimulationTime(next)
  raf = requestAnimationFrame(tick)
}

function togglePlay () {
  playing.value = !playing.value
  lastTs = 0
  if (playing.value) {
    if (plannerUi.simulationTimeS >= timeline.value.totalS) setSimulationTime(0)
    raf = requestAnimationFrame(tick)
  }
}

function onExit () {
  playing.value = false
  stopSimulation()
}

function formatT (s: number) {
  const m = Math.floor(s / 60)
  return `${m}:${String(Math.round(s % 60)).padStart(2, '0')}`
}

// 执行真实任务时强制退出预演（互斥）
watch(() => planningState.executing, (executing) => {
  if (executing && plannerUi.simulating) onExit()
})
watch(() => plannerUi.simulating, (on) => {
  if (!on) {
    playing.value = false
    cancelAnimationFrame(raf)
  }
})
onBeforeUnmount(() => cancelAnimationFrame(raf))
</script>

<style lang="scss" scoped>
.simulation-bar {
  position: absolute;
  bottom: 64px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 10px;
  background: rgba(13, 17, 23, 0.93);
  border: 1px solid #2c3a4f;
  border-radius: 6px;
  padding: 6px 12px;
  pointer-events: auto;
  color: #cfd8e3;
  z-index: 31;
  .sim-slider {
    width: 220px;
    margin: 0;
  }
  .sim-time {
    font-size: 12px;
    color: #7d8ca0;
    white-space: nowrap;
  }
}
</style>
