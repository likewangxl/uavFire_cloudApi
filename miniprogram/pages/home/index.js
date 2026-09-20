const api = require('../../services/api')
const session = require('../../services/session')
const view = require('../../services/presentation')
const empty = { onlineAircraft: '—', todayTasks: '—', urgentEvents: '—', activeTasks: [], urgentItems: [], reports: [] }
Page({
  data: { ...empty, loading: false, authenticated: false, hasLoaded: false, headline: '登录后查看巡护动态', workspaceName: '', warning: '', error: '', generatedAt: '', showWarnings: false },
  onShow() {
    this._visible = true
    this.stopAutoRefresh()
    if (session.getAccessToken()) this._refreshTimer = setInterval(() => this.refreshDashboard(), 5000)
    return this.refreshDashboard()
  },
  stopAutoRefresh() { if (this._refreshTimer) clearInterval(this._refreshTimer); this._refreshTimer = null },
  onHide() { this._visible = false; this.stopAutoRefresh(); this._requestId = (this._requestId || 0) + 1; this.setData({ loading: false }) },
  onUnload() { this.onHide() },
  onPullDownRefresh() { return this.refreshDashboard().finally(() => wx.stopPullDownRefresh()) },
  toggleWarnings() { this.setData({ showWarnings: !this.data.showWarnings }) },
  refreshDashboard() {
    if (this._inFlight) return this._inFlight
    const requestId = (this._requestId || 0) + 1; this._requestId = requestId
    const identity = session.getSession().sessionId
    if (this._identity !== identity) this.setData({ ...empty, workspaceName: '', generatedAt: '', hasLoaded: false, headline: '正在读取巡护动态' })
    this._identity = identity
    if (!session.getAccessToken()) { this.stopAutoRefresh(); this.setData({ ...empty, authenticated: false, hasLoaded: false, workspaceName: '', generatedAt: '', headline: '登录后查看巡护动态', loading: false, warning: '', error: '' }); return Promise.resolve() }
    this.setData({ loading: true, error: '', authenticated: true })
    this._inFlight = Promise.all([api.getMe(), api.getDashboardSummary()]).then(([me, summary]) => {
      if (requestId !== this._requestId || identity !== session.getSession().sessionId) return
      const metrics = summary.metrics || {}
      this.setData({
        headline: summary.conclusion.headline, workspaceName: me.workspace.name, hasLoaded: true,
        onlineAircraft: metrics.onlineAircraft == null ? '—' : String(metrics.onlineAircraft),
        todayTasks: metrics.todayTasks == null ? '—' : String(metrics.todayTasks),
        urgentEvents: metrics.urgentEvents == null ? '—' : String(metrics.urgentEvents),
        activeTasks: (summary.activeTasks || []).map(value => view.item(value, 'tasks')),
        urgentItems: (summary.urgentEvents || []).map(value => view.item(value, 'events')),
        reports: (summary.reports || []).map(value => view.item(value, 'reports')),
        generatedAt: view.date(summary.generatedAt), warning: (summary.warnings || []).join('；'),
      })
    }).catch(error => {
      if (requestId !== this._requestId) return
      if (!session.getAccessToken()) {
        this.stopAutoRefresh()
        this.setData({ ...empty, hasLoaded: false, authenticated: false, workspaceName: '', generatedAt: '', headline: '请重新登录', error: error.message })
      } else this.setData({ error: this.data.hasLoaded ? '更新失败，当前显示上次成功读取的数据。' : error.message, ...(this.data.hasLoaded ? {} : { headline: '暂时无法更新数据' }) })
    }).finally(() => { this._inFlight = null; if (requestId === this._requestId) this.setData({ loading: false }) })
    return this._inFlight
  },
  handleLogin() { wx.switchTab({ url: '/pages/mine/index' }) },
  openTasks() { wx.switchTab({ url: '/pages/tasks/index' }) },
  openEvents() { wx.switchTab({ url: '/pages/events/index' }) },
  openReports() { wx.switchTab({ url: '/pages/reports/index' }) },
  openItem(event) { view.openDetail(event.currentTarget.dataset.kind, event.currentTarget.dataset.id) },
})
