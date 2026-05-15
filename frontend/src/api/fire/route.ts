import client from './client'
import type { ApiResult } from '/@/types/fire/api'

export interface RouteFileDTO {
  id: number;
  missionNo: string;
  objectKey: string;
  sign: string;
  version: number;
  createTime: number;
}

export interface DownloadUrlDTO {
  url: string;
  expiresInSec: number;
}

export const routeApi = {
  exportKmz: (no: string, body: { operatorId: string }) =>
    client.post<ApiResult<RouteFileDTO>>(`/api/fire/missions/${no}/route/export-kmz`, body),

  getLatest: (no: string) =>
    client.get<ApiResult<RouteFileDTO>>(`/api/fire/missions/${no}/route/files/latest`),

  downloadUrl: (no: string, fileId: number) =>
    `/api/fire/missions/${no}/route/files/${fileId}/download`,

  fileDownloadUrl: (no: string, fileId: number) =>
    client.get<ApiResult<DownloadUrlDTO>>(`/api/fire/missions/${no}/route/files/${fileId}/download-url`),
}
