# Official Takeoff Two-Stage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Change official takeoff into a two-stage flow that uses current OSD latitude/longitude as the origin, climbs to 30 m first, then automatically sends `fly_to_point` 20 m north at 30 m.

**Architecture:** Extract the two-stage sequencing logic into a small pure helper module so altitude gating and target-point calculation can be tested outside the large Vue page. Then integrate that helper into `tsa.vue` with a page-local state machine that dispatches Stage 2 only once when telemetry shows the aircraft is ready.

**Tech Stack:** Vue 3, TypeScript, Vite, Node built-in test runner for pure helper verification

---

### Task 1: Add pure helper tests for two-stage flow decisions

**Files:**
- Create: `frontend/scripts/official-takeoff-flow.test.mjs`
- Create: `frontend/src/pages/page-web/projects/official-takeoff-flow.js`

- [ ] **Step 1: Write the failing test**

```js
import test from 'node:test'
import assert from 'node:assert/strict'

import {
  OFFICIAL_TAKEOFF_ALTITUDE_READY_THRESHOLD,
  OFFICIAL_TAKEOFF_NORTH_OFFSET_DEG,
  buildOfficialTakeoffPlan,
  shouldDispatchOfficialTakeoffStage2,
} from '../src/pages/page-web/projects/official-takeoff-flow.js'

test('buildOfficialTakeoffPlan keeps stage1 at origin and stage2 20m north at 30m', () => {
  const plan = buildOfficialTakeoffPlan({
    latitude: 34.658676,
    longitude: 109.3405,
    height: 12,
  })

  assert.equal(plan.stage1.targetLatitude, 34.658676)
  assert.equal(plan.stage1.targetLongitude, 109.3405)
  assert.equal(plan.stage1.targetHeight, 30)
  assert.equal(plan.stage2.targetLatitude, 34.658676 + OFFICIAL_TAKEOFF_NORTH_OFFSET_DEG)
  assert.equal(plan.stage2.targetLongitude, 109.3405)
  assert.equal(plan.stage2.targetHeight, 30)
})

test('shouldDispatchOfficialTakeoffStage2 returns false below altitude threshold', () => {
  const result = shouldDispatchOfficialTakeoffStage2({
    phase: 'waiting_altitude',
    stage2Dispatched: false,
    telemetry: {
      latitude: 34.658676,
      longitude: 109.3405,
      height: OFFICIAL_TAKEOFF_ALTITUDE_READY_THRESHOLD - 0.1,
      modeCode: 0,
      connected: true,
    },
  })

  assert.equal(result, false)
})

test('shouldDispatchOfficialTakeoffStage2 returns true once altitude threshold is reached', () => {
  const result = shouldDispatchOfficialTakeoffStage2({
    phase: 'waiting_altitude',
    stage2Dispatched: false,
    telemetry: {
      latitude: 34.658676,
      longitude: 109.3405,
      height: OFFICIAL_TAKEOFF_ALTITUDE_READY_THRESHOLD,
      modeCode: 0,
      connected: true,
    },
  })

  assert.equal(result, true)
})

test('shouldDispatchOfficialTakeoffStage2 returns false after stage2 already dispatched', () => {
  const result = shouldDispatchOfficialTakeoffStage2({
    phase: 'waiting_altitude',
    stage2Dispatched: true,
    telemetry: {
      latitude: 34.658676,
      longitude: 109.3405,
      height: 30,
      modeCode: 0,
      connected: true,
    },
  })

  assert.equal(result, false)
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node --test frontend/scripts/official-takeoff-flow.test.mjs`  
Expected: FAIL with module export or file-not-found errors because helper does not exist yet.

- [ ] **Step 3: Write minimal implementation**

