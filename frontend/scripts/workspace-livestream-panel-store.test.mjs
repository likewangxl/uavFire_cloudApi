import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const componentPath = new URL('../src/components/WorkspaceLivestreamPanel.vue', import.meta.url)
const source = readFileSync(componentPath, 'utf8')

test('workspace livestream panel uses injected store key helper', () => {
  assert.match(source, /useMyStore\(\)/)
  assert.doesNotMatch(source, /const store = useStore\(\)/)
})
