import test from 'node:test'
import assert from 'node:assert/strict'
import { createVideoPlayerSlot, createVideoViewerLease, monitorVideoPlayer, sampleVideoStats, videoProfileLabel } from '../video-bandwidth-runtime.mjs'

const report = row => new Map([['v', { id: 'v', type: 'inbound-rtp', kind: 'video', ...row }]])

test('a late player cannot replace a newer connection, and clearing closes pending results', async () => {
  const closed = []
  const slot = createVideoPlayerSlot(player => closed.push(player))
  let finishOld
  let oldIsCurrent
  const old = slot.replace(isCurrent => {
    oldIsCurrent = isCurrent
    return new Promise(resolve => { finishOld = resolve })
  })
  await slot.replace(async () => 'new')
  assert.equal(oldIsCurrent(), false)
  finishOld('old')
  await old
  assert.deepEqual(closed, ['old'])
  let finishHidden
  const pending = slot.replace(() => new Promise(resolve => { finishHidden = resolve }))
  slot.clear()
  finishHidden('hidden')
  await pending
  assert.deepEqual(closed, ['old', 'new', 'hidden'])
})

test('replacing a failed pane leaves the independent healthy pane connected', async () => {
  const closed = []
  const primary = createVideoPlayerSlot(player => closed.push(player))
  const preview = createVideoPlayerSlot(player => closed.push(player))
  await primary.replace(async () => 'healthy')
  await preview.replace(async () => 'failed')
  await preview.replace(async () => 'replacement')
  assert.deepEqual(closed, ['failed'])
  primary.clear()
  preview.clear()
  assert.deepEqual(closed, ['failed', 'healthy', 'replacement'])
})

test('interval counters produce bitrate, loss and buffering without calling them end-to-end delay', () => {
  const old = sampleVideoStats(report({ timestamp: 1000, bytesReceived: 100, framesDecoded: 20, packetsLost: 2, packetsReceived: 100, jitterBufferDelay: 1, jitterBufferEmittedCount: 20 })).snapshot
  const sample = sampleVideoStats(report({ timestamp: 3000, bytesReceived: 1000100, framesDecoded: 80, packetsLost: 4, packetsReceived: 298, jitterBufferDelay: 4, jitterBufferEmittedCount: 80 }), old)
  assert.equal(sample.bitrateMbps, 4)
  assert.equal(sample.fps, 30)
  assert.equal(sample.lossPercent, 1)
  assert.equal(sample.jitterBufferMs, 50)
})

test('missing fields, counter resets and replaced RTP streams remain unknown', () => {
  const old = sampleVideoStats(report({ timestamp: 1000, bytesReceived: 1000, framesDecoded: 100 })).snapshot
  assert.equal(sampleVideoStats(report({ timestamp: 2000, bytesReceived: 10 }), old).bitrateMbps, null)
  assert.equal(sampleVideoStats(report({ timestamp: 2000, id: 'new', bytesReceived: 9999 }), old).bitrateMbps, null)
  assert.equal(sampleVideoStats(report({ timestamp: 2000 }), old).fps, null)
  assert.equal(sampleVideoStats(new Map(), old).snapshot, null)
})

test('selected ICE transport supplies RTT, separately from jitter buffer', () => {
  const stats = new Map([
    ['transport', { type: 'transport', selectedCandidatePairId: 'pair' }],
    ['pair', { id: 'pair', type: 'candidate-pair', currentRoundTripTime: 0.08 }]
  ])
  assert.equal(sampleVideoStats(stats).rttMs, 80)
})

test('viewer switches and release are serialized after an in-flight renewal', async () => {
  let release
  const sent = []
  const lease = createVideoViewerLease({
    send: async sn => { sent.push(sn); if (sent.length === 1) await new Promise(resolve => { release = resolve }) },
    setIntervalFn: () => 1, clearIntervalFn: () => {}
  })
  lease.select('A')
  lease.select('B')
  lease.stop()
  release()
  await new Promise(resolve => setImmediate(resolve))
  assert.deepEqual(sent, ['A', null])
})

test('viewer periodic renewal and stopping release only this page demand', async () => {
  let tick
  let cleared = false
  const sent = []
  const lease = createVideoViewerLease({
    send: async sn => sent.push(sn),
    setIntervalFn: fn => { tick = fn; return 1 }, clearIntervalFn: () => { cleared = true }
  })
  lease.select('A')
  await new Promise(resolve => setImmediate(resolve))
  tick()
  await new Promise(resolve => setImmediate(resolve))
  lease.stop()
  await new Promise(resolve => setImmediate(resolve))
  assert.deepEqual(sent, ['A', 'A', null])
  assert.equal(cleared, true)
})

test('frame stagnation is detected and monitor disposal clears both timers and frame callback', async () => {
  let now = 0
  let tick
  let callback
  let cancelled = false
  let cleared = false
  const samples = []
  const video = { requestVideoFrameCallback: fn => { callback = fn; return 9 }, cancelVideoFrameCallback: () => { cancelled = true } }
  const stop = monitorVideoPlayer({}, video, value => samples.push(value), {
    now: () => now, setIntervalFn: fn => { tick = fn; return 1 }, clearIntervalFn: () => { cleared = true }
  })
  now = 6000
  await tick()
  assert.equal(samples.at(-1).stalled, true)
  callback()
  await tick()
  assert.equal(samples.at(-1).stalled, false)
  stop()
  await tick()
  assert.equal(samples.length, 2)
  assert.equal(cancelled && cleared, true)
})

test('status does not mistake desired high or stale reports for applied high', () => {
  assert.match(videoProfileLabel({ online: true, target_profile: 'HIGH', agent: { applied_profile: 'LOW' } }), /排队/)
  assert.match(videoProfileLabel({ online: false, agent: { applied_profile: 'HIGH' } }), /过期/)
  assert.match(videoProfileLabel({ online: true, agent: { error: 'failed', applied_profile: 'HIGH' } }), /保护/)
  assert.match(videoProfileLabel({ online: true, agent: { streaming: false, applied_profile: 'HIGH' } }), /未起流/)
})
