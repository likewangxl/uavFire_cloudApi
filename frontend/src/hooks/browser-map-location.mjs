import { resolveInitialMapView } from './map-site-policy.mjs'

const CACHE_KEY = 'browser_map_view_v1'

export function browserMapInitialView (site, storage = globalThis.localStorage) {
  let cached = null
  try { cached = JSON.parse(storage.getItem(CACHE_KEY)) } catch {}
  // A recent browser view is a temporary fallback while fresh positioning runs.
  const history = resolveInitialMapView(null, cached, { center: [105, 35], zoom: 4 })
  return history.source === 'history' ? history : resolveInitialMapView(site, null, history)
}

export function createBrowserLocationControl (env = globalThis) {
  let map, container, button, status
  let disposed = false
  let revision = 0
  let requestId = 0
  const cancelRelocation = () => { revision++ }
  const persist = () => {
    const c = map.getCenter()
    try { env.localStorage.setItem(CACHE_KEY, JSON.stringify({ center: [c.lng, c.lat], zoom: map.getZoom() })) } catch {}
  }
  function locate () {
    const id = ++requestId
    const startRevision = revision
    if (!env.isSecureContext) {
      status.textContent = '定位需要 HTTPS 或 localhost，当前保留原视野'
      return
    }
    if (!env.navigator?.geolocation) {
      status.textContent = '浏览器不支持定位，当前保留原视野'
      return
    }
    button.disabled = true
    status.textContent = '正在获取当前位置…'
    const finish = (position, error) => {
      if (disposed || id !== requestId) return
      button.disabled = false
      if (error) {
        status.textContent = error.code === 1 ? '未获位置授权，请在浏览器中允许后重试' : error.code === 3 ? '定位超时，请重试；当前保留原视野' : '暂时无法获取位置，请重试；当前保留原视野'
        return
      }
      const { longitude, latitude, accuracy } = position.coords
      if (![longitude, latitude].every(Number.isFinite) || Math.abs(longitude) > 180 || Math.abs(latitude) > 85) {
        status.textContent = '定位坐标无效，当前保留原视野'
        return
      }
      if (revision !== startRevision) {
        status.textContent = '已保留当前操作视野，可点击重新定位'
        return
      }
      map.jumpTo({ center: [longitude, latitude], zoom: 16 })
      persist()
      status.textContent = Number.isFinite(accuracy) ? `浏览器位置 · 精度约 ${Math.round(accuracy)} 米` : '已定位到浏览器当前位置'
    }
    try {
      env.navigator.geolocation.getCurrentPosition(p => finish(p), e => finish(null, e), { enableHighAccuracy: true, timeout: 10000, maximumAge: 0 })
    } catch { finish(null, { code: 2 }) }
  }
  return {
    onAdd (target) {
      map = target
      container = env.document.createElement('div')
      container.className = 'maplibregl-ctrl browser-location-control'
      container.style.cssText = 'background:#102c3b;color:#fff;padding:8px 12px;border-radius:6px;max-width:270px;font-size:12px;'
      button = env.document.createElement('button')
      button.type = 'button'
      button.textContent = '回到我的位置'
      button.style.cssText = 'display:block;color:#fff;background:transparent;border:0;cursor:pointer;padding:0 0 4px;font:inherit;'
      button.onclick = locate
      status = env.document.createElement('div')
      status.setAttribute('role', 'status')
      container.appendChild(button)
      container.appendChild(status)
      map.on('movestart', cancelRelocation)
      map.on('mousedown', cancelRelocation)
      map.on('touchstart', cancelRelocation)
      map.on('moveend', persist)
      locate()
      return container
    },
    onRemove () {
      disposed = true
      requestId++
      map.off('movestart', cancelRelocation)
      map.off('mousedown', cancelRelocation)
      map.off('touchstart', cancelRelocation)
      map.off('moveend', persist)
      button.onclick = null
      container.remove()
    },
  }
}
