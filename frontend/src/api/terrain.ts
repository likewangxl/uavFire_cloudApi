import request from '/@/api/http/request'

const HTTP_PREFIX = '/manage/api/v1'

export interface ElevationPoint {
  lat: number
  lng: number
}

/** 批量地形高程查询（WGS84）。单次 ≤500 点，缺数据的点返回 null。 */
export const getTerrainElevations = async function (points: ElevationPoint[]): Promise<(number | null)[]> {
  const result = await request.post(`${HTTP_PREFIX}/terrain/elevations`, points)
  const body = result.data
  if (body?.code !== 0 || !Array.isArray(body?.data)) {
    throw new Error(body?.message || 'terrain elevations failed')
  }
  return body.data
}
