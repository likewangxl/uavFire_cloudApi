const { getRuntimeConfig } = require('../config/runtime')
const { request } = require('./http')
const session = require('./session')

function loginWithWechat(code) {
  const config = getRuntimeConfig()
  return request({
    path: '/auth/wechat/login',
    method: 'POST',
    data: {
      code,
      deviceId: session.ensureDeviceId(),
      clientVersion: config.clientVersion,
    },
  })
}

function getMe() {
  return request({ path: '/me' })
}

function getDashboardSummary() {
  return request({ path: '/dashboard/summary' })
}

module.exports = {
  getDashboardSummary,
  getMe,
  loginWithWechat,
}
