import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const read = (relativePath) => fs.readFileSync(path.join(root, relativePath), 'utf8')

test('runtime feature flags keep flight control disabled', () => {
  assert.match(read('config/runtime.js'), /flightControlEnabled:\s*false/)
})

test('HTTP client uses bearer header and never places the token in the URL', () => {
  const source = read('services/http.js')
  assert.match(source, /headers\.Authorization = `Bearer \$\{token\}`/)
  assert.doesNotMatch(source, /[?&](token|accessToken)=/)
})

test('project configuration contains no production AppID', () => {
  const config = JSON.parse(read('project.config.json'))
  assert.equal(config.appid, 'touristappid')
})

test('home page labels unavailable metrics instead of inventing demo values', () => {
  const source = read('pages/home/index.js')
  assert.match(source, /todayTasks:\s*'—'/)
  assert.match(source, /urgentEvents:\s*'—'/)
  assert.doesNotMatch(source, /mock|demoData|fake/i)
})
