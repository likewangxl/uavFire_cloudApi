import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import {
  buildDualStreamCandidateSns,
  buildLivePaneState,
  buildLivePlaybackKey,
  resolveAppliedFocusPreference
} from '../src/pages/page-web/projects/leadership-cockpit-live-layout.mjs'

const cockpitPath = new URL('../src/pages/page-web/projects/leadership-cockpit.vue', import.meta.url)
const panelPath = new URL('../src/components/WorkspaceLivestreamPanel.vue', import.meta.url)
const homePath = new URL('../src/pages/page-web/home.vue', import.meta.url)

const cockpitSource = readFileSync(cockpitPath, 'utf8')
const panelSource = readFileSync(panelPath, 'utf8')
const homeSource = readFileSync(homePath, 'utf8')

test('leadership cockpit owns livestream tab state and mounts a local player shell', () => {
  assert.match(cockpitSource, /primaryPlayerShell/)
  assert.match(cockpitSource, /previewPlayerShell/)
  assert.match(cockpitSource, /activeVisualTab/)
  assert.match(cockpitSource, /visualTabs/)
})

test('leadership cockpit keeps separate map and livestream panel state', () => {
  assert.match(cockpitSource, /mapKpis/)
  assert.match(cockpitSource, /livePaneState/)
  assert.match(cockpitSource, /liveHudItems/)
})

