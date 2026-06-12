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
    'navigation_light',
    'laser_fill_light'
  ]) {
    assert.match(source, new RegExp(`['"]${command}['"]`), `panel should expose ${command}`)
  }
  assert.match(
    source,
    /AXIS_DISTANCE_MIN_METERS[\s\S]*AXIS_DISTANCE_MAX_METERS[\s\S]*getVerticalCommandDurationMs/,
    'distance controls should reuse the same displacement policy as TSA'
  )
  assert.match(source, /function commandLabel/, 'command success toasts should use operator-facing Chinese labels')
  assert.match(source, /gimbal_rotate:\s*'云台旋转'/, 'gimbal rotate toast should be translated')
  assert.match(source, /\$\{commandLabel\(command\)\}指令已发送。/, 'generic command toast should render translated command labels')
  assert.doesNotMatch(source, /\$\{command\} 指令已发送。/, 'generic command toast should not expose backend command codes')
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

test('cockpit flight control panel exposes a zoom-out control bound to the implemented camera_zoom command', () => {
  const source = readSource('src/components/cockpit/CockpitFlightControlPanel.vue')

  assert.match(source, /@click="runZoomOut"/, 'a zoom-out button should be wired in the camera action row')
  assert.match(source, /function runZoomOut[\s\S]*?zoomRatio\.value - ZOOM_STEP/, 'zoom-out should step the tracked ratio down')
  assert.match(source, /Math\.max\(ZOOM_MIN_RATIO[\s\S]*?camera_zoom[\s\S]*?if \(sent\) \{\s*zoomRatio\.value = clamped/, 'zoom ratio should clamp to the agent KeyCameraZoomRatios floor and update only after backend accepts')
  assert.match(source, /zoomRatio <= ZOOM_MIN_RATIO/, 'zoom-out button should disable once the minimum ratio is reached')
})

test('cockpit flight control panel enlarges disc touch targets and adds press feedback', () => {
  const source = readSource('src/components/cockpit/CockpitFlightControlPanel.vue')

  assert.match(
    source,
    /\.axis\.north,\s*\.axis\.south,\s*\.axis\.west,\s*\.axis\.east,[\s\S]*?width:\s*36px;\s*height:\s*36px;/,
    'flight and gimbal disc directions should use enlarged hit areas'
  )
  assert.match(source, /touch-action:\s*manipulation;/, 'disc buttons should opt into immediate touch handling')
  assert.match(source, /\.axis:not\(\.center\):not\(:disabled\):active/, 'disc buttons should provide an active-press visual feedback')
})

test('cockpit flight control panel toolbar drops the redundant inline title', () => {
  const source = readSource('src/components/cockpit/CockpitFlightControlPanel.vue')

  assert.doesNotMatch(source, /class="console-title"/, 'the inline 飞行面板 toolbar title and its accent bar should be removed')
})

test('cockpit flight control panel zoom is a repeatable +/- stepper, not a fixed jump', () => {
  const source = readSource('src/components/cockpit/CockpitFlightControlPanel.vue')

  assert.match(source, /class="zoom-stepper"/, 'zoom controls should be a stepper group')
  assert.match(source, /@click="runZoomIn"/, 'stepper should expose a zoom-in step')
  assert.match(source, /@click="runZoomOut"/, 'stepper should expose a zoom-out step')
  assert.match(source, /function runZoomIn[\s\S]*?zoomRatio\.value \+ ZOOM_STEP/, 'zoom-in should increment by one step')
  assert.match(source, /function runZoomOut[\s\S]*?zoomRatio\.value - ZOOM_STEP/, 'zoom-out should decrement by one step')
  assert.doesNotMatch(source, /@click="runCameraZoom\(2\)"/, 'the old fixed 2x jump button should be gone')
  assert.match(source, /<span class="zoom-readout">\{\{ zoomRatio \}\}x<\/span>/, 'current ratio should be shown between the step buttons')
})

test('cockpit flight control panel reports the real agent execution result, not just enqueue', () => {
  const source = readSource('src/components/cockpit/CockpitFlightControlPanel.vue')

  assert.match(source, /import \{ sendMsdkCommand, getMsdkCommand/, 'panel should import the command result reader')
  assert.match(source, /async function awaitCommandResult/, 'panel should poll the backend for the terminal command result')
  assert.match(source, /TERMINAL_COMMAND_STATUSES/, 'terminal statuses should gate the result poll')
  assert.match(source, /if \(result\.status === 'APPLIED'\)/, 'success toast should require the agent to actually apply the command')
  assert.match(source, /飞机未执行该指令/, 'a real failure should surface the agent error to the operator')
})

test('cockpit flight control panel night light drives aircraft navigation LEDs', () => {
  const source = readSource('src/components/cockpit/CockpitFlightControlPanel.vue')

  assert.match(source, /command: 'navigation_light'[\s\S]*?capability: 'navigationLight'/, '夜航灯 should map to the aircraft navigation light command')
  assert.match(source, /navigation_light: '航行灯'/, 'navigation light should have an operator-facing label')
  assert.doesNotMatch(source, /command: 'night_scene'/, '夜航灯 should no longer trigger the camera night-scene command')
})

test('cockpit flight control panel renders dialogs and toasts inside the fullscreen element', () => {
  const source = readSource('src/components/cockpit/CockpitFlightControlPanel.vue')

  assert.match(source, /function popupContainer[\s\S]*?document\.fullscreenElement[\s\S]*?\|\| document\.body/, 'popups should target the active fullscreen element, falling back to body')
  assert.match(source, /Modal\.confirm\(\{[\s\S]*?getContainer: popupContainer/, 'danger confirm dialog must mount into the fullscreen container')
  assert.match(source, /notification\.success\(\{[^}]*getContainer: popupContainer/, 'success toast must mount into the fullscreen container')
  assert.match(source, /notification\.error\(\{[^}]*getContainer: popupContainer/, 'error toast must mount into the fullscreen container')
})

test('cockpit flight control panel gates decorative controls and surfaces coming-soon features', () => {
  const source = readSource('src/components/cockpit/CockpitFlightControlPanel.vue')

  assert.match(source, /const COMING_SOON_MODES: ViewModeKey\[\] = \['tracking', 'arLabel', 'referenceLine', 'measureArea'\]/, 'AR/参考线/测面/视频跟踪 should be flagged coming-soon')
  assert.match(source, /该功能后续开放/, 'coming-soon controls should notify the operator instead of silently toggling')
  assert.match(source, /const DECORATIVE_MODES: ViewModeKey\[\] = \['stealth'\]/, '隐藏模式 should be a disabled decorative control')
  assert.match(source, /DECORATIVE_MODES\.includes\(mode\.key\)/, 'decorative modes should be disabled in the toolbar')
  assert.match(source, /class="payload-row quality-row"[\s\S]*?disabled\n\s*title="画质切换功能后续开放"/, 'the quality row should be greyed out')
})
