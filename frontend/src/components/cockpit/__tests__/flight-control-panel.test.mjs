import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../../..')

function readSource (path) {
  return readFileSync(resolve(root, path), 'utf8')
}

test('leadership cockpit renders the fire monitor flight panel inside the player stage', () => {
  const cockpitSource = readSource('src/pages/page-web/projects/leadership-cockpit.vue')

  assert.match(
    cockpitSource,
    /import CockpitFlightControlPanel from '\/@\/components\/cockpit\/CockpitFlightControlPanel\.vue'/,
    'cockpit page should import the reusable flight control panel'
  )
  assert.match(
    cockpitSource,
    /<div class="dual-stream-player-stage">[\s\S]*?<CockpitFlightControlPanel[\s\S]*?class="fire-monitor-flight-panel"[\s\S]*?:target="selectedFireMonitorTarget"[\s\S]*?:msdk-device="selectedFireMonitorMsdkDevice"[\s\S]*?:osd="selectedFireMonitorOsd"[\s\S]*?<\/div>/,
    'fire monitor panel should be mounted inside the live player so fullscreen keeps it visible'
  )
  assert.match(
    cockpitSource,
    /v-if="activeVisualTab === 'map'"\s+class="map-kpi-grid"/,
    'the old lower KPI grid should stay on the map tab only'
  )
  assert.doesNotMatch(cockpitSource, /当前约束/, 'fire monitor live view should not render the lower current constraint block')
  assert.match(cockpitSource, /画面温度 \$\{formatThermalTemperature/, 'temperature HUD chip should use the operator-facing label')
  assert.doesNotMatch(cockpitSource, /中心温度/, 'temperature HUD chip should not use the old center-temperature label')
})

test('cockpit flight control panel reuses MSDK commands from TSA monitor controls', () => {
  const source = readSource('src/components/cockpit/CockpitFlightControlPanel.vue')

  assert.match(source, /sendMsdkCommand/, 'panel should use the existing MSDK command API')
  for (const command of [
    'takeoff',
    'land',
    'hover',
    'virtual_stick',
    'emergency_stop',
    'return_home',
    'cancel_return_home',
    'stop_fly_to_point',
    'fly_to_point',
    'gimbal_reset',
    'gimbal_rotate',
    'camera_start_photo',
    'camera_start_record',
    'camera_stop_record',
    'camera_stream_source',
    'camera_zoom',
    'night_scene',
    'laser_fill_light'
  ]) {
    assert.match(source, new RegExp(`['"]${command}['"]`), `panel should expose ${command}`)
  }
  assert.match(
    source,
    /AXIS_DISTANCE_MIN_METERS[\s\S]*AXIS_DISTANCE_MAX_METERS[\s\S]*getVerticalCommandDurationMs/,
    'distance controls should reuse the same displacement policy as TSA'
  )
})

test('cockpit flight control panel uses a collapsible console drawer layout', () => {
  const source = readSource('src/components/cockpit/CockpitFlightControlPanel.vue')

  assert.match(source, /position:\s*absolute;[\s\S]*?bottom:\s*14px;/, 'panel should be a bottom HUD overlay in the player')
  assert.match(source, /max-height:\s*min\(248px,\s*calc\(100vh - 180px\)\)/, 'expanded HUD drawer should be compact and height-limited')
  assert.match(source, /border:\s*2px solid rgba\(95,\s*165,\s*255,\s*0\.48\)/, 'expanded HUD should reuse the infrared preview border tone')
  assert.match(source, /rgba\(4,\s*18,\s*31,\s*0\.3\)/, 'expanded HUD should use a transparent cockpit-themed surface')
  assert.match(source, /class="flight-drawer-tab"/, 'panel should expose a drawer tab for expand and collapse')
  assert.match(source, /panelExpanded = !panelExpanded/, 'drawer tab should toggle the console body')
  assert.match(source, /class="joystick-ring"/, 'panel should render a circular flight joystick')
  assert.match(source, /class="gimbal-pad"/, 'panel should render a gimbal control pad')
  assert.match(source, /class="command-btn danger strong"[\s\S]*急停/, 'emergency stop should remain prominent in the right command area')
  assert.doesNotMatch(source, /class="flight-panel-card"/, 'panel should not use the old card grid layout')
  for (const restoredControl of ['视频跟踪', 'AR标签', '云台居中', 'WEBRTC', '拍照', '录像']) {
    assert.match(source, new RegExp(restoredControl), `${restoredControl} should be available in the restored flight drawer`)
  }
  for (const removedRcCopy of ['遥控器', 'rc ·', 'FC100 离线', '获取控制权']) {
    assert.doesNotMatch(source, new RegExp(removedRcCopy), `${removedRcCopy} should not be shown in the fire monitor drawer`)
  }
  assert.match(source, /当前设备\/负载不支持该能力/, 'disabled backend capabilities should show an operator-facing warning')
})

test('cockpit flight control panel gates backend-backed HUD controls on capabilities', () => {
  const source = readSource('src/components/cockpit/CockpitFlightControlPanel.vue')

  assert.match(source, /backendViewModeCommands/, 'backend-backed HUD modes should be described explicitly')
  assert.match(source, /laserFillLight/, 'laser fill light should be exposed as a backend-backed capability')
  assert.match(source, /laser_fill_light/, 'laser fill light should send the implemented backend command')
  assert.match(source, /:disabled="mode\.disabled"/, 'backend-backed HUD mode buttons should disable when capability is unavailable')
  assert.match(source, /const sent = await runBackendViewMode/, 'backend-backed HUD toggles should wait for backend command success')
  assert.match(source, /if \(sent\) \{\s*viewState\[key\] = nextEnabled/, 'backend-backed HUD state should only change after command enqueue succeeds')
})

test('cockpit flight control panel only flips recording state after backend accepts command', () => {
  const source = readSource('src/components/cockpit/CockpitFlightControlPanel.vue')

  assert.match(source, /async function runCommand[\s\S]*Promise<boolean>/, 'command helper should report enqueue success to callers')
  assert.match(source, /const sent = await runCommand\(nextCommand, 'cameraRecord'\)/, 'record toggle should wait for the backend command result')
  assert.match(source, /if \(sent\) \{\s*recording\.value = !recording\.value/, 'recording UI should not flip after a failed backend enqueue')
})
