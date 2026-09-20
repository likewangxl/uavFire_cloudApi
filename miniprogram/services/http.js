const { getRuntimeConfig } = require('../config/runtime')
const session = require('./session')
let refreshInFlight = null

function createRequestId() { return `mp-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}` }
function transport(options, token) {
  const base = String(getRuntimeConfig().apiBaseUrl || '').replace(/\/+$/, '')
  if (!base) return Promise.reject({ code: 'CLIENT_CONFIG_MISSING', message: '请先配置小程序后端 HTTPS 地址' })
  const local = /^http:\/\/(localhost|127\.0\.0\.1)(:\d+)?\/miniapp\/api\/v1$/.test(base)
  let devtools = false
  if (local) {
    try { devtools = (typeof wx.getDeviceInfo === 'function' ? wx.getDeviceInfo() : wx.getSystemInfoSync()).platform === 'devtools' } catch (_) {}
  }
  if (!/^https:\/\/[^/?#@]+\/miniapp\/api\/v1$/.test(base) && !(local && devtools)) {
    return Promise.reject({ code: 'CLIENT_CONFIG_INVALID', message: '真实设备必须使用已登记的 HTTPS 接口地址' })
  }
  const headers = { 'content-type': 'application/json', 'X-Request-Id': createRequestId() }
  if (token) headers.Authorization = `Bearer ${token}`
  return new Promise((resolve, reject) => wx.request({
    url: base + options.path, method: options.method || 'GET', data: options.data, header: headers, timeout: 12000,
    success(result) {
      const body = result.data || {}
      if (result.statusCode >= 200 && result.statusCode < 300 && body.code === 'OK') return resolve(body.data)
      reject({ statusCode: result.statusCode, code: body.code || 'HTTP_ERROR', message: body.message || '请求失败', requestId: body.requestId })
    },
    fail() { reject({ code: 'NETWORK_ERROR', message: '网络连接失败，请稍后重试' }) },
  }))
}
function refresh() {
  if (refreshInFlight) return refreshInFlight
  const version = session.getVersion()
  const refreshToken = session.getRefreshToken()
  const pending = transport({ path: '/auth/refresh', method: 'POST', data: { refreshToken, deviceId: session.ensureDeviceId() } }, '')
    .then(value => {
      if (session.getVersion() !== version) throw { code: 'SESSION_CHANGED', message: '登录状态已改变，请重试' }
      session.setSession(value)
    }).catch(error => {
      if (session.getVersion() === version && [401, 403].includes(error.statusCode)) session.clearSession()
      throw error
    }).finally(() => { if (refreshInFlight === pending) refreshInFlight = null })
  refreshInFlight = pending
  return pending
}
function request(options, retried = false) {
  const identity = session.getSession().sessionId
  const token = options.auth === false ? '' : session.getAccessToken()
  const changedSession = () => identity !== session.getSession().sessionId
  const sessionChanged = () => ({ code: 'SESSION_CHANGED', message: '登录状态已改变，请重试' })
  return transport(options, token).then(value => {
    if (options.auth !== false && changedSession()) throw sessionChanged()
    return value
  }).catch(error => {
    if (options.auth === false) throw error
    if (changedSession()) throw sessionChanged()
    if (error.statusCode === 401 && !retried && session.getRefreshToken()) {
      const changed = session.getAccessToken() !== token
      return (changed ? Promise.resolve() : refresh()).then(() => {
        if (changedSession()) throw sessionChanged()
        return request(options, true)
      })
    }
    if ([401, 403].includes(error.statusCode) && session.getAccessToken() === token) session.clearSession()
    throw error
  })
}
module.exports = { request }
