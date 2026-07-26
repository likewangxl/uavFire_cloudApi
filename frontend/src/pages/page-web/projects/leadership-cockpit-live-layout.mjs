export function swapPrimaryPreference (currentPreference) {
  return currentPreference === 'thermal' ? 'visible' : 'thermal'
}

export function buildDualStreamCandidateSns ({
  flightHudSn,
  agentAircraftSn,
  currentSn,
  fireDetectionSn,
  fallbackSn = 'RC_PLUS_LOCAL'
}) {
  const realSns = [
    agentAircraftSn,
    fireDetectionSn,
    flightHudSn,
    currentSn
  ].filter((sn, index, arr) => Boolean(sn) && sn !== fallbackSn && arr.indexOf(sn) === index)
  const fallbackSns = [flightHudSn, currentSn, fallbackSn]
    .filter((sn, index, arr) => Boolean(sn) && sn === fallbackSn && arr.indexOf(sn) === index)
  return [...realSns, ...fallbackSns]
}

export function buildLivePaneState ({
  visiblePlayUrl,
  thermalPlayUrl,
  primaryPreference,
  appliedFocusAction,
  appliedFocusStatus,
  allowSharedThermalPreview = false
}) {
  const visibleUrl = visiblePlayUrl || ''
  const hasThermalUrl = Boolean(thermalPlayUrl)
  const thermalUrl = hasThermalUrl ? thermalPlayUrl : ''
  const isSharedStream = allowSharedThermalPreview && hasThermalUrl && thermalPlayUrl === visibleUrl
  const isAppliedThermalFocus = appliedFocusAction === 'focus-thermal' &&
    (appliedFocusStatus || '').toLowerCase() === 'applied'
  const canUseVisibleStreamForFocusedThermal = allowSharedThermalPreview &&
    primaryPreference === 'thermal' &&
    isAppliedThermalFocus &&
    Boolean(visibleUrl) &&
    (!hasThermalUrl || thermalPlayUrl === visibleUrl)
  const effectiveThermalUrl = isSharedStream || !hasThermalUrl || thermalPlayUrl !== visibleUrl ? thermalUrl : ''
  const thermalKind = isSharedStream || canUseVisibleStreamForFocusedThermal ? 'thermal-shared' : 'thermal'
  const primaryThermalUrl = effectiveThermalUrl || (canUseVisibleStreamForFocusedThermal ? visibleUrl : '')
  const wantsThermalPrimary = primaryPreference === 'thermal' && primaryThermalUrl
  const visibleCrop = null
  const thermalCrop = null
  const thermalPreviewUrl = isSharedStream ? '' : effectiveThermalUrl
  const visiblePreviewUrl = isSharedStream || canUseVisibleStreamForFocusedThermal ? '' : visibleUrl

  if (wantsThermalPrimary) {
    return {
      primary: {
        kind: thermalKind,
        url: primaryThermalUrl,
        crop: thermalCrop,
        clickable: false
      },
      preview: {
        kind: visibleUrl ? 'visible' : 'visible-placeholder',
        url: visiblePreviewUrl,
        crop: visibleCrop,
        clickable: Boolean(visibleUrl),
        focusAction: 'focus-visible'
      }
    }
  }

  return {
    primary: {
      kind: visibleUrl ? 'visible' : 'visible-placeholder',
      url: visibleUrl,
      crop: visibleCrop,
      clickable: false
    },
    preview: {
      kind: thermalPreviewUrl ? thermalKind : 'thermal-placeholder',
      url: thermalPreviewUrl,
      crop: thermalCrop,
      clickable: Boolean(visibleUrl || effectiveThermalUrl),
      focusAction: 'focus-thermal'
    }
  }
}

export function resolveAppliedFocusPreference ({
  currentPreference,
  lastCommandAction,
  lastCommandStatus,
  fireDetectionRunning = true
}) {
  if ((lastCommandStatus || '').toLowerCase() !== 'applied') {
    return currentPreference
  }
  if (lastCommandAction === 'focus-thermal') {
    if (!fireDetectionRunning) {
      return currentPreference
    }
    return 'thermal'
  }
  if (lastCommandAction === 'focus-visible') {
    return 'visible'
  }
  return currentPreference
}

export function shouldAutoRestoreVisibleFocus ({
  fireDetectionRunning,
  focusSwitching = false,
  primaryPreference = 'visible',
  lastCommandAction,
  lastCommandStatus,
  currentMode
}) {
  // 用户主动把主画面切成红外时（preference=thermal）尊重用户选择，不自动还原。
  if (fireDetectionRunning || focusSwitching || primaryPreference === 'thermal') {
    return false
  }
  // 可见光/红外是同一路共享流，画面取决于飞机相机当前在哪个源。
  // 只要最后一条焦点命令是 focus-thermal（无论 applied/expired/pending），就说明相机被切到了红外源——
  // 不能再要求 status==='applied'，因为命令会过期（expired）但相机仍停在红外。
  const lastWasThermal = lastCommandAction === 'focus-thermal'
  const thermalMode = (currentMode || '').toLowerCase().includes('thermal')
  return lastWasThermal || thermalMode
}

export const LIVE_RECONNECT_MAX_ATTEMPTS = 8
export const LIVE_RECONNECT_DELAY_MS = 2500

// 断流自愈：agent 切镜头（focus-thermal/focus-visible）会 restartLiveStream 重建推流，
// ZLM 随之踢掉播放端 WebRTC 会话，播放器定格在最后一帧。
// 策略：播通过的会话断开 → 自动重连；重连尝试自身失败（推流还没回来）→ 继续重试到上限。
// 从未播通的首次失败不自动重试——那是流还不存在，由轮询拿到 url 变化时经 playbackKey 重建。
export function shouldReconnectLivePlayer ({ hasPlayed, attempts }) {
  if (attempts >= LIVE_RECONNECT_MAX_ATTEMPTS) {
    return false
  }
  return Boolean(hasPlayed) || attempts > 0
}

// 命令流转（focus-*/measure-thermal-region 的 pending/sent/applied）不进 key：
// 识别期间后端命令流水不断，进 key 会让播放器被反复销毁重建、一直卡在加载态。
// 只有画面来源真正变化（kind/url/crop/相机当前模式）才值得重建播放器。
export function buildLivePlaybackKey ({
  primaryKind,
  primaryUrl,
  primaryCrop,
  previewKind,
  previewUrl,
  previewCrop,
  currentMode
}) {
  return [
    primaryKind || '',
    primaryUrl || '',
    JSON.stringify(primaryCrop || null),
    previewKind || '',
    previewUrl || '',
    JSON.stringify(previewCrop || null),
    currentMode || ''
  ].join('|')
}
