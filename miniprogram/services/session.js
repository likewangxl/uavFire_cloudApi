const ACCESS_TOKEN_KEY = 'uavfire.miniapp.accessToken'
const SESSION_KEY = 'uavfire.miniapp.session'
const DEVICE_ID_KEY = 'uavfire.miniapp.deviceId'
let version = 0

function ensureDeviceId() {
  let value = wx.getStorageSync(DEVICE_ID_KEY)
  if (!value) {
    value = `wx-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`
    wx.setStorageSync(DEVICE_ID_KEY, value)
  }
  return value
}
function getSession() { return wx.getStorageSync(SESSION_KEY) || {} }
function getAccessToken() { return getSession().accessToken || '' }
function getRefreshToken() { return getSession().refreshToken || '' }
function setSession(value) {
  version += 1
  wx.setStorageSync(SESSION_KEY, value)
  wx.removeStorageSync(ACCESS_TOKEN_KEY)
}
function clearSession() {
  version += 1
  wx.removeStorageSync(SESSION_KEY)
  wx.removeStorageSync(ACCESS_TOKEN_KEY)
}
module.exports = { ensureDeviceId, getSession, getAccessToken, getRefreshToken, setSession, clearSession, getVersion: () => version }
