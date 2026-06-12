<template>
  <div class="planner-overlay">
    <MissionStatsBar />
    <div class="planner-overlay-left">
      <WaypointListPanel />
      <MissionParamsPanel />
    </div>
    <PlannerToolbar
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
import MissionStatsBar from './MissionStatsBar.vue'
import WaypointListPanel from './WaypointListPanel.vue'
import MissionParamsPanel from './MissionParamsPanel.vue'
import PlannerToolbar from './PlannerToolbar.vue'
import WaypointParamDrawer from './WaypointParamDrawer.vue'
import ElevationProfile from './ElevationProfile.vue'
import SimulationBar from './SimulationBar.vue'

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
