import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const __filename = fileURLToPath(import.meta.url)
const __dirname = dirname(__filename)
const root = join(__dirname, '..')
const captchaApiPath = join(root, 'src/api/captcha.ts')

test('src/api/captcha.ts exists', () => {
  assert.ok(existsSync(captchaApiPath))
})

test('captcha api exports getCaptcha calling GET /manage/api/v1/captcha', () => {
  const src = readFileSync(captchaApiPath, 'utf8')
  assert.match(src, /export\s+(const|async\s+function)\s+getCaptcha\b/)
  assert.match(src, /HTTP_PREFIX.*\/captcha|\/manage\/api\/v1\/captcha/)
  assert.match(src, /request\.get/)
})

test('captcha api exports demoLogin calling POST /manage/api/v1/demo-login', () => {
  const src = readFileSync(captchaApiPath, 'utf8')
  assert.match(src, /export\s+(const|async\s+function)\s+demoLogin\b/)
  assert.match(src, /HTTP_PREFIX.*\/demo-login|\/manage\/api\/v1\/demo-login/)
  assert.match(src, /request\.post/)
})
