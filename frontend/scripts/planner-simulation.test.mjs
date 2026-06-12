import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const bar = readFileSync(new URL('../src/components/wayline-planner/SimulationBar.vue', import.meta.url), 'utf8')
const overlays = readFileSync(new URL('../src/hooks/use-planner-overlays.ts', import.meta.url), 'utf8')
const toolbar = readFileSync(new URL('../src/components/wayline-planner/PlannerToolbar.vue', import.meta.url), 'utf8')
const workspace = readFileSync(new URL('../src/components/wayline-planner/PlannerWorkspace.vue', import.meta.url), 'utf8')

test('simulation: 时间轴驱动 + 倍速 + 执行互斥 + 幻影飞机', () => {
  assert.match(bar, /buildSimulationTimeline/)
  assert.match(bar, /requestAnimationFrame/)
  assert.match(bar, /simulationSpeedX/)
  assert.match(bar, /planningState\.executing/)
  assert.match(overlays, /positionAtTime/)
  assert.match(overlays, /planner-sim-ghost/)
  assert.match(overlays, /clearSimGhost/)
  assert.match(toolbar, /startSimulation/)
  assert.match(toolbar, /planningState\.executing/)
  assert.match(workspace, /SimulationBar/)
})
