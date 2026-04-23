import test from 'node:test'
import assert from 'node:assert/strict'

import {
  OFFICIAL_TAKEOFF_TARGET_HEIGHT,
  OFFICIAL_TAKEOFF_STAGE1_OFFSET_DEG,
  OFFICIAL_TAKEOFF_STAGE2_OFFSET_DEG,
  buildOfficialTakeoffPlan,
} from '../src/pages/page-web/projects/official-takeoff-flow.mjs'

test('buildOfficialTakeoffPlan restores the 50m south-then-north route', () => {
  const plan = buildOfficialTakeoffPlan({
    latitude: 34.658638,
    longitude: 109.34058,
    absoluteHeight: 350.101,
  })

  assert.equal(OFFICIAL_TAKEOFF_TARGET_HEIGHT, 50)
  assert.equal(plan.stage1.targetLatitude, 34.658638 + OFFICIAL_TAKEOFF_STAGE1_OFFSET_DEG)
  assert.equal(plan.stage1.targetLongitude, 109.34058)
  assert.equal(plan.stage1.targetHeight, 400.101)
  assert.equal(plan.stage1.securityTakeoffHeight, 50)
  assert.equal(plan.stage1.commanderFlightHeight, 50)
  assert.equal(plan.stage2South.targetLatitude, 34.658638 - OFFICIAL_TAKEOFF_STAGE2_OFFSET_DEG)
  assert.equal(plan.stage2South.targetLongitude, 109.34058)
  assert.equal(plan.stage2South.targetHeight, 400.101)
  assert.equal(plan.stage2North.targetLatitude, 34.658638)
  assert.equal(plan.stage2North.targetLongitude, 109.34058)
  assert.equal(plan.stage2North.targetHeight, 400.101)
})
