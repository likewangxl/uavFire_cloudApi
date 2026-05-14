import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../../..')

function readSource (path) {
  return readFileSync(resolve(root, path), 'utf8')
}

test('task pages use fire inspection Chinese copy instead of DJI demo English copy', () => {
  const sources = [
    readSource('src/components/task/CreatePlan.vue'),
    readSource('src/components/task/TaskPanel.vue'),
    readSource('src/types/task.ts')
  ].join('\n')

  const requiredCopy = [
    '火情巡检任务库',
    '新建巡检计划',
    '计划名称',
    '巡检航线',
    '执行设备',
    '立即巡检',
    '定时巡检',
    '条件巡检',
    '返航高度',
    '失控动作',
    '媒体文件上传',
    '暂无巡检任务',
    '条/页'
  ]

  for (const copy of requiredCopy) {
    assert.match(sources, new RegExp(copy), `缺少中文文案：${copy}`)
  }

  const forbiddenCopy = [
    'Task Plan Library',
    'Create Plan',
    'Plan Name',
    'Flight Route',
    'Select Route',
    'Select Device',
    'Plan Timer',
    'Lost Action',
    'Upload now',
    'Are you sure you want to',
    'Deleted successfully',
    'Suspended successfully',
    'Resumed successfully',
    'Upload Media File successfully',
    "'Immediate'",
    "'Timed'",
    "'Continuous'"
  ]

  for (const copy of forbiddenCopy) {
    assert.doesNotMatch(sources, new RegExp(copy), `仍存在英文文案：${copy}`)
  }
})
