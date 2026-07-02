import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../../../..')

function readSource (path) {
  return readFileSync(resolve(root, path), 'utf8')
}

test('task plan route picker only exposes generated KMZ wayline files', () => {
  const source = readSource('src/pages/page-web/projects/wayline.vue')

  assert.match(source, /const isTaskRouteSelector = computed\(\(\) => route\.name === ERouterName\.SELECT_PLAN\)/)
  assert.match(source, /const showPlanningTools = computed\(\(\) => !isTaskRouteSelector\.value\)/)
  assert.match(source, /<a-tabs[\s\S]*v-if="showPlanningTools"[\s\S]*v-model:activeKey="plannerTab"/)
  assert.match(source, /<a-tab-pane key="monitor"/)
  assert.match(source, /<a-tab-pane key="delivery"/)
  assert.match(source, /<Fc100DeliveryView[\s\S]*v-if="showPlanningTools"[\s\S]*v-show="plannerTab === 'delivery'"/)
  assert.match(source, /<a-collapse[\s\S]*v-if="showPlanningTools"[\s\S]*v-show="plannerTab === 'monitor'"/)
  assert.match(source, /<div id="data" class="height-100 uranus-scrollbar" v-else-if="waylinesData\.data\.length !== 0"/)
  assert.match(source, /isTaskRouteSelector \? '.*KMZ.*' : '.*'/)
})
