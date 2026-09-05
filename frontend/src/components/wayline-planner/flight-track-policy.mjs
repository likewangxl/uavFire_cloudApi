const ACTIVE_TRACK_STATUSES = new Set([
  'executing',
  'paused',
  'broken',
])

const ACTIVE_TRACK_STATUS_PRIORITY = {
  executing: 3,
  paused: 2,
  broken: 1,
}

const TERMINAL_TRACK_STATUSES = new Set([
  'stopped',
  'completed',
  'finished',
  'failed',
  'canceled',
])

export function normalizeTrackTimestamp (value) {
  const timestamp = Number(value)
  return Number.isFinite(timestamp) && timestamp > 0 ? timestamp : null
}

export function shouldAcceptFlightPosition (current, incoming) {
  if (!current || !incoming || current.aircraftSn !== incoming.aircraftSn) return true
  const currentTimestamp = normalizeTrackTimestamp(current.updatedAt)
  const incomingTimestamp = normalizeTrackTimestamp(incoming.updatedAt)
  if (currentTimestamp === null || incomingTimestamp === null) return true
  return incomingTimestamp >= currentTimestamp
}

export function plannedWaylineTrackState (record) {
  const status = String(record?.taskStatus || record?.status || '').toLowerCase()
  // execute 接口刚返回时可能还没有 flightId；先以规划航线 ID 建立会话，
  // 后续轮询拿到 flightId 后由状态层原地升级，不能把刚记录的轨迹清掉。
  const sessionId = record?.flightId || record?.plannedWaylineId || ''
  return {
    sessionId,
    recording: ACTIVE_TRACK_STATUSES.has(status),
    terminal: TERMINAL_TRACK_STATUSES.has(status),
  }
}

function recordTimestamp (record) {
  const values = [
    record?.lastProgressTime,
    record?.executedTime,
    record?.updateTime,
    record?.publishTime,
    record?.createTime,
  ].map(normalizeTrackTimestamp).filter(value => value !== null)
  return values.length > 0 ? Math.max(...values) : 0
}

/**
 * Pick the one task allowed to own the aircraft marker and actual-flight track.
 * A merely prepared/publishing task has not started flying and must never win.
 * Once a route owns the track, polling another stale route cannot steal it.
 */
export function selectActiveTrackRecord (records, lockedPlannedWaylineId = '') {
  const active = (Array.isArray(records) ? records : []).filter(record => {
    const status = String(record?.taskStatus || record?.status || '').toLowerCase()
    return ACTIVE_TRACK_STATUSES.has(status)
  })
  if (lockedPlannedWaylineId) {
    return active.find(record => record?.plannedWaylineId === lockedPlannedWaylineId) || null
  }
  return active.sort((left, right) => {
    const leftStatus = String(left?.taskStatus || left?.status || '').toLowerCase()
    const rightStatus = String(right?.taskStatus || right?.status || '').toLowerCase()
    const priorityDiff = (ACTIVE_TRACK_STATUS_PRIORITY[rightStatus] || 0) -
      (ACTIVE_TRACK_STATUS_PRIORITY[leftStatus] || 0)
    return priorityDiff || recordTimestamp(right) - recordTimestamp(left)
  })[0] || null
}

export function isSameTrackPoint (previous, next, epsilon = 1e-7) {
  if (!previous || !next) return false
  return Math.abs(previous[0] - next[0]) <= epsilon &&
    Math.abs(previous[1] - next[1]) <= epsilon
}
