// 规划阶段飞行区合规：拉取已定义飞行区（NFZ/DFENCE）、对当前航点做违规判定、
// 在保存/下发前给软确认弹窗（可忽略继续，非硬拦截）。
//
// 飞行区数据来自 getFlightAreaList（系统自定义；DJI 官方限飞区按离线导入到同一库后
// 自动同等处理，本模块对数据来源无感）。坐标 WGS84，与航点 wgsLng/wgsLat 同坐标系。
import { computed, createVNode, reactive } from 'vue'
import { Modal } from 'ant-design-vue'
import { getFlightAreaList } from '/@/api/flight-area'
import { EFlightAreaType } from '/@/types/flight-area'
import { getPlanningStateRaw } from '/@/hooks/use-wayline-planning'
import type { PlannedWaypoint } from '/@/hooks/use-wayline-planning'
import { gcj02towgs84 } from '/@/vendors/coordtransform'
// @ts-ignore .mjs 纯计算模块（node 测试可直跑）
import { checkWaylineCompliance, circleToRing, summarizeViolations } from '/@/components/wayline-planner/flight-area-compliance.mjs'
// @ts-ignore .mjs 纯计算模块
import { geojsonToZones } from '/@/components/wayline-planner/airspace-import.mjs'

export type FlightAreaKind = 'circle' | 'polygon'

export interface NormalizedZone {
  id: string
  name: string
  // 'warning' = 仅渲染的增强警示区（不参与硬判定，见 airspace-import）。
  type: EFlightAreaType | 'warning'
  kind: FlightAreaKind
  // 渲染用闭合环（圆已转 64 边形），WGS84 [lng,lat]
  ring: Array<[number, number]>
  // 圆判定用精确中心/半径
  center?: [number, number]
  radius?: number
}

const state = reactive({
  zones: [] as NormalizedZone[],
  loaded: false,
  loading: false,
})

const planningState = getPlanningStateRaw()

export function getFlightAreaComplianceRaw () {
  return state
}

function wpToLngLat (wp: PlannedWaypoint): { lng: number; lat: number } {
  const lng = Number(wp.wgsLng); const lat = Number(wp.wgsLat)
  if (Number.isFinite(lng) && Number.isFinite(lat)) return { lng, lat }
  const [g0, g1] = gcj02towgs84(wp.gcjLng, wp.gcjLat) as [number, number]
  return { lng: g0, lat: g1 }
}

function normalizeZone (area: any): NormalizedZone | null {
  const g = area?.content?.geometry
  if (!g) return null
  if (g.type === 'Circle') {
    const c = g.coordinates as number[]
    const center: [number, number] = [Number(c?.[0]), Number(c?.[1])]
    const radius = Number(g.radius)
    if (!Number.isFinite(center[0]) || !Number.isFinite(center[1]) || !(radius > 0)) return null
    return { id: area.area_id, name: area.name, type: area.type, kind: 'circle', center, radius, ring: circleToRing(center, radius) }
  }
  if (g.type === 'Polygon') {
    const ring = ((g.coordinates?.[0] || []) as number[][])
      .map(c => [Number(c[0]), Number(c[1])] as [number, number])
      .filter(c => Number.isFinite(c[0]) && Number.isFinite(c[1]))
    if (ring.length < 3) return null
    return { id: area.area_id, name: area.name, type: area.type, kind: 'polygon', ring }
  }
  return null
}

// 系统自定义飞行区（后端库）。读取失败返回 []（不阻断规划）。
async function loadSystemZones (): Promise<NormalizedZone[]> {
  try {
    const res: any = await getFlightAreaList()
    const list: any[] = Array.isArray(res?.data) ? res.data : []
    // 仅纳入已启用（status !== false）的飞行区；停用的不作为规划约束。
    return list.filter(a => a?.status !== false).map(normalizeZone).filter(Boolean) as NormalizedZone[]
  } catch (e) {
    return []
  }
}

// 离线导入空域：DJI FlySafe 西安周边 100km 限飞区静态快照（见 data/airspace，sub_areas 已逐层展开）。
// 动态 import 资源：按需加载、不进主包；坐标 WGS84，与系统区同口径。
async function loadImportedZones (): Promise<NormalizedZone[]> {
  try {
    const mod: any = await import('/@/assets/airspace/dji_flysafe_xian.json')
    return geojsonToZones(mod?.default ?? mod) as NormalizedZone[]
  } catch (e) {
    return []
  }
}

export async function loadFlightAreas (): Promise<void> {
  if (state.loading) return
  state.loading = true
  try {
    // 系统区 + 离线导入区合并：两路各自吞错，互不阻断。
    const [sys, imported] = await Promise.all([loadSystemZones(), loadImportedZones()])
    state.zones = [...sys, ...imported]
    state.loaded = true
  } finally {
    state.loading = false
  }
}

// 与 use-planner-overlays 的渲染口径一致：预览航点优先，否则编辑航点。
function activeWaypoints (): PlannedWaypoint[] {
  return planningState.previewWaypoints.length > 0 ? planningState.previewWaypoints : planningState.waypoints
}

export const flightAreaCompliance = computed(() => {
  const wps = activeWaypoints()
  const points = wps.map(wpToLngLat)
  const closed = planningState.routeKind === 'patrol' && points.length >= 2
  const result = checkWaylineCompliance(points, state.zones, closed)
  return {
    ...result,
    points,
    messages: summarizeViolations(result) as string[],
    badSegmentCoords: (result.badSegments as Array<[number, number]>).map(
      ([i, j]) => [[points[i].lng, points[i].lat], [points[j].lng, points[j].lat]] as Array<[number, number]>
    ),
  }
})

// 保存/下发前的软确认：无违规直接放行；有违规弹窗提示，用户可“仍然继续”。
export function confirmComplianceBeforeAction (actionLabel: string): Promise<boolean> {
  const r = flightAreaCompliance.value
  if (!r.hasViolation) return Promise.resolve(true)
  return new Promise(resolve => {
    Modal.confirm({
      title: '存在违规飞行',
      icon: undefined,
      content: createVNode(
        'div',
        { style: 'line-height:1.7' },
        [
          createVNode('div', { style: 'margin-bottom:6px' }, '当前航线与飞行区存在冲突：'),
          ...r.messages.map((m: string) => createVNode('div', { style: 'color:#cf1322' }, `· ${m}`)),
          createVNode('div', { style: 'margin-top:8px;color:#8c8c8c;font-size:12px' }, '可返回修改，或确认知悉风险后仍然继续。'),
        ]
      ),
      okText: `仍然${actionLabel}`,
      okType: 'danger',
      cancelText: '返回修改',
      onOk: () => resolve(true),
      onCancel: () => resolve(false),
    })
  })
}
