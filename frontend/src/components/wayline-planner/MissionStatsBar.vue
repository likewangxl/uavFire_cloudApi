<template>
  <div class="mission-stats-bar" v-if="stats.count > 0">
    <span v-if="planningState.previewTitle" class="stats-title">{{ planningState.previewTitle }}</span>
    <span>里程 <b>{{ formatDistance(stats.distanceM) }}</b></span>
    <span>预计 <b>{{ formatDuration(stats.durationS) }}</b></span>
    <span>航点 <b>{{ stats.count }}</b></span>
  </div>
</template>

<script lang="ts" setup>
import { computed } from 'vue'
import { getPlanningStateRaw } from '/@/hooks/use-wayline-planning'
import { computeRouteStats } from './planner-utils.mjs'

const planningState = getPlanningStateRaw()

// 与 store 的 routeWaypoints 同口径：预览优先
const stats = computed(() => {
  const wps = planningState.previewWaypoints.length > 0 ? planningState.previewWaypoints : planningState.waypoints
  return computeRouteStats(wps, planningState.maxSpeed)
})

function formatDistance (m: number) {
  return m >= 1000 ? `${(m / 1000).toFixed(2)}km` : `${Math.round(m)}m`
}
function formatDuration (s: number) {
  const min = Math.floor(s / 60)
  const sec = Math.round(s % 60)
  return min > 0 ? `${min}min${sec}s` : `${sec}s`
}
</script>

<style lang="scss" scoped>
.mission-stats-bar {
  position: absolute;
  top: 12px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 4px 18px;
  background: rgba(13, 17, 23, 0.93);
  border: 1px solid #2c3a4f;
  border-radius: 16px;
  color: #cfd8e3;
  font-size: 13px;
  white-space: nowrap;
  pointer-events: auto;
  z-index: 30;
  b {
    color: #fff;
  }
  .stats-title {
    max-width: 160px;
    overflow: hidden;
    text-overflow: ellipsis;
    color: #7d8ca0;
  }
}
</style>
