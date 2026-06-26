// 规划/飞行覆盖物渲染（MapLibre 版，自高德迁移）。
// 渲染坐标统一 WGS84（天地图原生）：航点用 wgsLng/wgsLat，测区顶点 gcj→wgs，飞机用 wgs。
// 状态层内部仍是 GCJ-02，点击/拖拽输入的 WGS84 在边界处转回 GCJ 回灌（addWaypointGcj 等不变）。
// 页签规则（设计 v2）：监测页签渲染 预览∥编辑航点与飞机位置；投放页签预览/手动布点。
import { computed, watch } from 'vue'
import maplibregl from 'maplibre-gl'
import {
  addAreaVertexGcj,
  addWaypointGcj,
  getPlanningStateRaw,
  insertWaypointAfterGcj,
  removeAreaVertex,
  removeWaypoint,
  selectWaypoint,
  updateAreaVertex,
  updateWaypointPositionGcj,
} from '/@/hooks/use-wayline-planning'
import type { PlannedWaypoint } from '/@/hooks/use-wayline-planning'
import { getPlannerUiRaw } from '/@/hooks/use-planner-ui'
import { WAYPOINT_ACTION_LABELS } from '/@/components/wayline-planner/wayline-format'
import { fc100PositionState } from '/@/hooks/use-fc100-position'
import { getFlightAreaComplianceRaw, flightAreaCompliance } from '/@/hooks/use-flight-area-compliance'
// @ts-ignore .mjs 共用模块（node 测试可直跑）
import { buildSimulationTimeline, haversineMeters, positionAtTime } from '/@/components/wayline-planner/planner-utils.mjs'
// @ts-ignore .mjs 纯计算模块
import { looksLikeAreaSweep, convexHull } from '/@/components/wayline-planner/area-utils.mjs'
import { gcj02towgs84, wgs84togcj02 } from '/@/vendors/coordtransform'

/** 低于该缩放级别时收起信息牌/距离标签，防止覆盖物拥挤 */
const LABEL_MIN_ZOOM = 15

type LngLat = [number, number]
const EMPTY_FC = { type: 'FeatureCollection' as const, features: [] as any[] }
function lineFeature (coords: LngLat[]) {
  return { type: 'FeatureCollection', features: coords.length >= 2 ? [{ type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: coords } }] : [] }
}
function polygonFeature (ring: LngLat[]) {
  if (ring.length < 3) return EMPTY_FC
  const closed = [...ring, ring[0]]
  return { type: 'FeatureCollection', features: [{ type: 'Feature', properties: {}, geometry: { type: 'Polygon', coordinates: [closed] } }] }
}
function multiLineFeature (segs: LngLat[][]) {
  return { type: 'FeatureCollection', features: segs.map(coords => ({ type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: coords } })) }
}
function closeRing (ring: LngLat[]): LngLat[] {
  if (ring.length < 3) return ring
  const a = ring[0]; const b = ring[ring.length - 1]
  return (a[0] === b[0] && a[1] === b[1]) ? ring : [...ring, a]
}

