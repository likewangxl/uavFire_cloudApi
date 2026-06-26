// 离线空域数据导入器（.mjs：与 node 测试共用，禁止引浏览器/Vue 依赖）。
// 把归一化后的 GeoJSON（见 data/airspace 下 DJI FlySafe 大陆限飞区快照）转成
// use-flight-area-compliance 的 NormalizedZone，合并进规划期飞行区做渲染+判定。
//
// 类别映射（feature.properties.category → NormalizedZone.type）：
//   nfz      禁飞/限飞/授权区 → 'nfz'    （红，参与判定：航点落入/航段穿越=违规）
//   dfence   作业区          → 'dfence' （绿，参与判定：航线须落在内）
//   warning  增强警示        → 'warning'（琥珀，仅渲染、不硬拦，符合 DJI 警示语义）
//   altitude 高度限制        → 'warning'（2D 无高度，降级为仅渲染告警）
import { circleToRing } from './flight-area-compliance.mjs'

const CATEGORY_TO_TYPE = { nfz: 'nfz', dfence: 'dfence', warning: 'warning', altitude: 'warning' }

function featureToZone (f, idPrefix) {
  const p = (f && f.properties) || {}
  const g = (f && f.geometry) || {}
  const type = CATEGORY_TO_TYPE[p.category] || 'warning'
  const id = `${idPrefix}:${p.area_id != null ? p.area_id : ''}`
  const name = p.name || '未命名区域'

  if (g.type === 'Polygon') {
    const ring = ((g.coordinates && g.coordinates[0]) || [])
      .map(c => [Number(c[0]), Number(c[1])])
      .filter(c => Number.isFinite(c[0]) && Number.isFinite(c[1]))
    if (ring.length < 3) return null
    return { id, name, type, kind: 'polygon', ring }
  }
  if (g.type === 'Point') {
    const c = g.coordinates || []
    const center = [Number(c[0]), Number(c[1])]
    const radius = Number(p.radius_m)
    if (!Number.isFinite(center[0]) || !Number.isFinite(center[1]) || !(radius > 0)) return null
    return { id, name, type, kind: 'circle', center, radius, ring: circleToRing(center, radius) }
  }
  return null
}

// GeoJSON FeatureCollection → NormalizedZone[]。非法/不支持的 feature 直接跳过。
export function geojsonToZones (fc, idPrefix = 'dji') {
  const feats = (fc && Array.isArray(fc.features)) ? fc.features : []
  const zones = []
  for (const f of feats) {
    const z = featureToZone(f, idPrefix)
    if (z) zones.push(z)
  }
  return zones
}
