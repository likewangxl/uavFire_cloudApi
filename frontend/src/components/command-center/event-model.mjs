const finite = value => value !== null && value !== '' && Number.isFinite(Number(value))
export function eventState (event) {
  if (event.confirmedStatus === 'REJECTED' || event.status === 'IGNORED') return { key: 'closed', label: '已排除', tone: 'success' }
  if (['COMPLETED', 'ARCHIVED', 'RESOLVED'].includes(event.missionStatus)) return { key: 'closed', label: '已结束', tone: 'success' }
  if (event.confirmedStatus === 'CONFIRMED' || event.linkedIncidentId || event.status === 'MISSION_CREATED') return { key: 'handling', label: '处理中', tone: 'info' }
  if (['NEW', 'CANDIDATE', 'LOW_CONFIDENCE'].includes(event.status) || event.confirmedStatus === 'PENDING') return { key: 'pending', label: '待复核', tone: 'warn' }
  return { key: 'unknown', label: '状态待核实', tone: 'neutral' }
}
export function eventLocation (event) {
  if (['UNLOCATED', 'LASER_LOCATING', 'LASER_FAILED'].includes(event.geoQuality || event.locationQuality)) return '定位待确认'
  return finite(event.lng) && finite(event.lat) && Math.abs(Number(event.lng)) <= 180 && Math.abs(Number(event.lat)) <= 90 && !(Number(event.lng) === 0 && Number(event.lat) === 0)
    ? `${Number(event.lng).toFixed(5)}, ${Number(event.lat).toFixed(5)}`
    : '定位待确认'
}
export function eventImages (event) {
  return [{ key: 'visible', label: '可见光现场', url: event?.visibleImageUrl }, { key: 'thermal', label: '红外现场', url: event?.thermalImageUrl }].filter(image => typeof image.url === 'string' && /^(https?:\/\/|\/)/.test(image.url))
}
export function isValidationEvent (event) { return event.source === 'COMMAND_CENTER_QA' }
export function filterEvents (events, state, search, includeValidation = false) {
  const term = search.trim().toLowerCase()
  return events.filter(event => (includeValidation || !isValidationEvent(event)) && (!state || eventState(event).key === state) && `${event.eventId} ${event.deviceSn || ''} ${event.source || ''} ${eventLocation(event)}`.toLowerCase().includes(term))
}
const labels = { CONFIRMED: '人工确认', REJECTED: '排除火情', RECHECK_RESULT: '复查反馈', CREATED: '发现火情线索', MERGED: '同火点复检', VISIBLE_CONFIRM: '可见光确认', CONFIRM: '人工确认', REJECT: '排除火情', CREATE: '建立处置事件', DISPATCH: '派遣资源', CLOSE: '处置关闭', RESOLVE: '处置完成', ASSIGN_MONITOR: '安排巡护', ASSIGN_DELIVERY: '安排投送' }
export function timestamp (value) { if (value == null || value === '') return 0; const n = Number(value); return Number.isFinite(n) ? (n > 0 && n < 1e11 ? n * 1000 : n) : Date.parse(value) || 0 }
export function formatTime (value) { const t = timestamp(value); return t ? new Date(t).toLocaleString('zh-CN', { hour12: false }) : '时间未记录' }
export function buildEventTimeline (history = [], operations = []) {
  return [...history.map(row => ({ key: `history-${row.id}`, title: labels[row.action] || row.action || '识别记录', time: row.eventTimestamp || row.createTime, actor: ['CONFIRMED', 'REJECTED', 'RECHECK_RESULT'].includes(row.action) ? row.sourceEventId || '操作人未记录' : row.deviceSn || '来源未记录', description: row.decisionReason || row.source || '识别证据已留存', result: finite(row.confidence) ? `置信度 ${(Number(row.confidence) * 100).toFixed(1)}%` : '', kind: 'evidence' })), ...operations.map((row, index) => ({ key: `operation-${index}-${row.createTime}`, title: labels[row.action] || row.action || '处置记录', time: row.createTime, actor: row.operatorId || row.resourceSn || '操作人未记录', description: row.description || '', result: row.toStatus || row.status || '', kind: 'operation' }))].sort((a, b) => timestamp(a.time) - timestamp(b.time))
}
// Every consumer gets its own generation; late reads cannot replace a newer selection.
export function createLatestRequest () { let generation = 0; return { invalidate () { generation++ }, async run (task, commit, onError = () => {}) { const own = ++generation; try { const value = await task(); if (own === generation)commit(value) } catch (error) { if (own === generation)onError(error) } } } }
export function responseData (response) { const body = response?.data; if (!body || body.code !== 0) throw new Error(body?.message || '服务未返回有效结果'); return body.data }
