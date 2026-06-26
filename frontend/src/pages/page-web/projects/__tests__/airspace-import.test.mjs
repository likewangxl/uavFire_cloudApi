import test from 'node:test'
import assert from 'node:assert/strict'
import { geojsonToZones } from '../../../../components/wayline-planner/airspace-import.mjs'

function fc (features) {
  return { type: 'FeatureCollection', features }
}
const POLY = {
  type: 'Feature',
  properties: { area_id: 40000218, name: '无人机管控空域', category: 'nfz', radius_m: undefined },
  geometry: { type: 'Polygon', coordinates: [[[114.0, 22.0], [114.01, 22.0], [114.01, 22.01], [114.0, 22.01]]] },
}
const CIRCLE = {
  type: 'Feature',
  properties: { area_id: 40000282, name: '增强警示区', category: 'warning', radius_m: 500 },
  geometry: { type: 'Point', coordinates: [116.39, 39.9] },
}

test('Polygon feature → polygon zone（coordinates[0] 作为环）', () => {
  const [z] = geojsonToZones(fc([POLY]))
  assert.equal(z.kind, 'polygon')
  assert.equal(z.type, 'nfz')
  assert.equal(z.id, 'dji:40000218')
  assert.equal(z.name, '无人机管控空域')
  assert.equal(z.ring.length, 4)
  assert.deepEqual(z.ring[0], [114.0, 22.0])
})

test('Point+radius_m → circle zone（带 center/radius，并生成渲染环）', () => {
  const [z] = geojsonToZones(fc([CIRCLE]))
  assert.equal(z.kind, 'circle')
  assert.equal(z.type, 'warning')
  assert.deepEqual(z.center, [116.39, 39.9])
  assert.equal(z.radius, 500)
  assert.ok(Array.isArray(z.ring) && z.ring.length > 8) // circleToRing 闭合环
})

test('category 映射：nfz/dfence 原样，warning/altitude → warning，缺失 → warning', () => {
  const mk = (cat) => ({ type: 'Feature', properties: { area_id: 1, name: 'x', category: cat },
    geometry: { type: 'Polygon', coordinates: [[[0, 0], [1, 0], [1, 1]]] } })
  const types = geojsonToZones(fc([mk('nfz'), mk('dfence'), mk('warning'), mk('altitude'), mk(undefined)]))
    .map(z => z.type)
  assert.deepEqual(types, ['nfz', 'dfence', 'warning', 'warning', 'warning'])
})

test('非法/不支持的 feature 被跳过（点不足/无半径/未知几何）', () => {
  const badPoly = { type: 'Feature', properties: { category: 'nfz' }, geometry: { type: 'Polygon', coordinates: [[[0, 0], [1, 1]]] } }
  const noRadius = { type: 'Feature', properties: { category: 'nfz', radius_m: 0 }, geometry: { type: 'Point', coordinates: [1, 1] } }
  const weird = { type: 'Feature', properties: { category: 'nfz' }, geometry: { type: 'LineString', coordinates: [[0, 0], [1, 1]] } }
  assert.equal(geojsonToZones(fc([badPoly, noRadius, weird])).length, 0)
})

test('空/缺失输入安全返回 []', () => {
  assert.deepEqual(geojsonToZones(null), [])
  assert.deepEqual(geojsonToZones({}), [])
  assert.deepEqual(geojsonToZones(fc([])), [])
})
