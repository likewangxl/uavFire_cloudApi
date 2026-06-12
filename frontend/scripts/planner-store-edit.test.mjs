import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const store = readFileSync(new URL('../src/hooks/use-wayline-planning.ts', import.meta.url), 'utf8')
const overlays = readFileSync(new URL('../src/hooks/use-planner-overlays.ts', import.meta.url), 'utf8')

test('store exposes drag-update and midpoint-insert with wgs sync and exec guard', () => {
  assert.match(store, /export function updateWaypointPositionGcj/)
  assert.match(store, /export function insertWaypointAfterGcj/)
  const upd = store.slice(store.indexOf('export function updateWaypointPositionGcj'))
  assert.match(upd.slice(0, 600), /state\.executing/)
  assert.match(upd.slice(0, 600), /gcj02towgs84/)
  assert.match(upd.slice(0, 600), /persistDraft/)
  const ins = store.slice(store.indexOf('export function insertWaypointAfterGcj'))
  assert.match(ins.slice(0, 1200), /state\.executing/)
  assert.match(ins.slice(0, 1200), /gcj02towgs84/)
  assert.match(ins.slice(0, 1200), /persistDraft/)
  // 插点与相邻点间距校验（与 addWaypointGcj 同一规则）
  assert.match(ins.slice(0, 1200), /MIN_WAYPOINT_SPACING_M/)
})

test('overlays wire draggable markers, midpoint ghosts, rightclick delete', () => {
  assert.match(overlays, /draggable:/)
  assert.match(overlays, /dragend/)
  assert.match(overlays, /updateWaypointPositionGcj/)
  assert.match(overlays, /insertWaypointAfterGcj/)
  assert.match(overlays, /rightclick/)
  assert.match(overlays, /removeWaypoint/)
  // 仅监测页签且非执行中才可编辑
  assert.match(overlays, /canEditWaypoints/)
})
