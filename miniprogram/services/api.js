const { getRuntimeConfig } = require('../config/runtime')
const { request } = require('./http')
const session = require('./session')
function loginWithWechat(code) {
  session.clearSession()
  const version = session.getVersion()
  return request({ path: '/auth/wechat/login', method: 'POST', auth: false, data: {
    code, deviceId: session.ensureDeviceId(), clientVersion: getRuntimeConfig().clientVersion,
  } }).then(result => {
    if (session.getVersion() !== version) throw { code: 'SESSION_CHANGED', message: '登录状态已改变，请重试' }
    if (result.loginStatus === 'AUTHENTICATED') session.setSession(result)
    return result
  })
}
function login() {
  const version = session.getVersion()
  return new Promise((resolve, reject) => wx.login({
    timeout: 10000,
    success: value => value.code ? resolve(value.code) : reject({ message: '未获得微信登录凭证，请重试' }),
    fail: () => reject({ message: '无法获取微信登录凭证，请重试' }),
  })).then(code => {
    if (session.getVersion() !== version) throw { code: 'SESSION_CHANGED', message: '登录状态已改变，请重试' }
    return loginWithWechat(code)
  })
}
function query(values) { return Object.keys(values).filter(k => values[k] !== undefined).map(k => `${encodeURIComponent(k)}=${encodeURIComponent(values[k])}`).join('&') }
const routes = { tasks: 'inspection-tasks', events: 'events', reports: 'reports' }
module.exports = {
  login, loginWithWechat,
  bind: body => request({ path: '/auth/bindings', method: 'POST', auth: false, data: body }),
  logout: () => {
    const id = session.getSession().sessionId
    return request({ path: '/auth/logout', method: 'POST' }).finally(() => {
      if (session.getSession().sessionId === id) session.clearSession()
    })
  },
  getMe: () => request({ path: '/me' }),
  getDashboardSummary: () => request({ path: '/dashboard/summary' }),
  list: (kind, values = {}) => request({ path: `/${routes[kind]}?${query(values)}` }),
  detail: (kind, id) => request({ path: `/${routes[kind]}/${encodeURIComponent(id)}` }),
  flightReport: (id, values = {}) => request({ path: '/inspection-tasks/' + encodeURIComponent(id) + '/flight-report?' + query(values) }),
  flightRelated: (id, values = {}) => request({ path: '/inspection-tasks/' + encodeURIComponent(id) + '/flight-report/related?' + query(values) }),
  bookmark: (id, saved) => request({ path: `/reports/${encodeURIComponent(id)}/bookmark`, method: 'PUT', data: { saved } }),
  getPreferences: () => request({ path: '/notification-preferences' }),
  savePreferences: values => request({ path: '/notification-preferences', method: 'PUT', data: values }),
  getDevices: () => request({ path: '/devices' }),
}
