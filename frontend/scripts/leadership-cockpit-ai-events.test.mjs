import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const cockpitPath = new URL('../src/pages/page-web/projects/leadership-cockpit.vue', import.meta.url)
const manageApiPath = new URL('../src/api/manage.ts', import.meta.url)

const cockpitSource = readFileSync(cockpitPath, 'utf8')
const manageApiSource = readFileSync(manageApiPath, 'utf8')

test('manage api exposes dual stream task AI events', () => {
  assert.match(manageApiSource, /export interface DualStreamEvent/)
  assert.match(manageApiSource, /visibleScore\?:\s*number/)
  assert.match(manageApiSource, /fusionScore\?:\s*number/)
  assert.match(manageApiSource, /reviewStatus\?:\s*string/)
  assert.match(manageApiSource, /export const getDualStreamTaskEvents/)
  assert.match(manageApiSource, /\/dual-stream\/tasks\/\$\{taskId\}\/events/)
  assert.match(manageApiSource, /normalizeDualStreamEvent/)
  assert.match(manageApiSource, /visible_score/)
  assert.match(manageApiSource, /fusion_score/)
  assert.match(manageApiSource, /risk_level/)
  assert.match(manageApiSource, /analysis_channel/)
  assert.match(manageApiSource, /review_status/)
})

test('leadership cockpit renders polling AI risk records inside key alerts', () => {
  assert.match(cockpitSource, /getDualStreamTaskEvents/)
  assert.match(cockpitSource, /AI_EVENT_TASK_ID/)
  assert.match(cockpitSource, /aiRiskEventTimer/)
  assert.match(cockpitSource, /loadAiRiskEvents/)
  assert.match(cockpitSource, /AI 风险识别记录/)
  assert.match(cockpitSource, /可见光分数/)
  assert.match(cockpitSource, /融合分数/)
  assert.match(cockpitSource, /VISIBLE_SUSPECTED/)
  assert.match(cockpitSource, /THERMAL_CONFIRMED/)
  assert.match(cockpitSource, /THERMAL_REJECTED/)
  assert.doesNotMatch(cockpitSource, /<section class="ai-risk-panel">/)
  assert.match(cockpitSource, /class="info-list ai-risk-alert-list"/)
})

test('leadership cockpit limits visible AI event records and handles empty state', () => {
  assert.match(cockpitSource, /recentAiRiskEvents/)
  assert.match(cockpitSource, /\.slice\(-10\)\.reverse\(\)/)
  assert.match(cockpitSource, /暂无 AI 识别记录/)
  assert.match(cockpitSource, /formatAiScore/)
  assert.match(cockpitSource, /formatAiEventTime/)
})

test('leadership cockpit notifies low risk fire events too', () => {
  assert.match(
    cockpitSource,
    /return level === 'LOW' \|\| level === 'MEDIUM' \|\| level === 'HIGH'/,
  )
  assert.match(cockpitSource, /level === 'LOW' \? '低'/)
  assert.match(cockpitSource, /检测到\$\{levelLabel\}风险火情/)
})

test('leadership cockpit notifies low risk AI recognition events too', () => {
  assert.match(cockpitSource, /lastSeenAiRiskEventTs/)
  assert.match(cockpitSource, /aiRiskEventsBootstrapped/)
  assert.match(cockpitSource, /AI_RISK_NOTIFY_SUPPRESS_MS = 5 \* 60 \* 1000/)
  assert.match(cockpitSource, /lastAiRiskNotificationByKey/)
  assert.match(cockpitSource, /buildAiRiskNotificationKey/)
  assert.match(cockpitSource, /notifyNewAiRiskEvents/)
  assert.match(cockpitSource, /level === 'LOW' \|\| level === 'MEDIUM' \|\| level === 'HIGH'/)
  assert.match(cockpitSource, /Number\(event\.fusionScore\) > 0/)
  assert.match(cockpitSource, /AI 识别提示：\$\{levelLabel\}火情/)
})

test('leadership cockpit suppresses duplicate fire event notifications by notification version', () => {
  assert.match(cockpitSource, /lastNotifiedFireEventVersions/)
  assert.match(cockpitSource, /notificationVersion/)
  assert.match(cockpitSource, /shouldNotifyFireEvent/)
  assert.match(cockpitSource, /通知版本: \$\{evt\.notificationVersion \?\? 1\}/)
})

test('right side column puts aircraft status above FC100 and link status', () => {
  const aircraftIndex = cockpitSource.indexOf('<h3>飞机与直播状态</h3>')
  const fc100Index = cockpitSource.indexOf('<h3>FC100 投放与链路状态</h3>')

  assert.notEqual(aircraftIndex, -1)
  assert.notEqual(fc100Index, -1)
  assert.ok(aircraftIndex < fc100Index)
})
