// Serial requests prevent a delayed renewal for the old aircraft overtaking a release/new selection.
export function createVideoViewerLease ({ send, setIntervalFn = setInterval, clearIntervalFn = clearInterval, onError = () => {} }) {
  let target = null
  let lastSent
  let inFlight = false
  let dirty = false
  let stopped = false
  async function flush () {
    if (inFlight) { dirty = true; return }
    if (target == null && lastSent == null) return
    inFlight = true
    const sending = target
    try {
      await send(sending)
      lastSent = sending
    } catch (error) {
      onError(error)
    } finally {
      inFlight = false
      if (dirty || target !== sending) {
        dirty = false
        await flush()
      }
    }
  }
  const timer = setIntervalFn(() => { if (!stopped) flush() }, 5000)
  return {
    select (sn) { if (stopped) return; target = sn || null; void flush() },
    stop () { stopped = true; clearIntervalFn(timer); target = null; void flush() }
  }
}

// A late factory result is closed even if the page disappeared while loading the RTC client.
export function createVideoPlayerSlot (close) {
  let generation = 0
  let current = null
  const clear = () => { generation++; if (current) close(current); current = null }
  return {
    clear,
    async replace (factory) {
      clear()
      const epoch = generation
      const next = await factory(() => epoch === generation)
      if (epoch !== generation) { if (next) close(next); return }
      current = next
    }
  }
}

const finite = value => typeof value === 'number' && Number.isFinite(value)
const delta = (current, previous, key) => finite(current?.[key]) && finite(previous?.[key]) && current[key] >= previous[key]
  ? current[key] - previous[key] : null

export function sampleVideoStats (stats, previous) {
  const rows = Array.from(stats.values())
  const current = rows.find(row => row.type === 'inbound-rtp' && (row.kind === 'video' || row.mediaType === 'video'))
  const prior = current?.id === previous?.id ? previous : null
  const elapsed = current && prior ? (current.timestamp - prior.timestamp) / 1000 : 0
  const bytes = delta(current, prior, 'bytesReceived')
  const frames = delta(current, prior, 'framesDecoded')
  const lost = delta(current, prior, 'packetsLost')
  const received = delta(current, prior, 'packetsReceived')
  const buffer = delta(current, prior, 'jitterBufferDelay')
  const emitted = delta(current, prior, 'jitterBufferEmittedCount')
  const transport = rows.find(row => row.type === 'transport' && row.selectedCandidatePairId)
  const pair = rows.find(row => row.id === transport?.selectedCandidatePairId) ||
    rows.find(row => row.type === 'candidate-pair' && row.nominated && row.state === 'succeeded')
  return {
    snapshot: current ? { ...current } : null,
    bitrateMbps: elapsed > 0 && bytes != null ? bytes * 8 / elapsed / 1e6 : null,
    fps: elapsed > 0 && frames != null ? frames / elapsed : null,
    lossPercent: elapsed > 0 && lost != null && received != null && lost + received > 0 ? lost * 100 / (lost + received) : null,
    jitterBufferMs: buffer != null && emitted > 0 ? buffer * 1000 / emitted : null,
    rttMs: finite(pair?.currentRoundTripTime) ? pair.currentRoundTripTime * 1000 : null,
    freezeCount: finite(current?.freezeCount) ? current.freezeCount : null
  }
}

export function monitorVideoPlayer (endpoint, video, onSample, options = {}) {
  const now = options.now || (() => performance.now())
  const setTimer = options.setIntervalFn || setInterval
  const clearTimer = options.clearIntervalFn || clearInterval
  let disposed = false
  let sampling = false
  let previous = null
  let lastFrameAt = now()
  let frameCallback
  let lastProgress
  const onFrame = () => {
    if (disposed) return
    lastFrameAt = now()
    frameCallback = video.requestVideoFrameCallback(onFrame)
  }
  if (video.requestVideoFrameCallback) frameCallback = video.requestVideoFrameCallback(onFrame)
  const tick = async () => {
    if (disposed || sampling) return
    sampling = true
    try {
      if (!video.requestVideoFrameCallback) {
        const progress = video.getVideoPlaybackQuality?.().totalVideoFrames ?? video.currentTime
        if (finite(progress) && progress !== lastProgress) lastFrameAt = now()
        lastProgress = progress
      }
      let metrics = {}
      if (endpoint.pc?.getStats) {
        metrics = sampleVideoStats(await endpoint.pc.getStats(), previous)
        previous = metrics.snapshot
      }
      if (!disposed) onSample({ ...metrics, stalled: now() - lastFrameAt >= 5000 })
    } catch (_) {
      if (!disposed) onSample({ stalled: now() - lastFrameAt >= 5000 })
    } finally { sampling = false }
  }
  const timer = setTimer(tick, 2000)
  return () => {
    disposed = true
    clearTimer(timer)
    if (frameCallback != null) video.cancelVideoFrameCallback?.(frameCallback)
  }
}

export function videoProfileLabel (row) {
  if (!row) return '视频档位待确认'
  if (!row.online) return '视频策略状态已过期'
  if (row.agent?.error) return '视频降档保护中'
  if (row.agent?.streaming === false) return '视频尚未起流'
  if (row.agent?.applied_profile === 'HIGH') return '高清上传'
  if (row.agent?.applied_profile === 'LOW') {
    return row.target_profile === 'HIGH' || row.reason === 'waiting-for-high-slot' ? '低清上传 · 高清排队中' : '低清上传'
  }
  return '视频档位待确认'
}
