import test from 'node:test'
import assert from 'node:assert/strict'
import { existsSync, readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const __filename = fileURLToPath(import.meta.url)
const __dirname = dirname(__filename)
const root = join(__dirname, '..')
const p = (rel) => join(root, rel)

test('login files all exist', () => {
  for (const rel of [
    'src/api/captcha.ts',
    'src/pages/page-web/index.vue',
    'src/pages/page-web/login/LoginPage.vue',
    'src/pages/page-web/login/components/LoginForm.vue',
    'src/pages/page-web/login/components/CaptchaImage.vue',
    'src/pages/page-web/login/components/ForgotPasswordModal.vue',
    'src/pages/page-web/login/composables/use-login.ts',
    'src/assets/login-bg.png',
  ]) {
    assert.ok(existsSync(p(rel)), `missing ${rel}`)
  }
})

test('LoginBody interface includes captcha + captcha_token (snake_case)', () => {
  const src = readFileSync(p('src/api/manage.ts'), 'utf8')
  assert.match(src, /captcha:\s*string/)
  assert.match(src, /captcha_token:\s*string/)
})

test('page-web/index.vue is collapsed to <LoginPage />', () => {
  const src = readFileSync(p('src/pages/page-web/index.vue'), 'utf8')
  assert.match(src, /<LoginPage\s*\/>/)
  assert.doesNotMatch(src, /<a-input\s+v-model:value="formState\.username"/)
})

test('LoginForm has both 登录系统 and 演示模式 buttons', () => {
  const src = readFileSync(p('src/pages/page-web/login/components/LoginForm.vue'), 'utf8')
  assert.match(src, /登录系统/)
  assert.match(src, /演示模式/)
})

test('use-login persists remember_username only when remember is true', () => {
  const src = readFileSync(p('src/pages/page-web/login/composables/use-login.ts'), 'utf8')
  assert.match(src, /RememberUsername/)
  assert.match(src, /removeItem.*RememberUsername/)
})

test('use-login refreshes captcha in finally', () => {
  const src = readFileSync(p('src/pages/page-web/login/composables/use-login.ts'), 'utf8')
  assert.match(src, /finally\s*\{[\s\S]*?refreshCaptcha/)
})
