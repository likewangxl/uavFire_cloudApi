import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../../../..')

function readSource (path) {
  return readFileSync(resolve(root, path), 'utf8')
}

test('fire mission detail delegates FC100 delivery task execution action to ActionButtons', () => {
  const source = readSource('src/pages/page-web/projects/fire/FireMissionDetail.vue')
  const actionButtons = readSource('src/components/fire/ActionButtons.vue')

  assert.match(source, /import ActionButtons/)
  assert.match(source, /<ActionButtons[\s\S]*:mission-no="dto\.missionNo"[\s\S]*:actions="dto\.availableActions"[\s\S]*@refresh="refresh"/)
  assert.match(actionButtons, /START_DELIVERY:\s*\{\s*label:/)
  assert.match(actionButtons, /case 'START_DELIVERY':[\s\S]*deliveryApi\.startTask\(no, op\)/)
})

test('fire mission detail can prepare FC100 delivery tasks through route preparation actions', () => {
  const source = readSource('src/pages/page-web/projects/fire/FireMissionDetail.vue')
  const actionButtons = readSource('src/components/fire/ActionButtons.vue')

  assert.match(source, /<ActionButtons/)
  assert.match(actionButtons, /routePreparationActions = new Set\(\['GEN_WP', 'EXP_KMZ', 'CREATE_DELIVERY_TASK'\]\)/)
  assert.match(actionButtons, /PREPARE_FC100_DELIVERY/)
  assert.match(actionButtons, /case 'PREPARE_FC100_DELIVERY':[\s\S]*deliveryApi\.prepareFireMissionDeliveryTask\(no, op\)/)
  assert.match(actionButtons, /canPrepareFc100Delivery/)
})

test('fire mission detail formats fire temperature to two decimals', () => {
  const source = readSource('src/pages/page-web/projects/fire/FireMissionDetail.vue')

  assert.match(source, /function fmtTemperature/)
  assert.match(source, /toFixed\(2\)/)
  assert.match(source, /\{\{ fmtTemperature\(fireEvent\?\.thermalTemperature, fireEvent\?\.temperatureUnit\) \}\}/)
})

test('mission timeline renders status and action labels through label helpers', () => {
  const source = readSource('src/components/fire/MissionTimeline.vue')

  assert.match(source, /const statusLabel/)
  assert.match(source, /PAYLOAD_RELEASED:/)
  assert.match(source, /START_DELIVERY:/)
  assert.match(source, /\{\{ labelStatus\(log\.fromStatus\) \}\}/)
  assert.match(source, /\{\{ labelStatus\(log\.toStatus\) \}\}/)
  assert.match(source, /\{\{ labelAction\(log\.action\) \}\}/)
})

test('workspace fire pages keep their main content vertically scrollable', () => {
  const source = readSource('src/pages/page-web/projects/workspace.vue')

  assert.match(source, /\.project-app-wrapper/)
  assert.match(source, /&\.fire-mode[\s\S]*\.main-content[\s\S]*overflow-y: auto/)
})
