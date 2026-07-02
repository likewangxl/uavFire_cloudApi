import { readdirSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'
import { spawnSync } from 'node:child_process'

const root = process.cwd()
const testRoot = join(root, 'src', 'pages', 'page-web', 'projects')

function collectTests (dir, out = []) {
  for (const name of readdirSync(dir)) {
    const full = join(dir, name)
    const stat = statSync(full)
    if (stat.isDirectory()) {
      collectTests(full, out)
    } else if (name.endsWith('.test.mjs')) {
      out.push(relative(root, full))
    }
  }
  return out
}

const files = collectTests(testRoot).sort()
const result = spawnSync(process.execPath, ['--test', ...files], {
  cwd: root,
  stdio: 'inherit',
  shell: false,
})

process.exit(result.status ?? 1)
