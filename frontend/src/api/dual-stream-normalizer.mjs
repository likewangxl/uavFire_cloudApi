const pick = (source, camelKey, snakeKey) => {
  if (!source || typeof source !== 'object') {
    return undefined
  }
  if (source[camelKey] !== undefined) {
    return source[camelKey]
  }
  return source[snakeKey]
}

export const normalizeDualStreamGroup = (group) => {
  if (!group || typeof group !== 'object') {
    return group ?? null
  }

  return {
    droneSn: pick(group, 'droneSn', 'drone_sn'),
    connectionState: pick(group, 'connectionState', 'connection_state'),
    sessionState: pick(group, 'sessionState', 'session_state'),
    liveStatus: pick(group, 'liveStatus', 'live_status'),
    currentMode: pick(group, 'currentMode', 'current_mode'),
    statusMessage: pick(group, 'statusMessage', 'status_message'),
    visibleState: pick(group, 'visibleState', 'visible_state'),
    thermalState: pick(group, 'thermalState', 'thermal_state'),
    statusReason: pick(group, 'statusReason', 'status_reason'),
    playbackStatus: pick(group, 'playbackStatus', 'playback_status'),
    visiblePlayUrl: pick(group, 'visiblePlayUrl', 'visible_play_url'),
    thermalPlayUrl: pick(group, 'thermalPlayUrl', 'thermal_play_url'),
    lastCommandAction: pick(group, 'lastCommandAction', 'last_command_action'),
    lastCommandStatus: pick(group, 'lastCommandStatus', 'last_command_status'),
    visibleSupported: pick(group, 'visibleSupported', 'visible_supported'),
    thermalSupported: pick(group, 'thermalSupported', 'thermal_supported')
  }
}
