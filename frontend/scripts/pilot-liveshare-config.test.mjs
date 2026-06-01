import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const configPath = new URL('../src/api/http/config.ts', import.meta.url)
const configSource = readFileSync(configPath, 'utf8')

test('pilot liveshare RTMP config uses a real endpoint instead of placeholder text', () => {
  assert.match(configSource, /const rtmpUrl = import\.meta\.env\.VITE_APP_LIVESTREAM_RTMP_URL \|\| 'rtmp:\/\/localhost:1935\/live\/'/)
  assert.match(configSource, /rtmpURL:\s*rtmpUrl/)
  assert.doesNotMatch(configSource, /rtmpURL:\s*'rtmp:\/\/(?:10\.|172\.(?:1[6-9]|2\d|3[01])\.|192\.168\.)/)
  assert.doesNotMatch(configSource, /rtmpURL:\s*'Please enter/i)
})

const liveSharePath = new URL('../src/pages/page-pilot/pilot-liveshare.vue', import.meta.url)
const liveShareSource = readFileSync(liveSharePath, 'utf8')

test('pilot manual liveshare uses the RC_PLUS_LOCAL-0 stream key expected by cockpit playback', () => {
  assert.match(liveShareSource, /url:\s*CURRENT_CONFIG\.rtmpURL\s*\+\s*'RC_PLUS_LOCAL-0'/)
  assert.doesNotMatch(liveShareSource, /CURRENT_CONFIG\.rtmpURL\s*\+\s*new Date\(\)\.getTime\(\)/)
})
