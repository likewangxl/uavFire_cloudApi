import test from 'node:test'
import assert from 'node:assert/strict'

import {
  INCIDENT_STATUSES,
  buildIncidentActions,
  DANGEROUS_ACTION_IDS,
  coordinateQualityBadge,
  shouldShowSaturationWarning,
  draftMissionHint,
  requiresDangerConfirmation,
  requiresActionReason,
  sortTimelineItems,
  STATUS_BADGE_MAP,
} from '../operation-policy.mjs'

const allActionIds = [
  'CONFIRM_FIRE',
  'GENERATE_MISSION',
  'RUN_PREFLIGHT',
  'DISPATCH',
  'ABORT',
  'MARK_FALSE_ALARM',
  'ARCHIVE',
  'CONFIRM_RELEASE',
]

function visibleActionIds (status, assignments = []) {
  return buildIncidentActions({ status, assignments })
    .filter(action => action.visible)
    .map(action => action.id)
}

function assertVisibleOnly (status, expected, assignments = []) {
  const visible = visibleActionIds(status, assignments)
  assert.deepEqual(visible, expected, `${status} visible action set`)

  for (const actionId of allActionIds) {
    if (expected.includes(actionId)) {
      assert.ok(visible.includes(actionId), `${status} should show ${actionId}`)
    } else {
      assert.ok(!visible.includes(actionId), `${status} should hide ${actionId}`)
    }
  }
}

test('button visibility rules cover every operation incident status', () => {
  assert.deepEqual(INCIDENT_STATUSES, [
    'CANDIDATE',
    'CONFIRMED',
    'DISPATCHING',
    'RESPONDING',
    'RECHECKING',
    'RESOLVED',
    'ARCHIVED',
    'FALSE_ALARM',
    'ABORTED',
  ])

  assertVisibleOnly('CANDIDATE', ['CONFIRM_FIRE', 'MARK_FALSE_ALARM'])
  assertVisibleOnly('CONFIRMED', ['GENERATE_MISSION', 'RUN_PREFLIGHT', 'DISPATCH', 'ABORT', 'MARK_FALSE_ALARM'], [
    { role: 'DELIVERY_PRIMARY', status: 'ACTIVE', resourceSn: 'FC100-001' },
  ])
  assertVisibleOnly('DISPATCHING', ['ABORT'])
  assertVisibleOnly('RESPONDING', ['ABORT'])
  assertVisibleOnly('RECHECKING', ['ABORT'])
  assertVisibleOnly('RESOLVED', ['ARCHIVE'])
  assertVisibleOnly('ARCHIVED', [])
  assertVisibleOnly('FALSE_ALARM', ['ARCHIVE'])
  assertVisibleOnly('ABORTED', ['ARCHIVE'])
})

test('dispatch stays hidden until CONFIRMED incident has active DELIVERY_PRIMARY assignment', () => {
  assert.ok(!visibleActionIds('CONFIRMED').includes('DISPATCH'))
  assert.ok(!visibleActionIds('CONFIRMED', [
    { role: 'DELIVERY_BACKUP', status: 'ACTIVE', resourceSn: 'FC100-002' },
  ]).includes('DISPATCH'))
  assert.ok(!visibleActionIds('CONFIRMED', [
    { role: 'DELIVERY_PRIMARY', status: 'RELEASED', resourceSn: 'FC100-003' },
  ]).includes('DISPATCH'))
  assert.ok(visibleActionIds('CONFIRMED', [
    { role: 'DELIVERY_PRIMARY', status: 'ACTIVE', resourceSn: 'FC100-004' },
  ]).includes('DISPATCH'))
})

test('dangerous operations require second confirmation with consequence copy', () => {
  assert.deepEqual(DANGEROUS_ACTION_IDS, ['DISPATCH', 'ABORT', 'MARK_FALSE_ALARM', 'ARCHIVE', 'CONFIRM_RELEASE'])

  for (const actionId of DANGEROUS_ACTION_IDS) {
    assert.equal(requiresDangerConfirmation(actionId), true, `${actionId} should require confirmation`)
    const action = buildIncidentActions({
      status: actionId === 'ARCHIVE' ? 'RESOLVED' : 'CONFIRMED',
      assignments: [{ role: 'DELIVERY_PRIMARY', status: 'ACTIVE', resourceSn: 'FC100-001' }],
    }).find(item => item.id === actionId)
    assert.ok(action?.consequence, `${actionId} should expose consequence copy`)
  }

  assert.equal(requiresDangerConfirmation('RUN_PREFLIGHT'), false)
  assert.equal(requiresActionReason('ABORT'), true)
  assert.equal(requiresActionReason('MARK_FALSE_ALARM'), true)
  assert.equal(requiresActionReason('DISPATCH'), false)
  assert.equal(requiresActionReason('ARCHIVE'), false)
  assert.equal(requiresDangerConfirmation('CONFIRM_RELEASE'), true)
})