export function usePlannerOverlays (
  getMap: () => any,
  _getAMap: () => any,
  onPlanningActivated?: () => void,
) {
  const planningState = getPlanningStateRaw()
  const plannerUi = getPlannerUiRaw()
  const faState = getFlightAreaComplianceRaw()

  const renderPlanningWaypoints = computed(() =>
    planningState.previewWaypoints.length > 0 ? planningState.previewWaypoints : planningState.waypoints)

  const canEditWaypoints = computed(() =>
    !planningState.executing &&
    planningState.previewWaypoints.length === 0)

  // ---- 坐标助手：渲染一律 WGS84 ----
  const wpLngLat = (wp: PlannedWaypoint): LngLat => {
    const lng = Number(wp.wgsLng); const lat = Number(wp.wgsLat)
    if (Number.isFinite(lng) && Number.isFinite(lat)) return [lng, lat]
    return gcj02towgs84(wp.gcjLng, wp.gcjLat) as LngLat
  }
  const vertLngLat = (v: { gcjLng: number; gcjLat: number }): LngLat => gcj02towgs84(v.gcjLng, v.gcjLat) as LngLat
  const posLngLat = (p: any): LngLat => {
    const lng = Number(p.wgsLng); const lat = Number(p.wgsLat)
    if (Number.isFinite(lng) && Number.isFinite(lat)) return [lng, lat]
    return gcj02towgs84(p.gcjLng, p.gcjLat) as LngLat
  }
  const fc100LngLat = (): LngLat | null => {
    const props = fc100PositionState.selectedDeviceProps
    const lng = Number(props?.longitude)
    const lat = Number(props?.latitude)
    if (!Number.isFinite(lng) || !Number.isFinite(lat) || lng === 0 || lat === 0) return null
    return [lng, lat]
  }

  // ---- HTML marker 管理 ----
  const planningMarkers: maplibregl.Marker[] = []
  const areaMarkers: maplibregl.Marker[] = []
  const distanceMarkers: maplibregl.Marker[] = []
  const arrowMarkers: maplibregl.Marker[] = []
  let homeMarker: maplibregl.Marker | null = null
  let flightMarker: maplibregl.Marker | null = null
  let fc100Marker: maplibregl.Marker | null = null
  let simGhostMarker: maplibregl.Marker | null = null

  let labelsVisible = true
  let zoomListenerBound = false
  let layersReady = false
  let flightTrackAircraftSn = ''
  const flightTrackPath: LngLat[] = []
  let planningClickBound = false
  let pendingAircraftRecenter = false

  function elFromHtml (html: string): HTMLElement {
    const d = document.createElement('div')
    d.innerHTML = html.trim()
    return (d.firstElementChild as HTMLElement) || d
  }
  function makeMarker (html: string, lngLat: LngLat, opts: { anchor?: any; zIndex?: number; draggable?: boolean } = {}) {
    const el = elFromHtml(html)
    if (opts.zIndex != null) el.style.zIndex = String(opts.zIndex)
    const m = new maplibregl.Marker({ element: el, anchor: opts.anchor || 'center', draggable: !!opts.draggable })
    m.setLngLat(lngLat)
    const map = getMap()
    if (map) m.addTo(map)
    return m
  }
  function removeMarkers (arr: maplibregl.Marker[]) {
    arr.forEach(m => m.remove())
    arr.length = 0
  }

  // ---- GeoJSON 源/层（连线、多边形、轨迹）懒创建一次，之后 setData ----
  const SRC = { route: 'plan-route', home: 'plan-home', area: 'plan-area', track: 'plan-track', fa: 'plan-fa', faBad: 'plan-fa-bad' }
  const FA_COLOR = ['match', ['get', 'type'], 'nfz', '#ff4d4f', 'dfence', '#52c41a', 'warning', '#faad14', '#8a93a3'] as any
  function ensureLayers (map: any) {
    if (layersReady) return true
    if (!map || typeof map.isStyleLoaded !== 'function' || !map.isStyleLoaded()) return false
    try {
      // 飞行区放最底层：NFZ 红 / DFENCE 绿，盖在底图上、压在航线下。
      map.addSource(SRC.fa, { type: 'geojson', data: EMPTY_FC })
      map.addLayer({ id: 'plan-fa-fill', type: 'fill', source: SRC.fa, paint: { 'fill-color': FA_COLOR, 'fill-opacity': 0.14 } })
      map.addLayer({ id: 'plan-fa-line', type: 'line', source: SRC.fa, paint: { 'line-color': FA_COLOR, 'line-width': 1.5, 'line-opacity': 0.85, 'line-dasharray': [2, 1] } })
      map.addSource(SRC.area, { type: 'geojson', data: EMPTY_FC })
      map.addLayer({ id: 'plan-area-fill', type: 'fill', source: SRC.area, paint: { 'fill-color': '#1668dc', 'fill-opacity': 0.12 } })
      map.addLayer({ id: 'plan-area-line', type: 'line', source: SRC.area, paint: { 'line-color': '#1668dc', 'line-width': 2, 'line-opacity': 0.9 } })
      map.addSource(SRC.home, { type: 'geojson', data: EMPTY_FC })
      map.addLayer({ id: 'plan-home-line', type: 'line', source: SRC.home, paint: { 'line-color': '#8a93a3', 'line-width': 2, 'line-opacity': 0.9, 'line-dasharray': [2, 2] } })
      map.addSource(SRC.track, { type: 'geojson', data: EMPTY_FC })
      map.addLayer({ id: 'plan-track-line', type: 'line', source: SRC.track, layout: { 'line-cap': 'round', 'line-join': 'round' }, paint: { 'line-color': '#13c2c2', 'line-width': 4, 'line-opacity': 0.85 } })
      map.addSource(SRC.route, { type: 'geojson', data: EMPTY_FC })
      map.addLayer({ id: 'plan-route-line', type: 'line', source: SRC.route, layout: { 'line-cap': 'round', 'line-join': 'round' }, paint: { 'line-color': '#43d675', 'line-width': 6, 'line-opacity': 0.92 } })
      // 违规航段高亮（橙）：压在航线之上，最显眼。
      map.addSource(SRC.faBad, { type: 'geojson', data: EMPTY_FC })
      map.addLayer({ id: 'plan-fa-bad-line', type: 'line', source: SRC.faBad, layout: { 'line-cap': 'round', 'line-join': 'round' }, paint: { 'line-color': '#ff7a00', 'line-width': 8, 'line-opacity': 0.95 } })
      layersReady = true
    } catch (e) {
      return false
    }
    return true
  }
  function setData (map: any, src: string, data: any) {
    const s = map.getSource && map.getSource(src)
    if (s && typeof s.setData === 'function') s.setData(data)
  }

  function onPlanningMapClick (e: any) {
    if (!planningState.active) return
    const lng = e?.lngLat?.lng
    const lat = e?.lngLat?.lat
    if (!Number.isFinite(lng) || !Number.isFinite(lat)) return
    // 地图给 WGS84 → 转 GCJ 回灌状态层
    const [gLng, gLat] = wgs84togcj02(lng, lat) as LngLat
    if (planningState.routeKind === 'area') {
      addAreaVertexGcj(gLng, gLat)
    } else {
      addWaypointGcj(gLng, gLat)
    }
  }

  function clearMarkerOverlays () {
    removeMarkers(planningMarkers)
    removeMarkers(areaMarkers)
    removeMarkers(distanceMarkers)
    removeMarkers(arrowMarkers)
    if (homeMarker) { homeMarker.remove(); homeMarker = null }
  }

  function clearVectorOverlays (map: any) {
    if (!layersReady || !map) return
    setData(map, SRC.route, EMPTY_FC)
    setData(map, SRC.home, EMPTY_FC)
    setData(map, SRC.area, EMPTY_FC)
    setData(map, SRC.faBad, EMPTY_FC)
  }

  // 飞行区（NFZ/DFENCE）多边形：圆已在归一化时转环。常驻渲染，不随航点清除。
  function rebuildFlightAreaOverlay (map: any) {
    const features = faState.zones.map(z => ({
      type: 'Feature',
      properties: { type: z.type, name: z.name },
      geometry: { type: 'Polygon', coordinates: [closeRing(z.ring as LngLat[])] },
    }))
    setData(map, SRC.fa, { type: 'FeatureCollection', features })
  }

  function clearFlightPositionOverlay () {
    const map = getMap()
    if (flightMarker) { flightMarker.remove(); flightMarker = null }
    if (map && layersReady) setData(map, SRC.track, EMPTY_FC)
    flightTrackAircraftSn = ''
    flightTrackPath.length = 0
  }

  function clearFc100PositionOverlay () {
    if (fc100Marker) { fc100Marker.remove(); fc100Marker = null }
  }

  // S2 信息常显风格：序号圆点 + 信息牌
  function waypointMarkerContent (idx: number, wp: PlannedWaypoint, bad = false) {
    const isSelected = planningState.selectedWaypointId === wp.id
    const speed = wp.speed || planningState.maxSpeed
    const acts = (wp.actions || []).map(a => WAYPOINT_ACTION_LABELS[a.actuatorFunc] || a.actuatorFunc).join('·')
    const info = `${wp.height}m · ${speed}m/s${acts ? ' · ' + acts : ''}`
    const classes = ['planner-wp-marker', isSelected ? 'planner-wp-marker--selected' : '', labelsVisible ? '' : 'planner-wp-marker--mini'].filter(Boolean).join(' ')
    // 违规航点：圆点染红 + 橙环，不依赖外部 CSS，确保醒目。
    const dotStyle = bad ? ' style="background:#ff4d4f;box-shadow:0 0 0 2px #ff7a00"' : ''
    return `<div class="${classes}"><span class="planner-wp-dot"${dotStyle}>${idx + 1}</span><span class="planner-wp-card">${info}</span></div>`
  }

  function formatSegmentDistance (meters: number) {
    return meters >= 1000 ? `${(meters / 1000).toFixed(2)}km` : `${Math.round(meters)}m`
  }

  function rebuildInsertGhosts (waypoints: PlannedWaypoint[]) {
    for (let i = 1; i < waypoints.length; i++) {
      const a = waypoints[i - 1]; const b = waypoints[i]
      const mid: LngLat = [(wpLngLat(a)[0] + wpLngLat(b)[0]) / 2, (wpLngLat(a)[1] + wpLngLat(b)[1]) / 2]
      const m = makeMarker('<div class="planner-insert-ghost">+</div>', mid, { anchor: 'center', zIndex: 109 })
      const el = m.getElement()
      el.style.cursor = 'pointer'
      el.addEventListener('click', (ev) => { ev.stopPropagation(); insertWaypointAfterGcj(a.id, (a.gcjLng + b.gcjLng) / 2, (a.gcjLat + b.gcjLat) / 2) })
      planningMarkers.push(m)
    }
  }

  // 段方向角（正北顺时针，度）——用 WGS 坐标算
  function segmentBearing (a: LngLat, b: LngLat) {
    const rad = Math.PI / 180
    const dLng = (b[0] - a[0]) * Math.cos(((a[1] + b[1]) / 2) * rad)
    const dLat = b[1] - a[1]
    return (Math.atan2(dLng, dLat) * 180) / Math.PI
  }

  function rebuildDirectionArrows (coords: LngLat[]) {
    for (let i = 1; i < coords.length; i++) {
      const a = coords[i - 1]; const b = coords[i]
      const deg = segmentBearing(a, b)
      const mid: LngLat = [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2]
      const m = makeMarker(`<div class="planner-dir-arrow" style="transform: rotate(${deg}deg)"><svg width="12" height="12" viewBox="0 0 12 12"><path d="M6 1.5 L10 9.5 L6 7 L2 9.5 Z" fill="#eafff1"/></svg></div>`, mid, { anchor: 'center', zIndex: 106 })
      arrowMarkers.push(m)
    }
  }

  function rebuildDistanceLabels (waypoints: PlannedWaypoint[]) {
    if (!labelsVisible || waypoints.length < 2) return
    for (let i = 1; i < waypoints.length; i++) {
      const a = waypoints[i - 1]; const b = waypoints[i]
      const d = haversineMeters(a.gcjLng, a.gcjLat, b.gcjLng, b.gcjLat)
      const aw = wpLngLat(a); const bw = wpLngLat(b)
      const mid: LngLat = [(aw[0] + bw[0]) / 2, (aw[1] + bw[1]) / 2]
      const m = makeMarker(`<div class="planner-seg-label">${formatSegmentDistance(d)}</div>`, mid, { anchor: 'center', zIndex: 105 })
      distanceMarkers.push(m)
    }
  }

  // 起飞点 H：监测页签用飞机当前位置近似
  function rebuildHomeMarker (map: any, waypoints: PlannedWaypoint[]) {
    if (plannerUi.activeTab !== 'monitor') return
    const pos = planningState.flightPosition
    if (!pos || waypoints.length === 0) return
    const home = posLngLat(pos)
    homeMarker = makeMarker('<div class="planner-home-marker">H</div>', home, { anchor: 'center', zIndex: 108 })
    setData(map, SRC.home, lineFeature([home, wpLngLat(waypoints[0])]))
  }

  // 面状测区：多边形 + 可拖/右键删除的顶点
  function rebuildAreaOverlay (map: any) {
    if (planningState.routeKind !== 'area') return
    const verts = planningState.areaPolygon
    if (verts.length === 0) return
    const ring = verts.map(vertLngLat)
    setData(map, SRC.area, verts.length >= 3 ? polygonFeature(ring) : lineFeature(ring))
    const editable = !planningState.executing
    verts.forEach((v, idx) => {
      const m = makeMarker(`<div class="planner-area-vertex">${idx + 1}</div>`, vertLngLat(v), { anchor: 'center', zIndex: 70, draggable: editable })
      if (editable) {
        const el = m.getElement()
        el.style.cursor = 'move'
        el.addEventListener('contextmenu', (ev) => { ev.preventDefault(); removeAreaVertex(idx) })
        // 拖动过程：实时改多边形（不走 state，避免重建打断拖拽）
        m.on('drag', () => {
          const ll = m.getLngLat()
          const live = verts.map((p, i) => (i === idx ? [ll.lng, ll.lat] as LngLat : vertLngLat(p)))
          setData(map, SRC.area, verts.length >= 3 ? polygonFeature(live) : lineFeature(live))
        })
        // 落点：WGS→GCJ 提交
        m.on('dragend', () => {
          const ll = m.getLngLat()
          const [g0, g1] = wgs84togcj02(ll.lng, ll.lat) as LngLat
          updateAreaVertex(idx, g0, g1)
        })
      }
      areaMarkers.push(m)
    })
  }

  function bindZoomListener (map: any) {
    if (zoomListenerBound) return
    map.on('zoomend', () => {
      const zoom = Number(map.getZoom?.())
      const next = !Number.isFinite(zoom) || zoom >= LABEL_MIN_ZOOM
      if (next !== labelsVisible) {
        labelsVisible = next
        rebuildPlanningOverlays()
      }
    })
    zoomListenerBound = true
  }

  function rebuildPlanningOverlays () {
    const map = getMap()
    if (!map) return
    if (!ensureLayers(map)) return
    bindZoomListener(map)
    clearMarkerOverlays()
    clearVectorOverlays(map)
    rebuildFlightAreaOverlay(map)
    rebuildAreaOverlay(map)
    const waypoints = renderPlanningWaypoints.value
    if (waypoints.length === 0) { updateFlightPositionOverlay(); return }
    const editable = canEditWaypoints.value
    const renderingPreview = planningState.previewWaypoints.length > 0
    const isArea = renderingPreview ? looksLikeAreaSweep(waypoints) : planningState.routeKind === 'area'
    const compliance = flightAreaCompliance.value
    const badWp: number[] = compliance.badWaypoints

    // 面状预览：测区多边形未保存，用扫描端点凸包重建边界
    if (isArea && renderingPreview) {
      const hull = convexHull(waypoints)
      if (hull.length >= 3) setData(map, SRC.area, polygonFeature(hull.map((p: any) => gcj02towgs84(p.gcjLng, p.gcjLat) as LngLat)))
    }

    if (!isArea) {
      waypoints.forEach((wp, idx) => {
        const m = makeMarker(waypointMarkerContent(idx, wp, badWp.includes(idx)), wpLngLat(wp), {
          anchor: 'bottom',
          zIndex: planningState.selectedWaypointId === wp.id ? 130 : 110,
          draggable: editable,
        })
        const el = m.getElement()
        el.addEventListener('click', (ev) => { ev.stopPropagation(); selectWaypoint(wp.id) })
        if (editable) {
          el.addEventListener('contextmenu', (ev) => { ev.preventDefault(); removeWaypoint(wp.id) })
          // 拖动过程：实时改航线连线（不走 state，避免重建打断拖拽）。距离/箭头/H 在落点后整体刷新。
          m.on('drag', () => {
            const ll = m.getLngLat()
            const live = waypoints.map((p, i) => (i === idx ? [ll.lng, ll.lat] as LngLat : wpLngLat(p)))
            if (planningState.routeKind === 'patrol' && live.length >= 2) live.push(live[0])
            if (live.length >= 2) setData(map, SRC.route, lineFeature(live))
          })
          // 落点：WGS→GCJ 提交，触发 state 变更后整体重绘
          m.on('dragend', () => {
            const ll = m.getLngLat()
            const [g0, g1] = wgs84togcj02(ll.lng, ll.lat) as LngLat
            updateWaypointPositionGcj(wp.id, g0, g1)
          })
        }
        planningMarkers.push(m)
      })
      if (editable && labelsVisible) rebuildInsertGhosts(waypoints)
    }

    // 航线连线（巡逻闭合；面状细线）
    if (waypoints.length >= 2) {
      const coords = waypoints.map(wpLngLat)
      if (planningState.routeKind === 'patrol') coords.push(wpLngLat(waypoints[0]))
      setData(map, SRC.route, lineFeature(coords))
      map.setPaintProperty('plan-route-line', 'line-width', isArea ? 2 : 6)
      rebuildDirectionArrows(coords)
    }
    // 违规航段橙线高亮（穿越禁飞区的段）
    setData(map, SRC.faBad, compliance.badSegmentCoords.length ? multiLineFeature(compliance.badSegmentCoords as LngLat[][]) : EMPTY_FC)
    if (!isArea) rebuildDistanceLabels(waypoints)
    rebuildHomeMarker(map, waypoints)
    fitPlanningPreviewToMap()
    updateFlightPositionOverlay()
    updateFc100PositionOverlay()
  }

  function flightPositionContent (label: string) {
    const progress = label ? `<em>${label}</em>` : ''
    return `<div class="flight-position-marker" title="DJI Matrice 4T">${matrice4tPositionContent()}${progress}</div>`
  }

  function matrice4tPositionContent () {
    return `<svg class="m4t-airframe" width="34" height="34" viewBox="0 0 36 36" aria-hidden="true" focusable="false">
      <g class="m4t-props">
        <ellipse cx="7.4" cy="7.2" rx="5.7" ry="1.55" transform="rotate(-32 7.4 7.2)" />
        <ellipse cx="28.6" cy="7.2" rx="5.7" ry="1.55" transform="rotate(32 28.6 7.2)" />
        <ellipse cx="7.4" cy="28.8" rx="5.7" ry="1.55" transform="rotate(32 7.4 28.8)" />
        <ellipse cx="28.6" cy="28.8" rx="5.7" ry="1.55" transform="rotate(-32 28.6 28.8)" />
      </g>
      <g class="m4t-arms" stroke-linecap="round">
        <path d="M15.5 15.8 L8.9 9.2 M20.5 15.8 L27.1 9.2 M15.5 20.2 L8.9 26.8 M20.5 20.2 L27.1 26.8" />
        <path d="M14.6 18 H8.7 M21.4 18 H27.3" />
      </g>
      <g class="m4t-motors">
        <circle cx="7.4" cy="7.2" r="2.15" />
        <circle cx="28.6" cy="7.2" r="2.15" />
        <circle cx="7.4" cy="28.8" r="2.15" />
        <circle cx="28.6" cy="28.8" r="2.15" />
      </g>
      <g class="m4t-body">
        <path d="M18 8.5 L22.9 13.5 L23.8 21.2 L20.6 27.2 L15.4 27.2 L12.2 21.2 L13.1 13.5 Z" />
        <path d="M15.6 11.8 L20.4 11.8 L21.8 15.6 L14.2 15.6 Z" />
        <rect x="15.2" y="16.2" width="5.6" height="8.4" rx="1.8" />
        <circle cx="18" cy="25.3" r="1.35" />
      </g>
      <g class="m4t-accents">
        <path d="M4.2 6.2 L6.3 5.1 L5.2 7.3 Z" />
        <path d="M31.8 6.2 L29.7 5.1 L30.8 7.3 Z" />
        <path d="M4.2 29.8 L6.3 30.9 L5.2 28.7 Z" />
        <path d="M31.8 29.8 L29.7 30.9 L30.8 28.7 Z" />
      </g>
    </svg>`
  }

  function shouldRenderFlightPositionOverlay () {
    return plannerUi.activeTab === 'monitor'
  }

  function shouldRenderFc100PositionOverlay () {
    return plannerUi.activeTab === 'delivery' &&
      Boolean(fc100PositionState.selectedDeviceSn) &&
      Boolean(fc100LngLat())
  }

  function fc100PositionContent () {
    return `<div class="fc100-position-marker" title="DJI FlyCart 100">
      <svg class="fc100-airframe" width="30" height="30" viewBox="0 0 36 36" aria-hidden="true" focusable="false">
        <g class="fc100-arms" stroke-linecap="round">
          <path d="M17 17 L8.5 8.5 M19 17 L27.5 8.5 M17 19 L8.5 27.5 M19 19 L27.5 27.5" />
          <path d="M14 18 H8 M22 18 H28" />
        </g>
        <g class="fc100-props">
          <ellipse cx="7" cy="7" rx="5.8" ry="1.7" transform="rotate(-35 7 7)" />
          <ellipse cx="29" cy="7" rx="5.8" ry="1.7" transform="rotate(35 29 7)" />
          <ellipse cx="7" cy="29" rx="5.8" ry="1.7" transform="rotate(35 7 29)" />
          <ellipse cx="29" cy="29" rx="5.8" ry="1.7" transform="rotate(-35 29 29)" />
        </g>
        <g class="fc100-motors">
          <circle cx="7" cy="7" r="2.4" />
          <circle cx="29" cy="7" r="2.4" />
          <circle cx="7" cy="29" r="2.4" />
          <circle cx="29" cy="29" r="2.4" />
        </g>
        <g class="fc100-body">
          <rect x="11.8" y="14" width="4.2" height="8" rx="1" />
          <rect x="20" y="14" width="4.2" height="8" rx="1" />
          <path d="M18 10.8 L23.6 14.6 L23 22.1 L18 25.2 L13 22.1 L12.4 14.6 Z" />
          <circle cx="18" cy="21.3" r="2.1" />
        </g>
        <g class="fc100-accents">
          <circle cx="12" cy="12" r="0.9" />
          <circle cx="24" cy="12" r="0.9" />
          <circle cx="12" cy="24" r="0.9" />
          <circle cx="24" cy="24" r="0.9" />
        </g>
      </svg>
    </div>`
  }

  function setAircraftView (position = planningState.flightPosition) {
    const map = getMap()
    if (!map || !position) return
    const cur = typeof map.getZoom === 'function' ? Number(map.getZoom()) : 17
    const zoom = Number.isFinite(cur) ? Math.max(cur, 17) : 17
    map.easeTo({ center: posLngLat(position), zoom, duration: 500 })
  }

  function locateAircraftPosition () { setAircraftView() }

  function updateFlightPositionOverlay () {
    if (!shouldRenderFlightPositionOverlay()) { clearFlightPositionOverlay(); return }
    const map = getMap()
    const position = planningState.flightPosition
    if (!position) { clearFlightPositionOverlay(); return }
    if (!map || !ensureLayers(map)) return
    const lngLat = posLngLat(position)
    const last = flightTrackPath[flightTrackPath.length - 1]
    const jumpLng = last ? Math.abs(last[0] - lngLat[0]) : 0
    const jumpLat = last ? Math.abs(last[1] - lngLat[1]) : 0
    if (flightTrackAircraftSn !== position.aircraftSn || jumpLng > 0.003 || jumpLat > 0.003) {
      flightTrackPath.length = 0
      flightTrackAircraftSn = position.aircraftSn
      setData(map, SRC.track, EMPTY_FC)
    }
    const label = position.currentWaypointIndex != null && position.totalWaypoints != null
      ? `${position.currentWaypointIndex + 1}/${position.totalWaypoints}`
      : ''
    if (!flightMarker) {
      flightMarker = makeMarker(flightPositionContent(label), lngLat, { anchor: 'center', zIndex: 120 })
    } else {
      flightMarker.setLngLat(lngLat)
      flightMarker.getElement().innerHTML = flightPositionContent(label)
    }
    flightTrackPath.push(lngLat)
    if (flightTrackPath.length > 600) flightTrackPath.shift()
    setData(map, SRC.track, lineFeature(flightTrackPath))
    // H 随飞机移动
    if (homeMarker) {
      homeMarker.setLngLat(lngLat)
      const wps = renderPlanningWaypoints.value
      if (wps.length > 0) setData(map, SRC.home, lineFeature([lngLat, wpLngLat(wps[0] as PlannedWaypoint)]))
    } else if (plannerUi.activeTab === 'monitor' && renderPlanningWaypoints.value.length > 0) {
      rebuildHomeMarker(map, renderPlanningWaypoints.value as PlannedWaypoint[])
    }
    if (pendingAircraftRecenter) { pendingAircraftRecenter = false; setAircraftView(position) }
  }

  function updateFc100PositionOverlay () {
    if (!shouldRenderFc100PositionOverlay()) { clearFc100PositionOverlay(); return }
    const map = getMap()
    const lngLat = fc100LngLat()
    if (!lngLat || !map || !ensureLayers(map)) return
    if (!fc100Marker) {
      fc100Marker = makeMarker(fc100PositionContent(), lngLat, { anchor: 'center', zIndex: 121 })
    } else {
      fc100Marker.setLngLat(lngLat)
      fc100Marker.getElement().innerHTML = fc100PositionContent()
    }
  }

  function fitPlanningPreviewToMap () {
    const map = getMap()
    if (!map || planningState.waypoints.length > 0 || planningState.previewWaypoints.length === 0) return
    const pts = planningState.previewWaypoints.map(wpLngLat)
    if (pts.length === 0) return
    const b = new maplibregl.LngLatBounds(pts[0], pts[0])
    pts.forEach(p => b.extend(p))
    map.fitBounds(b, { padding: 80, maxZoom: 17, duration: 0 })
  }

  function bindPlanningClick () {
    const map = getMap()
    if (!map || planningClickBound) return
    map.on('click', onPlanningMapClick)
    planningClickBound = true
  }
  function unbindPlanningClick () {
    const map = getMap()
    if (!map || !planningClickBound) return
    map.off('click', onPlanningMapClick)
    planningClickBound = false
  }

  watch(() => planningState.active, (active) => {
    if (active) { onPlanningActivated?.(); bindPlanningClick() } else { unbindPlanningClick() }
  })

  watch(
    () => `${plannerUi.activeTab}|${canEditWaypoints.value}|${planningState.selectedWaypointId}|${planningState.routeKind}|${planningState.areaPolygon.map(v => `${v.gcjLng},${v.gcjLat}`).join('|')}|${renderPlanningWaypoints.value.map(w => `${w.id}:${w.gcjLng}:${w.gcjLat}:${w.height}:${w.speed ?? ''}:${w.actions?.length ?? 0}`).join('|')}`,
    () => rebuildPlanningOverlays()
  )

  // 飞行区数据到位（异步加载晚于航点）后重绘飞行区图层与违规高亮。
  watch(() => `${faState.loaded}:${faState.zones.length}`, () => rebuildPlanningOverlays())

  watch(
    () => planningState.flightPosition
      ? `${planningState.flightPosition.aircraftSn}:${planningState.flightPosition.gcjLng}:${planningState.flightPosition.gcjLat}:${planningState.flightPosition.currentWaypointIndex}:${planningState.flightPosition.updatedAt}`
      : '',
    () => { updateFlightPositionOverlay(); updateFc100PositionOverlay() }
  )

  watch(
    () => `${fc100PositionState.selectedDeviceSn}:${fc100PositionState.selectedDeviceProps?.longitude}:${fc100PositionState.selectedDeviceProps?.latitude}:${fc100PositionState.selectedDeviceProps?.osdTimestamp}`,
    () => updateFc100PositionOverlay()
  )

  // ---------- 模拟预演幻影飞机 ----------
  function clearSimGhost () {
    if (simGhostMarker) { simGhostMarker.remove(); simGhostMarker = null }
  }
  function updateSimGhost () {
    const map = getMap()
    if (!map) return
    if (!plannerUi.simulating) { clearSimGhost(); return }
    const timeline = buildSimulationTimeline(planningState.waypoints, planningState.maxSpeed)
    const pos = positionAtTime(timeline, plannerUi.simulationTimeS)
    if (!pos) { clearSimGhost(); return }
    const ll = gcj02towgs84(pos.gcjLng, pos.gcjLat) as LngLat
    const content = `<div class="planner-sim-ghost"><span>✈️</span><em>${Math.round(pos.height)}m</em></div>`
    if (!simGhostMarker) {
      simGhostMarker = makeMarker(content, ll, { anchor: 'center', zIndex: 125 })
    } else {
      simGhostMarker.setLngLat(ll)
      simGhostMarker.getElement().innerHTML = content
    }
  }
  watch(() => `${plannerUi.simulating}|${plannerUi.simulationTimeS}`, () => updateSimGhost())

  watch(() => planningState.recenterAircraftToken, () => {
    if (planningState.flightPosition) setAircraftView(planningState.flightPosition)
    else pendingAircraftRecenter = true
  })

  function initPlannerOverlays () {
    rebuildPlanningOverlays()
    if (planningState.active) bindPlanningClick()
  }

  function disposePlannerOverlays () {
    unbindPlanningClick()
    clearMarkerOverlays()
    const map = getMap()
    if (map) { clearVectorOverlays(map); if (layersReady) setData(map, SRC.fa, EMPTY_FC) }
    clearFlightPositionOverlay()
    clearFc100PositionOverlay()
    clearSimGhost()
  }

  return {
    setAircraftView,
    locateAircraftPosition,
    initPlannerOverlays,
    disposePlannerOverlays,
  }
}
