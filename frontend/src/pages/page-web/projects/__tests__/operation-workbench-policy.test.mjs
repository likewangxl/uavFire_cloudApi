import test from 'node:test'
import assert from 'node:assert/strict'

import {
  INCIDENT_STATUSES,
  buildIncidentActions,
  DANGEROUS_ACTION_IDS,
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
  assertVisibleOnly('RESPONDING', ['ABORT', 'CONFIRM_RELEASE'])
  assertVisibleOnly('RECHECKING', ['ABORT', 'CONFIRM_RELEASE'])
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
  assert.deepEqual(DANGEROUS_ACTION_IDS, ['DISPATCH', 'ABORT', 'MARK_FALSE_ALARM', 'ARCHIVE'])

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
