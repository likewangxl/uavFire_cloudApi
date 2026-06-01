export function buildDeliveryExecutionHud ({
  selectedDeviceSn = '',
  deliveryTargetSummary = '',
  taskPhase = '',
  taskProgress = '',
  aircraftStatus = '',
  liveSource = '',
  taskMessage = ''
} = {}) {
  const deviceText = selectedDeviceSn || '未选择飞行器'
  const sourceText = liveSource || '未上报'
  const aircraftText = aircraftStatus || '未上报'
  const targetText = deliveryTargetSummary || '无可选投放目标'
  const phaseText = taskPhase || '待命'
  const progressText = taskProgress || '--'
  const messageText = taskMessage || '暂无投放任务消息'

  return {
    identity: [
      { label: '播放对象', value: deviceText },
      { label: '直播来源', value: sourceText }
    ],
    flight: [
      { label: '飞行器状态', value: aircraftText },
      { label: '投放目标', value: targetText }
    ],
    task: [
      { label: '任务阶段', value: phaseText },
      { label: '执行进度', value: progressText }
    ],
    chips: [
      deviceText,
      `来源 ${sourceText}`,
      `阶段 ${phaseText}`,
      `进度 ${progressText}`,
      aircraftText
    ],
    flightRows: [
      [{ label: '投放设备', value: deviceText }],
      [{ label: '状态', value: aircraftText }],
      [
        { label: '任务', value: phaseText },
        { label: '进度', value: progressText }
      ],
      [{ label: '目标', value: targetText }]
    ],
    message: messageText
  }
}
