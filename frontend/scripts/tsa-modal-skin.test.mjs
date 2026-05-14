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

test('takeoff confirmation uses the TSA modal skin contract', () => {
  const confirmObject = extractCallObject(tsaVue, 'handleTakeoff', 'confirmTsaModal')

  assert.match(
    confirmObject,
    /action:\s*['"]takeoff['"]/,
    'takeoff confirmation should register the takeoff action guard'
  )
  assert.match(confirmObject, /tone:\s*['"]confirm['"]/, 'takeoff confirmation should request the confirm tone')
  assert.match(confirmObject, /summary:/, 'takeoff confirmation should provide structured summary content')
  assert.match(confirmObject, /notice:/, 'takeoff confirmation should provide structured notice content')
  assert.match(confirmObject, /warning:/, 'takeoff confirmation should provide structured warning content')
  assert.match(
    tsaVue,
    /className:\s*`tsa-modal-skin tsa-modal-skin--\$\{options\.tone \?\? 'confirm'\}`/,
    'confirm helper should apply the TSA modal skin class'
  )
})

test('aircraft action dialogs no longer use legacy window.confirm', () => {
  const aircraftActionDialogs = [
    'handleLanding',
    'handleReturnHome',
    'handleCancelReturnHome',
    'handleFlyForwardTest',
  ]

  for (const functionName of aircraftActionDialogs) {
    const functionBody = extractFunctionBody(tsaVue, functionName)
    assert.doesNotMatch(
      functionBody,
      /window\.confirm\(/,
      `${functionName} should not block the browser with window.confirm`
    )
  }
})
