export const DRC_LINK_STATE = {
  DISCONNECT: 0,
  CONNECTING: 1,
  CONNECT: 2,
}

export function getDrcWsEventDecision ({
  bizCode,
  remoteConnected,
  drcState,
  officialTakeoffLocked = false,
}) {
  if (!remoteConnected) {
    return {
      updateDrcLinkState: null,
      updateJoystickAvailable: null,
      destroyRemoteSession: false,
      notice: 'noop',
    }
  }

  if (bizCode === 'joystick_invalid_notify') {
    return {
      updateDrcLinkState: null,
      updateJoystickAvailable: false,
      destroyRemoteSession: false,
      notice: officialTakeoffLocked ? 'autonomous_joystick_invalid' : 'noop',
    }
  }

  if (bizCode === 'drc_status_notify') {
    return {
      updateDrcLinkState: drcState ?? null,
      updateJoystickAvailable: drcState === DRC_LINK_STATE.CONNECT ? true : null,
      destroyRemoteSession: false,
      notice: officialTakeoffLocked && drcState === DRC_LINK_STATE.DISCONNECT ? 'autonomous_disconnect' : 'noop',
    }
  }

  return {
    updateDrcLinkState: null,
    updateJoystickAvailable: null,
    destroyRemoteSession: false,
    notice: 'noop',
  }
}
