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

export function isMockCockpitDeviceSn (sn) {
  return /(^|[_-])mock([_-]|$)/i.test(String(sn || '').trim())
}

export function isConnectedMsdkDevice (device) {
  return Boolean(device?.aircraftSn) && device?.online === true && !isMockCockpitDeviceSn(device.aircraftSn)
}

export function isConnectedDeliveryTarget (target) {
  return Boolean(target?.deviceSn) && isDeliveryOnline(target) && !isMockCockpitDeviceSn(target.deviceSn)
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
  msdkDevices = msdkDevices.filter(isConnectedMsdkDevice)
  deliveryTargets = deliveryTargets.filter(isConnectedDeliveryTarget)

  const activeFireEvents = fireEvents.filter(event => {
    const status = String(event?.status || '').toUpperCase()
    return status !== 'IGNORED' && status !== 'ARCHIVED' && String(event?.confirmedStatus || '').toUpperCase() !== 'REJECTED'
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
  const aircraftHoverTitle = buildAircraftHoverTitle(msdkDevices, deliveryTargets)
  const liveHoverTitle = buildLiveHoverTitle({
    msdkDevices,
    deliveryTargets,
    dualStreamGroup,
    runningDualStream,
    runningDeliveryTargets
  })
  const batteryHoverTitle = buildBatteryHoverTitle(msdkDevices, deliveryTargets)
  const liveCardNote = buildLiveCardNote({
    msdkDevices,
    deliveryTargets,
    dualStreamGroup,
    runningDualStream,
    runningDeliveryTargets
  })
  const batteryCardNote = buildBatteryCardNote(msdkDevices, deliveryTargets)

  const geoReadyCount = activeFireEvents.filter(event => {
    return isRouteReadyFireLocation(event)
  }).length
  const geoKnownCount = activeFireEvents.filter(event => event?.geoQuality).length
  const geoReadyRate = geoKnownCount > 0 ? (geoReadyCount / geoKnownCount) * 100 : null

  const activeDeliveryTasks = deliveryTaskStatuses.filter(task => {
    const status = String(task?.status || '').toUpperCase()
    return status && !['FINISHED', 'COMPLETED', 'CANCELED', 'CANCELLED', 'FAILED'].includes(status)
  })

  const recentFireEvents = sortFireEvents(activeFireEvents).slice(0, 5)
  const recentAiEvents = aiEvents.slice(-5).reverse()
  const aircraftRows = buildAircraftRows(msdkDevices, deliveryTargets)
  const fireQueueStats = buildFireQueueStats(activeFireEvents)
  const aircraftGroups = buildAircraftGroups(aircraftRows)

  const metrics = [
      {
        key: 'activeFireEvents',
        label: '活跃火情',
        value: String(activeFireEvents.length),
        note: fireEventsError || formatFireLevelCounts(fireLevelCounts),
        tone: highestFireLevel === 'HIGH' || highestFireLevel === 'MEDIUM' ? 'danger' : 'default',
        source: '/api/fire/events',
        detailPopover: buildActiveFirePopover({
          activeFireEvents,
          fireLevelCounts,
          fireQueueStats,
          fireEventsError
        })
      },
      {
        key: 'highestFireLevel',
        label: '最高等级',
        value: highestFireLevel === 'UNKNOWN' ? '--' : highestFireLevel,
        note: pendingFireEvents > 0 ? `${pendingFireEvents} 个待处置` : `${missionLinked} 个已关联任务`,
        tone: highestFireLevel === 'HIGH' || highestFireLevel === 'MEDIUM' ? 'danger' : 'safe',
        source: '/api/fire/events',
        detailPopover: buildHighestFireLevelPopover({
          activeFireEvents,
          highestFireLevel,
          pendingFireEvents,
          fireEventsError
        })
      },
      {
        key: 'aiEvents',
        label: 'AI 识别',
        value: String(aiEvents.length),
        note: aiEventsError || `${highRiskAiEvents.length} 条风险记录`,
        tone: highRiskAiEvents.length > 0 ? 'danger' : 'default',
        source: '/manage/api/v1/dual-stream/tasks/{taskId}/events',
        detailPopover: buildAiEventsPopover({
          aiEvents,
          highRiskAiEvents,
          aiEventsError
        })
      },
      {
        key: 'liveOnline',
        label: '直播在线',
        value: String(liveOnlineCount),
        note: dualStreamError || liveCardNote || `${runningDualStream ? '监测直播在线' : '监测直播待接入'} / FC100 ${runningDeliveryTargets.length}`,
        tone: liveOnlineCount > 0 ? 'safe' : 'default',
        source: 'dual-stream + delivery live',
        title: liveHoverTitle || 'dual-stream + delivery live'
      },
      {
        key: 'aircraftOnline',
        label: '在线飞机',
        value: `${onlineMsdkDevices.length + onlineDeliveryTargets.length}/${msdkDevices.length + deliveryTargets.length}`,
        note: `监测 ${onlineMsdkDevices.length} / 投放 ${onlineDeliveryTargets.length}`,
        tone: onlineMsdkDevices.length + onlineDeliveryTargets.length > 0 ? 'safe' : 'danger',
        source: '/manage/api/v1/msdk/devices + /api/fire/delivery/devices',
        title: aircraftHoverTitle || '/manage/api/v1/msdk/devices + /api/fire/delivery/devices',
        detailPopover: buildAircraftOnlinePopover({
          aircraftRows,
          onlineCount: onlineMsdkDevices.length + onlineDeliveryTargets.length,
          totalCount: msdkDevices.length + deliveryTargets.length
        })
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
        note: batteryCardNote || (minBattery == null ? '等待 OSD / FC100 属性' : '来自已接入飞机状态'),
        tone: minBattery != null && minBattery < 30 ? 'danger' : 'default',
        source: 'msdk devices + delivery properties',
        title: batteryHoverTitle || 'msdk devices + delivery properties'
      },
      {
        key: 'geoQuality',
        label: '定位质量',
        value: geoReadyRate == null ? '--' : formatPercent(geoReadyRate),
        note: geoReadyRate == null ? '暂无定位质量统计' : `${geoReadyCount}/${geoKnownCount} 可生成航线`,
        tone: geoReadyRate == null || geoReadyRate < 70 ? 'default' : 'safe',
        source: '/api/fire/events'
      }
    ]
  const taskRows = activeDeliveryTasks.slice(0, 4)
  const dataGaps = [
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

  return {
    metrics,
    activeFireEvents,
    recentFireEvents,
    recentAiEvents,
    aircraftRows,
    aircraftGroups,
    fireQueueStats,
    taskRows,
    dataGaps,
    visualContextMetrics: buildVisualContextMetrics({
      metrics,
      aiEvents,
      msdkDevices,
      deliveryTargets,
      activeDeliveryTasks,
      dualStreamGroup
    }),
    visualInstrumentBelt: buildVisualInstrumentBelt({
      activeFireEvents,
      aiEvents,
      msdkDevices,
      deliveryTargets,
      activeDeliveryTasks,
      dualStreamGroup,
      fireEventsError,
      aiEventsError,
      dualStreamError,
      deliveryTargetsLoading
    }),
    sideHealthRows: buildSideHealthRows({
      fireEventsError,
      aiEventsError,
      dualStreamError,
      runningDualStream,
      runningDeliveryTargets,
      deliveryTargets,
      activeFireEvents,
      aiEvents,
      msdkDevices,
      deliveryTargetsLoading
    }),
    emptyStateHints: buildEmptyStateHints({
      fireEventsError,
      aiEventsError,
      activeFireEvents,
      aiEvents,
      msdkDevices,
      deliveryTargets,
      activeDeliveryTasks,
      deliveryTargetsLoading
    })
  }
}

export function sortFireEvents (events) {
  return [...events].sort((a, b) => {
    const levelDiff = (LEVEL_WEIGHT[normalizeLevel(b?.fireLevel)] || 0) - (LEVEL_WEIGHT[normalizeLevel(a?.fireLevel)] || 0)
    if (levelDiff !== 0) return levelDiff
    return Number(b?.lastSeenTime || b?.eventTimestamp || b?.createTime || 0) - Number(a?.lastSeenTime || a?.eventTimestamp || a?.createTime || 0)
  })
}

function buildVisualInstrumentBelt ({
  activeFireEvents,
  aiEvents,
  msdkDevices,
  deliveryTargets,
  activeDeliveryTasks,
  dualStreamGroup,
  fireEventsError,
  aiEventsError,
  dualStreamError,
  deliveryTargetsLoading
}) {
  const monitorDevice = selectMonitorDevice(msdkDevices)
  const deliveryTarget = selectDeliveryTarget(deliveryTargets)
  const deliveryTask = activeDeliveryTasks[0]
  const primaryFire = sortFireEvents(activeFireEvents)[0]
  const latestAiEvent = aiEvents[aiEvents.length - 1]
  const visibleOnline = Boolean(dualStreamGroup?.visiblePlayUrl)
  const thermalOnline = Boolean(dualStreamGroup?.thermalPlayUrl)
  const dualStreamOnline = visibleOnline || thermalOnline || String(dualStreamGroup?.sessionState || '').toUpperCase() === 'RUNNING'
  const deliveryLiveOnline = Boolean(deliveryTarget?.primaryPlayUrl || deliveryTarget?.streamStatus === 'running')

  const buildBelt = (tabKey) => {
    const isDelivery = tabKey === 'delivery-execution'
    const targetDevice = isDelivery ? deliveryTarget : monitorDevice
    const videoSignals = isDelivery
      ? [
          { key: 'visible', label: 'FC100', status: deliveryLiveOnline ? 'online' : 'offline', detail: deliveryTarget?.primaryPlayUrl ? '直播地址已返回' : '等待平台地址' },
          { key: 'thermal', label: '平台', status: deliveryTarget?.online ? 'online' : 'offline', detail: deliveryTargetsLoading ? '同步中' : (deliveryTarget?.taskStatus || '等待任务') },
          { key: 'zlm', label: 'ZLM', status: deliveryLiveOnline ? 'online' : 'warning', detail: deliveryLiveOnline ? '播放链路可用' : '未验证播放' }
        ]
      : [
          { key: 'visible', label: '可见光', status: visibleOnline ? 'online' : 'offline', detail: visibleOnline ? '主画面可用' : '等待直播地址' },
          { key: 'thermal', label: '红外', status: thermalOnline ? 'online' : 'offline', detail: thermalOnline ? '红外可切换' : '红外未接入' },
          { key: 'zlm', label: 'ZLM', status: dualStreamOnline ? 'online' : 'warning', detail: dualStreamError || (dualStreamOnline ? '播放链路可用' : '未验证播放') }
        ]

    return {
      sections: [
        {
          key: 'videoLink',
          title: '视频链路',
          summary: isDelivery
            ? (deliveryLiveOnline ? 'FC100 直播在线' : '等待 FC100 直播')
            : (dualStreamOnline ? '双光链路接入' : '双光待接入'),
          status: videoSignals.some(item => item.status === 'online') ? 'online' : 'warning',
          signals: videoSignals,
          footers: [
            { label: '码率', value: '等待码率' },
            { label: '延迟', value: '等待播放器' },
            { label: '丢包', value: '未上报' }
          ]
        },
        {
          key: 'flightReadouts',
          title: '飞行读数',
          summary: targetDevice?.online ? 'OSD 已接入' : '等待 OSD',
          status: targetDevice?.online ? 'online' : 'warning',
          readouts: buildInstrumentReadouts(targetDevice)
        },
        {
          key: 'aiObservation',
          title: isDelivery ? '任务观察' : 'AI 观察',
          summary: isDelivery
            ? (deliveryTask ? `${deliveryTask.phase || deliveryTask.status || '执行中'} ${deliveryTask.progressPercent == null ? '' : formatPercent(deliveryTask.progressPercent)}`.trim() : '未启动投放任务')
            : (aiEvents.length > 0 ? `最近 ${aiEvents.length} 条识别` : '暂无识别事件'),
          status: aiEventsError ? 'warning' : (aiEvents.length > 0 || deliveryTask ? 'online' : 'idle'),
          ticks: buildAiTicks(aiEvents),
          note: isDelivery
            ? (deliveryTask?.displayMessage || deliveryTask?.message || deliveryTarget?.message || '等待 FC100 平台回执')
            : (latestAiEvent ? `最新风险 ${normalizeLevel(latestAiEvent.riskLevel)}` : (aiEventsError || '识别任务待启动'))
        }
      ],
      events: [
        {
          key: 'osd',
          time: formatTimeLabel(primaryFire?.lastSeenTime || primaryFire?.eventTimestamp || primaryFire?.createTime),
          label: targetDevice?.online ? 'OSD 已接入' : '等待 OSD',
          status: targetDevice?.online ? 'online' : 'warning'
        },
        {
          key: 'thermal',
          time: formatTimeLabel(latestAiEvent?.eventTimestamp || latestAiEvent?.createTime),
          label: isDelivery ? (deliveryLiveOnline ? 'FC100 直播在线' : 'FC100 直播待接入') : (thermalOnline ? '红外已接入' : '红外未接入'),
          status: (isDelivery ? deliveryLiveOnline : thermalOnline) ? 'online' : 'warning'
        },
        {
          key: 'aiTask',
          time: formatTimeLabel(latestAiEvent?.eventTimestamp || latestAiEvent?.createTime),
          label: isDelivery
            ? (deliveryTask ? `${deliveryTask.phase || deliveryTask.status || '执行中'} ${deliveryTask.progressPercent == null ? '' : formatPercent(deliveryTask.progressPercent)}`.trim() : '投放任务待启动')
            : (aiEvents.length > 0 ? `AI 识别 ${aiEvents.length} 条` : (fireEventsError || aiEventsError || '识别任务待启动')),
          status: (aiEvents.length > 0 || deliveryTask) ? 'online' : 'idle'
        }
      ]
    }
  }

  return {
    map: buildBelt('map'),
    'fire-monitor': buildBelt('fire-monitor'),
    'delivery-execution': buildBelt('delivery-execution')
  }
}

function buildVisualContextMetrics ({
  metrics,
  aiEvents,
  msdkDevices,
  deliveryTargets,
  activeDeliveryTasks,
  dualStreamGroup
}) {
  const metricByKey = new Map(metrics.map(item => [item.key, item]))
  const monitorDevice = msdkDevices.find(device => device?.online && device?.aircraftSn) ||
    msdkDevices.find(device => device?.aircraftSn)
  const deliveryTarget = deliveryTargets.find(target => target?.online && target?.streamStatus === 'running') ||
    deliveryTargets.find(target => target?.online) ||
    deliveryTargets[0]
  const deliveryTask = activeDeliveryTasks[0]
  const visibleOnline = Boolean(dualStreamGroup?.visiblePlayUrl)
  const thermalOnline = Boolean(dualStreamGroup?.thermalPlayUrl)

  return {
    map: ['activeFireEvents', 'highestFireLevel', 'aiEvents', 'liveOnline']
      .map(key => metricByKey.get(key))
      .filter(Boolean),
    'fire-monitor': [
      {
        key: 'monitorTarget',
        label: '当前监测机',
        value: monitorDevice?.model || monitorDevice?.deviceName || (monitorDevice?.aircraftSn ? `监测机 ${monitorDevice.aircraftSn.slice(-4)}` : '未选择'),
        note: monitorDevice?.online ? '在线' : '等待 MSDK Agent'
      },
      {
        key: 'visibleStream',
        label: '可见光',
        value: visibleOnline ? '在线' : '待接入',
        note: dualStreamGroup?.visiblePlayUrl || dualStreamGroup?.sessionState || '等待直播地址'
      },
      {
        key: 'thermalStream',
        label: '红外/焦点',
        value: thermalOnline ? '在线' : '待切换',
        note: dualStreamGroup?.thermalPlayUrl || dualStreamGroup?.currentMode || '可通过画面切换触发'
      },
      {
        key: 'aiRecent',
        label: 'AI 最近识别',
        value: `${aiEvents.length} 条`,
        note: aiEvents.length > 0 ? `最新风险 ${normalizeLevel(aiEvents[aiEvents.length - 1]?.riskLevel)}` : '未收到 AI 事件'
      },
      {
        key: 'flightHud',
        label: '飞行 HUD',
        value: monitorDevice?.online ? '已接入' : '等待 OSD',
        note: `GPS ${monitorDevice?.gpsCount ?? '--'} / RTK ${monitorDevice?.rtkCount ?? '--'} / H ${formatNumber(monitorDevice?.height, 1)}m`
      }
    ],
    'delivery-execution': [
      {
        key: 'deliveryTarget',
        label: '当前 FC100',
        value: deliveryTarget?.callsign || (deliveryTarget?.deviceSn ? `FC100 ${deliveryTarget.deviceSn.slice(-4)}` : '未选择'),
        note: deliveryTarget?.online ? (deliveryTarget?.streamStatus || '在线') : '等待投放机'
      },
      {
        key: 'liveSource',
        label: '直播来源',
        value: deliveryTarget?.primaryPlayUrl || deliveryTarget?.streamStatus === 'running' ? '在线' : '待接入',
        note: deliveryTarget?.primaryPlayUrl || deliveryTarget?.message || '等待 FC100 平台返回地址'
      },
      {
        key: 'taskPhase',
        label: '任务阶段',
        value: deliveryTask?.phase || deliveryTask?.status || deliveryTarget?.taskStatus || '待命',
        note: deliveryTask?.taskId || deliveryTarget?.message || '等待投放任务'
      },
      {
        key: 'taskProgress',
        label: '执行进度',
        value: deliveryTask?.progressPercent == null ? '--' : formatPercent(deliveryTask.progressPercent),
        note: deliveryTask?.displayMessage || deliveryTask?.message || '按任务状态上报'
      },
      {
        key: 'deliveryBattery',
        label: '电量/在线',
        value: deliveryTarget?.batteryPercent == null ? '--' : formatPercent(deliveryTarget.batteryPercent),
        note: deliveryTarget?.online ? 'FC100 在线' : 'FC100 离线或未上报'
      }
    ]
  }
}

function buildSideHealthRows ({
  fireEventsError,
  aiEventsError,
  dualStreamError,
  runningDualStream,
  runningDeliveryTargets,
  deliveryTargets,
  activeFireEvents,
  aiEvents,
  msdkDevices,
  deliveryTargetsLoading
}) {
  const hasApiError = Boolean(fireEventsError || aiEventsError || dualStreamError)
  const onlineMsdkDevices = msdkDevices.filter(device => device?.online)
  const onlineDeliveryTargets = deliveryTargets.filter(target => target?.online)
  return [
    {
      key: 'backendApi',
      label: '后端 API',
      value: hasApiError ? '部分异常' : '正常',
      note: hasApiError ? [fireEventsError, aiEventsError, dualStreamError].filter(Boolean).join(' / ') : `火情 ${activeFireEvents.length} / AI ${aiEvents.length}`
    },
    {
      key: 'dualStream',
      label: 'MSDK Agent 连接设备',
      value: `${onlineMsdkDevices.length}/${msdkDevices.length}`,
      note: dualStreamError || (runningDualStream ? '双光直播链路在线' : '等待 MSDK Agent 上报直播链路')
    },
    {
      key: 'zlmPlayback',
      label: 'ZLM 播放',
      value: runningDualStream || runningDeliveryTargets.length > 0 ? '可用' : '未验证',
      note: '无专用 health 接口，以播放地址和播放器状态判断'
    },
    {
      key: 'deliveryPlatform',
      label: '司运平台连接设备',
      value: deliveryTargetsLoading ? '同步中' : `${onlineDeliveryTargets.length}/${deliveryTargets.length}`,
      note: deliveryTargets.length > 0 ? `${deliveryTargets.length} 架投放设备` : '等待司运平台返回设备'
    },
    {
      key: 'dataRefresh',
      label: '数据刷新',
      value: hasApiError ? '需关注' : '正常',
      note: `监测机 ${msdkDevices.length} / 投放机 ${deliveryTargets.length}`
    }
  ]
}

function buildEmptyStateHints ({
  fireEventsError,
  aiEventsError,
  activeFireEvents,
  aiEvents,
  msdkDevices,
  deliveryTargets,
  activeDeliveryTasks,
  deliveryTargetsLoading
}) {
  return {
    aiRisk: aiEventsError || (aiEvents.length > 0 ? `已有 ${aiEvents.length} 条 AI 识别记录` : '未收到 AI 识别事件，可先选择监测机并启动火情识别。'),
    fireEvents: fireEventsError || (activeFireEvents.length > 0 ? `已有火情 ${activeFireEvents.length} 条，按风险等级排序。` : '暂无火情事件，等待 /api/fire/events 返回。'),
    aircraft: msdkDevices.length + deliveryTargets.length > 0 ? `已接入 ${msdkDevices.length + deliveryTargets.length} 架飞机/投放设备。` : '未检测到飞机或 FC100 投放设备，请确认 MSDK Agent 与投放平台接入。',
    deliveryTasks: activeDeliveryTasks.length > 0 ? `已有 ${activeDeliveryTasks.length} 个投放任务同步中。` : (deliveryTargetsLoading ? '投放任务同步中。' : '等待投放任务，创建或下发 FC100 任务后会在这里显示。')
  }
}

function buildFireQueueStats (activeFireEvents) {
  const pending = activeFireEvents.filter(event => {
    const status = String(event?.status || '').toUpperCase()
    return !event?.missionNo || ['NEW', 'LOW_CONFIDENCE'].includes(status)
  }).length
  const missionLinked = activeFireEvents.filter(event => event?.missionNo).length
  const routeReady = activeFireEvents.filter(event => {
    return isRouteReadyFireLocation(event)
  }).length

  return [
    { key: 'pending', label: '待处置', value: String(pending) },
    { key: 'missionLinked', label: '已关联任务', value: String(missionLinked) },
    { key: 'routeReady', label: '可生成航线', value: String(routeReady) }
  ]
}

function buildAircraftGroups (aircraftRows) {
  return [
    {
      key: 'monitor',
      label: '火情监测机',
      rows: aircraftRows.filter(row => row.role === '火情监测')
    },
    {
      key: 'delivery',
      label: '投放设备',
      rows: aircraftRows.filter(row => row.role === 'FC100 投放')
    }
  ].filter(group => group.rows.length > 0)
}

function buildActiveFirePopover ({
  activeFireEvents,
  fireLevelCounts,
  fireQueueStats,
  fireEventsError
}) {
  const sortedRows = sortFireEvents(activeFireEvents).slice(0, 5).map(buildFirePopoverRow)
  const statByKey = new Map(fireQueueStats.map(item => [item.key, item]))
  return {
    title: '活跃火情详情',
    subtitle: '火情事件实时汇总',
    statusLabel: fireEventsError ? '接口异常' : (activeFireEvents.length > 0 ? '实时同步' : '暂无火情'),
    statusTone: fireEventsError ? 'danger' : (activeFireEvents.length > 0 ? 'danger' : 'default'),
    error: fireEventsError,
    emptyText: '暂无活跃火情事件',
    stats: [
      { key: 'total', label: '活跃总数', value: String(activeFireEvents.length), tone: activeFireEvents.length > 0 ? 'danger' : 'default' },
      { key: 'high', label: 'HIGH', value: String(fireLevelCounts.HIGH || 0), tone: fireLevelCounts.HIGH ? 'danger' : 'default' },
      { key: 'medium', label: 'MED', value: String(fireLevelCounts.MEDIUM || 0), tone: fireLevelCounts.MEDIUM ? 'warning' : 'default' },
      { key: 'low', label: 'LOW', value: String(fireLevelCounts.LOW || 0), tone: 'safe' },
      { key: 'pending', label: '待处置', value: statByKey.get('pending')?.value || '0', tone: Number(statByKey.get('pending')?.value) > 0 ? 'warning' : 'default' },
      { key: 'missionLinked', label: '已关联', value: statByKey.get('missionLinked')?.value || '0', tone: 'safe' },
      { key: 'routeReady', label: '可生成航线', value: statByKey.get('routeReady')?.value || '0', tone: 'safe' }
    ],
    rows: sortedRows
  }
}

function buildHighestFireLevelPopover ({
  activeFireEvents,
  highestFireLevel,
  pendingFireEvents,
  fireEventsError
}) {
  const highestRows = highestFireLevel === 'UNKNOWN'
    ? []
    : sortFireEvents(activeFireEvents)
      .filter(event => normalizeLevel(event?.fireLevel) === highestFireLevel)
      .slice(0, 5)
      .map(buildFirePopoverRow)
  const routeReady = highestRows.filter(row => row.metrics.some(item => item.key === 'geoQuality' && /READY|OK|AUTO/.test(item.value))).length
  return {
    title: '最高等级火情',
    subtitle: '火情等级研判',
    statusLabel: fireEventsError ? '接口异常' : (highestFireLevel === 'UNKNOWN' ? '暂无等级' : highestFireLevel),
    statusTone: highestFireLevel === 'HIGH' || highestFireLevel === 'MEDIUM' ? 'danger' : 'safe',
    error: fireEventsError,
    emptyText: '暂无最高等级火情',
    stats: [
      { key: 'highest', label: '最高等级', value: highestFireLevel === 'UNKNOWN' ? '--' : highestFireLevel, tone: highestFireLevel === 'HIGH' || highestFireLevel === 'MEDIUM' ? 'danger' : 'safe' },
      { key: 'highestCount', label: '同级事件', value: String(highestRows.length), tone: highestRows.length > 0 ? 'danger' : 'default' },
      { key: 'pending', label: '待处置', value: String(pendingFireEvents), tone: pendingFireEvents > 0 ? 'warning' : 'default' },
      { key: 'routeReady', label: '定位可用', value: String(routeReady), tone: 'safe' }
    ],
    rows: highestRows
  }
}

function buildAiEventsPopover ({
  aiEvents,
  highRiskAiEvents,
  aiEventsError
}) {
  const rows = aiEvents.slice(-5).reverse().map((event, index) => {
    const level = normalizeLevel(event?.riskLevel)
    const title = event?.eventId || event?.id || event?.sourceId || `AI-${index + 1}`
    const visibleScore = formatScorePercent(event?.visibleScore)
    const fusionScore = formatScorePercent(event?.fusionScore)
    return {
      key: `ai:${title}:${event?.sourceTs || event?.eventTimestamp || event?.createTime || index}`,
      title,
      status: level === 'UNKNOWN' ? 'UNKNOWN' : level,
      statusTone: level === 'HIGH' || level === 'MEDIUM' ? 'danger' : (level === 'LOW' ? 'safe' : 'default'),
      meta: formatTimeLabel(event?.sourceTs || event?.eventTimestamp || event?.createTime),
      detail: `${event?.analysisChannel || '通道未知'} · 可见光 ${visibleScore} · 融合 ${fusionScore} · ${formatAiReviewText(event?.reviewStatus)}`,
      metrics: [
        { key: 'channel', label: '通道', value: event?.analysisChannel || '--' },
        { key: 'visibleScore', label: '可见光', value: visibleScore },
        { key: 'fusionScore', label: '融合', value: fusionScore },
        { key: 'reviewStatus', label: '复核', value: formatAiReviewText(event?.reviewStatus) }
      ]
    }
  })
  const latest = rows[0]
  return {
    title: 'AI 识别详情',
    subtitle: '双光 AI 识别事件',
    statusLabel: aiEventsError ? '接口异常' : (aiEvents.length > 0 ? '识别同步' : '暂无识别'),
    statusTone: aiEventsError ? 'danger' : (highRiskAiEvents.length > 0 ? 'danger' : 'default'),
    error: aiEventsError,
    emptyText: '暂无 AI 识别事件',
    stats: [
      { key: 'total', label: '识别事件', value: String(aiEvents.length), tone: aiEvents.length > 0 ? 'default' : 'default' },
      { key: 'risk', label: '风险记录', value: String(highRiskAiEvents.length), tone: highRiskAiEvents.length > 0 ? 'danger' : 'default' },
      { key: 'high', label: 'HIGH', value: String(aiEvents.filter(event => normalizeLevel(event?.riskLevel) === 'HIGH').length), tone: 'danger' },
      { key: 'latest', label: '最新通道', value: latest?.metrics.find(item => item.key === 'channel')?.value || '--', tone: 'default' }
    ],
    rows
  }
}

function buildAircraftOnlinePopover ({
  aircraftRows,
  onlineCount,
  totalCount
}) {
  return {
    title: '在线飞机详情',
    subtitle: '监测机与投放机状态',
    statusLabel: totalCount === 0 ? '未接入' : `${onlineCount} 架在线`,
    statusTone: onlineCount > 0 ? 'safe' : 'danger',
    emptyText: '暂无飞机或 FC100 投放设备',
    stats: [
      { key: 'online', label: '在线/总数', value: `${onlineCount}/${totalCount}`, tone: onlineCount > 0 ? 'safe' : 'danger' },
      { key: 'monitor', label: '监测机', value: String(aircraftRows.filter(row => row.role === '火情监测').length), tone: 'default' },
      { key: 'delivery', label: '投放机', value: String(aircraftRows.filter(row => row.role === 'FC100 投放').length), tone: 'default' },
      { key: 'batteryKnown', label: '电量上报', value: String(aircraftRows.filter(row => Number.isFinite(Number(row.battery))).length), tone: 'default' }
    ],
    rows: aircraftRows.map(row => ({
      key: row.key,
      title: row.name,
      status: row.online ? '在线' : '离线',
      statusTone: row.online ? 'safe' : 'danger',
      meta: `${row.role} · SN ${row.sn || '--'}`,
      detail: `${row.status || '--'} · 电量 ${formatPercent(row.battery)} · ${row.detail || '--'}`,
      metrics: [
        { key: 'role', label: '类型', value: row.role },
        { key: 'sn', label: 'SN', value: row.sn || '--' },
        { key: 'battery', label: '电量', value: formatPercent(row.battery) },
        { key: 'status', label: '状态', value: row.status || '--' }
      ]
    }))
  }
}

function buildFirePopoverRow (event, index = 0) {
  const level = normalizeLevel(event?.fireLevel)
  const id = event?.eventId || event?.id || event?.fireId || `fire-${index + 1}`
  const confidence = event?.confidence ?? event?.fusionScore ?? event?.score
  const confidenceText = Number.isFinite(Number(confidence)) ? Number(confidence).toFixed(2) : '--'
  const status = event?.status || event?.missionStatus || 'UNKNOWN'
  return {
    key: `fire:${id}`,
    title: `${level === 'UNKNOWN' ? 'UNKNOWN' : level} · ${id}`,
    status,
    statusTone: level === 'HIGH' || level === 'MEDIUM' ? 'danger' : (level === 'LOW' ? 'safe' : 'default'),
    meta: formatTimeLabel(event?.lastSeenTime || event?.eventTimestamp || event?.createTime),
    detail: `置信度 ${confidenceText} · ${formatCoordinate(event)} · ${event?.missionNo ? `任务 ${event.missionNo}` : '未关联任务'}`,
    metrics: [
      { key: 'confidence', label: '置信度', value: confidenceText },
      { key: 'geoQuality', label: '定位', value: event?.geoQuality || '--' },
      { key: 'mission', label: '任务', value: event?.missionNo || '未关联' },
      { key: 'action', label: '建议', value: formatFireActionNote(event) }
    ]
  }
}

function buildAircraftHoverTitle (msdkDevices, deliveryTargets) {
  const rows = [
    ...msdkDevices.map(device => {
      const status = device?.online ? (device?.mode || device?.connectionState || 'online') : 'offline'
      return formatHoverRow(resolveMonitorModel(device), device?.aircraftSn, status, device?.batteryPercent)
    }),
    ...deliveryTargets.map(target => {
      const status = target?.online ? (target?.taskStatus || target?.streamStatus || 'online') : 'offline'
      return formatHoverRow(resolveDeliveryModel(target), target?.deviceSn, status, target?.batteryPercent)
    })
  ].filter(Boolean)
  return rows.join('\n')
}

function buildLiveHoverTitle ({
  msdkDevices,
  deliveryTargets,
  dualStreamGroup,
  runningDualStream,
  runningDeliveryTargets
}) {
  const rows = []
  const liveMonitorDevices = msdkDevices.filter(device => {
    return device?.online || device?.aircraftSn === dualStreamGroup?.droneSn || runningDualStream
  })
  for (const device of liveMonitorDevices) {
    const status = runningDualStream ? (dualStreamGroup?.sessionState || 'live') : (device?.connectionState || 'idle')
    rows.push(formatHoverRow(resolveMonitorModel(device), device?.aircraftSn, status, device?.batteryPercent))
  }
  for (const target of deliveryTargets) {
    if (target?.streamStatus !== 'running' && !target?.primaryPlayUrl && !target?.online) continue
    const isRunning = runningDeliveryTargets.some(item => item?.deviceSn === target?.deviceSn)
    const status = isRunning || target?.streamStatus === 'running' ? 'live' : (target?.streamStatus || 'idle')
    rows.push(formatHoverRow(resolveDeliveryModel(target), target?.deviceSn, status, target?.batteryPercent))
  }
  return rows.join('\n')
}

function buildLiveCardNote ({
  msdkDevices,
  deliveryTargets,
  dualStreamGroup,
  runningDualStream,
  runningDeliveryTargets
}) {
  const monitorParts = msdkDevices.map(device => {
    const status = runningDualStream || device?.aircraftSn === dualStreamGroup?.droneSn
      ? '监测直播'
      : (device?.online ? '监测在线' : '监测离线')
    return `${resolveMonitorModel(device)} ${status}`
  })
  const deliveryParts = deliveryTargets.map(target => {
    const running = runningDeliveryTargets.some(item => item?.deviceSn === target?.deviceSn)
    const status = running || target?.streamStatus === 'running' ? '直播在线' : (target?.online ? '待播放' : '离线')
    return `${resolveDeliveryModel(target)} ${status}`
  })
  return [...monitorParts, ...deliveryParts].filter(Boolean).join(' / ')
}

function buildBatteryCardNote (msdkDevices, deliveryTargets) {
  const parts = [
    ...msdkDevices
      .filter(device => Number.isFinite(Number(device?.batteryPercent)))
      .map(device => `${resolveMonitorModel(device)} ${formatPercent(device.batteryPercent)}`),
    ...deliveryTargets
      .filter(target => Number.isFinite(Number(target?.batteryPercent)))
      .map(target => `${resolveDeliveryModel(target)} ${formatPercent(target.batteryPercent)}`)
  ]
  return parts.join(' / ')
}

function buildBatteryHoverTitle (msdkDevices, deliveryTargets) {
  const rows = [
    ...msdkDevices
      .filter(device => Number.isFinite(Number(device?.batteryPercent)))
      .map(device => {
        const status = device?.online ? (device?.mode || device?.connectionState || 'online') : 'offline'
        return formatHoverRow(resolveMonitorModel(device), device?.aircraftSn, status, device?.batteryPercent)
      }),
    ...deliveryTargets
      .filter(target => Number.isFinite(Number(target?.batteryPercent)))
      .map(target => {
        const status = target?.online ? (target?.taskStatus || target?.streamStatus || 'online') : 'offline'
        return formatHoverRow(resolveDeliveryModel(target), target?.deviceSn, status, target?.batteryPercent)
      })
  ].filter(Boolean)
  return rows.join('\n')
}

function formatHoverRow (model, sn, status, batteryPercent) {
  const snText = sn || 'SN --'
  const batteryText = Number.isFinite(Number(batteryPercent)) ? `battery ${formatPercent(batteryPercent)}` : 'battery --'
  return `${model} | ${snText} | ${status || 'unknown'} | ${batteryText}`
}

function formatScorePercent (value) {
  const n = Number(value)
  if (!Number.isFinite(n)) return '--'
  const normalized = n <= 1 ? n * 100 : n
  return formatPercent(normalized)
}

function formatAiReviewText (value) {
  const status = String(value || '').toUpperCase()
  if (!status) return '未复核'
  if (status === 'CONFIRMED' || status === 'APPROVED') return '已确认'
  if (status === 'REJECTED' || status === 'IGNORED') return '已排除'
  if (status === 'PENDING' || status === 'WAITING') return '待复核'
  return value
}

function resolveMonitorModel (device) {
  return device?.model || device?.deviceName || (device?.aircraftSn ? `Monitor ${device.aircraftSn.slice(-4)}` : 'Monitor aircraft')
}

function resolveDeliveryModel (target) {
  const raw = [
    target?.model,
    target?.displayName,
    target?.callsign,
    target?.deviceType,
    target?.deviceModelKey,
    target?.deviceModelClass
  ].filter(Boolean).join(' ').toLowerCase()
  if (raw.includes('0-122-0') || raw.includes('fc100') || raw.includes('flycart')) {
    return 'DJI Flycart100'
  }
  return target?.model || target?.displayName || target?.callsign || (target?.deviceSn ? `DJI Flycart100 ${target.deviceSn.slice(-4)}` : 'DJI Flycart100')
}

function buildAircraftRows (msdkDevices, deliveryTargets) {
  return [
    ...msdkDevices.map(device => ({
      key: `msdk:${device.aircraftSn}`,
      role: '火情监测',
      sn: device.aircraftSn || '--',
      name: resolveMonitorModel(device),
      online: Boolean(device.online),
      status: device.online ? (device.mode || device.connectionState || '在线') : '离线',
      battery: device.batteryPercent,
      detail: `GPS ${device.gpsCount ?? '--'} / RTK ${device.rtkCount ?? '--'} / H ${formatNumber(device.height, 1)}m`
    })),
    ...deliveryTargets.map(target => ({
      key: `delivery:${target.deviceSn}`,
      role: 'FC100 投放',
      sn: target.deviceSn || '--',
      name: resolveDeliveryModel(target),
      online: Boolean(target.online),
      status: target.streamStatus === 'running' ? '直播在线' : (target.taskStatus || target.streamStatus || '待命'),
      battery: target.batteryPercent,
      detail: target.message || target.primaryPlayUrl || '等待投放平台状态'
    }))
  ]
}

function selectMonitorDevice (msdkDevices) {
  return msdkDevices.find(device => device?.online && device?.aircraftSn) ||
    msdkDevices.find(device => device?.aircraftSn)
}

function selectDeliveryTarget (deliveryTargets) {
  return deliveryTargets.find(target => target?.online && target?.streamStatus === 'running') ||
    deliveryTargets.find(target => target?.online) ||
    deliveryTargets[0]
}

function formatFireFocus (event) {
  if (!event) return '等待火情'
  const level = normalizeLevel(event.fireLevel)
  return `${level === 'UNKNOWN' ? '未分级' : level} · ${event.eventId || event.id || event.fireId || '火情'}`
}

function formatFireDetail (event) {
  const confidence = event.confidence ?? event.fusionScore ?? event.score
  const confidenceText = Number.isFinite(Number(confidence)) ? `置信度 ${Number(confidence).toFixed(2)}` : '置信度 --'
  const coord = formatCoordinate(event)
  return `${confidenceText} / ${coord} / ${event.missionNo ? `任务 ${event.missionNo}` : '未关联任务'}`
}

function formatFireActionNote (event) {
  if (!event) return ''
  if (event.missionNo) return `已关联任务 ${event.missionNo}`
  if (isRouteReadyFireLocation(event)) return '定位满足航线生成条件'
  return `定位质量 ${event.geoQuality || '未知'}，建议先人工复核`
}

function formatCoordinate (event) {
  return formatFireLocation(event, 5, '坐标未知')
}

function buildFireNextAction (event, fireEventsError) {
  if (fireEventsError) return '检查火情接口'
  if (!event) return '等待火情上报'
  if (event.missionNo) return '跟踪投放任务'
  if (isRouteReadyFireLocation(event)) return '可生成航线'
  return '人工复核定位'
}

function formatMonitorFocus (device) {
  if (!device) return '未选择监测机'
  return device.model || device.deviceName || (device.aircraftSn ? `监测机 ${device.aircraftSn.slice(-4)}` : '监测机')
}

function formatDeliveryFocus (target) {
  if (!target) return '未选择 FC100'
  return target.callsign || (target.deviceSn ? `FC100 ${target.deviceSn.slice(-4)}` : 'FC100')
}

function formatAircraftSignal (device) {
  return `GPS ${device?.gpsCount ?? '--'} / RTK ${device?.rtkCount ?? '--'} / H ${formatNumber(device?.height, 1)}m`
}

function buildMonitorNextAction ({ monitorDevice, visibleOnline, thermalOnline, aiEvents, dualStreamError }) {
  if (dualStreamError) return '检查双光直播'
  if (!monitorDevice) return '选择监测机'
  if (!visibleOnline || !thermalOnline) return '接入双光画面'
  if (aiEvents.length === 0) return '启动或等待 AI 识别'
  return '人工复核最新识别'
}

function buildDeliveryNextAction ({ deliveryTarget, deliveryTask, deliveryTargetsLoading }) {
  if (deliveryTargetsLoading) return '同步 FC100 平台'
  if (!deliveryTarget) return '选择 FC100'
  if (!deliveryTarget.online) return '检查 FC100 在线'
  if (!deliveryTask) return '等待投放任务'
  if (deliveryTask.progressPercent == null) return '跟踪任务阶段'
  return Number(deliveryTask.progressPercent) >= 100 ? '等待结果回传' : '跟踪执行进度'
}

function buildInstrumentReadouts (device) {
  return [
    { key: 'ALT', label: 'ALT', value: formatNumber(device?.height, 1), unit: 'm' },
    { key: 'H.S', label: 'H.S', value: formatNumber(device?.horizontalSpeed, 1), unit: 'm/s' },
    { key: 'V.S', label: 'V.S', value: formatNumber(device?.verticalSpeed, 1), unit: 'm/s' },
    { key: 'W.S', label: 'W.S', value: formatNumber(device?.windSpeed, 1), unit: 'm/s' },
    { key: 'GPS', label: 'GPS', value: device?.gpsCount == null ? '--' : String(device.gpsCount), unit: '' },
    { key: 'RTK', label: 'RTK', value: device?.rtkCount == null ? '--' : String(device.rtkCount), unit: '' },
    { key: 'BATT', label: '电量', value: formatPercent(device?.batteryPercent), unit: '' }
  ]
}

function buildAiTicks (aiEvents) {
  if (aiEvents.length === 0) {
    return [
      { key: 'empty-1', status: 'idle' },
      { key: 'empty-2', status: 'idle' },
      { key: 'empty-3', status: 'idle' },
      { key: 'empty-4', status: 'idle' },
      { key: 'empty-5', status: 'idle' },
      { key: 'empty-6', status: 'idle' }
    ]
  }
  return aiEvents.slice(-6).map((event, index) => ({
    key: event.eventId || event.id || `ai-${index}`,
    status: normalizeLevel(event.riskLevel).toLowerCase()
  }))
}

function formatTimeLabel (value) {
  const raw = Number(value)
  const date = Number.isFinite(raw) ? new Date(raw) : new Date(value)
  if (Number.isNaN(date.getTime())) return '--'
  return date.toLocaleTimeString('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false
  })
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
import {
  formatFireLocation,
  isRouteReadyFireLocation
} from './fire/fire-event-location.mjs'
