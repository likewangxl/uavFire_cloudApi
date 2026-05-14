export function getRemoteReconnectDecision ({
  remoteConnected,
  currentGatewaySn,
  targetGatewaySn,
}) {
  if (!remoteConnected) {
    return 'connect_fresh'
  }

  if (currentGatewaySn && targetGatewaySn && currentGatewaySn === targetGatewaySn) {
    return 'reuse_existing_session'
  }

  return 'disconnect_before_connect'
}
