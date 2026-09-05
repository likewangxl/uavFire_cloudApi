const { getRuntimeConfig } = require('../config/runtime')
const session = require('./session')

function createRequestId() {
  return `mp-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}

function normalizeBaseUrl(value) {
  return String(value || '').replace(/\/+$/, '')
}

function request(options) {
  const config = getRuntimeConfig()
  const baseUrl = normalizeBaseUrl(config.apiBaseUrl)
  if (!baseUrl) {
    return Promise.reject({
      code: 'CLIENT_CONFIG_MISSING',
      message: '尚未配置小程序后端 HTTPS 地址',
    })
  }

  const token = session.getAccessToken()
  const headers = Object.assign({
    'content-type': 'application/json',
    'X-Request-Id': createRequestId(),
  }, options.header || {})
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }

  return new Promise((resolve, reject) => {
    wx.request({
      url: `${baseUrl}${options.path}`,
      method: options.method || 'GET',
      data: options.data,
      header: headers,
      timeout: options.timeout || 12000,
      success(result) {
        const envelope = result.data || {}
        if (result.statusCode >= 200 && result.statusCode < 300 && envelope.code === 'OK') {
          resolve(envelope.data)
          return
        }
        if (result.statusCode === 401) {
          session.clearSession()
        }
        reject({
          statusCode: result.statusCode,
          requestId: envelope.requestId,
          code: envelope.code || 'HTTP_ERROR',
          message: envelope.message || '服务请求失败',
          details: envelope.details,
        })
      },
      fail(error) {
        reject({
          code: 'NETWORK_ERROR',
          message: error.errMsg || '网络连接失败',
        })
      },
    })
  })
}

module.exports = {
  request,
}
