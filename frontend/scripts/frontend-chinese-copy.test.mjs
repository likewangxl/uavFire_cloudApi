import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const read = (path) => readFileSync(new URL(path, import.meta.url), 'utf8')

const coreWebSources = [
  '../src/pages/page-web/index.vue',
  '../src/pages/page-web/home.vue',
  '../src/pages/page-web/projects/livestream.vue',
  '../src/components/WorkspaceLivestreamPanel.vue'
].map(read).join('\n')

const adminSources = [
  '../src/pages/page-web/projects/devices.vue',
  '../src/pages/page-web/projects/members.vue',
  '../src/pages/page-web/projects/Firmwares.vue',
  '../src/pages/page-web/projects/wayline.vue'
].map(read).join('\n')

const pilotSources = [
  '../src/pages/page-pilot/pilot-index.vue',
  '../src/pages/page-pilot/pilot-home.vue',
  '../src/pages/page-pilot/pilot-liveshare.vue',
  '../src/api/pilot-bridge.ts'
].map(read).join('\n')

test('core web pages no longer expose common English UI copy', () => {
  assert.doesNotMatch(coreWebSources, />\s*Login\s*</)
  assert.doesNotMatch(coreWebSources, />\s*Livestream\s*</)
  assert.doesNotMatch(coreWebSources, /placeholder="Select Drone"/)
  assert.doesNotMatch(coreWebSources, /placeholder="Select Camera"/)
  assert.doesNotMatch(coreWebSources, /placeholder="Quality"/)
  assert.doesNotMatch(coreWebSources, />\s*Play\s*</)
  assert.doesNotMatch(coreWebSources, />\s*Stop\s*</)
  assert.doesNotMatch(coreWebSources, />\s*Apply Quality\s*</)
  assert.doesNotMatch(coreWebSources, />\s*Refresh Capacity\s*</)
  assert.doesNotMatch(coreWebSources, /Waiting for stream/)
})

test('admin pages no longer expose common English table and action labels', () => {
  assert.doesNotMatch(adminSources, /title:\s*'Account'/)
  assert.doesNotMatch(adminSources, /title:\s*'Joined'/)
  assert.doesNotMatch(adminSources, /title:\s*'Last Online'/)
  assert.doesNotMatch(adminSources, /title:\s*'Release Date'/)
  assert.doesNotMatch(adminSources, />\s*Stop Placing\s*</)
  assert.doesNotMatch(adminSources, />\s*Stop Execute\s*</)
})

test('pilot pages and api-side user-facing errors are Chinese', () => {
  assert.doesNotMatch(pilotSources, />\s*Login\s*</)
  assert.doesNotMatch(pilotSources, /Pilot platform stopped\./)
  assert.doesNotMatch(pilotSources, /Failed to load api module/)
  assert.doesNotMatch(pilotSources, /Login Success/)
  assert.doesNotMatch(pilotSources, /Select Livestream Type/)
})
