import request, { IWorkspaceResponse } from '/@/api/http/request'

const API_PREFIX = '/control/api/v1/aircrafts'

export interface RcAircraftDrcControlBody {
  seq: number
  x?: number
  y?: number
  h?: number
  w?: number
  freq?: number
  delay_time?: number
}

export interface RcAircraftVerticalSkeletonBody {
  start_seq: number
  pulse_count: number
  throttle: number
  freq?: number
  delay_time?: number
  interval_ms?: number
  send_heartbeat?: boolean
}

export async function postRcAircraftHeartbeat (gatewaySn: string, seq: number): Promise<IWorkspaceResponse<any>> {
  const resp = await request.post(`${API_PREFIX}/${gatewaySn}/drc/heartbeat?seq=${seq}`)
  return resp.data
}

export async function postRcAircraftDrcControl (
  gatewaySn: string,
  body: RcAircraftDrcControlBody
): Promise<IWorkspaceResponse<any>> {
  const resp = await request.post(`${API_PREFIX}/${gatewaySn}/drc/control`, body)
  return resp.data
}

export async function postRcAircraftTakeoffSkeleton (
  gatewaySn: string,
  body: RcAircraftVerticalSkeletonBody
): Promise<IWorkspaceResponse<any>> {
  const resp = await request.post(`${API_PREFIX}/${gatewaySn}/jobs/takeoff-skeleton`, body)
  return resp.data
}

export async function postRcAircraftLandingSkeleton (
  gatewaySn: string,
  body: RcAircraftVerticalSkeletonBody
): Promise<IWorkspaceResponse<any>> {
  const resp = await request.post(`${API_PREFIX}/${gatewaySn}/jobs/landing-skeleton`, body)
  return resp.data
}

export async function postRcAircraftEmergencyStop (gatewaySn: string): Promise<IWorkspaceResponse<any>> {
  const resp = await request.post(`${API_PREFIX}/${gatewaySn}/drc/emergency-stop`)
  return resp.data
}
