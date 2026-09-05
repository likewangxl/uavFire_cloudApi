import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../..')

function readSource (path) {
  return readFileSync(resolve(root, path), 'utf8')
}

test('new waypoints default to strict point arrival without turn damping', () => {
  const planningSource = readSource('hooks/use-wayline-planning.ts')
  const drawerSource = readSource('components/wayline-planner/WaypointParamDrawer.vue')

  assert.match(planningSource, /DEFAULT_WAYPOINT_TURN_MODE[^\n]+toPointAndStopWithDiscontinuityCurvature/)
  assert.match(planningSource, /turnMode: DEFAULT_WAYPOINT_TURN_MODE,\s*turnDamping: 0/g)
  assert.match(drawerSource, /默认严格过点/)
  assert.match(drawerSource, /严格过点（到点停）/)
  assert.match(drawerSource, /平滑通过（提前转弯）/)
  assert.match(drawerSource, /mode === 'toPointAndStopWithDiscontinuityCurvature'/)
})
