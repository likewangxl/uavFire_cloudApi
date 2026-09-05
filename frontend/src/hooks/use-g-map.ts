import maplibregl from 'maplibre-gl'
import 'maplibre-gl/dist/maplibre-gl.css'
import { App, reactive } from 'vue'
import { buildTiandituStyle } from '/@/hooks/tianditu'
import { gcj02towgs84 } from '/@/vendors/coordtransform'
import { resolveInitialMapView, resolveSiteView } from './map-site-policy.mjs'

// 地图引擎已从高德(GCJ-02)迁移到 MapLibre + 天地图(WGS84)。坐标全程 WGS84。
// v3：不复用旧默认视野；明确配置的部署点始终优先于历史视野。
const LAST_MAP_CENTER_KEY = 'g_map_last_center_wgs_v3'
// 兜底：西安（把原 GCJ-02 默认中心转成 WGS84）
const DEFAULT_CENTER: [number, number] = gcj02towgs84(108.92854, 34.231804) as [number, number]
// 默认比例尺对齐 TSA/航线页(~100m)：天地图 17 级在作业纬度下 ScaleControl(120px) 即显示 100m
const DEFAULT_ZOOM = 17

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
    const site = window.__UAVFIRE_SITE_LOCATION__
    const initial = resolveInitialMapView(site, cached, { center: DEFAULT_CENTER, zoom: 5 })

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

    // 部署点属于服务器；浏览器/飞机位置不能当作服务器位置。
    // 同步读取部署文件，避免异步定位覆盖用户已经选中的航线或拖动后的视野。
    const siteView = resolveSiteView(site)
    const locationControl = {
      container: null as HTMLDivElement | null,
      onAdd () {
        const el = document.createElement('div')
        el.className = 'maplibregl-ctrl'
        el.style.cssText = 'background:#102c3b;color:#fff;padding:8px 12px;border-radius:4px;font-size:12px;max-width:260px;'
        if (siteView) {
          const button = document.createElement('button')
          button.type = 'button'
          button.textContent = '回到服务器部署点'
          button.style.cssText = 'color:inherit;background:transparent;border:0;cursor:pointer;'
          button.onclick = () => map.jumpTo(siteView)
          el.appendChild(button)
        } else {
          el.textContent = initial.source === 'history'
            ? '显示历史视野 · 服务器部署点未设置'
            : '服务器部署点未设置 · 当前为概览'
          el.title = '请在 Windows 服务器运行 SET-MAP-LOCATION.bat 设置部署点经纬度。'
        }
        this.container = el
        return el
      },
      onRemove () { this.container?.remove() }
    }
    map.addControl(locationControl, 'bottom-left')

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
