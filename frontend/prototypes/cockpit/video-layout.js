export const VIDEO_LAYOUT_KEY = 'uavfire.cockpit.prototype.video-layout.v2'
export const clamp = (value, min, max) => Math.min(max, Math.max(min, value))

// Coordinates are fractions of the stage, so layouts survive viewport changes.
export function boundWindow (window) {
  const width = clamp(window.width, 0.08, 1)
  const height = clamp(window.height, 0.08, 1)
  return { ...window, width, height, x: clamp(window.x, 0, 1 - width), y: clamp(window.y, 0, 1 - height) }
}
export function tileWindows (windows) {
  if (!windows.length) return []
  const columns = Math.ceil(Math.sqrt(windows.length))
  const rows = Math.ceil(windows.length / columns)
  const gap = 0.016
  return windows.map((window, index) => ({
    ...window, x: (index % columns) / columns + gap / 2, y: Math.floor(index / columns) / rows + gap / 2,
    width: 1 / columns - gap, height: 1 / rows - gap, z: index + 1
  }))
}
export function defaultWindows () {
  return tileWindows([1, 2, 3, 4].map(id => ({ id, lens: 'visible' })))
}
export function loadWindows (ids) {
  try {
    const saved = JSON.parse(localStorage.getItem(VIDEO_LAYOUT_KEY))
    if (!Array.isArray(saved) || saved.length > ids.length) return defaultWindows()
    const seen = new Set()
    return saved.filter(window => {
      if (!window || !ids.includes(window.id) || seen.has(window.id)) return false
      if (!['x', 'y', 'width', 'height', 'z'].every(key => Number.isFinite(window[key]))) return false
      seen.add(window.id)
      return true
    }).map(window => boundWindow({ ...window, lens: window.lens === 'thermal' ? 'thermal' : 'visible' }))
  } catch (_) { return defaultWindows() }
}
export function clearVideoLayout () {
  try { localStorage.removeItem(VIDEO_LAYOUT_KEY) } catch (_) { /* Session state remains usable. */ }
}
