import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../../..')

function readSource (path) {
  return readFileSync(resolve(root, path), 'utf8')
}

function cssBlock (source, selector) {
  const pattern = new RegExp(`${selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*\\{([\\s\\S]*?)\\n\\}`, 'm')
  const match = source.match(pattern)
  assert.ok(match, `expected ${selector} styles to exist`)
  return match[1]
}

test('aircraft stream menu opens into the cockpit panel instead of spilling left', () => {
  const source = readSource('src/components/cockpit/CockpitAircraftStreamSelector.vue')
  const menuStyles = cssBlock(source, '.stream-target-menu')

  assert.match(menuStyles, /left:\s*0;/, 'menu should align to the trigger left edge')
  assert.match(menuStyles, /right:\s*auto;/, 'menu should not expand left from the trigger right edge')
  assert.match(menuStyles, /overflow:\s*hidden auto;/, 'menu should suppress horizontal spill while preserving vertical scroll')
})
