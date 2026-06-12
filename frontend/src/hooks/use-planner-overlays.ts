// 规划/飞行覆盖物渲染（自 GMap.vue 行为等价抽出）。
// 页签规则（设计 v2）：监测页签渲染 预览∥编辑航点；投放页签只渲染预览（FC100 选择航线
// 时通过 previewPlannedWayline 写入 previewWaypoints）；飞机位置/轨迹跨页签常显。
import { computed, watch } from 'vue'
import {
  addWaypointGcj,
  getPlanningStateRaw,
  insertWaypointAfterGcj,
  removeWaypoint,
  selectWaypoint,
  updateWaypointPositionGcj,
} from '/@/hooks/use-wayline-planning'
import type { PlannedWaypoint } from '/@/hooks/use-wayline-planning'
import { getPlannerUiRaw } from '/@/hooks/use-planner-ui'
import { WAYPOINT_ACTION_LABELS } from '/@/components/wayline-planner/wayline-format'
// @ts-ignore .mjs 共用模块（node 测试可直跑）
import { buildSimulationTimeline, haversineMeters, positionAtTime } from '/@/components/wayline-planner/planner-utils.mjs'

/** 低于该缩放级别时收起信息牌/距离标签，防止覆盖物拥挤 */
const LABEL_MIN_ZOOM = 15

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

  // 仅监测页签、非执行中、且显示的是编辑航点（非预览）时允许地图直接编辑
  const canEditWaypoints = computed(() =>
    plannerUi.activeTab === 'monitor' &&
    !planningState.executing &&
    planningState.previewWaypoints.length === 0)

  const planningMarkers: any[] = []
  let planningPolyline: any = null
  const distanceLabels: any[] = []
  let homeMarker: any = null
  let homeGuideLine: any = null
  let labelsVisible = true
  let zoomListenerBound = false
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
    distanceLabels.forEach(m => map?.remove(m))
    distanceLabels.length = 0
    if (planningPolyline) {
      map?.remove(planningPolyline)
      planningPolyline = null
    }
    if (homeMarker) {
      map?.remove(homeMarker)
      homeMarker = null
    }
    if (homeGuideLine) {
      map?.remove(homeGuideLine)
      homeGuideLine = null
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

  // S2 信息常显风格：序号圆点 + 常驻信息牌（高度·速度·动作摘要），低缩放收起信息牌
  function waypointMarkerContent (idx: number, wp: PlannedWaypoint) {
    const isSelected = planningState.selectedWaypointId === wp.id
    const speed = wp.speed || planningState.maxSpeed
    const acts = (wp.actions || [])
      .map(a => WAYPOINT_ACTION_LABELS[a.actuatorFunc] || a.actuatorFunc)
      .join('·')
    const info = `${wp.height}m · ${speed}m/s${acts ? ' · ' + acts : ''}`
    const classes = [
      'planner-wp-marker',
      isSelected ? 'planner-wp-marker--selected' : '',
      labelsVisible ? '' : 'planner-wp-marker--mini',
    ].filter(Boolean).join(' ')
    return `<div class="${classes}"><span class="planner-wp-dot">${idx + 1}</span><span class="planner-wp-card">${info}</span></div>`
  }

  function formatSegmentDistance (meters: number) {
    return meters >= 1000 ? `${(meters / 1000).toFixed(2)}km` : `${Math.round(meters)}m`
  }

  // 相邻航点中点的 "+" 幽灵插点
  function rebuildInsertGhosts (AMap: any, map: any, waypoints: PlannedWaypoint[]) {
    for (let i = 1; i < waypoints.length; i++) {
      const a = waypoints[i - 1]
      const b = waypoints[i]
      const midLng = (a.gcjLng + b.gcjLng) / 2
      const midLat = (a.gcjLat + b.gcjLat) / 2
      const ghost = new AMap.Marker({
        position: [midLng, midLat],
        content: '<div class="planner-insert-ghost">+</div>',
        anchor: 'center',
        zIndex: 109,
      })
      ghost.on('click', () => insertWaypointAfterGcj(a.id, midLng, midLat))
      map.add(ghost)
      planningMarkers.push(ghost)
    }
  }

  function rebuildDistanceLabels (AMap: any, map: any, waypoints: PlannedWaypoint[]) {
    if (!labelsVisible || waypoints.length < 2) return
    for (let i = 1; i < waypoints.length; i++) {
      const a = waypoints[i - 1]
      const b = waypoints[i]
      const d = haversineMeters(a.gcjLng, a.gcjLat, b.gcjLng, b.gcjLat)
      const label = new AMap.Marker({
        position: [(a.gcjLng + b.gcjLng) / 2, (a.gcjLat + b.gcjLat) / 2],
        content: `<div class="planner-seg-label">${formatSegmentDistance(d)}</div>`,
        anchor: 'center',
        zIndex: 105,
      })
      map.add(label)
      distanceLabels.push(label)
    }
  }

  // 起飞点 H（设计 v2 分模式）：监测页签用飞机当前位置近似；无位置不画
  function rebuildHomeMarker (AMap: any, map: any, waypoints: PlannedWaypoint[]) {
    if (plannerUi.activeTab !== 'monitor') return
    const pos = planningState.flightPosition
    if (!pos || waypoints.length === 0) return
    homeMarker = new AMap.Marker({
      position: [pos.gcjLng, pos.gcjLat],
      content: '<div class="planner-home-marker">H</div>',
      anchor: 'center',
      zIndex: 108,
    })
    homeGuideLine = new AMap.Polyline({
      path: [[pos.gcjLng, pos.gcjLat], [waypoints[0].gcjLng, waypoints[0].gcjLat]],
      strokeColor: '#8a93a3',
      strokeWeight: 2,
      strokeOpacity: 0.9,
      strokeStyle: 'dashed',
    })
    map.add([homeMarker, homeGuideLine])
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
    const AMap = getAMap()
    const map = getMap()
    if (!AMap || !map) return
    bindZoomListener(map)
    clearPlanningOverlays()
    const waypoints = renderPlanningWaypoints.value
    if (waypoints.length === 0) return
    const editable = canEditWaypoints.value
    waypoints.forEach((wp, idx) => {
      const marker = new AMap.Marker({
        position: [wp.gcjLng, wp.gcjLat],
        content: waypointMarkerContent(idx, wp),
        anchor: 'bottom-center',
        zIndex: planningState.selectedWaypointId === wp.id ? 130 : 110,
        draggable: editable,
        extData: { waylinePlanningId: wp.id },
      })
      marker.on('click', () => selectWaypoint(wp.id))
      if (editable) {
        marker.on('dragend', (e: any) => {
          const lng = e?.lnglat?.getLng?.()
          const lat = e?.lnglat?.getLat?.()
          if (Number.isFinite(lng) && Number.isFinite(lat)) {
            updateWaypointPositionGcj(wp.id, lng, lat)
          }
        })
        marker.on('rightclick', () => removeWaypoint(wp.id))
      }
      map.add(marker)
      planningMarkers.push(marker)
    })
    if (editable && labelsVisible) {
      rebuildInsertGhosts(AMap, map, waypoints)
    }
    if (waypoints.length >= 2) {
      planningPolyline = new AMap.Polyline({
        path: waypoints.map(w => [w.gcjLng, w.gcjLat]),
        strokeColor: '#43d675',
        strokeOpacity: 0.92,
        strokeWeight: 6,
        strokeStyle: 'solid',
        showDir: true,
      })
      map.add(planningPolyline)
    }
    rebuildDistanceLabels(AMap, map, waypoints)
    rebuildHomeMarker(AMap, map, waypoints)
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
    // H 标记随飞机当前位置移动（监测页签近似起飞点）
    if (homeMarker) {
      homeMarker.setPosition(lngLat)
      const wps = renderPlanningWaypoints.value
      if (homeGuideLine && wps.length > 0) {
        homeGuideLine.setPath([lngLat, [wps[0].gcjLng, wps[0].gcjLat]])
      }
    } else if (plannerUi.activeTab === 'monitor' && renderPlanningWaypoints.value.length > 0) {
      // 飞机位置首次到达且航线已存在：补画 H
      rebuildHomeMarker(AMap, map, renderPlanningWaypoints.value as PlannedWaypoint[])
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
    () => `${plannerUi.activeTab}|${canEditWaypoints.value}|${planningState.selectedWaypointId}|${renderPlanningWaypoints.value.map(w => `${w.id}:${w.gcjLng}:${w.gcjLat}:${w.height}:${w.speed ?? ''}:${w.actions?.length ?? 0}`).join('|')}`,
    () => rebuildPlanningOverlays()
  )

  watch(
    () => planningState.flightPosition
      ? `${planningState.flightPosition.aircraftSn}:${planningState.flightPosition.gcjLng}:${planningState.flightPosition.gcjLat}:${planningState.flightPosition.currentWaypointIndex}:${planningState.flightPosition.updatedAt}`
      : '',
    () => updateFlightPositionOverlay()
  )

  // ---------- 模拟预演幻影飞机 ----------
  let simGhostMarker: any = null

  function clearSimGhost () {
    const map = getMap()
    if (simGhostMarker) {
      map?.remove(simGhostMarker)
      simGhostMarker = null
    }
  }

  function updateSimGhost () {
    const AMap = getAMap()
    const map = getMap()
    if (!AMap || !map) return
    if (!plannerUi.simulating) {
      clearSimGhost()
      return
    }
    const timeline = buildSimulationTimeline(planningState.waypoints, planningState.maxSpeed)
    const pos = positionAtTime(timeline, plannerUi.simulationTimeS)
    if (!pos) {
      clearSimGhost()
      return
    }
    const content = `<div class="planner-sim-ghost"><span>✈️</span><em>${Math.round(pos.height)}m</em></div>`
    if (!simGhostMarker) {
      simGhostMarker = new AMap.Marker({
        position: [pos.gcjLng, pos.gcjLat],
        content,
        anchor: 'center',
        zIndex: 125,
      })
      map.add(simGhostMarker)
    } else {
      simGhostMarker.setPosition([pos.gcjLng, pos.gcjLat])
      simGhostMarker.setContent(content)
    }
  }

  watch(() => `${plannerUi.simulating}|${plannerUi.simulationTimeS}`, () => updateSimGhost())

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
    clearSimGhost()
  }

  return {
    setAircraftView,
    locateAircraftPosition,
    initPlannerOverlays,
    disposePlannerOverlays,
  }
}
