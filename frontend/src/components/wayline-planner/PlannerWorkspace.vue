<template>
  <div class="planner-overlay">
    <MissionStatsBar />
    <div class="planner-overlay-left">
      <WaypointListPanel />
      <AreaParamsPanel v-if="planningVisible && planningState.routeKind === 'area'" />
      <MissionParamsPanel v-if="planningVisible" />
    </div>
    <PlannerToolbar
      v-if="planningVisible"
      :can-execute="canExecute"
      :on-start-placing="onStartPlacing"
      :on-stop-placing="onStopPlacing"
      :on-start-execution="onStartExecution"
      :on-stop-execution="onStopExecution"
      :on-save="onSave" />
    <WaypointParamDrawer />
    <ElevationProfile />
    <SimulationBar />
    <slot />
  </div>
</template>

<script lang="ts" setup>
import { computed } from 'vue'
import MissionStatsBar from './MissionStatsBar.vue'
import WaypointListPanel from './WaypointListPanel.vue'
import MissionParamsPanel from './MissionParamsPanel.vue'
import AreaParamsPanel from './AreaParamsPanel.vue'
import PlannerToolbar from './PlannerToolbar.vue'
import WaypointParamDrawer from './WaypointParamDrawer.vue'
import ElevationProfile from './ElevationProfile.vue'
import SimulationBar from './SimulationBar.vue'
import { getPlanningStateRaw } from '/@/hooks/use-wayline-planning'

// 任务参数面板 + 底部工具条只在开始规划（布点中或已有航点编辑）时显示，平时不常驻
const planningState = getPlanningStateRaw()
const planningVisible = computed(() => planningState.active || planningState.waypoints.length > 0)

defineProps<{
  canExecute: boolean
  onStartPlacing:() => void
  onStopPlacing: () => void
  onStartExecution: () => void
  onStopExecution: () => void
  onSave: (saveAs: boolean) => void
}>()
</script>

<style lang="scss" scoped>
.planner-overlay {
  position: absolute;
  inset: 0;
  pointer-events: none;
}
.planner-overlay-left {
  position: absolute;
  top: 12px;
  left: 12px;
  bottom: 12px;
  width: 280px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  pointer-events: none;
  > * {
    pointer-events: auto;
  }
}
</style>
