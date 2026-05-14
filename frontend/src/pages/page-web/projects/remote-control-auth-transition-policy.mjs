export const CLOUD_CONTROL_AUTH_RELEASE_GRACE_MS = 10000

export function getCloudControlAuthTransitionDecision ({
  currentAuthorized,
  nextAuthorized,
  remoteConnected,
  reconnecting = false,
  officialTakeoffLocked = false,
}) {
  if (nextAuthorized) {
    return {
      nextAuthorized: true,
      notice: currentAuthorized ? 'noop' : 'restored',
    }
  }

  if (currentAuthorized && remoteConnected && reconnecting) {
    return {
      nextAuthorized: true,
      notice: 'defer_release',
    }
  }

  if (currentAuthorized && remoteConnected && officialTakeoffLocked) {
    return {
      nextAuthorized: false,
      notice: 'autonomous_release',
    }
  }

  return {
    nextAuthorized: false,
    notice: currentAuthorized ? 'released' : 'noop',
  }
}
