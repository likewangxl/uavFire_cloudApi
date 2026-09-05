const api = require('../../services/api')
const { getRuntimeConfig } = require('../../config/runtime')
const session = require('../../services/session')

Page({
  data: {
    loading: false,
    configured: false,
    authenticated: false,
    statusLabel: '系统未配置',
    headline: '移动驾驶舱等待接入',
    workspaceName: '未登录',
    onlineAircraft: '—',
    todayTasks: '—',
    urgentEvents: '—',
    warning: '请先完成后端地址和微信登录服务配置。',
    flightControlEnabled: false,
  },

  onShow() {
    const config = getRuntimeConfig()
    const configured = Boolean(config.apiBaseUrl)
    const authenticated = Boolean(session.getAccessToken())
    this.setData({
      configured,
      authenticated,
      statusLabel: !configured ? '系统未配置' : (authenticated ? '数据连接中' : '待登录'),
      flightControlEnabled: Boolean(config.flightControlEnabled),
    })
    if (configured && authenticated) {
      this.refreshDashboard()
    }
  },

  handleLogin() {
    if (!this.data.configured || this.data.loading) {
      wx.showToast({ title: '请先配置后端地址', icon: 'none' })
      return
    }
    this.setData({ loading: true, warning: '' })
    wx.login({
      success: ({ code }) => {
        api.loginWithWechat(code)
          .then((result) => {
            if (result.loginStatus !== 'AUTHENTICATED' || !result.accessToken) {
              throw { message: '当前微信账号尚未完成系统账号绑定' }
            }
            session.setAccessToken(result.accessToken)
            this.setData({ authenticated: true })
            return this.refreshDashboard()
          })
          .catch((error) => this.showError(error))
          .finally(() => this.setData({ loading: false }))
      },
      fail: () => {
        this.setData({ loading: false })
        this.showError({ message: '无法获取微信登录凭证' })
      },
    })
  },

  refreshDashboard() {
    this.setData({ loading: true })
    return Promise.all([api.getMe(), api.getDashboardSummary()])
      .then(([me, summary]) => {
        const metrics = summary.metrics || {}
        this.setData({
          statusLabel: summary.dataStatus === 'FRESH' ? '数据正常' : '部分数据待接入',
          headline: (summary.conclusion && summary.conclusion.headline) || '暂无态势结论',
          workspaceName: (me.workspace && me.workspace.name) || '当前工作空间',
          onlineAircraft: metrics.onlineAircraft === undefined ? '—' : String(metrics.onlineAircraft),
          todayTasks: metrics.todayTasks === undefined ? '—' : String(metrics.todayTasks),
          urgentEvents: metrics.urgentEvents === undefined ? '—' : String(metrics.urgentEvents),
          warning: (summary.warnings || []).join('；'),
          flightControlEnabled: Boolean(me.capabilities && me.capabilities.flightControlEnabled),
        })
      })
      .catch((error) => this.showError(error))
      .finally(() => this.setData({ loading: false }))
  },

  openTasks() {
    wx.navigateTo({ url: '/pages/tasks/index' })
  },

  openReports() {
    wx.navigateTo({ url: '/pages/reports/index' })
  },

  openMine() {
    wx.navigateTo({ url: '/pages/mine/index' })
  },

  showError(error) {
    const message = error && error.message ? error.message : '服务请求失败'
    this.setData({
      statusLabel: '连接异常',
      warning: message,
    })
    wx.showToast({ title: message, icon: 'none' })
  },
})
