import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import vm from 'node:vm'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const ok = (request, data) => request.success({ statusCode: 200, data: { code: 'OK', data } })
const denied = request => request.success({ statusCode: 401, data: { code: 'AUTH_TOKEN_EXPIRED', message: 'expired' } })
function harness(handler, base = 'https://test.example/miniapp/api/v1') {
  const storage = new Map(), cache = new Map(), calls = [], clipboard = [], intervals = new Map()
  let timerId = 0
  const wx = {
    getStorageSync: key => storage.get(key), setStorageSync: (key, value) => storage.set(key, value), removeStorageSync: key => storage.delete(key),
    getSystemInfoSync: () => ({ platform: 'ios' }), request: value => { calls.push(value); handler(value) },
    login: ({ success }) => success({ code: 'one-time-code' }), showToast() {}, switchTab() {}, navigateTo() {},
    setNavigationBarTitle() {}, stopPullDownRefresh() {}, setClipboardData: value => clipboard.push(value.data),
  }
  function load(relative) {
    const filename = path.resolve(root, relative)
    if (filename.endsWith(path.join('config', 'runtime.js'))) return { getRuntimeConfig: () => ({ apiBaseUrl: base, clientVersion: '0.2.0' }) }
    if (cache.has(filename)) return cache.get(filename).exports
    const module = { exports: {} }; cache.set(filename, module)
    const context = vm.createContext({ wx, module, exports: module.exports, Page: definition => { module.exports = definition }, console, setTimeout, clearTimeout,
      setInterval: (callback, delay) => { const id = ++timerId; intervals.set(id, { callback, delay }); return id }, clearInterval: id => intervals.delete(id),
      require: name => load(path.relative(root, path.resolve(path.dirname(filename), name.endsWith('.js') ? name : name + '.js'))) })
    new vm.Script(fs.readFileSync(filename, 'utf8'), { filename }).runInContext(context)
    return module.exports
  }
  const session = load('services/session.js')
  session.setSession({ accessToken: 'old', refreshToken: 'refresh-old', sessionId: 's1' })
  return { load, session, calls, wx, clipboard, intervals }
}
const settle = () => new Promise(resolve => setImmediate(resolve))
test('home polls every 5 seconds without overlap and stops on hide or unload', async () => {
  let pending = [], fail = false
  const h = harness(r => pending.push(r)), page = pageHarness(h.load('pages/home/index.js'))
  const complete = () => { const batch = pending.splice(0); for (const r of batch) { if (fail) r.fail({errMsg:'offline'}); else ok(r, r.url.endsWith('/me') ? {workspace:{name:'ws'}} : {conclusion:{headline:'已更新'},metrics:{todayTasks:12},generatedAt:Date.now()}) } }
  const initial = page.onShow(); assert.equal(h.intervals.size, 1)
  const timer = [...h.intervals.values()][0]; assert.equal(timer.delay, 5000)
  timer.callback(); assert.equal(pending.length, 2)
  complete(); await initial; assert.equal(page.data.todayTasks, '12')
  fail = true; timer.callback(); complete(); await settle(); assert.equal(page.data.todayTasks, '12'); assert.match(page.data.error, /上次/)
  fail = false; timer.callback(); page.onHide(); assert.equal(h.intervals.size, 0)
  complete(); await settle(); assert.equal(page.data.loading, false)
  const again=page.onShow(); complete(); await again; assert.equal(h.intervals.size,1)
  page.onUnload(); assert.equal(h.intervals.size,0)
})
test('flight formatting distinguishes missing values from zero and uses official model names', () => {
  const h=harness(()=>{}), f=h.load('services/flight-report.js')
  assert.equal(f.value(null,'%'),'未记录');assert.equal(f.value('','%'),'未记录');assert.equal(f.value(0,'%'),'0 %')
  const model=h.load('services/device-models.js')
  assert.equal(model.deviceModelName('FC100'),'DJI FlyCart 100');assert.equal(model.deviceModelName('L2'),'DJI Zenmuse L2')
  assert.equal(model.formatDeviceText('任务 L2 / P1'),'任务 L2 / P1')
  assert.equal(model.formatDeviceText('DJI FlyCart 100'),'DJI FlyCart 100')
})
function pageHarness(page) {
  page.data = JSON.parse(JSON.stringify(page.data))
  page.setData = values => {
    for (const [key, value] of Object.entries(values)) {
      const parts = key.split('.'); let target = page.data
      for (const part of parts.slice(0, -1)) target = target[part]
      target[parts.at(-1)] = value
    }
  }
  return page
}

