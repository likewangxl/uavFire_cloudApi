import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import {
  buildDualStreamCandidateSns,
  buildLivePaneState,
  buildLivePlaybackKey,
  resolveAppliedFocusPreference,
  shouldAutoRestoreVisibleFocus
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
  assert.match(cockpitSource, /flightHudData/)
  assert.doesNotMatch(cockpitSource, /liveHudItems/)
})

test('leadership cockpit switches map panel layout for livestream tabs', () => {
  assert.match(cockpitSource, /'live-mode': activeVisualTab !== 'map'/)
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

test('cockpit keeps playback failure card away from the lower-left flight HUD', () => {
  assert.match(cockpitSource, /\.flight-hud-overlay\s*\{[\s\S]*left:\s*18px[\s\S]*bottom:\s*40px/)
  assert.match(cockpitSource, /\.dual-stream-status-card\s*\{[\s\S]*inset:\s*auto 24px 64px auto/)
  assert.match(cockpitSource, /\.dual-stream-status-card\s*\{[\s\S]*width:\s*min\(420px,\s*calc\(100% - 48px\)\)/)
})

test('cockpit places thermal preview before fullscreen control on the right edge', () => {
  const previewIndex = cockpitSource.indexOf('class="dual-stream-preview"')
  const fullscreenIndex = cockpitSource.indexOf('class="dual-stream-fullscreen-btn"')

  assert.notEqual(previewIndex, -1)
  assert.notEqual(fullscreenIndex, -1)
  assert.ok(previewIndex < fullscreenIndex)
  assert.match(cockpitSource, /\.dual-stream-preview\s*\{[\s\S]*right:\s*82px/)
  assert.match(cockpitSource, /\.dual-stream-fullscreen-btn\s*\{[\s\S]*right:\s*18px/)
})

test('cockpit removes the upper live status chip row and gives flight HUD more room', () => {
  assert.doesNotMatch(cockpitSource, /class="dual-stream-hud"/)
  assert.doesNotMatch(cockpitSource, /liveHudItems/)
  assert.match(cockpitSource, /\.flight-hud-overlay\s*\{[\s\S]*min-height:\s*118px/)
  assert.match(cockpitSource, /\.flight-hud-overlay\s*\{[\s\S]*padding:\s*14px 16px/)
})

test('cockpit avoids duplicate playback when visible and thermal share one stream url', () => {
  assert.match(cockpitSource, /allowSharedThermalPreview:\s*true/)
  assert.match(cockpitSource, /livePaneState\.value\.preview\.url/)
  assert.match(cockpitSource, /slot: previewPlayer,[^\n]+url: livePaneState\.value\.preview\.url/)
  assert.match(cockpitSource, /mountPlayerInstance\(url, shell, state, isCurrent\)/)
  const shared = buildLivePaneState({
    visiblePlayUrl: 'webrtc://localhost/live/drone-0',
    thermalPlayUrl: 'webrtc://localhost/live/drone-0',
    primaryPreference: 'visible',
    allowSharedThermalPreview: true
  })
  assert.equal(shared.primary.url, 'webrtc://localhost/live/drone-0')
  assert.equal(shared.preview.url, '')
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

test('cockpit fire monitor selector filters RC Plus devices out of aircraft targets', () => {
  assert.match(cockpitSource, /isFireMonitorAircraftDevice/)
  assert.match(cockpitSource, /msdkDeviceSnapshots\.value\.filter\(isFireMonitorAircraftDevice\)/)
  assert.match(cockpitSource, /device\.aircraftSn === 'RC_PLUS_LOCAL'/)
  assert.match(cockpitSource, /RC_PLUS\|REMOTE\\s\*CONTROL\|遥控器/)
})

test('cockpit resets the visible view to 1x zoom when entering the fire monitor view', () => {
  // 进入火情监测 tab / 切换飞机时下发 focus-visible，agent 借此把可见光重置到 1x 焦距。
  assert.match(cockpitSource, /const ensureVisibleDefaultZoom/)
  assert.match(cockpitSource, /if \(tab === 'fire-monitor'\) \{[\s\S]*ensureVisibleDefaultZoom/)
  assert.match(cockpitSource, /ensureVisibleDefaultZoom\(target\.deviceSn\)/)
})

test('cockpit only falls back to the default agent placeholder when no real aircraft exist', () => {
  // 占位机仅在没有任何真实设备时回退；有真机时不得展示为幽灵"在线"项。
  assert.match(cockpitSource, /if \(targets\.size === 0\) \{[\s\S]*FIELD_AGENT_AIRCRAFT_SN/)
  assert.doesNotMatch(cockpitSource, /if \(!targets\.has\(FIELD_AGENT_AIRCRAFT_SN\)\)/)
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
    lastCommandStatus: 'applied',
    fireDetectionRunning: true
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

test('cockpit keeps visible playback as the default until fire detection is running', () => {
  assert.equal(resolveAppliedFocusPreference({
    currentPreference: 'visible',
    lastCommandAction: 'focus-thermal',
    lastCommandStatus: 'applied',
    fireDetectionRunning: false
  }), 'visible')
  assert.match(cockpitSource, /requestFireDetectionStart\(fireDetectionState\.droneSn\)[\s\S]*await switchFireMonitorFocus\('focus-thermal'\)/)
  assert.match(cockpitSource, /fireDetectionRunning:\s*fireDetectionState\.running/)
})

test('cockpit shows inline fire detection lifecycle feedback on the live image', () => {
  assert.match(cockpitSource, /fireDetectionState\.phase/)
  assert.match(cockpitSource, /fireDetectionButtonText/)
  assert.match(cockpitSource, /正在启动\.\.\./)
  assert.match(cockpitSource, /火情监测中/)
  assert.match(cockpitSource, /正在停止\.\.\./)
  assert.match(cockpitSource, /fireDetectionLiveFeedbackText/)
  assert.match(cockpitSource, /正在启动火情监测/)
  assert.match(cockpitSource, /火情监测已启动，正在切换红外画面/)
  assert.match(cockpitSource, /AI 火情监测中 · 红外识别/)
  assert.match(cockpitSource, /火情监测已启动 · 红外切换失败，当前显示可见光/)
  assert.match(cockpitSource, /class="live-badge"[\s\S]*fireDetectionLiveFeedbackText/)
})

test('cockpit fullscreen header reserves space for fullscreen control', () => {
  assert.match(cockpitSource, /\.dual-stream-shell\.fullscreen \.dual-stream-stage-head\s*\{[\s\S]*right:\s*104px/)
  assert.match(cockpitSource, /\.dual-stream-shell\.fullscreen \.dual-stream-stage-head\s*\{[\s\S]*max-width:\s*calc\(100% - 132px\)/)
  assert.match(cockpitSource, /\.fire-detect-btn\s*\{[\s\S]*white-space:\s*nowrap/)
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

test('idle cockpit asks to restore visible focus when thermal focus is still applied', () => {
  assert.equal(shouldAutoRestoreVisibleFocus({
    fireDetectionRunning: false,
    focusSwitching: false,
    lastCommandAction: 'focus-thermal',
    lastCommandStatus: 'applied',
    currentMode: ''
  }), true)

  assert.equal(shouldAutoRestoreVisibleFocus({
    fireDetectionRunning: false,
    focusSwitching: false,
    lastCommandAction: '',
    lastCommandStatus: '',
    currentMode: 'THERMAL_ONLY'
  }), true)
})

test('visible focus restore never fires while fire detection runs or a switch is in flight', () => {
  assert.equal(shouldAutoRestoreVisibleFocus({
    fireDetectionRunning: true,
    focusSwitching: false,
    lastCommandAction: 'focus-thermal',
    lastCommandStatus: 'applied',
    currentMode: 'THERMAL_ONLY'
  }), false)

  assert.equal(shouldAutoRestoreVisibleFocus({
    fireDetectionRunning: false,
    focusSwitching: true,
    lastCommandAction: 'focus-thermal',
    lastCommandStatus: 'applied',
    currentMode: ''
  }), false)
})

test('visible focus restore respects a user-selected thermal primary view', () => {
  assert.equal(shouldAutoRestoreVisibleFocus({
    fireDetectionRunning: false,
    focusSwitching: false,
    primaryPreference: 'thermal',
    lastCommandAction: 'focus-thermal',
    lastCommandStatus: 'applied',
    currentMode: 'THERMAL_ONLY'
  }), false)
})

test('visible focus restore stays quiet when stream is already visible', () => {
  assert.equal(shouldAutoRestoreVisibleFocus({
    fireDetectionRunning: false,
    focusSwitching: false,
    lastCommandAction: 'focus-visible',
    lastCommandStatus: 'applied',
    currentMode: 'VISIBLE_ONLY'
  }), false)
})

test('visible focus restore fires for an expired thermal command stuck on the shared stream', () => {
  // 真实现场状态：last_command_action=focus-thermal, status=expired, current_mode=DUAL。
  // 相机物理上仍停在红外源，必须恢复可见光——不能因为 status 不是 applied 就放过。
  assert.equal(shouldAutoRestoreVisibleFocus({
    fireDetectionRunning: false,
    focusSwitching: false,
    lastCommandAction: 'focus-thermal',
    lastCommandStatus: 'expired',
    currentMode: 'DUAL'
  }), true)

  // pending 的 focus-thermal 同样意味着相机正被切向红外，应恢复。
  assert.equal(shouldAutoRestoreVisibleFocus({
    fireDetectionRunning: false,
    focusSwitching: false,
    lastCommandAction: 'focus-thermal',
    lastCommandStatus: 'pending',
    currentMode: ''
  }), true)
})

test('cockpit wires the idle visible-focus restore into dual-stream polling', () => {
  assert.match(cockpitSource, /maybeRestoreDefaultVisibleFocus\(selectedGroup\)/)
  assert.match(cockpitSource, /shouldAutoRestoreVisibleFocus/)
})

test('fullscreen pins the swap preview window to the bottom-right corner', () => {
  assert.match(cockpitSource, /\.dual-stream-shell\.fullscreen \.dual-stream-preview\s*\{[^}]*top:\s*auto/)
  assert.match(cockpitSource, /\.dual-stream-shell\.fullscreen \.dual-stream-preview\s*\{[^}]*bottom:/)
})

test('web shell owns vertical scrolling for tall project pages', () => {
  assert.match(homeSource, /<a-layout-content[^>]*class="page-content"/)
  assert.match(homeSource, /\.page-content\s*\{[\s\S]*overflow-y:\s*auto/)
  assert.match(homeSource, /\.page-content\s*\{[\s\S]*min-height:\s*0/)
})
