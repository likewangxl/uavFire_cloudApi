import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const tsaVue = readFileSync(new URL('../src/pages/page-web/projects/tsa.vue', import.meta.url), 'utf8')

function extractFunctionBody (source, functionName) {
  const signatures = [`async function ${functionName}`, `function ${functionName}`]
  const start = signatures
    .map((signature) => ({ signature, index: source.indexOf(signature) }))
    .find(({ index }) => index >= 0)
  assert.ok(start, `expected to find function ${functionName}`)

  const braceStart = source.indexOf('{', start.index)
  assert.ok(braceStart >= 0, `expected ${functionName} to have a function body`)

  let depth = 0
  for (let index = braceStart; index < source.length; index += 1) {
    const char = source[index]
    if (char === '{') depth += 1
    if (char === '}') depth -= 1
    if (depth === 0) {
      return source.slice(start.index, index + 1)
    }
  }

  assert.fail(`unterminated function body for ${functionName}`)
}

function extractCallObject (source, functionName, calleeName) {
  const functionBody = extractFunctionBody(source, functionName)
  const callStart = functionBody.indexOf(`${calleeName}({`)
  assert.ok(callStart >= 0, `expected ${functionName} to call ${calleeName}`)

  const objectStart = functionBody.indexOf('{', callStart)
  assert.ok(objectStart >= 0, `expected ${functionName} ${calleeName} call to include an options object`)

  let depth = 0
  for (let index = objectStart; index < functionBody.length; index += 1) {
    const char = functionBody[index]
    if (char === '{') depth += 1
    if (char === '}') depth -= 1
    if (depth === 0) {
      return functionBody.slice(callStart, index + 1)
    }
  }

  assert.fail(`unterminated ${calleeName} options object for ${functionName}`)
}

test('official takeoff confirmation uses a non-blocking modal instead of window.confirm', () => {
  const handleTakeoffBody = extractFunctionBody(tsaVue, 'handleTakeoff')
  const confirmObject = extractCallObject(tsaVue, 'handleTakeoff', 'confirmTsaModal')

  assert.doesNotMatch(handleTakeoffBody, /window\.confirm\(/, 'handleTakeoff should not block the browser main thread')
  assert.match(confirmObject, /action:\s*['"]takeoff['"]/, 'handleTakeoff should use the takeoff modal action guard')
  assert.match(confirmObject, /subtitle:/, 'handleTakeoff should provide structured modal metadata')
  assert.match(
    tsaVue,
    /Modal\.confirm\(/,
    'confirmTsaModal should use Ant Design modal confirmation'
  )
  assert.match(
    tsaVue,
    /className:\s*`tsa-modal-skin tsa-modal-skin--\$\{options\.tone \?\? 'confirm'\}`/,
    'confirmTsaModal should opt into the TSA modal skin contract'
  )
})

test('official takeoff keeps the original 30m command height contract', () => {
  assert.match(
    tsaVue,
    /OFFICIAL_TAKEOFF_TARGET_HEIGHT/,
    'takeoff should wire the restored 50m climb contract into TSA'
  )
  assert.match(
    extractCallObject(tsaVue, 'handleTakeoff', 'confirmTsaModal'),
    /第二阶段：先自动向南约 200 米，再自动向北约 200 米返回/,
    'takeoff confirmation should describe the south-then-north second stage'
  )
  assert.match(
    extractFunctionBody(tsaVue, 'handleTakeoff'),
    /postTakeoffToPoint\(/,
    'takeoff should send takeoff_to_point for stage 1'
  )
  assert.match(
    extractFunctionBody(tsaVue, 'handleTakeoff'),
    /target_height:\s*plan\.stage1\.targetHeight/,
    'takeoff should send the absolute takeoff target height from the official plan'
  )
  assert.match(
    tsaVue,
    /EBizCode\.TakeoffToPointProgress/,
    'tsa should advance the official takeoff flow from takeoff_to_point progress events'
  )
  assert.doesNotMatch(
    extractFunctionBody(tsaVue, 'handleTakeoff'),
    /handleKeyup\(KeyCode\.ARROW_UP\)/,
    'takeoff should no longer start stage 1 with a DRC stick climb'
  )
})

test('official takeoff waits through the stage-2 stabilization window before fly_to_point', () => {
  assert.match(
    tsaVue,
    /OFFICIAL_TAKEOFF_STAGE2_STABILIZATION_MS/,
    'tsa should import the official stage-2 stabilization delay'
  )
  assert.match(
    tsaVue,
    /officialTakeoffFlow\.phase = 'arming_stage2_south'/,
    'tsa should mark the handoff window before dispatching stage 2'
  )
  assert.match(
    tsaVue,
    /window\.setTimeout\(\(\) => \{/,
    'tsa should dispatch stage 2 asynchronously after the handoff window'
  )
  assert.match(
    tsaVue,
    /OFFICIAL_TAKEOFF_STAGE2_STABILIZATION_MS/,
    'tsa should use the fixed official handoff delay before fly_to_point'
  )
})