test('concurrent unauthorized reads share a single refresh and retry with the rotated token', async () => {
  let refreshRequest, refreshCount = 0
  const h = harness(r => {
    if (r.url.endsWith('/auth/refresh')) { refreshCount++; refreshRequest = r; return }
    if (r.header.Authorization === 'Bearer old') denied(r)
    else ok(r, { workspace: 'ws-a' })
  })
  const http = h.load('services/http.js')
  const first = http.request({ path: '/me' }), second = http.request({ path: '/dashboard/summary' })
  await settle(); assert.equal(refreshCount, 1)
  ok(refreshRequest, { accessToken: 'new', refreshToken: 'refresh-new', sessionId: 's1' })
  await Promise.all([first, second]); assert.equal(h.session.getAccessToken(), 'new')
  assert.ok(h.calls.every(call => !call.url.includes('old') && !call.url.includes('refresh-new')))
})
test('expired refresh clears session, transient refresh failure preserves it', async () => {
  const expired = harness(denied)
  await assert.rejects(expired.load('services/http.js').request({ path: '/me' }))
  assert.equal(expired.session.getAccessToken(), '')
  const offline = harness(r => r.url.endsWith('/auth/refresh') ? r.fail({ errMsg: 'offline' }) : denied(r))
  await assert.rejects(offline.load('services/http.js').request({ path: '/me' }))
  assert.equal(offline.session.getAccessToken(), 'old')
})
test('late refresh cannot resurrect a cleared session', async () => {
  let pending
  const h = harness(r => r.url.endsWith('/auth/refresh') ? pending = r : denied(r))
  const request = h.load('services/http.js').request({ path: '/me' })
  await settle(); h.session.clearSession(); ok(pending, { accessToken: 'new', refreshToken: 'new-r', sessionId: 's1' })
  await assert.rejects(request, error => error.code === 'SESSION_CHANGED')
  assert.equal(h.session.getAccessToken(), '')
})
test('wechat login sends one-time code without an old bearer and waits for binding approval', async () => {
  const h = harness(r => ok(r, { loginStatus: 'BINDING_REQUIRED', bindingTicket: 'ticket' }))
  const result = await h.load('services/api.js').login()
  assert.equal(result.loginStatus, 'BINDING_REQUIRED'); assert.equal(h.session.getAccessToken(), '')
  assert.equal(h.calls[0].data.code, 'one-time-code'); assert.equal(h.calls[0].header.Authorization, undefined)
})
test('logout invalidates the refreshed server session and clears local state', async () => {
  const h = harness(r => {
    if (r.url.endsWith('/auth/refresh')) return ok(r, { accessToken: 'new', refreshToken: 'new-r', sessionId: 's1' })
    if (r.header.Authorization === 'Bearer old') return denied(r)
    ok(r, { loggedOut: true })
  })
  await h.load('services/api.js').logout(); assert.equal(h.session.getAccessToken(), '')
  assert.equal(h.calls.at(-1).header.Authorization, 'Bearer new')
})
test('real device refuses cleartext or credential-bearing API base URLs', async () => {
  for (const url of ['http://localhost:6789/miniapp/api/v1', 'https://user:pass@host/miniapp/api/v1']) {
    const h = harness(() => assert.fail('must not send token'), url)
    await assert.rejects(h.load('services/http.js').request({ path: '/me' }), error => error.code === 'CLIENT_CONFIG_INVALID')
  }
})

test('late responses cannot reveal old account data or replay a mutation as another account', async () => {
  for (const respond of [r => ok(r, { private: 'old account data' }), denied]) {
    let pending
    const h = harness(r => { pending = r })
    const request = h.load('services/http.js').request({ path: '/reports/id/bookmark', method: 'PUT', data: { saved: true } })
    h.session.setSession({ accessToken: 'other', refreshToken: 'other-r', sessionId: 's2' })
    respond(pending)
    await assert.rejects(request, error => error.code === 'SESSION_CHANGED')
    assert.equal(h.calls.length, 1)
    assert.equal(h.session.getAccessToken(), 'other')
  }
})

