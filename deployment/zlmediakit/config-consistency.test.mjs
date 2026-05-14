import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const configPath = new URL('./config/config.ini', import.meta.url)
const composePath = new URL('./docker-compose.yml', import.meta.url)
const envPath = new URL('./.env', import.meta.url)

const configSource = readFileSync(configPath, 'utf8')
const composeSource = readFileSync(composePath, 'utf8')
const envSource = readFileSync(envPath, 'utf8')
const rtcSectionMatch = configSource.match(/\[rtc\]([\s\S]*?)\n\[/)

assert.ok(rtcSectionMatch, 'missing [rtc] section')
const rtcSection = rtcSectionMatch[1]

function matchValue(source, pattern, label) {
  const match = source.match(pattern)
  assert.ok(match, `missing ${label}`)
  return match[1].trim()
}

test('ZLMediaKit rtc externIP is configured to a player-visible host', () => {
  const externIp = matchValue(rtcSection, /^externIP=(.*)$/m, 'rtc.externIP')
  assert.notEqual(externIp, '', 'rtc.externIP must not be blank for WebRTC playback')
})

test('docker compose exposes the configured rtc tcp and udp port', () => {
  const rtcPort = matchValue(rtcSection, /^port=(\d+)$/m, 'rtc.port')
  const rtcTcpPort = matchValue(rtcSection, /^tcpPort=(\d+)$/m, 'rtc.tcpPort')
  const hostUdpPort = matchValue(envSource, /^ZLM_STUN_UDP_PORT=(\d+)$/m, 'ZLM_STUN_UDP_PORT')
  const hostTcpPort = matchValue(envSource, /^ZLM_WEBRTC_TCP_PORT=(\d+)$/m, 'ZLM_WEBRTC_TCP_PORT')

  assert.equal(hostUdpPort, rtcPort, 'host UDP port must match rtc.port')
  assert.equal(hostTcpPort, rtcTcpPort, 'host TCP port must match rtc.tcpPort')
  assert.match(composeSource, new RegExp(`\\$\\{ZLM_WEBRTC_TCP_PORT:-${hostTcpPort}\\}:${rtcTcpPort}`))
  assert.match(composeSource, new RegExp(`\\$\\{ZLM_STUN_UDP_PORT:-${hostUdpPort}\\}:${rtcPort}/udp`))
})
