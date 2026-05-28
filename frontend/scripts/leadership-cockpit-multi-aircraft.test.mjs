import test from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'

const read = (path) => readFileSync(new URL(path, import.meta.url), 'utf8')
const exists = (path) => existsSync(new URL(path, import.meta.url))

const cockpitSource = read('../src/pages/page-web/projects/leadership-cockpit.vue')
const deliveryApiSource = read('../src/api/fire/delivery.ts')
const deliveryControllerSource = read('../../backend/uavfire/src/main/java/com/yx/uavfire/fc100/deliverysync/controller/DeliveryController.java')
const deliveryLiveDtoSource = read('../../backend/uavfire/src/main/java/com/yx/uavfire/fc100/deliverysync/model/dto/DeliveryDeviceLiveDTO.java')
const selectorPath = '../src/components/cockpit/CockpitAircraftStreamSelector.vue'
const deliveryPanelPath = '../src/components/cockpit/CockpitDeliveryExecutionPanel.vue'

test('cockpit exposes three visual tabs with fire monitoring and delivery execution', () => {
  assert.match(cockpitSource, /'fire-monitor'/)
  assert.match(cockpitSource, /'delivery-execution'/)
  assert.match(cockpitSource, /态势图/)
  assert.match(cockpitSource, /火情监测画面/)
  assert.match(cockpitSource, /投放执行画面/)
  assert.match(cockpitSource, /FC100 投放执行画面/)
})

test('cockpit integrates the shared aircraft stream selector for live tabs', () => {
  assert.match(cockpitSource, /CockpitAircraftStreamSelector/)
  assert.match(cockpitSource, /fireMonitorTargets/)
  assert.match(cockpitSource, /selectedFireMonitorTargetKey/)
  assert.match(cockpitSource, /selectedFireMonitorTarget/)
  assert.match(cockpitSource, /deliveryExecutionTargets/)
  assert.match(cockpitSource, /selectedDeliveryTargetKey/)
  assert.match(cockpitSource, /selectedDeliveryTarget/)
})

test('aircraft stream selector component exposes target contract and polished menu hooks', () => {
  assert.equal(exists(selectorPath), true)
  const source = read(selectorPath)
  assert.match(source, /export interface CockpitStreamTarget/)
  assert.match(source, /role:\s*'fire-monitor'\s*\|\s*'delivery'/)
  assert.match(source, /streamStatus:\s*'running'\s*\|\s*'idle'\s*\|\s*'offline'\s*\|\s*'error'/)
  assert.match(source, /defineProps/)
  assert.match(source, /defineEmits/)
  assert.match(source, /update:value/)
  assert.match(source, /stream-target-selector/)
  assert.match(source, /stream-target-trigger/)
  assert.match(source, /stream-target-menu/)
  assert.match(source, /stream-target-option/)
  assert.match(source, /stream-target-empty/)
  assert.match(source, /火情监测飞机/)
  assert.match(source, /投放执行飞机/)
  assert.match(source, /暂无可播放飞行器/)
})

test('delivery api exposes fc100 device live endpoint', () => {
  assert.match(deliveryApiSource, /export interface DeliveryDeviceLiveDTO/)
  assert.match(deliveryApiSource, /streamStatus:\s*'running'\s*\|\s*'idle'\s*\|\s*'offline'\s*\|\s*'error'\s*\|\s*string/)
  assert.match(deliveryApiSource, /deviceLive:\s*\(deviceSn:\s*string\)/)
  assert.match(deliveryApiSource, /\/api\/fire\/delivery\/devices\/\$\{deviceSn\}\/live/)
})

test('backend starts fc100 bypass stream and returns zlm playback url', () => {
  assert.match(deliveryLiveDtoSource, /class DeliveryDeviceLiveDTO/)
  assert.match(deliveryLiveDtoSource, /private String playUrl/)
  assert.match(deliveryLiveDtoSource, /private String streamStatus/)
  assert.match(deliveryControllerSource, /@GetMapping\("\/delivery\/devices\/\{sn\}\/live"\)/)
  assert.match(deliveryControllerSource, /startBypassStream/)
  assert.match(deliveryControllerSource, /buildFc100BypassPlaybackUrl/)
  assert.match(deliveryControllerSource, /livestream\.url\.rtmp\.url/)
  assert.match(deliveryControllerSource, /livestream\.playback\.webrtc-host/)
  assert.match(deliveryControllerSource, /"delivery-platform"/)
  assert.match(deliveryControllerSource, /"running"/)
  assert.doesNotMatch(deliveryControllerSource, /buildFc100PlaybackUrl/)
  assert.doesNotMatch(deliveryControllerSource, /fc100-zlm/)
})

test('delivery execution panel renders fc100 live and status sections', () => {
  assert.equal(exists(deliveryPanelPath), true)
  const source = read(deliveryPanelPath)
  assert.match(source, /CockpitStreamTarget/)
  assert.match(source, /deliveryApi\.deviceProps/)
  assert.match(source, /deliveryApi\.deviceLive/)
  assert.match(source, /delivery-execution-panel/)
  assert.match(source, /delivery-live-frame/)
  assert.match(source, /delivery-status-grid/)
  assert.match(source, /delivery-task-message/)
  assert.match(source, /delivery-action-row/)
  assert.match(source, /FC100 投放执行画面/)
  assert.match(source, /FC100 直播画面/)
  assert.match(source, /任务阶段/)
  assert.match(source, /执行进度/)
  assert.match(source, /飞行器状态/)
})

test('delivery execution panel mounts a real ZLM WebRTC player instead of only showing the url', () => {
  assert.equal(exists(deliveryPanelPath), true)
  const source = read(deliveryPanelPath)
  assert.match(source, /ref="playerShell"/)
  assert.match(source, /document\.createElement\('video'\)/)
  assert.match(source, /ZLMRTCClient/)
  assert.match(source, /index\/api\/webrtc/)
  assert.match(source, /new ZLMRTCClient\.Endpoint/)
  assert.doesNotMatch(source, /class="live-placeholder"/)
})
