export const INCIDENT_STATUSES = [
  'CANDIDATE',
  'CONFIRMED',
  'DISPATCHING',
  'RESPONDING',
  'RECHECKING',
  'RESOLVED',
  'ARCHIVED',
  'FALSE_ALARM',
  'ABORTED',
]

export const DANGEROUS_ACTION_IDS = ['DISPATCH', 'ABORT', 'MARK_FALSE_ALARM', 'ARCHIVE']

export const STATUS_BADGE_MAP = {
  CANDIDATE: { label: '待确认', color: 'gold', tone: 'warning' },
  CONFIRMED: { label: '已确认', color: 'cyan', tone: 'warning' },
  DISPATCHING: { label: '派发中', color: 'blue', tone: 'danger' },
  RESPONDING: { label: '处置中', color: 'processing', tone: 'danger' },
  RECHECKING: { label: '复核中', color: 'purple', tone: 'warning' },
  RESOLVED: { label: '已解决', color: 'green', tone: 'safe' },
  ARCHIVED: { label: '已归档', color: 'default', tone: 'default' },
  FALSE_ALARM: { label: '误报', color: 'orange', tone: 'default' },
  ABORTED: { label: '已中止', color: 'red', tone: 'default' },
}

export const LEVEL_BADGE_MAP = {
  HIGH: { label: '高', color: 'red', tone: 'danger', priority: 30 },
  MEDIUM: { label: '中', color: 'orange', tone: 'warning', priority: 20 },
  LOW: { label: '低', color: 'green', tone: 'safe', priority: 10 },
  UNKNOWN: { label: '未知', color: 'default', tone: 'default', priority: 0 },
}

const ACTION_META = {
  CONFIRM_FIRE: {
    label: '确认火情',
    type: 'primary',
    disabled: true,
    disabledReason: '当前后端未开放 CONFIRM 接口，S3 仅保留入口',
  },
  GENERATE_MISSION: {
    label: '生成灭火任务',
    type: 'default',
    disabled: true,
    disabledReason: 'TODO S6 接入',
  },
  RUN_PREFLIGHT: {
    label: '运行预检',
    type: 'default',
    disabled: true,
    disabledReason: 'TODO S5 接入',
  },
  DISPATCH: {
    label: '派发',
    type: 'primary',
    danger: true,
    consequence: '将把事件派发给主投送 FC100，后端会执行预检并推进事件进入处置中。',
  },
  ABORT: {
    label: '中止',
    type: 'default',
    danger: true,
    consequence: '将中止当前真实火情处置流程，并释放后续自动推进。',
  },
  MARK_FALSE_ALARM: {
    label: '标记误报',
    type: 'default',
    danger: true,
    consequence: '将把事件标记为误报，后续不再进入灭火派发流程。',
  },
  ARCHIVE: {
    label: '归档',
    type: 'default',
    danger: true,
    consequence: '将把终态事件归档，归档后工作台不再作为待处置事件展示。',
  },
  CONFIRM_RELEASE: {
    label: '释放确认',
    type: 'default',
    disabled: true,
    disabledReason: 'TODO S7 接入',
  },
}

function hasActiveDeliveryPrimary (assignments = []) {
  return assignments.some(item =>
    String(item?.role || '').toUpperCase() === 'DELIVERY_PRIMARY' &&
    String(item?.status || '').toUpperCase() === 'ACTIVE')
}

function action (id, visible, override = {}) {
  return {
    id,
    visible,
    ...ACTION_META[id],
    ...override,
  }
}

export function buildIncidentActions ({ status, assignments = [] } = {}) {
  const normalized = String(status || '').toUpperCase()
  const canDispatch = normalized === 'CONFIRMED' && hasActiveDeliveryPrimary(assignments)

  return [
    action('CONFIRM_FIRE', normalized === 'CANDIDATE', { disabled: false, disabledReason: undefined }),
    action('GENERATE_MISSION', normalized === 'CONFIRMED'),
    action('RUN_PREFLIGHT', normalized === 'CONFIRMED'),
    action('DISPATCH', canDispatch),
    action('ABORT', ['CONFIRMED', 'DISPATCHING', 'RESPONDING', 'RECHECKING'].includes(normalized)),
    action('MARK_FALSE_ALARM', ['CANDIDATE', 'CONFIRMED'].includes(normalized)),
    action('ARCHIVE', ['RESOLVED', 'FALSE_ALARM', 'ABORTED'].includes(normalized)),
    action('CONFIRM_RELEASE', ['RESPONDING', 'RECHECKING'].includes(normalized)),
  ]
}

export function requiresDangerConfirmation (actionId) {
  return DANGEROUS_ACTION_IDS.includes(actionId)
}

export function requiresActionReason (actionId) {
  return actionId === 'ABORT' || actionId === 'MARK_FALSE_ALARM'
}

export function sortTimelineItems (items = []) {
  return items
    .map((item, index) => ({ item, index }))
    .sort((a, b) => {
      const at = Number(a.item?.createTime)
      const bt = Number(b.item?.createTime)
      const aValid = Number.isFinite(at)
      const bValid = Number.isFinite(bt)
      if (aValid && bValid && at !== bt) return at - bt
      if (aValid !== bValid) return aValid ? -1 : 1
      return a.index - b.index
    })
    .map(entry => entry.item)
}

export function statusBadge (status) {
  return STATUS_BADGE_MAP[String(status || '').toUpperCase()] || {
    label: status || '-',
    color: 'default',
    tone: 'default',
  }
}

export function levelBadge (level) {
  return LEVEL_BADGE_MAP[String(level || '').toUpperCase()] || LEVEL_BADGE_MAP.UNKNOWN
}

export function coordinateQualityBadge (quality) {
  const normalized = String(quality || 'UNKNOWN').toUpperCase()
  const map = {
    PRECISE: { label: '精确', color: 'green' },
    ESTIMATED: { label: '估算', color: 'orange' },
    MANUAL_MARKED: { label: '人工标注', color: 'blue' },
    UNKNOWN: { label: '未知', color: 'default' },
  }
  return map[normalized] || { label: normalized, color: 'default' }
}

export function shouldShowSaturationWarning (maxTemp, threshold = 540) {
  const value = Number(maxTemp)
  const limit = Number(threshold)
  return Number.isFinite(value) && Number.isFinite(limit) && value >= limit
}

export function draftMissionHint (quality) {
  const normalized = String(quality || 'UNKNOWN').toUpperCase()
  if (normalized === 'PRECISE') {
    return { type: 'success', text: '坐标精确，确认后可生成 CREATED 草稿任务' }
  }
  return { type: 'warning', text: '坐标非 PRECISE，确认后需复测或人工标注坐标，不生成投放任务草稿' }
}
