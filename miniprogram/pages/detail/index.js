const api = require('../../services/api')
const session = require('../../services/session')
const view = require('../../services/presentation')
const flight = require('../../services/flight-report')
const { deviceModelName } = require('../../services/device-models')
const printable = value => value == null || value === '' ? '—' : String(value)
Page({
  data: { loading: false, error: '', record: null, displayName: '', updatedAt: '', authenticated: false, facts: [], timeline: [], waypoints: [], waypointTotal: 0, waypointHasMore: false, section: 'overview', kind: '', saving: false, flightReport: null, reportMetrics: [], reportFacts: [], planFacts: [], reportRecords: [], reportRuns: [], reportTimeline: [], reportEvents: [], reportRunIndex: 0, reportHasMore: false, reportRecordTotal: 0, reportPage: 1, reportLoading: false, reportError: '' },
  onLoad(options) {
    if (!['tasks', 'events', 'reports'].includes(options.kind) || !options.id) { this.setData({ error: '页面参数无效，请返回列表重新打开' }); return }
    this.kind = options.kind; this.id = options.id
    this.setData({ kind: options.kind })
    wx.setNavigationBarTitle({ title: options.kind === 'events' ? '火情详情' : options.kind === 'reports' ? '执行报告' : '任务详情' })
    return this.load()
  },
  onUnload() { this._requestId = (this._requestId || 0) + 1; this._active = false },
  onPullDownRefresh() { return this.load().finally(() => wx.stopPullDownRefresh()) },
  goLogin() { wx.switchTab({ url: '/pages/mine/index' }) },
  retry() { return session.getAccessToken() ? this.load() : this.goLogin() },
  switchSection(event) {
    const section = event.currentTarget.dataset.value
    if (!['overview', 'route', 'timeline'].includes(section) || (this.kind === 'events' && section === 'route')) return
    this.setData({ section })
    if (typeof wx.pageScrollTo === 'function') wx.pageScrollTo({ scrollTop: 0, duration: 0 })
  },
  load() {
    if (!this.kind || !this.id) return Promise.resolve()
    this._active = true
    const requestId = (this._requestId || 0) + 1; this._requestId = requestId
    const identity = session.getSession().sessionId
    if (!session.getAccessToken()) { this.setData({ authenticated: false, record: null, error: '请先登录后查看详情', loading: false }); return Promise.resolve() }
    this.setData({ loading: true, authenticated: true, error: '', record: null, reportLoading: false, reportError: '' })
    return api.detail(this.kind, this.id).then(record => {
      if (!this._active || requestId !== this._requestId || session.getSession().sessionId !== identity) return
      const events = this.kind === 'events'
      const values = events ? [
        ['事件编号', record.eventId], ['来源', record.source], ['航空器', record.deviceSn],
        ['风险等级', record.fireLevel], ['坐标精度', record.geoQuality], ['坐标来源', record.geoMethod],
        ['纬度（WGS84）', record.lat], ['经度（WGS84）', record.lng], ['误差半径（米）', record.geoErrorRadiusM],
      ] : [
        ['任务编号', record.taskId], ['机型', deviceModelName(record.aircraftModelKey)], ['航空器', record.aircraftSn],
        ['最近进度', record.taskProgress == null ? null : String(record.taskProgress) + '%'],
        ['当前航点', record.currentWaypointIndex], ['航点总数', record.totalWaypoints],
        ['执行时间', view.date(record.executedTime)], ['状态更新时间', view.date(record.lastProgressTime)],
      ]
      this._allWaypoints = (Array.isArray(record.waypoints) ? record.waypoints : []).map((point, index) => ({
        index: index + 1, lat: printable(point && (point.latitude == null ? point.lat : point.latitude)),
        lng: printable(point && (point.longitude == null ? point.lng : point.longitude)),
      }))
      this.setData({ record, displayName: view.displayName(record, this.kind), updatedAt: '更新于 ' + view.date(record.updateTime),
        ...flight.summarize(record.flightReport),
        facts: values.map(([label, value]) => ({ label, value: printable(value) })),
        timeline: (record.flightReport ? [] : record.timeline || []).map((entry, index) => ({ key: String(entry.time) + '-' + index, title: entry.title || entry.action || '事件记录', note: entry.decisionReason || '', time: view.date(entry.time) })),
        waypoints: this._allWaypoints.slice(0, 30), waypointTotal: this._allWaypoints.length, waypointHasMore: this._allWaypoints.length > 30,
      })
    }).catch(error => { if (this._active && requestId === this._requestId) this.setData({ error: error.message, record: null, authenticated: Boolean(session.getAccessToken()) }) })
      .finally(() => { if (this._active && requestId === this._requestId) this.setData({ loading: false }) })
  },
  loadMoreWaypoints() {
    const waypoints = (this._allWaypoints || []).slice(0, this.data.waypoints.length + 30)
    this.setData({ waypoints, waypointHasMore: waypoints.length < this.data.waypointTotal })
  },
  selectRun(event) {
    const index = Number(event.detail.value), run = this.data.reportRuns[index]
    if (!run || this.data.reportLoading) return Promise.resolve()
    return this.loadFlightReport(run.runId, 1)
  },
  loadMoreRecords() {
    if (!this.data.flightReport || !this.data.reportHasMore || this.data.reportLoading) return Promise.resolve()
    return this.loadFlightReport(this.data.flightReport.runId, this.data.reportPage + 1)
  },
  loadMoreRelated(event) {
    const requestId = this._requestId
    const kind = event.currentTarget.dataset.kind
    if (!['events', 'media'].includes(kind) || this.data.reportLoading || !this.data.flightReport) return Promise.resolve()
    const field = kind === 'events' ? 'relatedEvents' : 'media', page = (this.data.relationPages[kind] || 1) + 1
    this.setData({ reportLoading: true, reportError: '' })
    return api.flightRelated(this.data.record.taskId, { kind, runId: this.data.flightReport.runId, page, pageSize: 100, snapshotRecordId: this.data.flightReport.snapshotRecordId }).then(result => {
      if (!this._active || requestId !== this._requestId) return
      const report = { ...this.data.flightReport, [field]: { ...this.data.flightReport[field], ...result, items: this.data.flightReport[field].items.concat(result.items) } }
      const updates = { flightReport: report, relationPages: { ...this.data.relationPages, [kind]: page } }
      if (kind === 'events') updates.reportEvents = report.relatedEvents.items.map(value => ({ ...value, statusLabel: flight.label(value.status), timeLabel: flight.date(value.eventTimestamp) }))
      this.setData(updates)
    }).catch(error => { if (this._active && requestId === this._requestId) this.setData({ reportError: error.message }) }).finally(() => { if (this._active && requestId === this._requestId) this.setData({ reportLoading: false }) })
  },
  loadFlightReport(runId, page) {
    const requestId = this._requestId
    this.setData({ reportLoading: true, reportError: '' })
    return api.flightReport(this.data.record.taskId, { runId, page, pageSize: 50, snapshotRecordId: page > 1 ? this.data.flightReport.snapshotRecordId : undefined }).then(result => {
      if (!this._active || requestId !== this._requestId) return
      const formatted = flight.summarize(result)
      if (page > 1) {
        formatted.reportRecords = this.data.reportRecords.concat(formatted.reportRecords)
        formatted.relationPages = this.data.relationPages
        formatted.reportEvents = this.data.reportEvents
        formatted.flightReport.relatedEvents = this.data.flightReport.relatedEvents
        formatted.flightReport.media = this.data.flightReport.media
      }
      this.setData(formatted)
    }).catch(error => { if (this._active && requestId === this._requestId) this.setData({ reportError: error.message }) })
      .finally(() => { if (this._active && requestId === this._requestId) this.setData({ reportLoading: false }) })
  },
  copyId() { if (this.data.record) wx.setClipboardData({ data: String(this.data.record.eventId || this.data.record.taskId || this.id) }) },
  bookmark() {
    if (this.data.saving || this.data.loading || !this.data.record) return Promise.resolve()
    this.setData({ saving: true })
    return api.bookmark(this.id, !this.data.record.saved).then(result => {
      view.markReportsChanged()
      if (this._active) this.setData({ 'record.saved': result.saved })
      wx.showToast({ title: result.saved ? '已收藏' : '已取消收藏', icon: 'none' })
    }).catch(error => {
      if (this._active && !session.getAccessToken()) this.setData({ record: null, authenticated: false, error: error.message })
      else wx.showToast({ title: error.message, icon: 'none' })
    }).finally(() => { if (this._active) this.setData({ saving: false }) })
  },
  relatedTask() { if (this.data.record) view.openDetail('tasks', this.data.record.taskId) },
})
