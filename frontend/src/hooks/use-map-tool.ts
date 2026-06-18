import { GeojsonCoordinate } from '../utils/genjson'
import { getRoot } from '/@/root'

// 地图引擎已迁 MapLibre：panTo 用 flyTo 实现（坐标 WGS84）。
export function useMapTool () {
  const root = getRoot()

  function panTo (coordinate: GeojsonCoordinate) {
    const map = root?.$map
    if (!map) return
    if (typeof map.flyTo === 'function') {
      map.flyTo({ center: coordinate as unknown as [number, number], zoom: 18 })
    } else if (typeof map.panTo === 'function') {
      map.panTo(coordinate as unknown as [number, number])
    }
  }
  return {
    panTo,
  }
}
