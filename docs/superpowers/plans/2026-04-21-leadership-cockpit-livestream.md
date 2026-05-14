# Leadership Cockpit Livestream Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a tabbed livestream view to the leadership cockpit map card, reusing the existing HUD-enabled Agora panel and switching the bottom KPI row to livestream metrics when the livestream tab is active.

**Architecture:** Keep the cockpit card responsible for layout, title, tabs, and KPI switching. Refactor `WorkspaceLivestreamPanel.vue` into a reusable dark/cockpit-capable shared livestream surface that can emit summary state up to the cockpit page.

**Tech Stack:** Vue 3 SFC, TypeScript setup script, Ant Design Vue, existing Agora/WebRTC panel, node:test text-level regression scripts.

---

### Task 1: Lock the desired structure with failing tests

**Files:**
- Create: `frontend/scripts/leadership-cockpit-livestream.test.mjs`
- Modify: none
- Test: `frontend/scripts/leadership-cockpit-livestream.test.mjs`

- [ ] **Step 1: Write the failing test**

```js
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const cockpit = readFileSync(new URL('../src/pages/page-web/projects/leadership-cockpit.vue', import.meta.url), 'utf8')
const panel = readFileSync(new URL('../src/components/WorkspaceLivestreamPanel.vue', import.meta.url), 'utf8')

test('leadership cockpit references the shared livestream panel and tab state', () => {
  assert.match(cockpit, /WorkspaceLivestreamPanel/)
  assert.match(cockpit, /activeVisualTab/)
  assert.match(cockpit, /visualTabs/)
})

test('workspace livestream panel exposes cockpit reuse hooks', () => {
  assert.match(panel, /variant/)
  assert.match(panel, /showHeader/)
  assert.match(panel, /emit\\('state-change'/)
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node --test frontend/scripts/leadership-cockpit-livestream.test.mjs`

Expected: FAIL because the new tab state and panel reuse hooks do not exist yet.

- [ ] **Step 3: Implement the minimal code to make the test pass**

Add cockpit tab state in `leadership-cockpit.vue` and add reusable props / emits to `WorkspaceLivestreamPanel.vue`.

- [ ] **Step 4: Run test to verify it passes**

Run: `node --test frontend/scripts/leadership-cockpit-livestream.test.mjs`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add frontend/scripts/leadership-cockpit-livestream.test.mjs frontend/src/components/WorkspaceLivestreamPanel.vue frontend/src/pages/page-web/projects/leadership-cockpit.vue
git commit -m "feat: add cockpit livestream tab"
```

### Task 2: Refactor the shared livestream panel for cockpit reuse

**Files:**
- Modify: `frontend/src/components/WorkspaceLivestreamPanel.vue`
- Test: `frontend/scripts/leadership-cockpit-livestream.test.mjs`

- [ ] **Step 1: Add reusable props and emit contract**

Add props for `showHeader` and `variant`, and define an emitted `state-change` payload for parent consumption.

- [ ] **Step 2: Apply cockpit-aware styling**

Keep the existing workspace layout as default, then add a cockpit visual branch that removes the white card feel and allows the parent card to own the outer chrome.

- [ ] **Step 3: Emit livestream summary state**

Emit a summarized snapshot whenever playback/config/selection/HUD-derived values change.

- [ ] **Step 4: Re-run the targeted test**

Run: `node --test frontend/scripts/leadership-cockpit-livestream.test.mjs`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add frontend/src/components/WorkspaceLivestreamPanel.vue frontend/scripts/leadership-cockpit-livestream.test.mjs
git commit -m "refactor: reuse livestream panel in cockpit"
```

### Task 3: Add tab switching and live-mode KPI rendering to the cockpit page

**Files:**
- Modify: `frontend/src/pages/page-web/projects/leadership-cockpit.vue`
- Test: `frontend/scripts/leadership-cockpit-livestream.test.mjs`

- [ ] **Step 1: Add visual tab state and tab header UI**

Create `visualTabs`, `activeVisualTab`, and toggle buttons in the existing map card header.

- [ ] **Step 2: Preserve the map stage and add livestream stage**

Render the current map stage when the map tab is active; render `WorkspaceLivestreamPanel` when the livestream tab is active.

- [ ] **Step 3: Split KPI data by visual mode**

Keep the existing map KPI list, add a livestream KPI list derived from child state, and switch the rendered collection based on the active tab.

- [ ] **Step 4: Re-run the targeted test**

Run: `node --test frontend/scripts/leadership-cockpit-livestream.test.mjs`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/page-web/projects/leadership-cockpit.vue frontend/scripts/leadership-cockpit-livestream.test.mjs
git commit -m "feat: integrate livestream into leadership cockpit"
```

### Task 4: Verify build and regressions

**Files:**
- Modify: none
- Test: `frontend/scripts/leadership-cockpit-livestream.test.mjs`

- [ ] **Step 1: Run the targeted regression test**

Run: `node --test frontend/scripts/leadership-cockpit-livestream.test.mjs`

Expected: PASS

- [ ] **Step 2: Run an existing nearby regression test**

Run: `node --test frontend/scripts/workspace-livestream-panel-store.test.mjs`

Expected: PASS

- [ ] **Step 3: Run the frontend build**

Run: `npm --prefix frontend run build`

Expected: build succeeds; existing Sass / `::v-deep` warnings may remain.

- [ ] **Step 4: Commit**

```bash
git add docs/superpowers/specs/2026-04-21-leadership-cockpit-livestream-design.md docs/superpowers/plans/2026-04-21-leadership-cockpit-livestream.md
git commit -m "docs: capture cockpit livestream integration plan"
```
