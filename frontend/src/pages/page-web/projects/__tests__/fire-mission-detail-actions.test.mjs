import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../../../..')

function readSource (path) {
  return readFileSync(resolve(root, path), 'utf8')
}

test('fire mission detail exposes FC100 delivery task execution action', () => {
  const source = readSource('src/pages/page-web/projects/fire/FireMissionDetail.vue')

  assert.match(source, /const canStartDeliveryTask = computed/)
  assert.match(source, /dto\.value\?\.status === 'SENT_TO_DELIVERY'/)
  assert.match(source, /dto\.value\?\.status === 'ACCEPTED_BY_PILOT'/)
  assert.match(source, /deliveryApi\.startTask\(props\.no, \{ operatorId: 'test-operator' \}\)/)
  assert.match(source, />\s*执行航线任务\s*<\/a-button>/)
})

test('fire mission detail can recreate stale FC100 delivery tasks', () => {
  const source = readSource('src/pages/page-web/projects/fire/FireMissionDetail.vue')

  assert.match(source, /const canReprepareDeliveryTask = computed/)
  assert.match(source, /dto\.value\?\.status === 'SENT_TO_DELIVERY'/)
  assert.match(source, /dto\.value\?\.status === 'IN_PROGRESS'/)
  assert.match(source, /dto\.value\?\.status === 'PAYLOAD_RELEASED'/)
  assert.match(source, /deliveryApi\.prepareFireMissionDeliveryTask\(props\.no, \{ operatorId: 'test-operator' \}\)/)
  assert.match(source, />\s*重新生成并推送FC100\s*<\/a-button>/)
})

test('fire mission detail formats fire temperature to two decimals', () => {
  const source = readSource('src/pages/page-web/projects/fire/FireMissionDetail.vue')

  assert.match(source, /function fmtTemperature/)
  assert.match(source, /toFixed\(2\)/)
  assert.match(source, /\{\{ fmtTemperature\(fireEvent\?\.thermalTemperature, fireEvent\?\.temperatureUnit\) \}\}/)
})

test('mission timeline renders status and action labels in Chinese', () => {
  const source = readSource('src/components/fire/MissionTimeline.vue')

  assert.match(source, /const statusLabel/)
  assert.match(source, /PAYLOAD_RELEASED: '已投放'/)
  assert.match(source, /START_DELIVERY: '执行航线'/)
  assert.match(source, /\{\{ labelStatus\(log\.fromStatus\) \}\} → \{\{ labelStatus\(log\.toStatus\) \}\}/)
  assert.match(source, /操作: \{\{ labelAction\(log\.action\) \}\}/)
})

test('workspace fire pages keep their main content vertically scrollable', () => {
  const source = readSource('src/pages/page-web/projects/workspace.vue')

  assert.match(source, /\.project-app-wrapper/)
  assert.match(source, /&\.fire-mode[\s\S]*\.main-content[\s\S]*overflow-y: auto/)
})