```js
export const OFFICIAL_TAKEOFF_TARGET_HEIGHT = 30
export const OFFICIAL_TAKEOFF_SECURITY_HEIGHT = 30
export const OFFICIAL_TAKEOFF_MAX_SPEED = 5
export const OFFICIAL_TAKEOFF_RTH_ALTITUDE = 100
export const OFFICIAL_TAKEOFF_NORTH_OFFSET_DEG = 0.00018
export const OFFICIAL_TAKEOFF_ALTITUDE_READY_THRESHOLD = 28

export function buildOfficialTakeoffPlan ({ latitude, longitude }) {
  return {
    stage1: {
      targetLatitude: latitude,
      targetLongitude: longitude,
      targetHeight: OFFICIAL_TAKEOFF_TARGET_HEIGHT,
      securityTakeoffHeight: OFFICIAL_TAKEOFF_SECURITY_HEIGHT,
      maxSpeed: OFFICIAL_TAKEOFF_MAX_SPEED,
      rthAltitude: OFFICIAL_TAKEOFF_RTH_ALTITUDE,
    },
    stage2: {
      targetLatitude: latitude + OFFICIAL_TAKEOFF_NORTH_OFFSET_DEG,
      targetLongitude: longitude,
      targetHeight: OFFICIAL_TAKEOFF_TARGET_HEIGHT,
      maxSpeed: OFFICIAL_TAKEOFF_MAX_SPEED,
    },
  }
}

export function shouldDispatchOfficialTakeoffStage2 ({ phase, stage2Dispatched, telemetry }) {
  if (phase !== 'waiting_altitude' || stage2Dispatched) return false
  if (!telemetry?.connected) return false
  if (!Number.isFinite(telemetry?.latitude) || !Number.isFinite(telemetry?.longitude)) return false
  if (!Number.isFinite(telemetry?.height)) return false
  return telemetry.height >= OFFICIAL_TAKEOFF_ALTITUDE_READY_THRESHOLD
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `node --test frontend/scripts/official-takeoff-flow.test.mjs`  
Expected: PASS with 4 passing tests.

- [ ] **Step 5: Commit**

```bash
git add frontend/scripts/official-takeoff-flow.test.mjs frontend/src/pages/page-web/projects/official-takeoff-flow.js
git commit -m "test: cover official takeoff two-stage flow helper"
```

### Task 2: Integrate two-stage state machine into `tsa.vue`

**Files:**
- Modify: `frontend/src/pages/page-web/projects/tsa.vue`
- Modify: `frontend/src/api/drone-control/drone.ts`
- Reuse: `frontend/src/pages/page-web/projects/official-takeoff-flow.js`

- [ ] **Step 1: Write the failing integration behavior check**

Use the helper test from Task 1 as the contract and add console-level instrumentation in `tsa.vue`
that references the helper exports. The integration is considered incomplete until:

```ts
handleTakeoff()
```

captures origin latitude/longitude, stores phase `waiting_altitude`, and no longer computes the old:

```ts
const TARGET_LAT_OFFSET_DEG = 0.00015
const targetLat = latitude + TARGET_LAT_OFFSET_DEG
```

- [ ] **Step 2: Verify current page still contains the old single-stage behavior**

Run: `rg -n "TARGET_LAT_OFFSET_DEG|向北约 16 米|官方起飞指令已发送" frontend/src/pages/page-web/projects/tsa.vue`  
Expected: matches exist before the refactor.

- [ ] **Step 3: Write minimal integration code**

Add a page-local reactive state and import the helper:

```ts
import {
  OFFICIAL_TAKEOFF_ALTITUDE_READY_THRESHOLD,
  buildOfficialTakeoffPlan,
  shouldDispatchOfficialTakeoffStage2,
} from './official-takeoff-flow'

const officialTakeoffFlow = reactive({
  gatewaySn: '',
  aircraftSn: '',
  phase: 'idle' as 'idle' | 'waiting_altitude' | 'flying_forward' | 'completed' | 'failed',
  originLatitude: null as number | null,
  originLongitude: null as number | null,
  stage2Dispatched: false,
  startedAt: 0,
})
```

Refactor `handleTakeoff()` so Stage 1 uses current latitude/longitude directly:

```ts
const plan = buildOfficialTakeoffPlan({ latitude, longitude, height: Number(osd?.height) })

officialTakeoffFlow.gatewaySn = device.gateway.sn
officialTakeoffFlow.aircraftSn = device.sn
officialTakeoffFlow.originLatitude = latitude
officialTakeoffFlow.originLongitude = longitude
officialTakeoffFlow.phase = 'waiting_altitude'
officialTakeoffFlow.stage2Dispatched = false
officialTakeoffFlow.startedAt = Date.now()

