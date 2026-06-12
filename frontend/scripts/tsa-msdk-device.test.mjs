import test from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'

const read = (path) => readFileSync(new URL(path, import.meta.url), 'utf8')

const apiPath = new URL('../src/api/msdk-device.ts', import.meta.url)
const storeSource = read('../src/store/index.ts')
const tsaSource = read('../src/pages/page-web/projects/tsa.vue')
const mapHookSource = read('../src/hooks/use-g-map-tsa.ts')
const controlHookSource = read('../src/components/g-map/use-drone-control.ts')

test('frontend exposes msdk device list and command api wrappers', () => {
  assert.equal(existsSync(apiPath), true, 'missing src/api/msdk-device.ts')
  const apiSource = read('../src/api/msdk-device.ts')

  assert.match(apiSource, /export interface MsdkDeviceState/)
  assert.match(apiSource, /deviceName\?:\s*string/)
  assert.match(apiSource, /export function normalizeMsdkDeviceState\b/)
  assert.match(apiSource, /pick\(device,\s*'aircraftSn',\s*'aircraft_sn'\)/)
  assert.match(apiSource, /pick\(device,\s*'gatewaySn',\s*'gateway_sn'\)/)
  assert.match(apiSource, /pickText\(device,\s*\[\s*'deviceName',\s*'device_name',\s*'productName',\s*'product_name',\s*'name'\s*\]\)/)
  assert.match(apiSource, /response\.data\.map\(normalizeMsdkDeviceState\)/)
  assert.match(apiSource, /export async function listMsdkDevices\b/)
  assert.match(apiSource, /request\.get\(`?\$\{HTTP_PREFIX\}\/msdk\/devices`?\)/)
  assert.match(apiSource, /export async function sendMsdkCommand\b/)
  assert.match(apiSource, /\/msdk\/devices\/\$\{aircraftSn\}\/commands/)
  assert.match(apiSource, /command,\s*\n\s*params/)
})

test('store keeps latest msdk devices keyed by aircraft sn', () => {
  assert.match(storeSource, /msdkDeviceState:\s*\{[\s\S]*devices:\s*\{\}/)
  assert.match(storeSource, /SET_MSDK_DEVICE_STATE\s*\(state,\s*devices/)
  assert.match(storeSource, /device\.aircraftSn/)
  assert.match(storeSource, /state\.msdkDeviceState\.devices\s*=\s*next/)
})

test('tsa lists online aircraft from msdk devices instead of cloud device topology', () => {
  assert.match(tsaSource, /listMsdkDevices/)
  assert.match(tsaSource, /SET_MSDK_DEVICE_STATE/)
  assert.match(tsaSource, /msdkOnlineDevices/)
  assert.match(tsaSource, /function\s+toOnlineDeviceFromMsdk\b/)
  assert.match(tsaSource, /onlineDevices\.data\s*=\s*sortDevicesOnlineFirst\(msdkOnlineDevices\.value\.map\(toOnlineDeviceFromMsdk\)\)/)
  assert.match(tsaSource, /selectedMonitoringDeviceSn/)
  assert.match(tsaSource, /selectableMonitoringDevices/)
  assert.match(tsaSource, /monitoring-device-card-dropdown/)
  assert.doesNotMatch(tsaSource, /placeholder="选择监测飞行器"/)
  assert.doesNotMatch(tsaSource, /\bgetDeviceTopo\b/)
  assert.doesNotMatch(tsaSource, /from ['"]\/@\/api\/drc['"]/)
  assert.doesNotMatch(tsaSource, /from ['"]\/@\/api\/drone-control\/drone['"]/)
})

test('tsa monitoring cards prefer msdk reported device names over serial numbers', () => {
  assert.match(tsaSource, /callsign:\s*device\.deviceName\s*\|\|\s*device\.model\s*\|\|\s*''/)
  assert.doesNotMatch(tsaSource, /function defaultMonitoringDeviceName/)
  assert.match(tsaSource, /function normalizeMonitoringModelName[\s\S]*DJI Matrice 4T/)
  assert.match(tsaSource, /function formatMonitoringDeviceModel[\s\S]*normalizeMonitoringModelName\(device\.callsign\)[\s\S]*normalizeMonitoringModelName\(device\.model\)[\s\S]*device\.sn/)
  assert.doesNotMatch(tsaSource, /function formatMonitoringDeviceModel[\s\S]*device\.callsign \|\| device\.sn/)
})

test('tsa monitoring telemetry does not fabricate zero values for missing msdk fields', () => {
  assert.match(tsaSource, /function optionalMonitoringValue/)
  assert.match(tsaSource, /home_distance:\s*optionalMonitoringValue\(device\.homeDistance\)/)
  assert.match(tsaSource, /wind_speed:\s*optionalMonitoringValue\(device\.windSpeed\)/)
  assert.doesNotMatch(tsaSource, /home_distance:\s*String\(device\.homeDistance \?\? 0\)/)
  assert.doesNotMatch(tsaSource, /wind_speed:\s*String\(device\.windSpeed \?\? 0\)/)
})

test('tsa presents msdk control semantics to operators', () => {
  assert.match(tsaSource, /MSDK 控制通道已就绪/)
  assert.match(tsaSource, /MSDK 基础飞控已接入/)
  assert.match(tsaSource, /MSDK 控制通道保持中/)
  assert.match(tsaSource, /已进入 MSDK 控制通道/)
  assert.doesNotMatch(tsaSource, /云控会话/)
  assert.doesNotMatch(tsaSource, /当前 DRC 会话/)
  assert.doesNotMatch(tsaSource, /并已连接 DRC/)
})

test('tsa moves map markers from msdk latitude and longitude', () => {
  assert.match(tsaSource, /deviceTsaUpdate/)
  assert.match(tsaSource, /device\.longitude/)
  assert.match(tsaSource, /device\.latitude/)
  assert.match(tsaSource, /moveTo\(device\.aircraftSn/)
  assert.match(mapHookSource, /function moveTo \(sn: string, lng: number, lat: number, type\?: number, name\?: string\)/)
  assert.doesNotMatch(mapHookSource, /\bgetDeviceBySn\b/)
})

test('drone control hook sends msdk commands instead of cloud api drone control', () => {
  assert.match(controlHookSource, /sendMsdkCommand/)
  assert.doesNotMatch(controlHookSource, /postTakeoffToPoint/)
  assert.doesNotMatch(controlHookSource, /postFlyToPoint/)
  assert.doesNotMatch(controlHookSource, /deleteFlyToPoint/)
})

test('tsa always uses native msdk takeoff for the takeoff button', () => {
  assert.match(tsaSource, /function\s+canTakeoff\b/)
  assert.match(tsaSource, /function\s+canLand\b/)
  assert.match(tsaSource, /function\s+canVirtualStick\b/)
  assert.match(tsaSource, /function\s+canEmergencyStop\b/)
  assert.match(tsaSource, /sendMsdkCommand\(device\.sn,\s*'takeoff'\)/)
  assert.doesNotMatch(tsaSource, /sendMsdkCommand\(device\.sn,\s*'takeoff_to_point'/)
  assert.match(tsaSource, /sendMsdkCommand\(device\.sn,\s*'land'\)/)
  assert.match(tsaSource, /sendMsdkCommand\(device\.sn,\s*'virtual_stick'/)
  assert.match(tsaSource, /sendMsdkCommand\(device\.sn,\s*'emergency_stop'\)/)
})
