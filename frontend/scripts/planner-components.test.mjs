import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const read = (p) => readFileSync(new URL(p, import.meta.url), 'utf8')
const statsBar = read('../src/components/wayline-planner/MissionStatsBar.vue')
const toolbar = read('../src/components/wayline-planner/PlannerToolbar.vue')
const drawer = read('../src/components/wayline-planner/WaypointParamDrawer.vue')
const wpList = read('../src/components/wayline-planner/WaypointListPanel.vue')
const missionParams = read('../src/components/wayline-planner/MissionParamsPanel.vue')
const workspace = read('../src/components/wayline-planner/PlannerWorkspace.vue')
const wayline = read('../src/pages/page-web/projects/wayline.vue')

test('stats bar uses shared computeRouteStats with preview-first source', () => {
  assert.match(statsBar, /computeRouteStats/)
  assert.match(statsBar, /planner-utils\.mjs/)
  assert.match(statsBar, /previewWaypoints/)
})

test('toolbar wires undo/clear/save/execute/simulation with exec guards', () => {
  assert.match(toolbar, /removeWaypoint/)
  assert.match(toolbar, /clearWaypoints/)
  assert.match(toolbar, /startSimulation/)
  assert.match(toolbar, /planningState\.executing/)
  assert.match(toolbar, /onSave\(true\)/)
})

test('drawer uses antd2 visible prop and full waypoint params', () => {
  assert.match(drawer, /:visible=/)
  assert.doesNotMatch(drawer, /v-model:open=/)
  assert.match(drawer, /updateWaypointField/)
  assert.match(drawer, /WaypointActionEditor/)
  assert.match(drawer, /turnDamping/)
  assert.match(drawer, /headingMode/)
})

test('waypoint list panel syncs selection with drawer', () => {
  assert.match(wpList, /selectWaypoint/)
  assert.match(wpList, /setParamDrawerOpen\(true\)/)
  assert.match(wpList, /moveWaypoint/)
})

test('mission params panel keeps task-level config incl. rthAltitude', () => {
  assert.match(missionParams, /setMissionConfig/)
  assert.match(missionParams, /rthAltitude/)
  assert.match(missionParams, /takeoffSecurityHeight/)
  assert.match(missionParams, /finishAction/)
})

test('wayline page teleports PlannerWorkspace and keeps mission monitor', () => {
  assert.match(workspace, /MissionStatsBar/)
  assert.match(workspace, /PlannerToolbar/)
  assert.match(wayline, /<PlannerWorkspace/)
  assert.match(wayline, /WaylineMissionMonitor/)
  assert.match(wayline, /wayline-planning-overlay-host/)
})
