import AMapLoader from '@amap/amap-jsapi-loader'
import { App, reactive } from 'vue'
import { AMapConfig } from '/@/constants/index'

const LAST_MAP_CENTER_KEY = 'g_map_last_center'
// 最终兜底：西安
const DEFAULT_CENTER: [number, number] = [108.92854, 34.231804]
const DEFAULT_ZOOM = 12

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
    aMap: null, // Map类
    map: null, // 地图对象
    mouseTool: null,
  })

  async function initMap (container: string, app: App) {
    AMapLoader.load({
      ...AMapConfig
    }).then((AMap) => {
      state.aMap = AMap

      // 1) 优先使用上次的位置
      const cached = readCachedCenter()
      const initCenter = cached?.center ?? DEFAULT_CENTER
      const initZoom = cached?.zoom ?? DEFAULT_ZOOM

      state.map = new AMap.Map(container, {
        center: initCenter,
        zoom: initZoom
      })
      state.mouseTool = new AMap.MouseTool(state.map)

      // 地图拖动/缩放后把当前视野缓存下来
      const persist = () => {
        const c = (state.map as any).getCenter()
        const z = (state.map as any).getZoom()
        if (c) saveMapCenter([c.lng, c.lat], z)
      }
      ;(state.map as any).on('moveend', persist)
      ;(state.map as any).on('zoomend', persist)

      // 2) 没有缓存就用浏览器 HTML5 定位尝试一次
      if (!cached) {
        AMap.plugin('AMap.Geolocation', () => {
          const geo = new (AMap as any).Geolocation({
            enableHighAccuracy: true,
            timeout: 6000,
            showButton: false,
            showMarker: false,
            showCircle: false
          })
          geo.getCurrentPosition((status: string, result: any) => {
            if (status === 'complete' && result?.position) {
              const pos: [number, number] = [result.position.lng, result.position.lat]
              ;(state.map as any).setZoomAndCenter(14, pos)
              saveMapCenter(pos, 14)
            }
          })
        })
      }

      // 挂在到全局
      app.config.globalProperties.$aMap = state.aMap
      app.config.globalProperties.$map = state.map
      app.config.globalProperties.$mouseTool = state.mouseTool
    }).catch(e => {
      console.log(e)
    })
  }

  function globalPropertiesConfig (app: App) {
    initMap('g-container', app)
  }

  return {
    globalPropertiesConfig,
  }
}
