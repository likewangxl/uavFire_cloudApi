# TSA Universal Modal Skin Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give TSA page dialogs a reusable light-console modal skin while keeping Ant Design Vue `Modal.*` APIs and non-blocking behavior.

**Architecture:** Keep dialog invocation on native `Modal.confirm/info/warning/success/error`, but standardize their rendered DOM and attach a shared `className` skin plus content block classes. Implement the skin in `tsa.vue` styles, then migrate the highest-value confirmations to the new structured content so all dialogs share one visual language.

**Tech Stack:** Vue 3, Ant Design Vue Modal static methods, TypeScript in `tsa.vue`, Node test runner, Vite build

---

### Task 1: Lock in the modal skin contract with failing tests

**Files:**
- Create: `frontend/scripts/tsa-modal-skin.test.mjs`
- Modify: `frontend/scripts/official-takeoff-confirmation.test.mjs`
- Test: `frontend/scripts/tsa-modal-skin.test.mjs`

- [ ] **Step 1: Write the failing test for shared skin usage**

```js
import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'

const tsaPath = path.resolve('frontend/src/pages/page-web/projects/tsa.vue')
const source = fs.readFileSync(tsaPath, 'utf8')

test('takeoff confirmation uses TSA modal skin and structured blocks', () => {
  assert.match(source, /Modal\.confirm\(\{\s*className:\s*['"]tsa-modal-skin tsa-modal-skin--confirm['"]/s)
  assert.match(source, /tsa-modal-summary/s)
  assert.match(source, /tsa-modal-notice/s)
  assert.match(source, /tsa-modal-warning/s)
})

test('legacy window.confirm calls are removed from aircraft action dialogs', () => {
  assert.doesNotMatch(source, /window\.confirm\(/)
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node --test frontend/scripts/tsa-modal-skin.test.mjs frontend/scripts/official-takeoff-confirmation.test.mjs`

Expected: FAIL because `tsa.vue` still contains `window.confirm(...)` and does not yet attach the shared modal skin to all action dialogs.

- [ ] **Step 3: Tighten the existing confirmation regression to enforce the new contract**

```js
test('handleTakeoff uses non-blocking TSA-skinned Modal.confirm', () => {
  const handleTakeoffBlock = source.match(/async function handleTakeoff[\s\S]*?async function handleLanding/)
  assert.ok(handleTakeoffBlock, 'expected to find handleTakeoff block')
  assert.doesNotMatch(handleTakeoffBlock[0], /window\.confirm\(/)
  assert.match(handleTakeoffBlock[0], /Modal\.confirm\(/)
  assert.match(handleTakeoffBlock[0], /className:\s*['"]tsa-modal-skin tsa-modal-skin--confirm['"]/)
})
```

- [ ] **Step 4: Run tests again to confirm the red state is still valid**

Run: `node --test frontend/scripts/tsa-modal-skin.test.mjs frontend/scripts/official-takeoff-confirmation.test.mjs`

Expected: FAIL only because implementation is still missing, not because of syntax or broken test setup.

- [ ] **Step 5: Commit**

```bash
git add frontend/scripts/tsa-modal-skin.test.mjs frontend/scripts/official-takeoff-confirmation.test.mjs
git commit -m "test: define tsa modal skin contract"
```

### Task 2: Implement the shared light-console modal skin and content helpers

**Files:**
- Modify: `frontend/src/pages/page-web/projects/tsa.vue`
- Test: `frontend/scripts/tsa-modal-skin.test.mjs`

- [ ] **Step 1: Add minimal helper functions that render structured modal content**

```ts
function renderModalSummaryItem (label: string, value: string) {
  return h('div', { class: 'tsa-modal-summary-item' }, [
    h('div', { class: 'tsa-modal-summary-label' }, label),
    h('div', { class: 'tsa-modal-summary-value' }, value),
  ])
}

function renderModalSection (className: string, children: Array<string | ReturnType<typeof h>>) {
  return h('div', { class: className }, children.map((child) => (
    typeof child === 'string' ? h('p', child) : child
  )))
}
```

- [ ] **Step 2: Implement the shared modal skin CSS at the end of `tsa.vue`**

```scss
:deep(.tsa-modal-skin .ant-modal-content) {
  border-radius: 18px;
  border: 1px solid #d7e1ec;
  background: linear-gradient(180deg, #fcfdff 0%, #f4f7fb 100%);
  box-shadow: 0 22px 55px rgba(15, 23, 42, 0.16);
  overflow: hidden;
}

:deep(.tsa-modal-skin .ant-modal-confirm-title) {
  color: #152334;
  font-size: 22px;
  font-weight: 700;
}

:deep(.tsa-modal-summary) {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}
```

