import test from 'node:test'
import assert from 'node:assert/strict'

import {
  getCloudControlAuthState,
} from '../src/pages/page-web/projects/cloud-control-auth-policy.mjs'

test('cloud_control_auth with flight key is treated as authorized', () => {
  assert.deepEqual(getCloudControlAuthState({
    cloud_control_auth: ['flight'],
  }), {
    authorized: true,
    controlKeys: ['flight'],
  })
})

test('empty cloud_control_auth is treated as unauthorized', () => {
  assert.deepEqual(getCloudControlAuthState({
    cloud_control_auth: [],
  }), {
    authorized: false,
    controlKeys: [],
  })
})

test('boolean is_cloud_control_auth also controls authorization', () => {
  assert.deepEqual(getCloudControlAuthState({
    is_cloud_control_auth: true,
  }), {
    authorized: true,
    controlKeys: [],
  })
})
