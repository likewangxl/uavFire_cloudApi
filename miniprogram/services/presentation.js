const api = require('./api')
const session = require('./session')
const { deviceModelName, formatDeviceText } = require('./device-models')
let reportRevision = 0
const option = (label, value) => ({ label, value })
const filters = {
  tasks: [option('全部状态', ''), option('执行中', 'executing'), option('已暂停', 'paused'), option('已完成', 'completed'), option('已发布', 'published'), option('航线已生成', 'file_generated'), option('草稿', 'draft'), option('异常', 'failed'), option('已停止', 'stopped'), option('已取消', 'canceled')],
  events: [option('全部状态', ''), option('待复核', 'PENDING_REVIEW'), option('已关联任务', 'MISSION_CREATED'), option('已忽略', 'IGNORED')],
  reports: [option('全部报告', ''), option('已收藏', 'saved')],
}
function date(value) {
  if (!value) return '尚无记录'
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return '尚无记录'
  const two = v => String(v).padStart(2, '0')
  return d.getFullYear() + '-' + two(d.getMonth() + 1) + '-' + two(d.getDate()) + ' ' + two(d.getHours()) + ':' + two(d.getMinutes())
}
function displayName(value, kind) {
  if (kind === 'events' && (!value.name || value.name === '火情事件 ' + value.eventId)) return '火情事件'
  return formatDeviceText(value.name || (kind === 'reports' ? '任务执行报告' : kind === 'events' ? '火情事件' : '未命名任务'))
}
function item(value, kind) {
  return Object.assign({}, value, {
    id: kind === 'events' ? value.eventId : kind === 'reports' ? value.reportId || value.taskId : value.taskId,
    displayName: displayName(value, kind),
    statusLabel: !value.statusLabel || value.statusLabel === 'null' ? '状态未知' : value.statusLabel,
    timeLabel: date(value.updateTime),
    modelLabel: value.aircraftModelKey ? deviceModelName(value.aircraftModelKey) : value.deviceSn || '暂无航空器信息',
    progressLabel: value.taskProgress == null ? '—' : String(value.taskProgress) + '%', kind,
  })
}
function openDetail(kind, id) {
  if (!filters[kind] || !id) return
  wx.navigateTo({ url: '/pages/detail/index?kind=' + kind + '&id=' + encodeURIComponent(id) })
}
function createListPage(kind) {
  return {
    data: { items: [], loading: false, error: '', authenticated: false, keyword: '', appliedKeyword: '', status: '', statusIndex: 0,
      statusOptions: filters[kind], filterLabel: kind === 'tasks' ? '任务状态' : kind === 'events' ? '事件状态' : '报告范围',
      page: 1, hasMore: false, savedOnly: false, total: 0, hasLoaded: false, hasFilters: false },
    onShow() {
      const identity = session.getSession().sessionId
      if (identity !== this._identity || !this.data.hasLoaded || this._needsReload || (kind === 'reports' && this._reportRevision !== reportRevision)) return this.load(true)
      return Promise.resolve()
    },
    onPullDownRefresh() { return this.load(true).finally(() => wx.stopPullDownRefresh()) },
    onReachBottom() { return this.loadMore() },
    input(event) { this.setData({ keyword: event.detail.value }) },
    search() { this.setData({ appliedKeyword: this.data.keyword.trim() }); return this.load(true) },
    selectStatus(event) {
      const index = Number(event.detail.value)
      if (!Number.isInteger(index) || !filters[kind][index]) return Promise.resolve()
      this.setData({ statusIndex: index, status: kind === 'reports' ? '' : filters[kind][index].value, savedOnly: kind === 'reports' && index === 1 })
      return this.load(true)
    },
    resetFilters() { this.setData({ keyword: '', appliedKeyword: '', status: '', statusIndex: 0, savedOnly: false }); return this.load(true) },
    retry() { return this.load(this.data.items.length === 0) },
    loadMore() { return this.data.hasMore && !this.data.loading ? this.load(false) : Promise.resolve() },
    login() { wx.switchTab({ url: '/pages/mine/index' }) },
    open(event) { openDetail(kind, event.currentTarget.dataset.id) },
    openItem(event) { openDetail(kind, event.currentTarget.dataset.id) },
    load(reset = true) {
      if (!reset && (this.data.loading || !this.data.hasMore)) return Promise.resolve()
      const requestId = (this._requestId || 0) + 1; this._requestId = requestId
      const identity = session.getSession().sessionId
      this._identity = identity; this._needsReload = false
      if (!session.getAccessToken()) { this.setData({ items: [], authenticated: false, loading: false, error: '', total: 0, hasMore: false, hasLoaded: false }); return Promise.resolve() }
      const page = reset ? 1 : this.data.page + 1
      this.setData({ loading: true, authenticated: true, error: '', hasFilters: Boolean(this.data.status || this.data.appliedKeyword || this.data.savedOnly), ...(reset ? { items: [], hasMore: false, hasLoaded: false, total: 0 } : {}) })
      const values = { page, pageSize: 20 }
      if (kind === 'tasks') Object.assign(values, { keyword: this.data.appliedKeyword, status: this.data.status })
      if (kind === 'events') values.status = this.data.status
      if (kind === 'reports') values.savedOnly = this.data.savedOnly
      return api.list(kind, values).then(result => {
        if (requestId !== this._requestId || session.getSession().sessionId !== identity) return
        const next = result.items.map(value => item(value, kind))
        const rows = reset ? next : this.data.items.concat(next.filter(value => !this.data.items.some(old => old.id === value.id)))
        this._reportRevision = reportRevision
        this.setData({ items: rows, page, hasMore: Boolean(result.hasMore), total: result.total, hasLoaded: true })
      }).catch(error => {
        if (requestId !== this._requestId) return
        const authenticated = Boolean(session.getAccessToken())
        this.setData({ error: error.message || '记录加载失败，请重试', authenticated, ...(!authenticated ? { items: [], hasMore: false, hasLoaded: false, total: 0 } : {}) })
      }).finally(() => { if (requestId === this._requestId) this.setData({ loading: false }) })
    },
    onHide() { this._needsReload = this.data.loading; this._requestId = (this._requestId || 0) + 1; this.setData({ loading: false }) },
    onUnload() { this._requestId = (this._requestId || 0) + 1 },
  }
}
module.exports = { date, item, displayName, openDetail, createListPage, markReportsChanged: () => { reportRevision++ } }
