const { getRuntimeConfig } = require('../../config/runtime')
const session = require('../../services/session')

Page({
  data: {
    apiStatus: '未配置',
    loginStatus: '未登录',
    clientVersion: '0.1.0',
    flightControlStatus: '关闭',
  },

  onShow() {
    const config = getRuntimeConfig()
    this.setData({
      apiStatus: config.apiBaseUrl ? '已配置' : '未配置',
      loginStatus: session.getAccessToken() ? '已登录' : '未登录',
      clientVersion: config.clientVersion,
      flightControlStatus: config.flightControlEnabled ? '受控开放' : '关闭',
    })
  },

  clearSession() {
    session.clearSession()
    this.setData({ loginStatus: '未登录' })
    wx.showToast({ title: '本机登录凭证已清除', icon: 'none' })
  },
})
