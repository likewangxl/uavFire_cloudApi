import request, { IWorkspaceResponse } from '/@/api/http/request'
import { ELocalStorageKey } from '/@/types'

const DRC_API_PREFIX = '/control/api/v1'
const workspaceId: string = localStorage.getItem(ELocalStorageKey.WorkspaceId) || ''

export interface PostDrcBody {
  client_id?: string
  expire_sec?: number
}

export interface DrcParams {
  address: string
  username: string
  password: string
  client_id: string
  expire_time: number
  enable_tls: boolean
}

export async function postDrc (body: PostDrcBody): Promise<IWorkspaceResponse<DrcParams>> {
  const resp = await request.post(`${DRC_API_PREFIX}/workspaces/${workspaceId}/drc/connect`, body)
  return resp.data
}

export interface DrcEnterBody {
  client_id: string
  dock_sn?: string
  gateway_sn?: string
  expire_sec?: number
  device_info?: {
    osd_frequency?: number
    hsi_frequency?: number
  }
}

export interface DrcEnterResp {
  sub: string[]
  pub: string[]
}

export async function postDrcEnter (body: DrcEnterBody): Promise<IWorkspaceResponse<DrcEnterResp>> {
  const resp = await request.post(`${DRC_API_PREFIX}/workspaces/${workspaceId}/drc/enter`, body)
  return resp.data
}

export interface DrcExitBody {
  client_id: string
  dock_sn?: string
  gateway_sn?: string
}

export async function postDrcExit (body: DrcExitBody): Promise<IWorkspaceResponse<null>> {
  const resp = await request.post(`${DRC_API_PREFIX}/workspaces/${workspaceId}/drc/exit`, body)
  return resp.data
}
