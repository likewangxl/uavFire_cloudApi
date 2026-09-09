import test from 'node:test'
import { controlContext } from '../device-control.mjs'
import assert from 'node:assert/strict'
import { sectionFor, activeSubnav, sections, subnav } from '../navigation.mjs'
import { eventState, eventLocation, eventImages, filterEvents, buildEventTimeline, createLatestRequest, responseData, timestamp } from '../event-model.mjs'
import { boundWindow, tileWindows, restoreWindows, rtcPlaybackUrl } from '../video-layout.mjs'

test('all original business destinations remain reachable from the command navigation', () => {
  const paths = [...sections, ...Object.values(subnav).flat()].map(r => r.path.split('?')[0])
  for (const path of ['/wayline', '/task', '/task-history', '/devices', '/firmwares', '/media', '/members', '/layer', '/flight-area', '/operation-incidents', '/fire-missions'])assert.ok(paths.includes(path), path)
})
test('nested task selector and query-only delivery navigation select the correct section', () => { assert.equal(sectionFor('/task/create-plan/select-plan'), 'tasks'); assert.equal(sectionFor('/wayline', { view: 'delivery' }), 'tasks'); assert.equal(sectionFor('/fire-mission-detail/a'), 'tasks'); assert.equal(sectionFor('/fire-events-table'), 'events') })
test('planner deep link belongs to the single route library', () => { assert.equal(activeSubnav('/wayline', '/wayline', { view: 'planner' }), true); assert.equal(activeSubnav('/wayline?view=planner', '/wayline', { view: 'planner' }), true); assert.equal(activeSubnav('/task', '/task/create-plan', {}), true) })
test('event status preserves explicit rejection, confirmation and unknown values', () => { assert.equal(eventState({ status: 'CANDIDATE' }).key, 'pending'); assert.equal(eventState({ status: 'NEW', confirmedStatus: 'REJECTED' }).key, 'closed'); assert.equal(eventState({ status: 'NEW', linkedIncidentId: 4 }).key, 'handling'); assert.equal(eventState({ status: 'FUTURE_STATE' }).key, 'unknown') })
test('missing locations are not rendered as coordinates zero', () => { for (const e of [{}, { lng: null, lat: null }, { lng: 0, lat: 0 }, { lng: 181, lat: 34 }])assert.equal(eventLocation(e), '定位待确认'); assert.equal(eventLocation({ lng: 0, lat: 34 }), '0.00000, 34.00000') })
test('image options reflect only valid supplied evidence URLs', () => { assert.deepEqual(eventImages({}), []); assert.deepEqual(eventImages({ visibleImageUrl: 'javascript:alert(1)', thermalImageUrl: '/evidence/a.jpg' }).map(i => i.key), ['thermal']) })
test('search and state filter operate together, preserve caller data', () => { const rows = [{ id: 1, status: 'CANDIDATE', eventId: 'F-A', deviceSn: 'M300' }, { id: 2, status: 'IGNORED', eventId: 'F-B' }]; assert.deepEqual(filterEvents(rows, 'pending', ' m300 ').map(r => r.id), [1]); assert.deepEqual(filterEvents(rows, 'closed', 'F-A'), []); assert.equal(rows.length, 2) })
test('timeline combines actual records in chronological order without synthetic notifications', () => { const result = buildEventTimeline([{ id: 1, action: 'CREATED', createTime: 2000, deviceSn: 'S1', confidence: 0.8 }], [{ action: 'CONFIRM', createTime: 3000, operatorId: 'U1', description: '核实' }]); assert.equal(result.length, 2); assert.equal(result[0].actor, 'S1'); assert.equal(result[1].title, '人工确认'); assert.equal(result[1].description, '核实'); assert.deepEqual(buildEventTimeline(), []) })
test('timeline keeps stable ordering for equal timestamps and handles absent time', () => { const result = buildEventTimeline([{ id: 1, createTime: null }, { id: 2, createTime: 2000 }], [{ createTime: 2000, action: 'CONFIRM' }]); assert.equal(result[0].key, 'history-1'); assert.equal(result[1].key, 'history-2'); assert.equal(timestamp(null), 0) })
test('business error responses cannot be mistaken for success', () => { for (const r of [undefined, { data: { code: 401, message: '未授权' } }, { data: {} }])assert.throws(() => responseData(r)); assert.deepEqual(responseData({ data: { code: 0, data: [] } }), []) })
test('late event A does not overwrite selected event B', async () => { const gate = createLatestRequest(); const seen = []; let release; const a = gate.run(() => new Promise(resolve => { release = resolve }), value => seen.push(value)); await gate.run(async () => 'B', value => seen.push(value)); release('A'); await a; assert.deepEqual(seen, ['B']) })
test('unmount invalidates successes and stale errors', async () => { const gate = createLatestRequest(); const seen = []; let rejectLater; const work = gate.run(() => new Promise((resolve, reject) => { rejectLater = reject }), v => seen.push(v), () => seen.push('error')); gate.invalidate(); rejectLater(new Error('late')); await work; assert.deepEqual(seen, []) })
test('tiling 1 through 20 windows stays within the stage', () => { for (let n = 1; n <= 20; n++) { const windows = tileWindows(Array.from({ length: n }, (_, i) => ({ id: String(i) }))); assert.equal(windows.length, n); for (const w of windows) { assert.ok(w.x >= 0 && w.y >= 0 && w.width > 0 && w.height > 0); assert.ok(w.x + w.width <= 1 && w.y + w.height <= 1) } } })
test('corrupt stored video layouts and unknown/duplicate aircraft are rejected', () => { const valid = { id: 'A', x: 0, y: 0, width: 0.5, height: 0.5, z: 1 }; assert.deepEqual(restoreWindows([valid, valid, { ...valid, id: 'B' }, { ...valid, id: 'C', x: NaN }], ['A', 'C']), [valid]); assert.deepEqual(restoreWindows({}, ['A']), []) })
test('moving and resizing clamp the window and reject nonfinite geometry', () => { const w = boundWindow({ id: 'A', x: 99, y: -20, width: 3, height: NaN }); assert.equal(w.x, 0); assert.equal(w.y, 0); assert.equal(w.width, 1); assert.equal(w.height, 0.18) })
test('RTC playback keeps HTTP(S) origin and encodes stream identifiers', () => { assert.equal(rtcPlaybackUrl('https://media.example/live/one').sdp, 'https://media.example/index/api/webrtc?app=live&stream=one&type=play'); assert.throws(() => rtcPlaybackUrl('javascript:bad')); assert.throws(() => rtcPlaybackUrl('https://media.example/one')) })

