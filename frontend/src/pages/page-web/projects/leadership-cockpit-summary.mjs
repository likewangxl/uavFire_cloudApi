const LEVEL_WEIGHT = {
  HIGH: 3,
  MEDIUM: 2,
  LOW: 1
}

export function normalizeLevel (level) {
  const value = String(level || '').toUpperCase()
  return LEVEL_WEIGHT[value] ? value : 'UNKNOWN'
}

export function formatPercent (value, digits = 0) {
  const n = Number(value)
  if (!Number.isFinite(n)) return '--'
  return `${n.toFixed(digits)}%`
}

export function formatNumber (value, digits = 0, fallback = '--') {
  const n = Number(value)
  if (!Number.isFinite(n)) return fallback
  return n.toFixed(digits)
}

export function isDeliveryOnline (device) {
  const onlineText = String(device?.online || '').toLowerCase()
  return onlineText === 'true' || onlineText === 'online' || onlineText === '1'
}

export function buildCockpitSummary ({
  fireEvents = [],
  aiEvents = [],
  msdkDevices = [],
  deliveryTargets = [],
  deliveryTaskStatuses = [],
  dualStreamGroup = null,
  deliveryTargetsLoading = false,
  fireEventsError = '',
  aiEventsError = '',
  dualStreamError = ''
} = {}) {
  const activeFireEvents = fireEvents.filter(event => {
    const status = String(event?.status || '').toUpperCase()
    return status !== 'IGNORED' && status !== 'ARCHIVED'
  })

  const fireLevelCounts = activeFireEvents.reduce((acc, event) => {
    const level = normalizeLevel(event?.fireLevel)
    if (level !== 'UNKNOWN') acc[level] = (acc[level] || 0) + 1
    return acc
  }, {})

  const highestFireLevel = activeFireEvents.reduce((current, event) => {
    const level = normalizeLevel(event?.fireLevel)
    return (LEVEL_WEIGHT[level] || 0) > (LEVEL_WEIGHT[current] || 0) ? level : current
  }, 'UNKNOWN')

  const missionLinked = activeFireEvents.filter(event => event?.missionNo).length
  const pendingFireEvents = activeFireEvents.filter(event => {
    const status = String(event?.status || '').toUpperCase()
    const missionStatus = String(event?.missionStatus || '').toUpperCase()
    return !event?.missionNo || ['NEW', 'LOW_CONFIDENCE'].includes(status) || ['PENDING', 'CREATED', 'PREPARED'].includes(missionStatus)
  }).length

  const highRiskAiEvents = aiEvents.filter(event => ['LOW', 'MEDIUM', 'HIGH'].includes(normalizeLevel(event?.riskLevel)))
  const onlineMsdkDevices = msdkDevices.filter(device => device?.online)
  const onlineDeliveryTargets = deliveryTargets.filter(target => target?.online)
  const runningDeliveryTargets = deliveryTargets.filter(target => target?.streamStatus === 'running')
  const runningDualStream = Boolean(dualStreamGroup?.visiblePlayUrl || dualStreamGroup?.thermalPlayUrl || String(dualStreamGroup?.sessionState || '').toUpperCase() === 'RUNNING')
  const liveOnlineCount = (runningDualStream ? 1 : 0) + runningDeliveryTargets.length

  const batteryValues = [
    ...msdkDevices.map(device => Number(device?.batteryPercent)),
    ...deliveryTargets.map(target => Number(target?.batteryPercent))
  ].filter(Number.isFinite)
  const minBattery = batteryValues.length > 0 ? Math.min(...batteryValues) : null

  const geoReadyCount = activeFireEvents.filter(event => {
    const quality = String(event?.geoQuality || '').toUpperCase()
    return quality === 'AUTO_WAYPOINT_READY' || quality === 'READY' || quality === 'OK'
  }).length
  const geoKnownCount = activeFireEvents.filter(event => event?.geoQuality).length
  const geoReadyRate = geoKnownCount > 0 ? (geoReadyCount / geoKnownCount) * 100 : null

  const activeDeliveryTasks = deliveryTaskStatuses.filter(task => {
    const status = String(task?.status || '').toUpperCase()
    return status && !['FINISHED', 'COMPLETED', 'CANCELED', 'CANCELLED', 'FAILED'].includes(status)
  })

  return {
    metrics: [
      {
        key: 'activeFireEvents',
        label: '活跃火情',
        value: String(activeFireEvents.length),
        note: fireEventsError || formatFireLevelCounts(fireLevelCounts),
        tone: highestFireLevel === 'HIGH' || highestFireLevel === 'MEDIUM' ? 'danger' : 'default',
        source: '/api/fire/events'
      },
      {
        key: 'highestFireLevel',
        label: '最高等级',
        value: highestFireLevel === 'UNKNOWN' ? '--' : highestFireLevel,
        note: pendingFireEvents > 0 ? `${pendingFireEvents} 个待处置` : `${missionLinked} 个已关联任务`,
        tone: highestFireLevel === 'HIGH' || highestFireLevel === 'MEDIUM' ? 'danger' : 'safe',
        source: '/api/fire/events'
      },
      {
        key: 'aiEvents',
        label: 'AI 识别',
        value: String(aiEvents.length),
        note: aiEventsError || `${highRiskAiEvents.length} 条风险记录`,
        tone: highRiskAiEvents.length > 0 ? 'danger' : 'default',
        source: '/manage/api/v1/dual-stream/tasks/{taskId}/events'
      },
      {
        key: 'liveOnline',
        label: '直播在线',
        value: String(liveOnlineCount),
        note: dualStreamError || `${runningDualStream ? '监测直播在线' : '监测直播待接入'} / FC100 ${runningDeliveryTargets.length}`,
        tone: liveOnlineCount > 0 ? 'safe' : 'default',
        source: 'dual-stream + delivery live'
      },
      {
        key: 'aircraftOnline',
        label: '在线飞机',
        value: `${onlineMsdkDevices.length + onlineDeliveryTargets.length}/${msdkDevices.length + deliveryTargets.length}`,
        note: `监测 ${onlineMsdkDevices.length} / 投放 ${onlineDeliveryTargets.length}`,
        tone: onlineMsdkDevices.length + onlineDeliveryTargets.length > 0 ? 'safe' : 'danger',
        source: '/manage/api/v1/msdk/devices + /api/fire/delivery/devices'
      },
      {
        key: 'deliveryTasks',
        label: '投放任务',
        value: activeDeliveryTasks.length > 0 ? String(activeDeliveryTasks.length) : (deliveryTargetsLoading ? '同步中' : '待命'),
        note: summarizeDeliveryTask(activeDeliveryTasks[0]) || `${deliveryTargets.length} 架 FC100 可选`,
        tone: activeDeliveryTasks.length > 0 ? 'default' : 'safe',
        source: '/api/fire/delivery/*'
      },
      {
        key: 'minBattery',
        label: '最低电量',
        value: minBattery == null ? '--' : formatPercent(minBattery),
        note: minBattery == null ? '等待 OSD / FC100 属性' : '来自已接入飞机状态',
        tone: minBattery != null && minBattery < 30 ? 'danger' : 'default',
        source: 'msdk devices + delivery properties'
      },
      {
        key: 'geoQuality',
        label: '定位质量',
        value: geoReadyRate == null ? '--' : formatPercent(geoReadyRate),
        note: geoReadyRate == null ? '暂无定位质量统计' : `${geoReadyCount}/${geoKnownCount} 可生成航线`,
        tone: geoReadyRate == null || geoReadyRate < 70 ? 'default' : 'safe',
        source: '/api/fire/events'
      }
    ],
    activeFireEvents,
    recentFireEvents: sortFireEvents(activeFireEvents).slice(0, 5),
    recentAiEvents: aiEvents.slice(-5).reverse(),
    aircraftRows: buildAircraftRows(msdkDevices, deliveryTargets),
    taskRows: activeDeliveryTasks.slice(0, 4),
    dataGaps: [
      {
        key: 'serviceHealth',
        label: '系统链路健康',
        value: fireEventsError || aiEventsError || dualStreamError ? '部分异常' : '按最近请求判断',
        note: '当前没有统一 health 汇总接口，页面只显示接口请求状态。'
      },
      {
        key: 'zlmHealth',
        label: 'ZLM 状态',
        value: runningDualStream || runningDeliveryTargets.length > 0 ? '播放链路可用' : '未验证',
        note: '当前没有专用 ZLM health 接口，播放失败时在直播面板显示。'
      }
    ]
  }
}

