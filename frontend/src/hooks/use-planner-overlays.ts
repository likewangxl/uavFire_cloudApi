// 规划/飞行覆盖物渲染（自 GMap.vue 行为等价抽出）。
// 页签规则（设计 v2）：监测页签渲染 预览∥编辑航点；投放页签只渲染预览（FC100 选择航线
// 时通过 previewPlannedWayline 写入 previewWaypoints）；飞机位置/轨迹跨页签常显。
import { computed, watch } from 'vue'
import {
  addWaypointGcj,
  getPlanningStateRaw,
  selectWaypoint,
} from '/@/hooks/use-wayline-planning'
import { getPlannerUiRaw } from '/@/hooks/use-planner-ui'

export function usePlannerOverlays (
  getMap: () => any,
  getAMap: () => any,
  onPlanningActivated?: () => void,
) {
  const planningState = getPlanningStateRaw()
  const plannerUi = getPlannerUiRaw()

  const renderPlanningWaypoints = computed(() => {
    if (plannerUi.activeTab === 'delivery') {
      return planningState.previewWaypoints
    }
    return planningState.previewWaypoints.length > 0 ? planningState.previewWaypoints : planningState.waypoints
  })

  const planningMarkers: any[] = []
  let planningPolyline: any = null
  let flightPositionMarker: any = null
  let flightTrackPolyline: any = null
  let flightTrackAircraftSn = ''
  const flightTrackPath: any[] = []
  let planningClickBound = false
  // 进入页面请求居中飞机时，如果此刻还没有飞机位置，先挂起，等位置到达后居中一次。
  let pendingAircraftRecenter = false

  function onPlanningMapClick (e: any) {
    if (!planningState.active) return
    const lng = e?.lnglat?.getLng?.()
    const lat = e?.lnglat?.getLat?.()
    if (!Number.isFinite(lng) || !Number.isFinite(lat)) return
    addWaypointGcj(lng, lat)
  }

  function clearPlanningOverlays () {
    const map = getMap()
    planningMarkers.forEach(m => map?.remove(m))
    planningMarkers.length = 0
    if (planningPolyline) {
      map?.remove(planningPolyline)
      planningPolyline = null
    }
  }

  function clearFlightPositionOverlay () {
    const map = getMap()
    if (flightPositionMarker) {
      map?.remove(flightPositionMarker)
      flightPositionMarker = null
    }
    if (flightTrackPolyline) {
      map?.remove(flightTrackPolyline)
      flightTrackPolyline = null
    }
    flightTrackAircraftSn = ''
    flightTrackPath.length = 0
  }

  function waypointMarkerContent (idx: number, id: string) {
    const isStart = idx === 0
    const isSelected = planningState.selectedWaypointId === id
    const classes = [
      'wayline-planning-marker',
      isStart ? 'wayline-planning-marker--start' : '',
      isSelected ? 'wayline-planning-marker--selected' : '',
    ].filter(Boolean).join(' ')
    return `<div class="${classes}"><span>${idx + 1}</span></div>`
  }

  function rebuildPlanningOverlays () {
    const AMap = getAMap()
    const map = getMap()
    if (!AMap || !map) return
    clearPlanningOverlays()
    const waypoints = renderPlanningWaypoints.value
    if (waypoints.length === 0) return
    waypoints.forEach((wp, idx) => {
      const marker = new AMap.Marker({
        position: [wp.gcjLng, wp.gcjLat],
        content: waypointMarkerContent(idx, wp.id),
        anchor: 'bottom-center',
        zIndex: planningState.selectedWaypointId === wp.id ? 130 : 110,
        extData: { waylinePlanningId: wp.id },
      })
      marker.on('click', () => selectWaypoint(wp.id))
      map.add(marker)
      planningMarkers.push(marker)
    })
    if (waypoints.length >= 2) {
      planningPolyline = new AMap.Polyline({
        path: waypoints.map(w => [w.gcjLng, w.gcjLat]),
        strokeColor: '#00e5ff',
        strokeOpacity: 0.92,
        strokeWeight: 4,
        strokeStyle: 'solid',
        showDir: true,
      })
      map.add(planningPolyline)
    }
    fitPlanningPreviewToMap()
    updateFlightPositionOverlay()
  }

  function flightPositionContent (label: string) {
    const progress = label ? `<em>${label}</em>` : ''
    return `<div class="flight-position-marker"><span>✈️</span>${progress}</div>`
  }

  function setAircraftView (position = planningState.flightPosition) {
    const map = getMap()
    if (!map || !position) return
    const currentZoom = typeof map.getZoom === 'function' ? Number(map.getZoom()) : 17
    const zoom = Number.isFinite(currentZoom) ? Math.max(currentZoom, 17) : 17
    map.setZoomAndCenter(zoom, [position.gcjLng, position.gcjLat])
  }

  function locateAircraftPosition () {
    setAircraftView()
  }

  function updateFlightPositionOverlay () {
    const AMap = getAMap()
    const map = getMap()
    const position = planningState.flightPosition
    if (!position) {
      clearFlightPositionOverlay()
      return
    }
    if (!AMap || !map) return
    const lngLat = [position.gcjLng, position.gcjLat]
    const lastTrackPoint = flightTrackPath[flightTrackPath.length - 1]
    const jumpLng = lastTrackPoint ? Math.abs(Number(lastTrackPoint[0]) - position.gcjLng) : 0
    const jumpLat = lastTrackPoint ? Math.abs(Number(lastTrackPoint[1]) - position.gcjLat) : 0
    if (flightTrackAircraftSn !== position.aircraftSn || jumpLng > 0.003 || jumpLat > 0.003) {
      flightTrackPath.length = 0
      flightTrackAircraftSn = position.aircraftSn
      if (flightTrackPolyline) {
        map.remove(flightTrackPolyline)
        flightTrackPolyline = null
      }
    }
    const label = position.currentWaypointIndex != null && position.totalWaypoints != null
      ? `${position.currentWaypointIndex + 1}/${position.totalWaypoints}`
      : ''
    if (!flightPositionMarker) {
      flightPositionMarker = new AMap.Marker({
        position: lngLat,
        content: flightPositionContent(label),
        anchor: 'center',
        zIndex: 120,
      })
      map.add(flightPositionMarker)
    } else {
      flightPositionMarker.setPosition(lngLat)
      flightPositionMarker.setContent(flightPositionContent(label))
    }
    flightTrackPath.push(lngLat)
    if (flightTrackPath.length > 600) flightTrackPath.shift()
    if (!flightTrackPolyline) {
      flightTrackPolyline = new AMap.Polyline({
        path: flightTrackPath,
        strokeColor: '#13c2c2',
        strokeWeight: 4,
        strokeOpacity: 0.85,
      })
      map.add(flightTrackPolyline)
    } else {
      flightTrackPolyline.setPath(flightTrackPath)
    }
    // 进入页面时请求过居中、但当时还没有飞机位置：现在位置到了，居中一次。
    if (pendingAircraftRecenter) {
      pendingAircraftRecenter = false
      setAircraftView(position)
    }
  }

  function fitPlanningPreviewToMap () {
    const map = getMap()
    if (!map || planningState.waypoints.length > 0 || planningState.previewWaypoints.length === 0) return
    const overlays = planningPolyline ? [...planningMarkers, planningPolyline] : [...planningMarkers]
    if (overlays.length === 0 || typeof map.setFitView !== 'function') return
    map.setFitView(overlays, false, [80, 80, 80, 80], 17)
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
    if (active) {
      // 原生画图工具也会占用地图点击；激活布点时先关掉，避免两种模式抢输入。
      onPlanningActivated?.()
      bindPlanningClick()
    } else {
      unbindPlanningClick()
    }
  })

  watch(
    () => `${plannerUi.activeTab}|${planningState.selectedWaypointId}|${renderPlanningWaypoints.value.map(w => `${w.id}:${w.gcjLng}:${w.gcjLat}:${w.height}`).join('|')}`,
    () => rebuildPlanningOverlays()
  )

  watch(
    () => planningState.flightPosition
      ? `${planningState.flightPosition.aircraftSn}:${planningState.flightPosition.gcjLng}:${planningState.flightPosition.gcjLat}:${planningState.flightPosition.currentWaypointIndex}:${planningState.flightPosition.updatedAt}`
      : '',
    () => updateFlightPositionOverlay()
  )

  // 进入航线页面时 wayline.vue 会自增 recenterAircraftToken：
  // 若此刻已有飞机位置则立即居中，否则挂起，等位置到达后由 updateFlightPositionOverlay 居中一次。
  watch(() => planningState.recenterAircraftToken, () => {
    if (planningState.flightPosition) {
      setAircraftView(planningState.flightPosition)
    } else {
      pendingAircraftRecenter = true
    }
  })

  /** 地图就绪后由宿主调用：首绘覆盖物，并在布点模式已激活时恢复点击监听。 */
  function initPlannerOverlays () {
    rebuildPlanningOverlays()
    if (planningState.active) {
      bindPlanningClick()
    }
  }

  function disposePlannerOverlays () {
    unbindPlanningClick()
    clearPlanningOverlays()
    clearFlightPositionOverlay()
  }

  return {
    setAircraftView,
    locateAircraftPosition,
    initPlannerOverlays,
    disposePlannerOverlays,
  }
}
