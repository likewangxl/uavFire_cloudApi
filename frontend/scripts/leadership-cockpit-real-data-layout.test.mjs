import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const cockpitPath = new URL('../src/pages/page-web/projects/leadership-cockpit.vue', import.meta.url)
const cockpitSource = readFileSync(cockpitPath, 'utf8')

test('leadership cockpit uses real backend-backed summary data instead of static briefing cards', () => {
  assert.match(cockpitSource, /buildCockpitSummary/)
  assert.match(cockpitSource, /cockpitSummary/)
  assert.match(cockpitSource, /loadCockpitFireEvents/)
  assert.match(cockpitSource, /deliveryTaskStatuses/)
  assert.doesNotMatch(cockpitSource, /const summaryCards = \[/)
  assert.doesNotMatch(cockpitSource, /const decisions = \[/)
  assert.doesNotMatch(cockpitSource, /const impactMetrics = \[/)
  assert.doesNotMatch(cockpitSource, /const riskItems = \[/)
  assert.doesNotMatch(cockpitSource, /const alertItems = \[/)
  assert.doesNotMatch(cockpitSource, /const resourceItems = \[/)
  assert.doesNotMatch(cockpitSource, /const trendBars = \[/)
  assert.doesNotMatch(cockpitSource, /const focusItems = \[/)
})

test('leadership cockpit does not ship invented operational copy', () => {
  assert.doesNotMatch(cockpitSource, /秦岭北坡 2 号山火处于可控压制阶段/)
  assert.doesNotMatch(cockpitSource, /受威胁村组/)
  assert.doesNotMatch(cockpitSource, /道路管制段/)
  assert.doesNotMatch(cockpitSource, /火场受控比例持续提升/)
  assert.doesNotMatch(cockpitSource, /未来 30 分钟整体向东南缓慢扩展/)
  assert.doesNotMatch(cockpitSource, /轮换电池 24 组/)
})

test('leadership cockpit shows unavailable backend-only data explicitly', () => {
  assert.match(cockpitSource, /cockpitSummary\.dataGaps/)
  assert.match(cockpitSource, /系统链路健康/)
  assert.match(cockpitSource, /没有统一 health 汇总接口/)
})

test('leadership cockpit uses a unified shell with top operational metrics', () => {
  const shellIndex = cockpitSource.indexOf('<section class="cockpit-shell">')
  const topbarIndex = cockpitSource.indexOf('<header class="cockpit-topbar">')
  const summaryIndex = cockpitSource.indexOf('<section class="summary-grid">')
  const contentIndex = cockpitSource.indexOf('<section class="content-grid">')

  assert.notEqual(shellIndex, -1)
  assert.notEqual(topbarIndex, -1)
  assert.notEqual(contentIndex, -1)
  assert.notEqual(summaryIndex, -1)
  assert.ok(shellIndex < topbarIndex)
  assert.ok(topbarIndex < summaryIndex)
  assert.ok(summaryIndex < contentIndex)
  assert.doesNotMatch(cockpitSource, /<section class="hero-grid">/)
  assert.match(cockpitSource, /\.cockpit-shell\s*\{[\s\S]*border:\s*1px solid rgba\(69, 221, 255/)
})

test('leadership cockpit puts AI recognition above fire event queue', () => {
  const aiIndex = cockpitSource.indexOf('<h3>AI 识别记录</h3>')
  const fireEventIndex = cockpitSource.indexOf('<h3>火情事件队列</h3>')

  assert.notEqual(aiIndex, -1)
  assert.notEqual(fireEventIndex, -1)
  assert.ok(aiIndex < fireEventIndex)
})

test('leadership cockpit panel titles use layered cockpit colors', () => {
  assert.match(cockpitSource, /\.panel-header h3\s*\{[\s\S]*color:\s*#7ee8ff/)
  assert.match(cockpitSource, /\.info-card h4\s*\{[\s\S]*color:\s*#d8f3ff/)
})