- [ ] **Step 3: Run the contract tests to verify the CSS / helper names are now present**

Run: `node --test frontend/scripts/tsa-modal-skin.test.mjs`

Expected: still FAIL because action dialogs have not yet all migrated off `window.confirm(...)`.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/pages/page-web/projects/tsa.vue
git commit -m "feat: add tsa modal skin helpers"
```

### Task 3: Migrate the highest-value confirmations to the shared skin

**Files:**
- Modify: `frontend/src/pages/page-web/projects/tsa.vue`
- Test: `frontend/scripts/tsa-modal-skin.test.mjs`

- [ ] **Step 1: Replace landing, return-home, cancel-return-home, and stop confirmations with non-blocking `Modal.confirm`**

```ts
async function confirmModal (options: {
  title: string
  subtitle?: string
  summary?: Array<{ label: string, value: string }>
  notice?: string[]
  warning?: string[]
  okText?: string
  cancelText?: string
  tone?: 'confirm' | 'warning' | 'danger'
}) {
  return await new Promise<boolean>((resolve) => {
    Modal.confirm({
      className: `tsa-modal-skin tsa-modal-skin--${options.tone ?? 'confirm'}`,
      width: 720,
      title: options.title,
      content: renderTsaModalContent(options),
      okText: options.okText ?? '确定',
      cancelText: options.cancelText ?? '取消',
      onOk: () => resolve(true),
      onCancel: () => resolve(false),
    })
  })
}
```

- [ ] **Step 2: Rebuild the takeoff confirmation content using summary, notice, and warning blocks**

```ts
const confirmed = await confirmModal({
  title: `确认执行 DRC 爬升起飞`,
  subtitle: `${device.callsign} · 当前 DRC 会话保持中`,
  summary: [
    { label: '当前位置', value: `${latitude}, ${longitude}` },
    { label: '目标高度', value: `${OFFICIAL_TAKEOFF_TARGET_HEIGHT} m` },
  ],
  notice: [
    `第一阶段：保持当前 DRC 会话，原地爬升到 ${OFFICIAL_TAKEOFF_TARGET_HEIGHT} 米`,
    `第二阶段：先自动向南约 200 米，再自动向北约 200 米返回，保持 ${OFFICIAL_TAKEOFF_TARGET_HEIGHT} 米高度`,
  ],
  warning: [
    `高度门槛：达到约 ${OFFICIAL_TAKEOFF_ALTITUDE_READY_THRESHOLD} 米后自动发送第二阶段`,
    `该命令不会调用 takeoff_to_point，而是使用当前 DRC 链路完成第一阶段爬升。`,
  ],
  okText: '开始起飞',
})
```

- [ ] **Step 3: Run tests to verify all action confirmations are now non-blocking and skinned**

Run: `node --test frontend/scripts/tsa-modal-skin.test.mjs frontend/scripts/official-takeoff-confirmation.test.mjs frontend/scripts/official-takeoff-stage1-policy.test.mjs frontend/scripts/official-takeoff-session-policy.test.mjs`

Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add frontend/src/pages/page-web/projects/tsa.vue frontend/scripts/tsa-modal-skin.test.mjs frontend/scripts/official-takeoff-confirmation.test.mjs
git commit -m "feat: skin tsa modal confirmations"
```

### Task 4: Verify UI integration and build health

**Files:**
- Modify: `frontend/src/pages/page-web/projects/tsa.vue` (only if fixes are needed)
- Test: `frontend/scripts/tsa-modal-skin.test.mjs`

- [ ] **Step 1: Run focused tests**

Run: `node --test frontend/scripts/tsa-modal-skin.test.mjs frontend/scripts/official-takeoff-confirmation.test.mjs frontend/scripts/official-takeoff-stage1-policy.test.mjs frontend/scripts/official-takeoff-session-policy.test.mjs`

Expected: PASS

- [ ] **Step 2: Run lint on the changed page**

Run: `npx eslint frontend/src/pages/page-web/projects/tsa.vue`

Expected: PASS

- [ ] **Step 3: Run the frontend build**

Run: `npm --prefix frontend run build`

Expected: PASS with at most the repo’s pre-existing Sass / Vue warnings.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/pages/page-web/projects/tsa.vue frontend/scripts/tsa-modal-skin.test.mjs frontend/scripts/official-takeoff-confirmation.test.mjs
git commit -m "chore: verify tsa modal skin rollout"
```

