import maplibregl from 'maplibre-gl'
import 'maplibre-gl/dist/maplibre-gl.css'
import { App, reactive } from 'vue'
import { buildTiandituStyle } from '/@/hooks/tianditu'
import { gcj02towgs84 } from '/@/vendors/coordtransform'

// 地图引擎已从高德(GCJ-02)迁移到 MapLibre + 天地图(WGS84)。坐标全程 WGS84。
// 旧缓存是 GCJ-02，换新 key 不复用，避免几百米偏移。
const LAST_MAP_CENTER_KEY = 'g_map_last_center_wgs'
// 兜底：西安（把原 GCJ-02 默认中心转成 WGS84）
const DEFAULT_CENTER: [number, number] = gcj02towgs84(108.92854, 34.231804) as [number, number]
const DEFAULT_ZOOM = 15

function readCachedCenter (): { center: [number, number], zoom: number } | null {
  try {
    const raw = localStorage.getItem(LAST_MAP_CENTER_KEY)
    if (!raw) return null
    const obj = JSON.parse(raw)
    if (
      Array.isArray(obj.center) &&
      obj.center.length === 2 &&
      Number.isFinite(obj.center[0]) &&
      Number.isFinite(obj.center[1]) &&
      obj.center[0] !== 0 &&
      obj.center[1] !== 0
    ) {
      return { center: obj.center as [number, number], zoom: Number(obj.zoom) || DEFAULT_ZOOM }
    }
  } catch (e) {
    // ignore
  }
  return null
}

export function saveMapCenter (center: [number, number], zoom: number) {
  try {
    localStorage.setItem(LAST_MAP_CENTER_KEY, JSON.stringify({ center, zoom }))
  } catch (e) {
    // ignore
  }
}

export function useGMapManage () {
  const state = reactive({
    aMap: null as any, // maplibregl 命名空间（兼容旧 $aMap 取用方式）
    map: null as any, // MapLibre Map 实例
    mouseTool: null as any, // MapLibre 无原生 MouseTool，绘制在 use-mouse-tool 自实现
  })

  function initMap (container: string, app: App) {
    const cached = readCachedCenter()
    const initCenter = cached?.center ?? DEFAULT_CENTER
    const initZoom = cached?.zoom ?? DEFAULT_ZOOM

    const map = new maplibregl.Map({
      container,
      style: buildTiandituStyle('standard'),
      center: initCenter,
      zoom: initZoom,
      attributionControl: false,
      // 航线规划用 2D 正视，关闭旋转/倾斜避免航点错位误操作
      pitchWithRotate: false,
      dragRotate: false,
    })
    map.touchZoomRotate?.disableRotation?.()

    state.aMap = maplibregl
    state.map = map
    state.mouseTool = null

    // 比例尺（司空2 风格，左下角）
    map.addControl(new maplibregl.ScaleControl({ maxWidth: 120, unit: 'metric' }), 'bottom-left')

    // 拖动/缩放后缓存当前视野（WGS84）
    const persist = () => {
      const c = map.getCenter()
      if (c) saveMapCenter([c.lng, c.lat], map.getZoom())
    }
    map.on('moveend', persist)

    // 没有缓存则用浏览器 HTML5 定位一次（navigator 给的就是 WGS84，正好对齐天地图）
    if (!cached && navigator.geolocation) {
      navigator.geolocation.getCurrentPosition(
        (pos) => {
          const p: [number, number] = [pos.coords.longitude, pos.coords.latitude]
          map.jumpTo({ center: p, zoom: 14 })
          saveMapCenter(p, 14)
        },
        () => { /* 拒绝/失败忽略 */ },
        { enableHighAccuracy: true, timeout: 6000 }
      )
    }

    // 挂到全局（$aMap=maplibregl 命名空间，$map=Map 实例）
    app.config.globalProperties.$aMap = maplibregl
    app.config.globalProperties.$map = map
    app.config.globalProperties.$mouseTool = null
  }

  function globalPropertiesConfig (app: App) {
    initMap('g-container', app)
  }

  return {
    globalPropertiesConfig,
  }
}
