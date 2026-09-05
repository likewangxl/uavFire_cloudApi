import assert from 'node:assert/strict'
import fs from 'node:fs'
import test from 'node:test'

const indexHtml = fs.readFileSync(new URL('../index.html', import.meta.url), 'utf8')
const configSource = fs.readFileSync(new URL('../src/api/http/config.ts', import.meta.url), 'utf8')

test('runtime deployment config loads before the application bundle', () => {
  assert.ok(indexHtml.indexOf('/runtime-config.js') < indexHtml.indexOf('/src/main.ts'))
  assert.match(configSource, /runtimeConfig\.backendHost \|\| import\.meta\.env\.VITE_APP_APIGATEWAY_BACKEND_HOST/)
  assert.match(configSource, /runtimeConfig\.websocketHost \|\| import\.meta\.env\.VITE_APP_APIGATEWAY_WEBSOCKET_HOST/)
  assert.match(configSource, /runtimeConfig\.rtmpUrl \|\| import\.meta\.env\.VITE_APP_LIVESTREAM_RTMP_URL/)
})
