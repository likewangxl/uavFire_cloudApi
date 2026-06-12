// 航线规划页 UI 状态（页签/抽屉/剖面/预演）。与飞行 store(use-wayline-planning) 解耦：
// 这里只放纯界面状态，刷新即重置，不持久化。
import { reactive, readonly } from 'vue'

export type PlannerTab = 'monitor' | 'delivery'

const state = reactive({
  activeTab: 'monitor' as PlannerTab,
  paramDrawerOpen: false,
  profileOpen: true,
  simulating: false,
  simulationTimeS: 0,
  simulationSpeedX: 1,
})

export function usePlannerUi () {
  return readonly(state)
}

export function setPlannerTab (tab: PlannerTab) {
  state.activeTab = tab
}

export function setParamDrawerOpen (open: boolean) {
  state.paramDrawerOpen = open
}

export function setProfileOpen (open: boolean) {
  state.profileOpen = open
}

export function startSimulation () {
  state.simulating = true
  state.simulationTimeS = 0
}

export function stopSimulation () {
  state.simulating = false
  state.simulationTimeS = 0
}

export function setSimulationTime (timeS: number) {
  state.simulationTimeS = Math.max(0, timeS)
}

export function setSimulationSpeed (x: number) {
  state.simulationSpeedX = [1, 2, 4, 8].includes(x) ? x : 1
}

export function getPlannerUiRaw () {
  return state
}