test('unlocated event never presents fallback coordinates as a confirmed fire location', () => { assert.equal(eventLocation({ lat: 34, lng: 108, geoQuality: 'UNLOCATED' }), '定位待确认') })

test('review history displays actual reviewer and saved reason', () => { const [entry] = buildEventTimeline([{ id: 2, action: 'REJECTED', sourceEventId: 'U2', deviceSn: 'DRONE', decisionReason: '现场核验' }]); assert.equal(entry.actor, 'U2'); assert.equal(entry.title, '排除火情'); assert.equal(entry.description, '现场核验') })

test('task and incident destinations have a single navigation owner', () => {
  assert.equal(sections.length, 5)
  assert.equal(subnav.operations, undefined)
  assert.equal(sectionFor('/operation-incidents'), 'events')
  const paths = Object.values(subnav).flat().map(item => item.path)
  assert.equal(new Set(paths).size, paths.length)
  assert.ok(!paths.includes('/wayline?view=planner'))
  assert.ok(activeSubnav('/fire-missions', '/fire-payload-release/A'))
})

test('device controls require fresh real telemetry and keep M300 detection gate', () => {
  const now = 1788640000000
  const device = { online: true, aircraftModelKey: 'M300', updatedAt: now, height: 12 }
  assert.equal(controlContext(device, now).ready, true)
  assert.equal(controlContext(device, now).detectionAllowed, false)
  assert.equal(controlContext({ ...device, fireClosedLoopReady: true }, now).detectionAllowed, true)
  assert.equal(controlContext({ ...device, updatedAt: now - 16000 }, now).ready, false)
  assert.equal(controlContext({ ...device, online: false }, now).osd, null)
  assert.equal(controlContext({ ...device, height: undefined }, now).ready, false)
  assert.equal(controlContext(device, now).osd.battery.capacity_percent, undefined)
})

test('field event list excludes validation records by default and preserves newest-first order', () => {
  const rows = [{ id: 858, source: 'COMMAND_CENTER_QA' }, { id: 855, source: 'DJI_AGENT' }, { id: 854, source: 'DJI_AGENT' }, { id: 745, source: 'M4T' }]
  assert.deepEqual(filterEvents(rows, '', '').map(e => e.id), [855, 854, 745])
  assert.deepEqual(filterEvents(rows, '', '', true).map(e => e.id), [858, 855, 854, 745])
})
