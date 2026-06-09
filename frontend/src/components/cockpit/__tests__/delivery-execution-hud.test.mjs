import test from 'node:test'
import assert from 'node:assert/strict'

import { buildDeliveryExecutionHud } from '../delivery-execution-hud.mjs'

test('builds FC100 delivery HUD groups from panel state', () => {
  const hud = buildDeliveryExecutionHud({
    selectedDeviceSn: '1581FAN4C257L0010RBE',
    deliveryTargetSummary: '可选投放目标 2 架',
    taskPhase: '未关联任务',
    taskProgress: '--',
    aircraftStatus: '在线 / 地面 / 电量 52%',
    liveSource: 'delivery-platform',
    taskMessage: '检测到任务状态字段，但未发现任务 ID，已跳过任务详情查询。'
  })

  assert.deepEqual(hud.identity, [
    { label: '播放对象', value: '1581FAN4C257L0010RBE' },
    { label: '直播来源', value: 'delivery-platform' }
  ])
  assert.deepEqual(hud.flight, [
    { label: '飞行器状态', value: '在线 / 地面 / 电量 52%' },
    { label: '投放目标', value: '可选投放目标 2 架' }
  ])
  assert.deepEqual(hud.task, [
    { label: '任务阶段', value: '未关联任务' },
    { label: '执行进度', value: '--' }
  ])
  assert.deepEqual(hud.chips, [
    '1581FAN4C257L0010RBE',
    '来源 delivery-platform',
    '阶段 未关联任务',
    '进度 --',
    '在线 / 地面 / 电量 52%'
  ])
  assert.deepEqual(hud.flightRows, [
    [{ label: '投放设备', value: '1581FAN4C257L0010RBE' }],
    [{ label: '状态', value: '在线 / 地面 / 电量 52%' }],
    [
      { label: '任务', value: '未关联任务' },
      { label: '进度', value: '--' }
    ],
    [{ label: '目标', value: '可选投放目标 2 架' }]
  ])
  assert.equal(hud.message, '检测到任务状态字段，但未发现任务 ID，已跳过任务详情查询。')
})

test('fills HUD fallback values when delivery data is missing', () => {
  const hud = buildDeliveryExecutionHud()

  assert.equal(hud.identity[0].value, '未选择飞行器')
  assert.equal(hud.identity[1].value, '未上报')
  assert.equal(hud.flight[0].value, '未上报')
  assert.equal(hud.flight[1].value, '无可选投放目标')
  assert.equal(hud.task[0].value, '待命')
  assert.equal(hud.task[1].value, '--')
  assert.equal(hud.chips[0], '未选择飞行器')
  assert.equal(hud.flightRows[0][0].value, '未选择飞行器')
  assert.equal(hud.flightRows[3][0].value, '无可选投放目标')
  assert.equal(hud.message, '暂无投放任务消息')
})
