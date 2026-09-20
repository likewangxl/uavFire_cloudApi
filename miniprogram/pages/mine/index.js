const api = require('../../services/api')
const session = require('../../services/session')
Page({
  data: { loading: false, profileLoading: false, preferenceSaving: false, authenticated: false, status: '未登录', error: '', bindingTicket: '', username: '', displayName: '', organization: '', fieldErrors: {}, me: null, preferences: {} },
  onShow() {
    const identity = session.getSession().sessionId
    if (this._identity !== identity) { this._generation = (this._generation || 0) + 1; this.setData({ me: null, preferences: {}, error: '' }) }
    this._identity = identity
    this.setData({ authenticated: Boolean(session.getAccessToken()) })
    if (session.getAccessToken()) return this.loadMe()
    this.setData({ me: null, preferences: {} }); return Promise.resolve()
  },
  input(event) {
    const key = event.currentTarget.dataset.field
    if (!['username', 'displayName', 'organization'].includes(key)) return
    this.setData({ [key]: event.detail.value, fieldErrors: { ...this.data.fieldErrors, [key]: '' } })
  },
  login() {
    if (this.data.loading) return Promise.resolve()
    this.setData({ loading: true, error: '' })
    return api.login().then(result => {
      this._identity = session.getSession().sessionId
      this._bindingExpiresAt = Date.now() + (result.expiresIn || 0) * 1000
      this.setData({ bindingTicket: result.bindingTicket || '', authenticated: result.loginStatus === 'AUTHENTICATED',
        status: result.loginStatus === 'AUTHENTICATED' ? '已绑定' : result.loginStatus === 'PENDING_APPROVAL' ? '待审核' : '待绑定' })
      if (result.loginStatus === 'AUTHENTICATED') return this.loadMe()
    }).catch(error => this.setData({ error: error.message, authenticated: false, me: null }))
      .finally(() => this.setData({ loading: false }))
  },
  bind() {
    if (this.data.loading) return Promise.resolve()
    const { bindingTicket } = this.data
    const username = this.data.username.trim(), displayName = this.data.displayName.trim(), organization = this.data.organization.trim()
    const fieldErrors = { username: username ? '' : '请填写电脑端平台的用户名', displayName: displayName ? '' : '请填写真实姓名', organization: organization ? '' : '请填写单位或部门名称' }
    this.setData({ fieldErrors })
    if (Object.values(fieldErrors).some(Boolean)) return Promise.resolve()
    if (!bindingTicket || Date.now() >= this._bindingExpiresAt) { this.setData({ bindingTicket: '', status: '未登录', error: '微信授权已过期，请重新登录后提交申请' }); return Promise.resolve() }
    this.setData({ loading: true, error: '' })
    return api.bind({ bindingTicket, username, displayName, organization }).then(() => this.setData({ status: '待审核', bindingTicket: '' }))
      .catch(error => this.setData({ error: error.message })).finally(() => this.setData({ loading: false }))
  },
  loadMe() {
    const identity = session.getSession().sessionId
    const generation = this._generation || 0
    const requestId = (this._profileRequest || 0) + 1; this._profileRequest = requestId
    this.setData({ profileLoading: true, error: '' })
    return Promise.all([api.getMe(), api.getPreferences()]).then(([me, preferences]) => {
      if (requestId === this._profileRequest && identity === session.getSession().sessionId && generation === (this._generation || 0)) this.setData({ me, preferences, status: '已绑定', authenticated: true })
    }).catch(error => {
      if (requestId === this._profileRequest && generation === (this._generation || 0)) this.setData({ error: error.message, me: null, status: session.getAccessToken() ? '已登录' : '未登录', authenticated: Boolean(session.getAccessToken()) })
    }).finally(() => { if (requestId === this._profileRequest) this.setData({ profileLoading: false }) })
  },
  preference(event) {
    const key = event.currentTarget.dataset.key
    if (this.data.preferenceSaving || !['urgentEvents', 'taskUpdates', 'reportReady'].includes(key)) return Promise.resolve()
    const previous = { ...this.data.preferences }
    const identity = session.getSession().sessionId
    this.setData({ preferenceSaving: true, error: '', preferences: { ...previous, [key]: event.detail.value } })
    return api.savePreferences({ [key]: event.detail.value }).then(preferences => this.setData({ preferences }))
      .catch(error => {
        if (!session.getAccessToken()) {
          this.setData({ authenticated: false, me: null, preferences: {}, status: '未登录', error: error.message })
          return
        }
        if (session.getSession().sessionId !== identity) return
        this.setData({ preferences: previous, error: error.message })
      })
      .finally(() => this.setData({ preferenceSaving: false }))
  },
  logout() {
    if (this.data.loading || this.data.preferenceSaving) return Promise.resolve()
    this._generation = (this._generation || 0) + 1
    this.setData({ loading: true })
    return api.logout().then(() => this.setData({ error: '' })).catch(error => this.setData({ error: error.message }))
      .finally(() => this.setData({ loading: false, profileLoading: false, authenticated: false, me: null, preferences: {}, status: '未登录', bindingTicket: '' }))
  },
  onUnload() { this._generation = (this._generation || 0) + 1; this._profileRequest = (this._profileRequest || 0) + 1 },
})
