// 地图元素(pin/线/面)与飞行区(圆/多边形)覆盖物渲染（MapLibre 版，自高德迁移）。
// 坐标 WGS84（天地图原生）。公开 API 与高德版一致，供 GMap.vue / use-flight-area 复用。
import maplibregl from 'maplibre-gl'
import { EFlightAreaType } from '../types/flight-area'
import pin19be6b from '/@/assets/icons/pin-19be6b.svg'
import pin212121 from '/@/assets/icons/pin-212121.svg'
import pin2d8cf0 from '/@/assets/icons/pin-2d8cf0.svg'
import pinb620e0 from '/@/assets/icons/pin-b620e0.svg'
import pine23c39 from '/@/assets/icons/pin-e23c39.svg'
import pineffbb00 from '/@/assets/icons/pin-ffbb00.svg'
import { getRoot } from '/@/root'

const normalColor = '#2D8CF0'
const disableColor = '#b3b3b3'
const flightAreaColorMap: Record<string, string> = {
  [EFlightAreaType.DFENCE]: '#19be6b',
  [EFlightAreaType.NFZ]: '#ff0000',
}
const PIN_ICONS: Record<string, string> = {
  '2d8cf0': pin2d8cf0, '19be6b': pin19be6b, 212121: pin212121, b620e0: pinb620e0, e23c39: pine23c39, ffbb00: pineffbb00,
}

type LngLat = [number, number]
// 每个 id 的覆盖记录：矢量要素存进对应 FeatureCollection；pin/文字用 HTML marker。
interface CoverRecord { kind: 'pin' | 'line' | 'polygon' | 'fa-circle' | 'fa-polygon'; marker?: maplibregl.Marker; textMarker?: maplibregl.Marker; name?: string }

// 进程级单源：所有 cover 矢量要素汇总到一个源，按 properties 着色。
const records = new Map<string, CoverRecord>()
const lineFeatures = new Map<string, any>() // id -> LineString feature（含飞行区多边形描边走 polygon 源）
const polyFeatures = new Map<string, any>() // id -> Polygon feature
let layersInited = false

function circleRing (center: LngLat, radiusM: number, steps = 64): LngLat[] {
  const [lng, lat] = center
  const dLat = radiusM / 111320
  const dLng = radiusM / (111320 * Math.cos((lat * Math.PI) / 180))
  const ring: LngLat[] = []
  for (let i = 0; i <= steps; i++) {
    const t = (i / steps) * 2 * Math.PI
    ring.push([lng + dLng * Math.cos(t), lat + dLat * Math.sin(t)])
  }
  return ring
}

