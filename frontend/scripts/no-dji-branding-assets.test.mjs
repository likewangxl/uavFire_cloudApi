import test from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'

const read = (path) => readFileSync(new URL(path, import.meta.url), 'utf8')
const exists = (path) => existsSync(new URL(path, import.meta.url))

const webLoginSource = read('../src/pages/page-web/index.vue')
const pilotLoginSource = read('../src/pages/page-pilot/pilot-index.vue')
const indexHtmlSource = read('../index.html')

test('login pages do not render or import DJI logo assets', () => {
  const loginSources = `${webLoginSource}\n${pilotLoginSource}`

  assert.doesNotMatch(loginSources, /djiLogo/)
  assert.doesNotMatch(loginSources, /dji_logo\.png/)
  assert.doesNotMatch(loginSources, /dji-logo-vector\.svg/)
  assert.doesNotMatch(loginSources, /<a-image[\s\S]*logo/i)
})

test('source tree does not keep DJI logo or favicon assets', () => {
  assert.equal(exists('../src/assets/icons/dji_logo.png'), false)
  assert.equal(exists('../src/assets/icons/dji-logo-vector.svg'), false)
  assert.equal(exists('../public/favicon.ico'), false)
})

test('html shell does not advertise the DJI favicon', () => {
  assert.doesNotMatch(indexHtmlSource, /favicon\.ico/)
})
