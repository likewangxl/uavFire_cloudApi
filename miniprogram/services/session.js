const ACCESS_TOKEN_KEY = 'uavfire.miniapp.accessToken'
const DEVICE_ID_KEY = 'uavfire.miniapp.deviceId'

function createDeviceId() {
  const random = Math.random().toString(36).slice(2, 12)
  return `wx-${Date.now().toString(36)}-${random}`
}

function ensureDeviceId() {
  const existing = wx.getStorageSync(DEVICE_ID_KEY)
  if (existing) {
    return existing
  }
  const deviceId = createDeviceId()
  wx.setStorageSync(DEVICE_ID_KEY, deviceId)
  return deviceId
}

function getAccessToken() {
  return wx.getStorageSync(ACCESS_TOKEN_KEY) || ''
}

function setAccessToken(token) {
  if (token) {
    wx.setStorageSync(ACCESS_TOKEN_KEY, token)
  } else {
    wx.removeStorageSync(ACCESS_TOKEN_KEY)
  }
}

function clearSession() {
  wx.removeStorageSync(ACCESS_TOKEN_KEY)
}

module.exports = {
  clearSession,
  ensureDeviceId,
  getAccessToken,
  setAccessToken,
}
