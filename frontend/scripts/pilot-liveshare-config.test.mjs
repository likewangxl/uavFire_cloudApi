import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const configPath = new URL('../src/api/http/config.ts', import.meta.url)
const configSource = readFileSync(configPath, 'utf8')

test('pilot liveshare RTMP config uses a real endpoint instead of placeholder text', () => {
  const match = configSource.match(/rtmpURL:\s*'([^']+)'/)
  assert.ok(match, 'rtmpURL config should exist')
  assert.doesNotMatch(match[1], /Please enter/i)
  assert.match(match[1], /^rtmp:\/\/192\.168\.50\.254:1935\/live\/$/)
})

const liveSharePath = new URL('../src/pages/page-pilot/pilot-liveshare.vue', import.meta.url)
const liveShareSource = readFileSync(liveSharePath, 'utf8')

test('pilot manual liveshare uses the RC_PLUS_LOCAL-0 stream key expected by cockpit playback', () => {
  assert.match(liveShareSource, /url:\s*CURRENT_CONFIG\.rtmpURL\s*\+\s*'RC_PLUS_LOCAL-0'/)
  assert.doesNotMatch(liveShareSource, /CURRENT_CONFIG\.rtmpURL\s*\+\s*new Date\(\)\.getTime\(\)/)
})
