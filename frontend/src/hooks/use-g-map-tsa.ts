// 实时设备(飞机/遥控器/机场)地图标记(MapLibre 版，自高德迁移)。坐标 WGS84。
import maplibregl from 'maplibre-gl'
import store from '/@/store'
import { getRoot } from '/@/root'
import { EDeviceTypeName } from '/@/types'
import dockIcon from '/@/assets/icons/dock.png'
import rcIcon from '/@/assets/icons/rc.png'
import droneIcon from '/@/assets/icons/drone.png'

export function deviceTsaUpdate () {
  const root = getRoot()

  const icons = new Map<number, string>([
    [EDeviceTypeName.Aircraft, droneIcon],
    [EDeviceTypeName.Gateway, rcIcon],
    [EDeviceTypeName.Dock, dockIcon],
  ])
  const markers = store.state.markerInfo.coverMap as Record<string, maplibregl.Marker>

  function iconEl (type: number) {
    const img = document.createElement('img')
    img.src = icons.get(type) || droneIcon
    img.style.width = '40px'
    img.style.height = '40px'
    img.style.display = 'block'
    return img
  }

  function initMarker (type: number, name: string, sn: string, lng?: number, lat?: number) {
    if (markers[sn]) return
    const map = root?.$map
    if (!map) return
    const hasValid = !!lng && !!lat && Number.isFinite(lng) && Number.isFinite(lat) && lng !== 0 && lat !== 0
    const el = iconEl(type)
    el.title = name
    const m = new maplibregl.Marker({ element: el, anchor: 'center' })
    m.setLngLat([lng || 113.943225499, lat || 22.577673716])
    m.addTo(map)
    markers[sn] = m
    // 第一次出现时自动把视野跟过去
    if (hasValid && typeof map.easeTo === 'function') {
      map.easeTo({ center: [lng as number, lat as number], zoom: 17, duration: 500 })
    }
  }

  function removeMarker (sn: string) {
    if (!markers[sn]) return
    markers[sn].remove()
    delete markers[sn]
  }

  function addMarker (sn: string, lng?: number, lat?: number, type = EDeviceTypeName.Aircraft, name = sn) {
    initMarker(type, name, sn, lng, lat)
  }

  function moveTo (sn: string, lng: number, lat: number, type?: number, name?: string) {
    const marker = markers[sn]
    if (!marker) {
      addMarker(sn, lng, lat, type, name)
      return
    }
    if (Number.isFinite(lng) && Number.isFinite(lat)) marker.setLngLat([lng, lat])
  }

  // 航线页规划层会用自己的青色徽标渲染被追踪的飞机，此时隐藏该飞机的 TSA 设备标记，避免双图标重叠。
  // 直接操作本 hook 自己的 markers（与 moveTo 同一 coverMap 实例），不跨模块取 store，避免实例错位。
  function setMarkerHidden (sn: string, hidden: boolean) {
    const m = markers[sn]
    if (!m || typeof m.getElement !== 'function') return
    m.getElement().style.display = hidden ? 'none' : 'block'
  }

  return {
    marker: markers,
    initMarker,
    removeMarker,
    moveTo,
    setMarkerHidden,
  }
}