test('leadership cockpit switches map panel layout for livestream mode', () => {
  assert.match(cockpitSource, /'live-mode': activeVisualTab === 'live'/)
  assert.match(cockpitSource, /\.map-panel\.live-mode/)
  assert.match(cockpitSource, /\.livestream-stage\s*\{[\s\S]*overflow:\s*visible/)
})

test('leadership cockpit uses ZLM WebRTC API instead of legacy rtc play endpoint', () => {
  assert.match(cockpitSource, /index\/api\/webrtc/)
  assert.match(cockpitSource, /ZLMRTCClient/)
  assert.doesNotMatch(cockpitSource, /new jswebrtc\.Player/)
})

test('cockpit live video uses aspect-fit sizing instead of cropping', () => {
  assert.match(cockpitSource, /\.dual-stream-player-stage\s*\{[\s\S]*aspect-ratio:\s*16\s*\/\s*9/)
  assert.match(cockpitSource, /\.dual-stream-video\s*\{[\s\S]*object-fit:\s*contain/)
  assert.match(cockpitSource, /Object\.assign\(video\.style,[\s\S]*objectFit:\s*'contain'/)
  assert.doesNotMatch(cockpitSource, /objectFit:\s*'fill'/)
  assert.doesNotMatch(cockpitSource, /\.dual-stream-video\s*\{[\s\S]*object-fit:\s*cover/)
})

test('cockpit avoids duplicate playback when visible and thermal share one stream url', () => {
  assert.match(cockpitSource, /allowSharedThermalPreview:\s*true/)
  assert.match(cockpitSource, /livePaneState\.value\.preview\.url/)
  assert.match(cockpitSource, /mountPlayerInstance\(\s*livePaneState\.value\.preview\.url/)
  assert.match(cockpitSource, /focusAction/)
})

test('cockpit does not label visible-only playback as thermal when thermal url is missing', () => {
  const state = buildLivePaneState({
    visiblePlayUrl: 'webrtc://localhost/live/drone-0',
    thermalPlayUrl: '',
    primaryPreference: 'thermal',
    allowSharedThermalPreview: true
  })

  assert.equal(state.primary.kind, 'visible')
  assert.equal(state.primary.url, 'webrtc://localhost/live/drone-0')
  assert.equal(state.preview.kind, 'thermal-placeholder')
  assert.equal(state.preview.url, '')
  assert.equal(state.preview.focusAction, 'focus-thermal')
})

test('cockpit treats a backend-applied thermal focus as shared thermal playback', () => {
  const state = buildLivePaneState({
    visiblePlayUrl: 'webrtc://localhost/live/drone-0',
    thermalPlayUrl: '',
    primaryPreference: 'thermal',
    appliedFocusAction: 'focus-thermal',
    appliedFocusStatus: 'applied',
    allowSharedThermalPreview: true
  })

  assert.equal(state.primary.kind, 'thermal-shared')
  assert.equal(state.primary.url, 'webrtc://localhost/live/drone-0')
  assert.equal(state.preview.kind, 'visible')
  assert.equal(state.preview.url, '')
  assert.equal(state.preview.focusAction, 'focus-visible')
})

test('cockpit does not auto request RC thermal focus because it changes Pilot2 preview', () => {
  assert.match(cockpitSource, /requestDualStreamFocus/)
  assert.doesNotMatch(
    cockpitSource,
    /await requestDualStreamFocus\(dualStreamSummary\.value\.droneSn,\s*'focus-thermal'\)/,
  )
})

test('cockpit does not auto start Cloud SDK Pilot livestream fallback on mount', () => {
  assert.doesNotMatch(cockpitSource, /requestPilotLiveStart/)
  assert.doesNotMatch(cockpitSource, /startPilotLivestreamOnce/)
  assert.doesNotMatch(cockpitSource, /pilotLiveUrl/)
  assert.doesNotMatch(cockpitSource, /visiblePlayUrl\s*=\s*pilotLiveUrl\.value/)
})

test('cockpit fetches dual-stream group from the active aircraft sn when available', () => {
  assert.doesNotMatch(cockpitSource, /getDualStreamGroup\('RC_PLUS_LOCAL'\)/)
  assert.match(cockpitSource, /FIELD_AGENT_AIRCRAFT_SN/)
  assert.match(cockpitSource, /for \(const sn of candidateSns\)/)
  assert.match(cockpitSource, /getDualStreamGroup\(sn\)/)
})

test('cockpit prefers a real aircraft stream over stale RC_PLUS_LOCAL group state', () => {
  assert.deepEqual(buildDualStreamCandidateSns({
    flightHudSn: 'RC_PLUS_LOCAL',
    agentAircraftSn: '1581F7K3D249E00AM3Q3',
    currentSn: 'RC_PLUS_LOCAL',
    fireDetectionSn: ''
  }), ['1581F7K3D249E00AM3Q3', 'RC_PLUS_LOCAL'])
})

test('cockpit fire detection start uses the agent aircraft sn before live capacity fallback', () => {
  assert.match(cockpitSource, /resolveFireDetectionDroneSn/)
  assert.match(cockpitSource, /dualStreamState\.group\?\.droneSn/)
  assert.match(cockpitSource, /FIELD_AGENT_AIRCRAFT_SN/)
  assert.match(cockpitSource, /await getLiveCapacity/)
  assert.match(cockpitSource, /fireDetectionState\.droneSn = await resolveFireDetectionDroneSn\(\)/)
})

test('cockpit uses the preview window as the only visible thermal switch control', () => {
  assert.match(cockpitSource, /'focus-visible'/)
  assert.match(cockpitSource, /'focus-thermal'/)
  assert.match(cockpitSource, /handlePreviewSwap/)
  assert.match(cockpitSource, /requestDualStreamFocus/)
  assert.doesNotMatch(cockpitSource, /class="dual-stream-controls"/)
  assert.doesNotMatch(cockpitSource, />\s*可见光\s*</)
  assert.doesNotMatch(cockpitSource, />\s*红外\s*</)
  assert.doesNotMatch(cockpitSource, /会同步影响 Pilot2|Pilot2 预览/)
})

test('cockpit labels the thermal preview as thermal video without showing degraded state copy', () => {
  assert.match(cockpitSource, /'红外画面'/)
  assert.match(cockpitSource, /isClickableThermalPlaceholder/)
  assert.match(cockpitSource, /isClickableThermalPlaceholder\s*\?\s*''/)
  assert.match(cockpitSource, /<strong v-if="previewPaneMeta\.status">/)
  assert.doesNotMatch(cockpitSource, /isClickableThermalPlaceholder\s*\?\s*'点击切换'/)
  assert.doesNotMatch(cockpitSource, /'红外小窗'/)
  assert.doesNotMatch(cockpitSource, /const previewPaneMeta[\s\S]*status:\s*isThermal\s*\?\s*dualStreamSummary\.value\.thermal[\s\S]*const liveHudItems/)
})

test('cockpit waits for focus command ack before changing the primary stream preference', () => {
  assert.match(cockpitSource, /waitForFocusCommandApplied/)
  assert.match(cockpitSource, /lastCommandAction\s*===\s*action/)
  assert.match(cockpitSource, /lastCommandStatus\?\.toLowerCase\(\)\s*===\s*'applied'/)
  assert.match(cockpitSource, /const focusApplied = await waitForFocusCommandApplied/)
  assert.match(cockpitSource, /if \(!focusApplied\) \{/)
  assert.match(cockpitSource, /primaryPreference\.value = action === 'focus-thermal' \? 'thermal' : 'visible'/)
})

test('cockpit mirrors backend-applied focus commands into the primary preference', () => {
  assert.equal(resolveAppliedFocusPreference({
    currentPreference: 'visible',
    lastCommandAction: 'focus-thermal',
    lastCommandStatus: 'applied'
  }), 'thermal')
  assert.equal(resolveAppliedFocusPreference({
    currentPreference: 'thermal',
    lastCommandAction: 'focus-visible',
    lastCommandStatus: 'applied'
  }), 'visible')
  assert.equal(resolveAppliedFocusPreference({
    currentPreference: 'visible',
    lastCommandAction: 'focus-thermal',
    lastCommandStatus: 'pending'
  }), 'visible')
})

test('cockpit rebuilds shared-url playback when backend focus changes', () => {
  const visibleKey = buildLivePlaybackKey({
    primaryKind: 'visible',
    primaryUrl: 'webrtc://localhost/live/drone-0',
    primaryCrop: null,
    previewKind: 'thermal-placeholder',
    previewUrl: '',
    previewCrop: null,
    lastCommandAction: 'focus-visible',
    lastCommandStatus: 'applied',
    currentMode: 'VISIBLE'
  })
  const thermalKey = buildLivePlaybackKey({
    primaryKind: 'thermal-shared',
    primaryUrl: 'webrtc://localhost/live/drone-0',
    primaryCrop: null,
    previewKind: 'visible',
    previewUrl: '',
    previewCrop: null,
    lastCommandAction: 'focus-thermal',
    lastCommandStatus: 'applied',
    currentMode: 'DUAL'
  })

  assert.notEqual(visibleKey, thermalKey)
})

test('cockpit shows a loading animation while switching the live stream source', () => {
  assert.match(cockpitSource, /focusSwitching/)
  assert.match(cockpitSource, /dual-stream-switch-overlay/)
  assert.match(cockpitSource, /dual-stream-switch-spinner/)
  assert.match(cockpitSource, /红外画面加载中/)
  assert.match(cockpitSource, /可见光画面加载中/)
})

test('cockpit does not render duplicate text blocks on the preview placeholder', () => {
  assert.match(cockpitSource, /v-if="livePaneState\.preview\.url && !previewPlayerState\.loading && !previewPlayerState\.error"/)
})

test('workspace livestream panel exposes cockpit reuse hooks', () => {
  assert.match(panelSource, /showHeader/)
  assert.match(panelSource, /variant/)
  assert.match(panelSource, /emit\('state-change'/)
})

test('cockpit livestream variant does not lock content to a clipped fixed height', () => {
  assert.match(panelSource, /&\.variant-cockpit\s*\{[\s\S]*height:\s*auto/)
  assert.match(panelSource, /&\.variant-cockpit\s*\{[\s\S]*min-height:\s*100%/)
})

test('web shell owns vertical scrolling for tall project pages', () => {
  assert.match(homeSource, /<a-layout-content[^>]*class="page-content"/)
  assert.match(homeSource, /\.page-content\s*\{[\s\S]*overflow-y:\s*auto/)
  assert.match(homeSource, /\.page-content\s*\{[\s\S]*min-height:\s*0/)
})
