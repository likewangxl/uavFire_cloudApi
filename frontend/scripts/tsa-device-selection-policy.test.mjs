import test from 'node:test'
import assert from 'node:assert/strict'
import {
  isDeviceOnline,
  pickSelectedDeviceSn,
  sortDevicesOnlineFirst,
} from '../src/pages/page-web/projects/device-selection-policy.mjs'

test('tsa device selector sorts online devices before offline devices', () => {
  const devices = sortDevicesOnlineFirst([
    { deviceSn: 'offline-a', deviceType: 'FC100 A', online: 'offline' },
    { deviceSn: 'online-b', deviceType: 'FC100 B', online: 'online' },
    { deviceSn: 'online-a', deviceType: 'FC100 A', onlineStatus: true },
  ])

  assert.deepEqual(devices.map(device => device.deviceSn), ['online-a', 'online-b', 'offline-a'])
})

test('tsa device selector keeps an existing selection and otherwise prefers online aircraft', () => {
  const devices = [
    { aircraftSn: 'offline-aircraft', model: 'M4T', online: false },
    { aircraftSn: 'online-aircraft', model: 'FC100', online: true },
  ]

  assert.equal(pickSelectedDeviceSn(devices, 'offline-aircraft', device => device.aircraftSn), 'offline-aircraft')
  assert.equal(pickSelectedDeviceSn(devices, 'missing-aircraft', device => device.aircraftSn), 'online-aircraft')
})

test('tsa device selector recognizes backend string connection states', () => {
  assert.equal(isDeviceOnline({ online: 'connected' }), true)
  assert.equal(isDeviceOnline({ online: '已连接' }), true)
  assert.equal(isDeviceOnline({ connectionState: 'offline' }), false)
})
