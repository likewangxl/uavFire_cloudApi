import client from './client'
import type { ApiResult } from '/@/types/fire/api'
import type { WaypointDTO } from '/@/types/fire/waypoint'

export interface WaypointGenerateBody {
  operatorId: string;
}

export const waypointApi = {
  generate: (no: string, body: WaypointGenerateBody) =>
    client.post<ApiResult<WaypointDTO[]>>(`/api/fire/missions/${no}/waypoints/generate`, body),

  list: (no: string) =>
    client.get<ApiResult<WaypointDTO[]>>(`/api/fire/missions/${no}/waypoints`),
}
