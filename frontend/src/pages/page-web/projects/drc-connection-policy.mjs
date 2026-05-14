export const DRC_DISCONNECT_GRACE_MS = 10000

export function getDrcMqttDisconnectDecision ({
  status,
  remoteConnected,
  sameClient = true,
  officialTakeoffLocked = false,
}) {
  if (!remoteConnected || !sameClient) {
    return 'ignore'
  }
  if (status === 'close' || status === 'error') {
    if (officialTakeoffLocked) {
      return 'preserve_session'
    }
    return 'defer_disconnect'
  }
  if (status === 'open') {
    return 'clear_pending_disconnect'
  }
  return 'ignore'
}
