export function isDeviceOnline (device) {
  const value = device?.onlineStatus ?? device?.online ?? device?.status ?? device?.connectionState
  if (value === true || value === 1) return true
  if (typeof device?.mode === 'number') return device.mode !== 14
  if (typeof value !== 'string') return false
  const normalized = value.trim().toLowerCase()
  return ['1', 'true', 'online', 'connected', '已连接', '在线'].includes(normalized)
}

export function getDeviceSortLabel (device) {
  return String(
    device?.model ||
      device?.deviceType ||
      device?.callsign ||
      device?.deviceSn ||
      device?.aircraftSn ||
      device?.sn ||
      ''
  )
}

export function sortDevicesOnlineFirst (devices = []) {
  return [...devices].sort((a, b) => {
    const onlineDelta = Number(isDeviceOnline(b)) - Number(isDeviceOnline(a))
    if (onlineDelta !== 0) return onlineDelta
    const labelDelta = getDeviceSortLabel(a).localeCompare(getDeviceSortLabel(b), 'zh-CN')
    if (labelDelta !== 0) return labelDelta
    return String(a?.deviceSn || a?.aircraftSn || a?.sn || '').localeCompare(String(b?.deviceSn || b?.aircraftSn || b?.sn || ''), 'zh-CN')
  })
}

export function pickSelectedDeviceSn (devices = [], currentSn = '', getSn = device => device?.deviceSn || device?.aircraftSn || device?.sn || '') {
  if (currentSn && devices.some(device => getSn(device) === currentSn)) {
    return currentSn
  }
  const firstOnline = devices.find(isDeviceOnline)
  return getSn(firstOnline || devices[0] || {}) || ''
}
