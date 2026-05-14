export function swapPrimaryPreference (currentPreference) {
  return currentPreference === 'thermal' ? 'visible' : 'thermal'
}

export function buildLivePaneState ({
  visiblePlayUrl,
  thermalPlayUrl,
  primaryPreference,
  allowSharedThermalPreview = false
}) {
  const visibleUrl = visiblePlayUrl || ''
  const hasThermalUrl = Boolean(thermalPlayUrl)
  const thermalUrl = hasThermalUrl ? thermalPlayUrl : ''
  const isSharedStream = allowSharedThermalPreview && hasThermalUrl && thermalPlayUrl === visibleUrl
  const effectiveThermalUrl = isSharedStream || !hasThermalUrl || thermalPlayUrl !== visibleUrl ? thermalUrl : ''
  const canUseVisibleStreamForFocusedThermal = allowSharedThermalPreview &&
    primaryPreference === 'thermal' &&
    Boolean(visibleUrl) &&
    (!hasThermalUrl || thermalPlayUrl === visibleUrl)
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