export function sortFireEvents (events) {
  return [...events].sort((a, b) => {
    const levelDiff = (LEVEL_WEIGHT[normalizeLevel(b?.fireLevel)] || 0) - (LEVEL_WEIGHT[normalizeLevel(a?.fireLevel)] || 0)
    if (levelDiff !== 0) return levelDiff
    return Number(b?.lastSeenTime || b?.eventTimestamp || b?.createTime || 0) - Number(a?.lastSeenTime || a?.eventTimestamp || a?.createTime || 0)
  })
}

function buildAircraftRows (msdkDevices, deliveryTargets) {
  return [
    ...msdkDevices.map(device => ({
      key: `msdk:${device.aircraftSn}`,
      role: '火情监测',
      sn: device.aircraftSn || '--',
      name: device.model || (device.aircraftSn ? `监测机 ${device.aircraftSn.slice(-4)}` : '监测机'),
      online: Boolean(device.online),
      status: device.online ? (device.mode || device.connectionState || '在线') : '离线',
      battery: device.batteryPercent,
      detail: `GPS ${device.gpsCount ?? '--'} / RTK ${device.rtkCount ?? '--'} / H ${formatNumber(device.height, 1)}m`
    })),
    ...deliveryTargets.map(target => ({
      key: `delivery:${target.deviceSn}`,
      role: 'FC100 投放',
      sn: target.deviceSn || '--',
      name: target.callsign || (target.deviceSn ? `FC100 ${target.deviceSn.slice(-4)}` : 'FC100'),
      online: Boolean(target.online),
      status: target.streamStatus === 'running' ? '直播在线' : (target.taskStatus || target.streamStatus || '待命'),
      battery: target.batteryPercent,
      detail: target.message || target.primaryPlayUrl || '等待投放平台状态'
    }))
  ]
}

function formatFireLevelCounts (counts) {
  const parts = [
    counts.HIGH ? `HIGH ${counts.HIGH}` : '',
    counts.MEDIUM ? `MED ${counts.MEDIUM}` : '',
    counts.LOW ? `LOW ${counts.LOW}` : ''
  ].filter(Boolean)
  return parts.length > 0 ? parts.join(' / ') : '暂无风险等级'
}

function summarizeDeliveryTask (task) {
  if (!task) return ''
  const phase = task.phase || task.status || '任务同步中'
  const progress = task.progressPercent == null ? '' : ` · ${formatPercent(task.progressPercent)}`
  return `${phase}${progress}`
}