return await postTakeoffToPoint(device.gateway.sn, {
  target_latitude: plan.stage1.targetLatitude,
  target_longitude: plan.stage1.targetLongitude,
  target_height: plan.stage1.targetHeight,
  security_takeoff_height: plan.stage1.securityTakeoffHeight,
  max_speed: plan.stage1.maxSpeed,
  rc_lost_action: LostControlActionInCommandFLight.HOVER,
  exit_wayline_when_rc_lost: WaylineLostControlActionInCommandFlight.EXEC_LOST_ACTION,
  rth_mode: ERthMode.SETTING,
  rth_altitude: plan.stage1.rthAltitude,
  commander_mode_lost_action: ECommanderModeLostAction.EXEC_LOST_ACTION,
  commander_flight_mode: ECommanderFlightMode.SETTING,
  commander_flight_height: plan.stage1.targetHeight
})
```

Add a watcher on device telemetry that dispatches Stage 2 once:

```ts
watch(
  () => deviceInfo.value[officialTakeoffFlow.aircraftSn],
  async (osd) => {
    if (!officialTakeoffFlow.aircraftSn || !osd) return
    const ready = shouldDispatchOfficialTakeoffStage2({
      phase: officialTakeoffFlow.phase,
      stage2Dispatched: officialTakeoffFlow.stage2Dispatched,
      telemetry: {
        latitude: Number(osd.latitude),
        longitude: Number(osd.longitude),
        height: Number(osd.height),
        modeCode: osd.mode_code,
        connected: osd.mode_code !== EModeCode.Disconnected,
      },
    })
    if (!ready) return

    const device = onlineDevices.data.find(d => d.sn === officialTakeoffFlow.aircraftSn)
    if (!device || !isCurrentRemoteGateway(device)) {
      officialTakeoffFlow.phase = 'failed'
      message.warning('官方起飞第二阶段未执行：云端遥控链路未连接。')
      return
    }

    const plan = buildOfficialTakeoffPlan({
      latitude: officialTakeoffFlow.originLatitude,
      longitude: officialTakeoffFlow.originLongitude,
      height: Number(osd.height),
    })

    officialTakeoffFlow.stage2Dispatched = true
    officialTakeoffFlow.phase = 'flying_forward'
    const res = await postFlyToPoint(device.gateway.sn, {
      max_speed: plan.stage2.maxSpeed,
      points: [{
        latitude: plan.stage2.targetLatitude,
        longitude: plan.stage2.targetLongitude,
        height: plan.stage2.targetHeight,
      }]
    })
    if (res.code === 0) {
      officialTakeoffFlow.phase = 'completed'
      message.success('官方起飞第二阶段已发送：向北 20 米，保持 30 米高度。')
    } else {
      officialTakeoffFlow.phase = 'failed'
      message.warning('官方起飞已完成爬升，但第二阶段前飞发送失败。')
    }
  }
)
```

- [ ] **Step 4: Run focused checks**

Run: `rg -n "TARGET_LAT_OFFSET_DEG|向北约 16 米" frontend/src/pages/page-web/projects/tsa.vue`  
Expected: no matches.

Run: `rg -n "officialTakeoffFlow|buildOfficialTakeoffPlan|shouldDispatchOfficialTakeoffStage2" frontend/src/pages/page-web/projects/tsa.vue`  
Expected: integration matches exist.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/page-web/projects/tsa.vue frontend/src/api/drone-control/drone.ts
git commit -m "feat: split official takeoff into climb and forward stages"
```

### Task 3: Verify build and runtime safety

**Files:**
- Verify: `frontend/src/pages/page-web/projects/tsa.vue`
- Verify: `frontend/src/pages/page-web/projects/official-takeoff-flow.js`
- Verify: `frontend/scripts/official-takeoff-flow.test.mjs`

- [ ] **Step 1: Run helper tests**

Run: `node --test frontend/scripts/official-takeoff-flow.test.mjs`  
Expected: PASS.

- [ ] **Step 2: Run frontend build**

Run: `cd frontend && npm run build`  
Expected: build succeeds; existing warnings are acceptable if unrelated to this feature.

- [ ] **Step 3: Smoke-check final behavior in code**

Run: `rg -n "目标位置：|向北约 20 米|等待飞机爬升至 30 米|第二阶段" frontend/src/pages/page-web/projects/tsa.vue`  
Expected: confirmation and status copy match the new two-stage flow.

- [ ] **Step 4: Commit verification-ready state**

```bash
git add docs/superpowers/specs/2026-04-19-official-takeoff-two-stage-design.md docs/superpowers/plans/2026-04-19-official-takeoff-two-stage.md frontend/scripts/official-takeoff-flow.test.mjs frontend/src/pages/page-web/projects/official-takeoff-flow.js frontend/src/pages/page-web/projects/tsa.vue
git commit -m "feat: add two-stage official takeoff flow"
```
