// Control availability uses recent telemetry, never default height/battery values.
export function controlContext (device, now = Date.now()) {
  const rawTime = Number(device?.updatedAt)
  const updatedAt = rawTime > 0 && rawTime < 1e12 ? rawTime * 1000 : rawTime
  const fresh = Number.isFinite(updatedAt) && now - updatedAt >= -5000 && now - updatedAt <= 15000
  const ready = device?.online === true && fresh && typeof device?.height === 'number' && Number.isFinite(device.height)
  const model = String(device?.aircraftModelKey || device?.model || '').toUpperCase().replace(/[^A-Z0-9]/g, '')
  const m300 = ['M300', 'M300RTK', 'MATRICE300RTK', 'DJIMATRICE300RTK'].includes(model)
  return {
    ready,
    hint: !device ? '选择飞机后查看操作' : !device.online ? '飞机离线，控制不可用' : !fresh ? '遥测未更新，等待设备上报' : !ready ? '等待有效高度遥测' : '已接收近期设备遥测',
    detectionAllowed: ready && (!m300 || device.fireClosedLoopReady === true),
    detectionHint: m300 && device.fireClosedLoopReady !== true ? (device.blockingReasons || []).join('；') || 'M300 火情闭环能力尚未就绪' : '',
    osd: ready
      ? {
          mode_code: device.mode || 'CONNECTED',
          height: device.height,
          latitude: device.latitude,
          longitude: device.longitude,
          home_distance: device.homeDistance,
          wind_speed: device.windSpeed,
          battery: { capacity_percent: device.batteryPercent }
        }
      : null
  }
}