export function useGMapCover () {
  const root = getRoot()

  function getMap (): any { return root?.$map }

  function ensureLayers () {
    const map = getMap()
    if (!map || layersInited) return layersInited
    if (typeof map.isStyleLoaded !== 'function' || !map.isStyleLoaded()) return false
    try {
      map.addSource('cover-poly', { type: 'geojson', data: { type: 'FeatureCollection', features: [] } })
      map.addLayer({ id: 'cover-poly-fill', type: 'fill', source: 'cover-poly', paint: { 'fill-color': ['get', 'color'], 'fill-opacity': ['coalesce', ['get', 'fillOpacity'], 0.3] } })
      map.addLayer({ id: 'cover-poly-line', type: 'line', source: 'cover-poly', paint: { 'line-color': ['get', 'color'], 'line-width': ['coalesce', ['get', 'lineWidth'], 2] } })
      map.addSource('cover-line', { type: 'geojson', data: { type: 'FeatureCollection', features: [] } })
      map.addLayer({ id: 'cover-line-line', type: 'line', source: 'cover-line', layout: { 'line-cap': 'round', 'line-join': 'round' }, paint: { 'line-color': ['get', 'color'], 'line-width': 2 } })
      layersInited = true
    } catch (e) {
      return false
    }
    return true
  }

  function flush () {
    const map = getMap()
    if (!ensureLayers()) return
    const polySrc = map.getSource('cover-poly')
    const lineSrc = map.getSource('cover-line')
    if (polySrc?.setData) polySrc.setData({ type: 'FeatureCollection', features: [...polyFeatures.values()] })
    if (lineSrc?.setData) lineSrc.setData({ type: 'FeatureCollection', features: [...lineFeatures.values()] })
  }

  function pinIconUrl (color?: string) {
    const key = (color?.replaceAll('#', '') || '').toLocaleLowerCase()
    return PIN_ICONS[key] || pin2d8cf0
  }

  function makeHtmlMarker (el: HTMLElement, lngLat: LngLat, anchor: any = 'bottom') {
    const map = getMap()
    const m = new maplibregl.Marker({ element: el, anchor })
    m.setLngLat(lngLat)
    if (map) m.addTo(map)
    return m
  }

  function init2DPin (name: string, coordinates: LngLat, color?: string, data?: any) {
    const id = data?.id
    if (!id) return
    const img = document.createElement('img')
    img.src = pinIconUrl(color)
    img.style.width = '28px'
    img.style.display = 'block'
    img.title = name
    const m = makeHtmlMarker(img, coordinates, 'bottom')
    records.set(id, { kind: 'pin', marker: m, name })
  }

  function initPolyline (name: string, coordinates: LngLat[], color?: string, data?: any) {
    const id = data?.id
    if (!id) return
    lineFeatures.set(id, { type: 'Feature', properties: { id, color: color || normalColor }, geometry: { type: 'LineString', coordinates } })
    records.set(id, { kind: 'line', name })
    flush()
  }

  function initPolygon (name: string, coordinates: LngLat[][], color?: string, data?: any) {
    const id = data?.id
    if (!id) return
    const ring = coordinates[0]
    polyFeatures.set(id, { type: 'Feature', properties: { id, color: color || normalColor, fillOpacity: 0.4, lineWidth: 2 }, geometry: { type: 'Polygon', coordinates: [ring] } })
    records.set(id, { kind: 'polygon', name })
    flush()
  }

  function removeCoverFromMap (id: string) {
    const rec = records.get(id)
    if (rec?.marker) rec.marker.remove()
    if (rec?.textMarker) rec.textMarker.remove()
    records.delete(id)
    lineFeatures.delete(id)
    polyFeatures.delete(id)
    flush()
  }

  function getElementFromMap (id: string): any[] {
    const rec = records.get(id)
    return rec ? [rec] : []
  }

  function updatePinElement (id: string, name: string, coordinates: LngLat, color?: string) {
    const rec = records.get(id)
    if (rec?.marker) {
      rec.marker.setLngLat(coordinates)
      const img = rec.marker.getElement() as HTMLImageElement
      if (img && img.tagName === 'IMG') { img.src = pinIconUrl(color); img.title = name }
    } else {
      init2DPin(name, coordinates, color, { id, name })
    }
  }

  function updatePolylineElement (id: string, name: string, coordinates: LngLat[], color?: string) {
    if (lineFeatures.has(id)) {
      const f = lineFeatures.get(id)
      f.properties.color = color || normalColor
      f.geometry.coordinates = coordinates
      flush()
    } else {
      initPolyline(name, coordinates, color, { id, name })
    }
  }

  function updatePolygonElement (id: string, name: string, coordinates: LngLat[][], color?: string) {
    if (polyFeatures.has(id)) {
      const f = polyFeatures.get(id)
      f.properties.color = color || normalColor
      f.geometry.coordinates = [coordinates[0]]
      flush()
    } else {
      initPolygon(name, coordinates, color, { id, name })
    }
  }

  // ---- 文字标注（飞行区名）----
  function initTextInfo (content: string, coordinates: LngLat, id: string) {
    const el = document.createElement('div')
    el.className = 'cover-text-label'
    el.textContent = content
    el.style.cssText = 'font-size:14px;color:#fff;text-shadow:0 0 3px rgba(0,0,0,.7);white-space:nowrap;'
    const m = makeHtmlMarker(el, coordinates, 'top')
    const rec = records.get(id)
    if (rec) rec.textMarker = m
  }

  // ---- 飞行区 ----
  function faStyle (type: EFlightAreaType, enable: boolean) {
    return {
      color: enable ? flightAreaColorMap[type] : disableColor,
      fillOpacity: EFlightAreaType.NFZ === type && enable ? 0.3 : 0,
      lineWidth: 4,
    }
  }

  function initFlightAreaCircle (name: string, radius: number, position: LngLat, data: { id: string, type: EFlightAreaType, enable: boolean }) {
    const s = faStyle(data.type, data.enable)
    polyFeatures.set(data.id, { type: 'Feature', properties: { id: data.id, ...s }, geometry: { type: 'Polygon', coordinates: [circleRing(position, radius)] } })
    records.set(data.id, { kind: 'fa-circle', name })
    initTextInfo(name, position, data.id)
    flush()
  }

  function updateFlightAreaCircle (id: string, name: string, radius: number, position: LngLat, enable: boolean, type: EFlightAreaType) {
    const rec = records.get(id)
    if (polyFeatures.has(id)) {
      const f = polyFeatures.get(id)
      Object.assign(f.properties, faStyle(type, enable))
      f.geometry.coordinates = [circleRing(position, radius)]
      if (rec?.textMarker) { rec.textMarker.setLngLat(position); rec.textMarker.getElement().textContent = name }
      flush()
    } else {
      initFlightAreaCircle(name, radius, position, { id, type, enable })
    }
  }

  function calcPolygonPosition (coordinate: LngLat[]): LngLat {
    const index = coordinate.length - 1
    return [(coordinate[0][0] + coordinate[index][0]) / 2.0, (coordinate[0][1] + coordinate[index][1]) / 2]
  }

  function initFlightAreaPolygon (name: string, coordinates: LngLat[], data: { id: string, type: EFlightAreaType, enable: boolean }) {
    const s = faStyle(data.type, data.enable)
    polyFeatures.set(data.id, { type: 'Feature', properties: { id: data.id, ...s }, geometry: { type: 'Polygon', coordinates: [coordinates] } })
    records.set(data.id, { kind: 'fa-polygon', name })
    initTextInfo(name, calcPolygonPosition(coordinates), data.id)
    flush()
  }

  function updateFlightAreaPolygon (id: string, name: string, coordinates: LngLat[], enable: boolean, type: EFlightAreaType) {
    const rec = records.get(id)
    if (polyFeatures.has(id)) {
      const f = polyFeatures.get(id)
      Object.assign(f.properties, faStyle(type, enable))
      f.geometry.coordinates = [coordinates]
      if (rec?.textMarker) { rec.textMarker.setLngLat(calcPolygonPosition(coordinates)); rec.textMarker.getElement().textContent = name }
      flush()
    } else {
      initFlightAreaPolygon(name, coordinates, { id, type, enable })
    }
  }

  return {
    init2DPin,
    initPolyline,
    initPolygon,
    removeCoverFromMap,
    getElementFromMap,
    updatePinElement,
    updatePolylineElement,
    updatePolygonElement,
    initFlightAreaCircle,
    initFlightAreaPolygon,
    updateFlightAreaPolygon,
    updateFlightAreaCircle,
    calcPolygonPosition,
  }
}
