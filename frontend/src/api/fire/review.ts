import client from './client'
import type { ApiResult } from '/@/types/fire/api'

export interface ReviewDTO {
  id: number;
  missionId: number;
  reviewerId: string;
  afterTemperature: number | null;
  temperatureUnit: string | null;
  afterThermalImageUrl: string | null;
  afterVisibleImageUrl: string | null;
  fireSuppressed: number;
  needSecondDrop: number;
  suggestion: string | null;
  remark: string | null;
  createTime: number;
}

export interface ReviewSubmitBody {
  reviewerId: string;
  afterTemperature?: number;
  temperatureUnit?: string;
  afterThermalImageUrl?: string;
  afterVisibleImageUrl?: string;
  fireSuppressed?: boolean;
  needSecondDrop?: boolean;
  remark?: string;
}

export const reviewApi = {
  submit: (no: string, body: ReviewSubmitBody) =>
    client.post<ApiResult<null>>(`/api/fire/missions/${no}/review`, body),

  get: (no: string) =>
    client.get<ApiResult<ReviewDTO>>(`/api/fire/missions/${no}/review`),
}
