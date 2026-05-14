import test from 'node:test'
import assert from 'node:assert/strict'
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'

const frontendRoot = new URL('..', import.meta.url).pathname
const repoRoot = new URL('../..', import.meta.url).pathname
const srcRoot = join(frontendRoot, 'src')
const packageJsonPath = join(frontendRoot, 'package.json')
const pilotLivesharePath = join(srcRoot, 'pages/page-pilot/pilot-liveshare.vue')
const configPath = join(srcRoot, 'api/http/config.ts')
const backendSampleRoot = join(repoRoot, 'backend/uavfire')
const backendApplicationPath = join(backendSampleRoot, 'src/main/resources/application.yml')

function collectSourceFiles (dir) {
  const entries = readdirSync(dir)
  const files = []
  for (const entry of entries) {
    const fullPath = join(dir, entry)
    const stat = statSync(fullPath)
    if (stat.isDirectory()) {
      files.push(...collectSourceFiles(fullPath))
    } else if (/\.(vue|ts|js|mjs)$/.test(entry)) {
      files.push(fullPath)
    }
  }
  return files
}

test('browser runtime no longer imports the deprecated Agora SDK or legacy player', () => {
  const offenders = []
  for (const filePath of collectSourceFiles(srcRoot)) {
    const source = readFileSync(filePath, 'utf8')
    if (/agora-rtc-sdk-ng|livestream-agora\.vue/.test(source)) {
      offenders.push(relative(frontendRoot, filePath))
    }
  }

  assert.deepEqual(offenders, [])
})

test('frontend source no longer exposes Agora as a selectable or fallback live mode', () => {
  const offenders = []
  for (const filePath of collectSourceFiles(srcRoot)) {
    const source = readFileSync(filePath, 'utf8')
    if (/\bAgora\b|\bagora\b/.test(source)) {
      offenders.push(relative(frontendRoot, filePath))
    }
  }

  assert.deepEqual(offenders, [])
})

test('Vite optimizeDeps no longer pre-bundles Agora SDK', () => {
  const packageJson = JSON.parse(readFileSync(packageJsonPath, 'utf8'))
  const optimizeDeps = packageJson.vite?.optimizeDeps?.include || []

  assert.equal(optimizeDeps.includes('agora-rtc-sdk-ng'), false)
})

test('Pilot manual live-share page no longer exposes Agora configuration', () => {
  const source = readFileSync(pilotLivesharePath, 'utf8')

  assert.doesNotMatch(source, /ELiveTypeValue\.Agora/)
  assert.doesNotMatch(source, /ELiveTypeName\.Agora/)
  assert.doesNotMatch(source, /agoraParam/)
})

test('frontend HTTP config no longer contains Agora credentials placeholders', () => {
  const source = readFileSync(configPath, 'utf8')

  assert.doesNotMatch(source, /agoraAPPID|agoraToken|agoraChannel/)
})

test('backend sample livestream entrypoint rejects Agora instead of configuring it', () => {
  const serviceSource = readFileSync(join(backendSampleRoot, 'src/main/java/com/yx/uavfire/manage/service/impl/LiveStreamServiceImpl.java'), 'utf8')
  const propertySource = readFileSync(join(backendSampleRoot, 'src/main/java/com/yx/uavfire/manage/model/dto/LiveStreamProperty.java'), 'utf8')
  const applicationSource = readFileSync(backendApplicationPath, 'utf8')

  assert.doesNotMatch(serviceSource, /case AGORA|LivestreamAgoraUrl|AGORA_UID_ANY/)
  assert.doesNotMatch(propertySource, /case AGORA|LivestreamAgoraUrl|setAgora|private static .*agora/)
  assert.doesNotMatch(applicationSource, /^\s+agora:\s*$/m)
})
