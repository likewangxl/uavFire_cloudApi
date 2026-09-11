import maplibregl from 'maplibre-gl'
import 'maplibre-gl/dist/maplibre-gl.css'
import { App, onUnmounted, reactive } from 'vue'
import { buildTiandituStyle } from '/@/hooks/tianditu'
import { browserMapInitialView, createBrowserLocationControl } from './browser-map-location.mjs'

// 地图引擎已从高德(GCJ-02)迁移到 MapLibre + 天地图(WGS84)。坐标全程 WGS84。
const LAST_MAP_CENTER_KEY = 'g_map_last_center_wgs_v3'

export function saveMapCenter (center: [number, number], zoom: number) {
  try {
    localStorage.setItem(LAST_MAP_CENTER_KEY, JSON.stringify({ center, zoom }))
  } catch (e) {
    // ignore
  }
}

export function useGMapManage () {
  let disposeLocation: (() => void) | undefined
  onUnmounted(() => disposeLocation?.())
  const state = reactive({
    aMap: null as any, // maplibregl 命名空间（兼容旧 $aMap 取用方式）
    map: null as any, // MapLibre Map 实例
    mouseTool: null as any, // MapLibre 无原生 MouseTool，绘制在 use-mouse-tool 自实现
  })

  function initMap (container: string, app: App) {
    const initial = browserMapInitialView(window.__UAVFIRE_SITE_LOCATION__)

    const map = new maplibregl.Map({
      container,
      // 默认天地图影像档（卫星）。航线规划页对标司空2以影像为底；图层状态跨页签共享，
      // 历史上访问过航线页后全局即为影像，故初始即用影像，避免刷新时「标准→卫星」闪一下。
      style: buildTiandituStyle('satellite'),
      center: initial.center,
      zoom: initial.zoom,
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

    const locationControl = createBrowserLocationControl()
    map.addControl(locationControl, 'bottom-left')
    disposeLocation = () => map.removeControl(locationControl)

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
