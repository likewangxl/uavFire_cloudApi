import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const src = readFileSync(new URL('../src/hooks/use-planner-ui.ts', import.meta.url), 'utf8')

test('planner ui hook exposes tab/drawer/profile/simulation state and setters', () => {
  assert.match(src, /activeTab:\s*'monitor'/)
  assert.match(src, /export function setPlannerTab/)
  assert.match(src, /export function setParamDrawerOpen/)
  assert.match(src, /export function setProfileOpen/)
  assert.match(src, /export function startSimulation/)
  assert.match(src, /export function stopSimulation/)
  assert.match(src, /export function setSimulationSpeed/)
  assert.match(src, /simulationSpeedX/)
})