test('late wx.login code cannot start a session after logout', async () => {
  const h = harness(() => assert.fail('must not exchange an obsolete code'))
  let pending
  h.wx.login = request => { pending = request }
  const login = h.load('services/api.js').login()
  h.session.clearSession()
  pending.success({ code: 'late-code' })
  await assert.rejects(login, error => error.code === 'SESSION_CHANGED')
  assert.equal(h.session.getAccessToken(), '')
})
test('task page drops out-of-order search responses and clears records after logout', async () => {
  const requests = []; const h = harness(r => requests.push(r))
  const page = h.load('services/presentation.js').createListPage('tasks')
  page.setData = value => Object.assign(page.data, value)
  const first = page.load(true); page.data.keyword = 'new'; const second = page.load(true)
  ok(requests[1], { items: [{ taskId: 'new', name: 'new' }], page: 1, total: 1, hasMore: false }); await second
  ok(requests[0], { items: [{ taskId: 'old', name: 'old' }], page: 1, total: 1, hasMore: false }); await first
  assert.equal(page.data.items[0].id, 'new')
  h.session.clearSession(); await page.load(true); assert.equal(page.data.items.length, 0); assert.equal(page.data.authenticated, false)
})
test('all registered pages exist, JavaScript parses, and five native tabs are declared', () => {
  const config = JSON.parse(fs.readFileSync(path.join(root, 'app.json'), 'utf8'))
  assert.equal(config.tabBar.list.length, 5)
  for (const page of config.pages) {
    for (const suffix of ['.js', '.json', '.wxml', '.wxss']) assert.ok(fs.existsSync(path.join(root, page + suffix)), page + suffix)
    new vm.Script(fs.readFileSync(path.join(root, page + '.js'), 'utf8'))
    JSON.parse(fs.readFileSync(path.join(root, page + '.json'), 'utf8'))
  }
})

test('picker selections use consistent query states and reset pagination', async () => {
  const h = harness(r => ok(r, { items: [], total: 0, hasMore: false }))
  const tasks = pageHarness(h.load('services/presentation.js').createListPage('tasks'))
  tasks.data.keyword = '巡检'; await tasks.search()
  const completed = tasks.data.statusOptions.findIndex(value => value.value === 'completed')
  await tasks.selectStatus({ detail: { value: String(completed) } })
  assert.ok(h.calls.at(-1).url.includes('status=completed')); assert.ok(h.calls.at(-1).url.includes('page=1'))
  assert.equal(tasks.data.hasFilters, true)
  await tasks.resetFilters(); assert.equal(tasks.data.hasFilters, false); assert.equal(tasks.data.keyword, '')
  const events = pageHarness(h.load('services/presentation.js').createListPage('events'))
  await events.selectStatus({ detail: { value: '1' } }); assert.ok(h.calls.at(-1).url.includes('status=PENDING_REVIEW'))
  const reports = pageHarness(h.load('services/presentation.js').createListPage('reports'))
  await reports.selectStatus({ detail: { value: '1' } }); assert.ok(h.calls.at(-1).url.includes('savedOnly=true'))
})

test('failed next-page load retains records and retries the same page using applied search', async () => {
  let failNext = true
  const h = harness(r => {
    if (r.url.includes('page=1')) return ok(r, { items: [{ taskId: 'a', name: 'first' }], total: 2, hasMore: true })
    if (failNext) { failNext = false; return r.fail({ errMsg: 'offline' }) }
    ok(r, { items: [{ taskId: 'b', name: 'second' }], total: 2, hasMore: false })
  })
  const page = pageHarness(h.load('services/presentation.js').createListPage('tasks'))
  page.data.keyword = 'applied'; await page.search(); page.data.keyword = 'unsubmitted'
  await page.loadMore(); assert.equal(page.data.items[0].id, 'a'); assert.equal(page.data.page, 1)
  await page.retry(); assert.equal(page.data.items.length, 2); assert.equal(page.data.page, 2)
  assert.ok(h.calls.at(-1).url.includes('keyword=applied')); assert.equal(page.data.hasMore, false)
})

test('returning from a detail preserves list state; leaving an in-flight load does not stick loading', async () => {
  let pending
  const h = harness(r => { pending = r })
  const page = pageHarness(h.load('services/presentation.js').createListPage('tasks'))
  const first = page.onShow(); page.onHide(); ok(pending, { items: [], total: 0, hasMore: false }); await first
  assert.equal(page.data.loading, false)
  const next = page.onShow(); ok(pending, { items: [{ taskId: 'a' }], total: 1, hasMore: false }); await next
  page.onHide(); const before = h.calls.length; await page.onShow(); assert.equal(h.calls.length, before)
  assert.equal(page.data.items.length, 1)
})

