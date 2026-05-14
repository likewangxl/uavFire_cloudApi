import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const repoRoot = new URL('../..', import.meta.url)

const trackedFiles = [
  'frontend/src/components/g-map/use-connect-mqtt.ts',
  'frontend/src/websocket/index.ts',
  'frontend/src/types/device.ts',
  'frontend/src/api/drone-control/payload.ts',
  'backend/sample/src/main/java/com/dji/sample/control/service/impl/ControlServiceImpl.java'
]

test('tracked source files do not carry unresolved TODO markers', () => {
  const offenders = []

  for (const relativePath of trackedFiles) {
    const source = readFileSync(new URL(relativePath, repoRoot), 'utf8')
    if (/(?:@?TODO|FIXME|TBD)/i.test(source)) {
      offenders.push(relativePath)
    }
  }

  assert.deepEqual(offenders, [])
})
