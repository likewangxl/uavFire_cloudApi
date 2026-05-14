import test from 'node:test'
import assert from 'node:assert/strict'
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'

const frontendRoot = new URL('..', import.meta.url).pathname
const srcRoot = join(frontendRoot, 'src')
const viteConfigPath = join(frontendRoot, 'vite.config.ts')
const eslintConfigPath = join(frontendRoot, '.eslintrc.js')
const scriptSetupMacros = ['defineProps', 'defineEmits', 'withDefaults', 'defineExpose']

function collectFiles (dir, matcher) {
  const entries = readdirSync(dir)
  const files = []

  for (const entry of entries) {
    const fullPath = join(dir, entry)
    const stat = statSync(fullPath)

    if (stat.isDirectory()) {
      files.push(...collectFiles(fullPath, matcher))
    } else if (matcher(entry)) {
      files.push(fullPath)
    }
  }

  return files
}

function stripLineComments (source) {
  return source
    .split('\n')
    .filter(line => !line.trim().startsWith('//'))
    .join('\n')
}

test('script setup macros rely on compiler injection instead of importing from vue', () => {
  const offenders = []

  for (const filePath of collectFiles(srcRoot, name => /\.vue$/.test(name))) {
    const source = readFileSync(filePath, 'utf8')
    const importMatches = [...source.matchAll(/import\s*\{([^}]+)\}\s*from\s*['"]vue['"]/g)]
    const importedMacros = importMatches
      .flatMap(match => match[1].split(',').map(item => item.trim().split(/\s+as\s+/)[0]))
      .filter(item => scriptSetupMacros.includes(item))

    if (importedMacros.length > 0) {
      offenders.push(`${relative(frontendRoot, filePath)}: ${importedMacros.join(', ')}`)
    }
  }

  assert.deepEqual(offenders, [])
})

test('scoped styles use :deep() instead of deprecated ::v-deep', () => {
  const offenders = []

  for (const filePath of collectFiles(srcRoot, name => /\.(vue|scss)$/.test(name))) {
    const source = readFileSync(filePath, 'utf8')
    if (/::v-deep/.test(source)) {
      offenders.push(relative(frontendRoot, filePath))
    }
  }

  assert.deepEqual(offenders, [])
})

test('Sass styles use @use instead of deprecated @import', () => {
  const offenders = []

  for (const filePath of [...collectFiles(srcRoot, name => /\.(vue|scss)$/.test(name)), viteConfigPath]) {
    const source = stripLineComments(readFileSync(filePath, 'utf8'))
    if (/@import\s+['"`]/.test(source)) {
      offenders.push(relative(frontendRoot, filePath))
    }
  }

  assert.deepEqual(offenders, [])
})

test('Vite build has explicit manual chunks for large dependencies', () => {
  const source = readFileSync(viteConfigPath, 'utf8')

  assert.match(source, /manualChunks/)
  assert.match(source, /ant-design-vue/)
  assert.match(source, /mqtt/)
})

test('vConsole is kept out of non-serve builds', () => {
  const source = readFileSync(viteConfigPath, 'utf8')

  assert.match(source, /command === 'serve'/)
  assert.doesNotMatch(source, /localEnabled:\s*command === 'serve'/)
})

test('ESLint treats Vue script setup compiler macros as globals', () => {
  const source = readFileSync(eslintConfigPath, 'utf8')

  for (const macro of scriptSetupMacros) {
    assert.match(source, new RegExp(`${macro}: 'readonly'`))
  }
})
