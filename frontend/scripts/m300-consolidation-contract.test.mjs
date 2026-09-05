import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import ts from 'typescript'

// Execute the actual API sanitizer without importing browser/UI dependencies.
const source = readFileSync(new URL('../src/api/wayline.ts', import.meta.url), 'utf8')
const sanitizerSource = source.slice(source.indexOf('const HTTP_PREFIX'), source.indexOf('function normalizePlannedWaypointResponse'))
const compiled = ts.transpileModule(sanitizerSource, { compilerOptions: { target: ts.ScriptTarget.ES2020 } }).outputText
const validate = new Function('message', `${compiled}\nreturn validatePlannedWaylineBody` )({ error () {} })

const vertex = { gcjLng: 108.9, gcjLat: 34.2, wgsLng: 108.895, wgsLat: 34.201 }
const areaPolygon = [vertex, { ...vertex, gcjLng: 108.91, wgsLng: 108.905 }, { ...vertex, gcjLat: 34.21, wgsLat: 34.211 }]
const body = model => ({
  name: 'consolidation-contract', aircraftModelKey: model, payloadModelKey: 'H30T', payloadPositionIndex: 0,
  routeKind: 'area', areaPolygon, areaFrontOverlap: 80, areaSideOverlap: 70, areaHeadingDeg: 45,
  waypoints: [{ ...vertex, height: 60, turnMode: 'toPointAndStopWithDiscontinuityCurvature', turnDamping: 0 }],
})

for (const model of ['M300', 'M350']) {
  test(`${model} payload validation preserves area geometry and strict waypoint turns`, () => {
    const saved = validate(body(model))
    assert.deepEqual(saved.areaPolygon, areaPolygon)
    assert.equal(saved.areaFrontOverlap, 80)
    assert.equal(saved.areaHeadingDeg, 45)
    assert.equal(saved.payloadModelKey, 'H30T')
    assert.equal(saved.waypoints[0].turnMode, 'toPointAndStopWithDiscontinuityCurvature')
    assert.equal(saved.waypoints[0].turnDamping, 0)
    assert.equal(saved.defaultHeight, 30)
    assert.equal(saved.maxSpeed, 5)
  })

  test(`${model} still rejects invalid payloads and incomplete area polygons`, () => {
    assert.throws(() => validate({ ...body(model), payloadModelKey: 'UNKNOWN' }), /payload model and position/)
    assert.throws(() => validate({ ...body(model), payloadPositionIndex: 3 }), /payload model and position/)
    assert.throws(() => validate({ ...body(model), areaPolygon: areaPolygon.slice(0, 2) }), /at least 3 vertices/)
    assert.throws(() => validate({ ...body(model), waypoints: [{ ...vertex, wgsLng: null }] }), /coordinates required/)
  })
}
