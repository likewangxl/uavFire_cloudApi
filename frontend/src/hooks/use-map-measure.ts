// 地图测距工具（MapLibre 版，自高德 RangingTool 迁移）。
// 点击落点累加距离，双击/右键结束。坐标 WGS84，球面 haversine 计米。
import maplibregl from 'maplibre-gl'

const R = 6378137
function haversine (a: [number, number], b: [number, number]) {
  const rad = Math.PI / 180
  const dLat = (b[1] - a[1]) * rad
  const dLng = (b[0] - a[0]) * rad
  const s = Math.sin(dLat / 2) ** 2 + Math.cos(a[1] * rad) * Math.cos(b[1] * rad) * Math.sin(dLng / 2) ** 2
  return 2 * R * Math.asin(Math.sqrt(s))
}
function fmt (m: number) { return m >= 1000 ? `${(m / 1000).toFixed(2)}km` : `${Math.round(m)}m` }

export function useMapMeasure (getMap: () => any) {
  const SRC = 'measure-src'
  let active = false
  let pts: [number, number][] = []
  const dots: maplibregl.Marker[] = []
  let totalLabel: maplibregl.Marker | null = null
  let layersReady = false

  function ensureLayers (map: any) {
    if (layersReady) return true
    if (!map?.isStyleLoaded?.()) return false
    try {
      map.addSource(SRC, { type: 'geojson', data: { type: 'FeatureCollection', features: [] } })
      map.addLayer({ id: 'measure-line', type: 'line', source: SRC, layout: { 'line-cap': 'round', 'line-join': 'round' }, paint: { 'line-color': '#faad14', 'line-width': 3, 'line-dasharray': [2, 1] } })
      layersReady = true
    } catch (e) { return false }
    return true
  }
  function setLine (map: any) {
    const s = map.getSource(SRC)
    if (s?.setData) s.setData({ type: 'FeatureCollection', features: pts.length >= 2 ? [{ type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: pts } }] : [] })
  }
  function dotEl () {
    const d = document.createElement('div')
    d.style.cssText = 'width:8px;height:8px;border-radius:50%;background:#faad14;border:1.5px solid #fff;box-shadow:0 0 2px rgba(0,0,0,.5)'
    return d
  }
  function totalEl (text: string) {
    const d = document.createElement('div')
    d.textContent = text
    d.style.cssText = 'background:rgba(13,17,23,.9);color:#faad14;font-size:12px;padding:2px 8px;border-radius:10px;border:1px solid #2c3a4f;white-space:nowrap;transform:translateY(-14px)'
    return d
  }
  function totalDist () {
    let d = 0
    for (let i = 1; i < pts.length; i++) d += haversine(pts[i - 1], pts[i])
    return d
  }
  function refresh (map: any) {
    setLine(map)
    const last = pts[pts.length - 1]
    if (pts.length >= 2 && last) {
      const text = `共 ${fmt(totalDist())}`
      if (!totalLabel) totalLabel = new maplibregl.Marker({ element: totalEl(text), anchor: 'bottom' }).setLngLat(last).addTo(map)
      else { totalLabel.setLngLat(last); totalLabel.getElement().textContent = text }
    }
  }

  function onClick (e: any) {
    const map = getMap()
    if (!map || !ensureLayers(map)) return
    const ll: [number, number] = [e.lngLat.lng, e.lngLat.lat]
    pts.push(ll)
    dots.push(new maplibregl.Marker({ element: dotEl(), anchor: 'center' }).setLngLat(ll).addTo(map))
    refresh(map)
  }
  function onDblClick (e: any) {
    if (e?.preventDefault) e.preventDefault()
    stop()
  }

  function start () {
    const map = getMap()
    if (!map || active) return
    active = true
    clearGeometry(map)
    map.on('click', onClick)
    map.on('dblclick', onDblClick)
    map.doubleClickZoom?.disable?.()
    const c = map.getCanvas?.(); if (c) c.style.cursor = 'crosshair'
  }
  function clearGeometry (map: any) {
    pts = []
    dots.forEach(d => d.remove()); dots.length = 0
    if (totalLabel) { totalLabel.remove(); totalLabel = null }
    if (layersReady) setLine(map)
  }
  function stop () {
    const map = getMap()
    active = false
    if (map) {
      map.off('click', onClick)
      map.off('dblclick', onDblClick)
      map.doubleClickZoom?.enable?.()
      const c = map.getCanvas?.(); if (c) c.style.cursor = ''
      clearGeometry(map)
    }
  }

  return { start, stop, isActive: () => active }
}