test('long event identifiers remain available in details and all waypoints can be reached', async () => {
  const longId = 'agent-' + 'abcdef0123456789'.repeat(16)
  const record = { eventId: longId, name: '火情事件 ' + longId, taskId: 'task-a', timeline: [], waypoints: Array.from({ length: 521 }, (_, i) => ({ latitude: i, longitude: i })) }
  const h = harness(r => ok(r, record))
  const display = h.load('services/presentation.js').item(record, 'events')
  assert.equal(display.displayName, '火情事件'); assert.equal(display.id, longId)
  const page = pageHarness(h.load('pages/detail/index.js'))
  await page.onLoad({ kind: 'events', id: longId })
  assert.equal(page.data.facts[0].value, longId); page.copyId(); assert.equal(h.clipboard[0], longId)
  while (page.data.waypointHasMore) page.loadMoreWaypoints()
  assert.equal(page.data.waypoints.length, 521); assert.equal(page.data.waypoints.at(-1).index, 521)
  page.switchSection({ currentTarget: { dataset: { value: 'route' } } }); assert.equal(page.data.section, 'overview')
})

test('bookmark changes refresh report lists and duplicate taps only send one mutation', async () => {
  let write, mutations = 0
  const h = harness(r => {
    if (r.method === 'PUT') { mutations++; write = r; return }
    if (r.url.includes('/reports?')) return ok(r, { items: [], total: 0, hasMore: false })
    ok(r, { taskId: 'r1', saved: false, timeline: [] })
  })
  const list = pageHarness(h.load('services/presentation.js').createListPage('reports')); await list.onShow(); list.onHide()
  const page = pageHarness(h.load('pages/detail/index.js')); await page.onLoad({ kind: 'reports', id: 'r1' })
  const first = page.bookmark(); await page.bookmark(); assert.equal(mutations, 1)
  ok(write, { saved: true }); await first; assert.equal(page.data.record.saved, true)
  const before = h.calls.length; await list.onShow(); assert.equal(h.calls.length, before + 1)
})

test('binding form validates inline and expired tickets cannot submit', async () => {
  const h = harness(() => assert.fail('must not send incomplete or expired binding'))
  const page = pageHarness(h.load('pages/mine/index.js'))
  await page.bind(); assert.ok(page.data.fieldErrors.username); assert.ok(page.data.fieldErrors.organization)
  page.setData({ username: 'account', displayName: 'name', organization: 'org', bindingTicket: 'expired' })
  page._bindingExpiresAt = Date.now() - 1
  await page.bind(); assert.equal(page.data.bindingTicket, ''); assert.match(page.data.error, /过期/)
})

test('profile read errors retain logout access and failed preference writes restore the switch', async () => {
  const h = harness(r => r.url.endsWith('/auth/logout') ? ok(r, { loggedOut: true }) : r.fail({ errMsg: 'offline' }))
  const page = pageHarness(h.load('pages/mine/index.js'))
  await page.onShow(); assert.equal(page.data.authenticated, true); assert.equal(page.data.me, null); assert.equal(page.data.profileLoading, false)
  page.setData({ preferences: { urgentEvents: false } })
  await page.preference({ currentTarget: { dataset: { key: 'urgentEvents' } }, detail: { value: true } })
  assert.equal(page.data.preferences.urgentEvents, false); assert.equal(page.data.preferenceSaving, false)
  await page.logout(); assert.equal(page.data.authenticated, false); assert.equal(h.session.getAccessToken(), '')
})

test('HTTPS requests avoid deprecated device-info calls', async () => {
  const h = harness(r => ok(r, {}))
  h.wx.getSystemInfoSync = () => assert.fail('not needed for a configured HTTPS endpoint')
  await h.load('services/http.js').request({ path: '/me' })
})

test('expired authorization during a preference change returns the account page to login', async () => {
  const h = harness(denied)
  const page = pageHarness(h.load('pages/mine/index.js'))
  page.setData({ authenticated: true, status: '已绑定', me: { user: { displayName: 'old-user' } }, preferences: { urgentEvents: false } })
  await page.preference({ currentTarget: { dataset: { key: 'urgentEvents' } }, detail: { value: true } })
  assert.equal(page.data.authenticated, false); assert.equal(page.data.status, '未登录'); assert.equal(page.data.me, null)
  assert.equal(Object.keys(page.data.preferences).length, 0)
})

test('expired authorization while bookmarking clears the record and offers login', async () => {
  const h = harness(r => r.method === 'GET' ? ok(r, { taskId: 'r1', saved: false, timeline: [] }) : denied(r))
  const page = pageHarness(h.load('pages/detail/index.js'))
  await page.onLoad({ kind: 'reports', id: 'r1' }); await page.bookmark()
  assert.equal(page.data.record, null); assert.equal(page.data.authenticated, false); assert.ok(page.data.error)
})
