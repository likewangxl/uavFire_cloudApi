import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { createHash } from 'node:crypto'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const source = fs.readFileSync(path.join(root, 'src/pages/page-web/login/LoginPage.vue'), 'utf8')
const imagePaths = [...new Set([...source.matchAll(/['"]\/@\/assets\/login\/([^'"]+\.(?:png|jpg|webp))['"]/g)].map(match => match[1]))]
assert.ok(imagePaths.length >= 2, 'Login background and aircraft images must be declared')
const assets = path.join(root, 'dist/assets')
const files = fs.readdirSync(assets)
const bundles = files.filter(name => name.endsWith('.js')).map(name => fs.readFileSync(path.join(assets, name), 'utf8')).join('\n')
assert.ok(!bundles.includes('/@/assets/login/'), 'Production login references an unprocessed source alias')
const hash = value => createHash('sha256').update(value).digest('hex')
const images = files.filter(name => /\.(png|jpg|webp)$/.test(name)).map(name => ({ name, hash: hash(fs.readFileSync(path.join(assets, name))) }))
for (const relative of imagePaths) {
  const expected = hash(fs.readFileSync(path.join(root, 'src/assets/login', relative)))
  const emitted = images.find(image => image.hash === expected)
  assert.ok(emitted, 'Login image missing or altered in build: ' + relative)
  assert.ok(bundles.includes('/assets/' + emitted.name), 'Built login does not reference emitted image: ' + relative)
  console.log('Login image verified: ' + emitted.name)
}
