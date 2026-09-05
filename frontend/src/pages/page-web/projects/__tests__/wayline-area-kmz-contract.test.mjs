import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../../../..')

function readSource (path) {
  return readFileSync(resolve(root, path), 'utf8')
}

test('area plan persists its polygon and mapping2d parameters through API payloads', () => {
  const planningSource = readSource('src/hooks/use-wayline-planning.ts')
  const pageSource = readSource('src/pages/page-web/projects/wayline.vue')
  const apiSource = readSource('src/api/wayline.ts')

  assert.match(planningSource, /routeKind: state\.routeKind/)
  assert.match(planningSource, /areaPolygon: state\.routeKind === 'area'/)
  assert.match(planningSource, /gcj02towgs84\(vertex\.gcjLng, vertex\.gcjLat\)/)
  assert.match(planningSource, /areaFrontOverlap: state\.routeKind === 'area'/)
  assert.match(planningSource, /state\.routeKind = record\.routeKind === 'patrol' \|\| record\.routeKind === 'area'/)
  assert.match(planningSource, /state\.areaParams\.headingDeg = Number\.isFinite\(Number\(record\.areaHeadingDeg\)\)/)

  assert.match(pageSource, /routeKind: planningState\.routeKind/)
  assert.match(pageSource, /areaPolygon: planningState\.routeKind === 'area'/)
  assert.match(pageSource, /exitOnRcLost: planningState\.exitOnRcLost \?\? \(planningState\.routeKind === 'area' \? 'executeLostAction'/)
  assert.match(pageSource, /globalTransitionalSpeed: planningState\.globalTransitionalSpeed \?\? \(planningState\.routeKind === 'area' \? 15/)

  assert.match(apiSource, /body\.routeKind === 'area'/)
  assert.match(apiSource, /planned area polygon requires at least 3 vertices/)
  assert.match(apiSource, /areaCameraKey: record\?\.areaCameraKey \?\? record\?\.area_camera_key/)
})
