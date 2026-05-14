export function buildRemoteSessionDisconnectAudit ({
  source,
  gatewaySn,
  aircraftSn,
  clientId,
  officialTakeoffPhase,
  cloudControlAuthorized,
  drcLinkState,
  joystickAvailable,
}) {
  return {
    source: source || 'unknown',
    gatewaySn: gatewaySn || '',
    aircraftSn: aircraftSn || '',
    clientId: clientId || '',
    officialTakeoffPhase: officialTakeoffPhase || 'idle',
    cloudControlAuthorized: Boolean(cloudControlAuthorized),
    drcLinkState: drcLinkState ?? null,
    joystickAvailable: Boolean(joystickAvailable),
  }
}
