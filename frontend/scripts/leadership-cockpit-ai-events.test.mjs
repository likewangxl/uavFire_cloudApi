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

test('right side column puts key alerts above resources', () => {
  const keyAlertIndex = cockpitSource.indexOf('<h3>重点告警与处置状态</h3>')
  const resourceIndex = cockpitSource.indexOf('<h3>力量与保障资源</h3>')

  assert.notEqual(keyAlertIndex, -1)
  assert.notEqual(resourceIndex, -1)
  assert.ok(keyAlertIndex < resourceIndex)
})
