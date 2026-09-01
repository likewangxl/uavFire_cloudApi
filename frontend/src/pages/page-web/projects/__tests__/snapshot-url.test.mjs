import test from 'node:test'
import assert from 'node:assert/strict'
import {
  normalizeFireSnapshotUrls,
  normalizeSnapshotUrl,
} from '../../../../api/fire/snapshot-url.mjs'

test('loopback snapshot URLs use the same-origin proxy path', () => {
  assert.equal(
    normalizeSnapshotUrl('http://127.0.0.1:9000/api/v1/snapshots/fire-1-annotated.jpg'),
    '/api/v1/snapshots/fire-1-annotated.jpg',
  )
  assert.equal(
    normalizeSnapshotUrl('http://localhost:9000/api/v1/snapshots/fire-1-raw.jpg?v=2#preview'),
    '/api/v1/snapshots/fire-1-raw.jpg?v=2#preview',
  )
})

test('external snapshot hosts and unrelated loopback URLs stay unchanged', () => {
  const external = 'https://media.example.test/api/v1/snapshots/fire-2.jpg'
  const unrelated = 'http://127.0.0.1:9000/healthz'

  assert.equal(normalizeSnapshotUrl(external), external)
  assert.equal(normalizeSnapshotUrl(unrelated), unrelated)
})

test('fire event and history image fields are normalized recursively', () => {
  const input = {
    data: [{
      visibleImageUrl: 'http://127.0.0.1:9000/api/v1/snapshots/visible.jpg',
      history: [{
        thermalImageUrl: 'http://localhost:9000/api/v1/snapshots/thermal.jpg',
      }],
    }],
  }

  assert.deepEqual(normalizeFireSnapshotUrls(input), {
    data: [{
      visibleImageUrl: '/api/v1/snapshots/visible.jpg',
      history: [{
        thermalImageUrl: '/api/v1/snapshots/thermal.jpg',
      }],
    }],
  })
  assert.equal(input.data[0].visibleImageUrl, 'http://127.0.0.1:9000/api/v1/snapshots/visible.jpg')
})
