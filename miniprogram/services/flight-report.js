const { deviceModelName } = require('./device-models')
function value(number, unit) { return number == null || number === '' || !Number.isFinite(Number(number)) ? '未记录' : String(Math.round(Number(number) * 100) / 100) + (unit ? ' ' + unit : '') }
const labels = {telemetry:'遥测采样',wayline_progress:'航线进度',wayline_state_change:'执行状态变化',GPS_NORMAL:'卫星定位飞行',GO_HOME:'返航',AUTO_LANDING:'自动降落',ON_GROUND:'地面',CANDIDATE:'待复核',NEW:'待复核',LOW_CONFIDENCE:'低置信度待复核',IGNORED:'已忽略',MISSION_CREATED:'已关联任务'}
const label = raw => labels[raw] || raw || '未记录'
const coordinate = raw => raw == null || raw === '' || !Number.isFinite(Number(raw)) ? '未记录' : String(Number(raw))
function date(timestamp) {
  if (!timestamp) return '未记录'
  const d = new Date(Number(timestamp) + 8 * 3600000), pad = n => String(n).padStart(2, '0')
  if (Number.isNaN(d.getTime())) return '未记录'
  return d.getUTCFullYear() + '-' + pad(d.getUTCMonth() + 1) + '-' + pad(d.getUTCDate()) + ' ' + pad(d.getUTCHours()) + ':' + pad(d.getUTCMinutes()) + ':' + pad(d.getUTCSeconds())
}
function duration(seconds) { if (seconds == null) return '未记录'; const n = Math.max(0, Math.floor(seconds)); return (n >= 3600 ? Math.floor(n / 3600) + '时 ' : '') + Math.floor(n % 3600 / 60) + '分 ' + n % 60 + '秒' }
function row(label, result) { return { label, value: result == null || result === '' ? '未记录' : String(result) } }
function summarize(report) {
  if (!report) return { flightReport: null, reportMetrics: [], reportFacts: [], planFacts: [], reportRecords: [], reportRuns: [], reportTimeline: [], reportEvents: [], reportMissingText: '', reportRecordTotal: 0, reportHasMore: false, relationPages: { events: 1, media: 1 } }
  const m = report.metrics || {}, p = report.planned || {}, records = report.records || { items: [], total: 0, hasMore: false }, runs = report.runs || []
  const fields = [row('起飞时间', date(m.takeoffAt)), row('返航时间', date(m.returnAt)), row('落地时间', date(m.landingAt)), row('起飞电量', value(m.takeoffBatteryPercent, '%')), row('返航电量', value(m.returnBatteryPercent, '%')), row('落地电量', value(m.landingBatteryPercent, '%')), row('首条采样电量', value(m.firstRecordedBatteryPercent, '%')), row('末条采样电量', value(m.lastRecordedBatteryPercent, '%')), row('记录覆盖时长', duration(m.observedDurationSeconds)), row('最小相对高度', value(m.minimumHeightM, 'm')), row('最大采样风速', value(m.maximumWindSpeedMs, 'm/s')), row('遥测记录', value(m.telemetryCount, '条')), row('本次执行编号', report.flightId), row('时间基准', '北京时间 UTC+8')]
  return {
    flightReport: report,
    reportMissingText: (report.quality?.missingFields || []).join('、'),
    reportMetrics: [row('实际飞行时长', duration(m.flightDurationSeconds)), row('采样平均速度', value(m.averageSpeedMs, 'm/s')), row('最大采样速度', value(m.maximumSpeedMs, 'm/s')), row('最大相对高度', value(m.maximumHeightM, 'm')), row('电量消耗', value(m.batteryConsumedPercentagePoints, '百分点')), row('有效采样段距离', value(m.observedDistanceM, 'm'))],
    reportFacts: fields,
    planFacts: [row('计划高度', value(p.defaultHeight, 'm')), row('速度上限', value(p.maxSpeed, 'm/s')), row('返航高度', value(p.rthAltitude, 'm')), row('安全起飞高度', value(p.takeoffSecurityHeight, 'm')), row('航点数', value(p.totalWaypoints, '个')), row('负载', deviceModelName(p.payloadName)), row('完成动作', p.finishAction), row('失联策略', p.rcLostAction), row('创建人', p.creator), row('发布人', p.publisher)],
    reportRecords: records.items.map(record => ({ ...record, timeLabel: date(record.observedAt), receivedLabel: date(record.receivedAt), kindLabel: label(record.kind), altitudeLabel: value(record.altitudeM,'m'), fixedLabel: record.positionFixed == null ? '未记录' : record.positionFixed ? '有效' : '未定位', gpsLabel: value(record.gpsCount, '颗'), progressLabel: value(record.progressPercent, '%'), waypointLabel: value(record.waypointIndex), sourceLabel: record.source === 'MSDK_STATE' ? '机载遥测' : '执行代理', speedLabel: value(record.speedMs, 'm/s'), heightLabel: value(record.relativeHeightM, 'm'), batteryLabel: value(record.batteryPercent, '%'), verticalSpeedLabel: value(record.verticalSpeedMs, 'm/s'), windLabel: value(record.windSpeedMs, 'm/s'), locationLabel: coordinate(record.latitude) + ' / ' + coordinate(record.longitude), modeLabel: label(record.flightMode), flyingLabel: record.flying == null ? '未记录' : record.flying ? '空中' : '地面' })),
    reportRecordTotal: records.total, reportHasMore: Boolean(records.hasMore), reportPage: records.page || 1,
    reportRuns: runs.map(run => ({ ...run, label: date(run.firstRecordAt) + (run.closedAt ? ' · 已落地' : ' · 落地未记录') })),
    reportRunIndex: Math.max(0, runs.findIndex(run => run.runId === report.runId)),
    relationPages: { events: 1, media: 1 },
    reportTimeline: [...(report.recordedMilestones || []), ...(report.timeline || [])].sort((a,b)=>a.time-b.time).map((event, index) => ({ ...event, key: 'event-' + index, timeLabel: date(event.time), title: event.title || label(event.state || event.kind) })),
    reportEvents: (report.relatedEvents?.items || []).map(event => ({ ...event, statusLabel: label(event.status), timeLabel: date(event.eventTimestamp) })),
  }
}
module.exports = { value, date, duration, summarize, coordinate, label }