test('confirm release is visible only for responding incidents with a pending payload mission', () => {
  assert.ok(!visibleActionIds('RESPONDING').includes('CONFIRM_RELEASE'))
  const visible = buildIncidentActions({
    status: 'RESPONDING',
    missionStatus: 'PAYLOAD_RELEASE_PENDING',
  }).filter(action => action.visible).map(action => action.id)
  assert.ok(visible.includes('CONFIRM_RELEASE'))
  assert.ok(!buildIncidentActions({
    status: 'RECHECKING',
    missionStatus: 'PAYLOAD_RELEASE_PENDING',
  }).some(action => action.visible && action.id === 'CONFIRM_RELEASE'))
})

test('timeline sorting is ascending and stable for equal timestamps', () => {
  const ordered = sortTimelineItems([
    { action: 'RESOLVE', createTime: 30 },
    { action: 'ASSIGN_DELIVERY_PRIMARY', createTime: 20 },
    { action: 'CREATE', createTime: 10 },
    { action: 'ASSIGN_MONITOR_PRIMARY', createTime: 20 },
    { action: 'NO_TIME' },
  ])

  assert.deepEqual(ordered.map(item => item.action), [
    'CREATE',
    'ASSIGN_DELIVERY_PRIMARY',
    'ASSIGN_MONITOR_PRIMARY',
    'RESOLVE',
    'NO_TIME',
  ])
})

test('status badge mapping covers all nine operation incident states', () => {
  for (const status of INCIDENT_STATUSES) {
    assert.ok(STATUS_BADGE_MAP[status], `${status} should have badge mapping`)
    assert.equal(typeof STATUS_BADGE_MAP[status].label, 'string')
    assert.equal(typeof STATUS_BADGE_MAP[status].color, 'string')
  }

  assert.equal(STATUS_BADGE_MAP.CANDIDATE.color, 'gold')
  assert.equal(STATUS_BADGE_MAP.CONFIRMED.color, 'cyan')
  assert.equal(STATUS_BADGE_MAP.DISPATCHING.color, 'blue')
  assert.equal(STATUS_BADGE_MAP.RESPONDING.color, 'processing')
  assert.equal(STATUS_BADGE_MAP.RECHECKING.color, 'purple')
  assert.equal(STATUS_BADGE_MAP.RESOLVED.color, 'green')
  assert.equal(STATUS_BADGE_MAP.ARCHIVED.color, 'default')
  assert.equal(STATUS_BADGE_MAP.FALSE_ALARM.color, 'orange')
  assert.equal(STATUS_BADGE_MAP.ABORTED.color, 'red')
})

test('fire candidate confirmation actions are only visible for candidate fire events', () => {
  assert.deepEqual(visibleActionIds('CANDIDATE'), ['CONFIRM_FIRE', 'MARK_FALSE_ALARM'])
  assert.ok(!visibleActionIds('CONFIRMED').includes('CONFIRM_FIRE'))
  assert.ok(!visibleActionIds('RESPONDING').includes('MARK_FALSE_ALARM'))
})

test('coordinate quality badge maps S6 location quality values to distinct colors', () => {
  assert.deepEqual(coordinateQualityBadge('PRECISE'), { label: '精确', color: 'green' })
  assert.deepEqual(coordinateQualityBadge('ESTIMATED'), { label: '估算', color: 'orange' })
  assert.deepEqual(coordinateQualityBadge('MANUAL_MARKED'), { label: '人工标注', color: 'blue' })
  assert.deepEqual(coordinateQualityBadge('UNKNOWN'), { label: '未知', color: 'default' })
})

test('thermal saturation warning follows configured threshold', () => {
  assert.equal(shouldShowSaturationWarning(540, 540), true)
  assert.equal(shouldShowSaturationWarning(539.9, 540), false)
  assert.equal(shouldShowSaturationWarning(null, 540), false)
})

test('draft mission hint explains non precise coordinate behavior', () => {
  assert.equal(draftMissionHint('PRECISE').type, 'success')
  assert.match(draftMissionHint('ESTIMATED').text, /需复测或人工标注坐标/)
})
