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

  assert.match(source, /const isTaskRouteSelector = computed\(/)
  assert.match(source, /const showPlanningTools = computed\(\(\) => !isTaskRouteSelector\.value\)/)
  assert.match(source, /<a-collapse[\s\S]*v-if="showPlanningTools"/)
  assert.match(source, /header="监测航线"[\s\S]*<div class="planning-panel">[\s\S]*<div class="planned-wayline-panel">/)
  assert.match(source, /header="投放航线"[\s\S]*<div class="fc100-planning-panel">/)
  assert.match(source, /<a-col :span="15">\{\{ isTaskRouteSelector \? '选择KMZ航线文件' : '航线库' \}\}<\/a-col>/)
})
