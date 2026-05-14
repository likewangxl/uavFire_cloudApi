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
  assert.match(source, /<div class="planning-panel" v-if="showPlanningTools">/)
  assert.match(source, /<div class="planned-wayline-panel" v-if="showPlanningTools">/)
  assert.match(source, /<a-col :span="15">\{\{ isTaskRouteSelector \? '选择KMZ航线文件' : '航线库' \}\}<\/a-col>/)
})
