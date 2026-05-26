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
  lastCommandStatus
}) {
  if ((lastCommandStatus || '').toLowerCase() !== 'applied') {
    return currentPreference
  }
  if (lastCommandAction === 'focus-thermal') {
    return 'thermal'
  }
  if (lastCommandAction === 'focus-visible') {
    return 'visible'
  }
  return currentPreference
}

export function buildLivePlaybackKey ({
  primaryKind,
  primaryUrl,
  primaryCrop,
  previewKind,
  previewUrl,
  previewCrop,
  lastCommandAction,
  lastCommandStatus,
  currentMode
}) {
  return [
    primaryKind || '',
    primaryUrl || '',
    JSON.stringify(primaryCrop || null),
    previewKind || '',
    previewUrl || '',
    JSON.stringify(previewCrop || null),
    lastCommandAction || '',
    (lastCommandStatus || '').toLowerCase(),
    currentMode || ''
  ].join('|')
}
