import request from '/@/api/http/request'

const BASE = '/manage/api/v1/video-bandwidth'

export async function updateVideoViewer (viewerId: string, droneSn: string | null) {
  const response = await request.post(`${BASE}/viewers/${encodeURIComponent(viewerId)}`, { drone_sn: droneSn }, { timeout: 5000 })
  if (!response?.data || response.data.code !== 0) throw new Error('video-viewer-unavailable')
}

export async function getVideoBandwidthStatus () {
  const response = await request.get(`${BASE}/status`, { timeout: 5000 })
  if (!response?.data || response.data.code !== 0) throw new Error('video-status-unavailable')
  return response.data.data
}
