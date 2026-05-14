# Leadership Cockpit Live Panel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the cockpit live tab's cluttered dual-stream layout with a large primary video, a top-right preview, and click-to-swap behavior while keeping the existing cockpit page shell.

**Architecture:** Keep `leadership-cockpit.vue` as the integration point, but move stream-role selection into a tiny pure helper so the swap rules are testable outside the Vue file. Reuse the existing dual-stream polling and ZLMediaKit player mounting path, but support two player shells and a lightweight HUD instead of the current status-card grid.

**Tech Stack:** Vue 3 SFC, TypeScript setup script, existing ZLMediaKit WebRTC client loading, Node built-in `node:test`, Vite build.

---

### Task 1: Lock Down Swap Rules With a Failing Test

**Files:**
- Create: `frontend/src/pages/page-web/projects/__tests__/leadership-cockpit-live-layout.test.mjs`
- Create: `frontend/src/pages/page-web/projects/leadership-cockpit-live-layout.mjs`

- [ ] **Step 1: Write the failing test**

```js
import test from 'node:test'
import assert from 'node:assert/strict'
import { buildLivePaneState } from '../leadership-cockpit-live-layout.mjs'

test('defaults to visible primary and thermal preview', () => {
  const state = buildLivePaneState({
    visiblePlayUrl: 'webrtc://visible',
    thermalPlayUrl: 'webrtc://thermal',
    primaryPreference: 'visible'
  })

  assert.equal(state.primary.kind, 'visible')
  assert.equal(state.preview.kind, 'thermal')
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node --test frontend/src/pages/page-web/projects/__tests__/leadership-cockpit-live-layout.test.mjs`

Expected: FAIL with module/function missing.

- [ ] **Step 3: Write minimal implementation**

Implement:
- `buildLivePaneState(...)`
- `swapPrimaryPreference(...)`

Rules:
- visible defaults to primary
- thermal moves to preview when available
- clicking preview swaps preferences
- if thermal URL is absent, preview becomes a placeholder

- [ ] **Step 4: Run test to verify it passes**

Run: `node --test frontend/src/pages/page-web/projects/__tests__/leadership-cockpit-live-layout.test.mjs`

Expected: PASS

### Task 2: Rebuild Cockpit Live Panel Body

**Files:**
- Modify: `frontend/src/pages/page-web/projects/leadership-cockpit.vue`
- Reuse: `frontend/src/pages/page-web/projects/leadership-cockpit-live-layout.mjs`

- [ ] **Step 1: Add a second player shell and stream-role state**

Add:
- `thermalPlayerShell`
- `thermalPlayerState`
- `primaryPreference`
- computed pane state from `buildLivePaneState`

- [ ] **Step 2: Replace current live panel markup**

Render:
- one large primary stage
- one top-right preview window
- compact HUD / badges only
- placeholder preview when thermal is unavailable

- [ ] **Step 3: Implement preview click-to-swap**

Hook preview click to `swapPrimaryPreference(...)`.

- [ ] **Step 4: Mount/destroy players according to current pane roles**

Ensure:
- both visible and thermal can mount when URLs exist
- swapping only changes pane assignment
- leaving live tab destroys both players

### Task 3: Style and Verify

**Files:**
- Modify: `frontend/src/pages/page-web/projects/leadership-cockpit.vue`

- [ ] **Step 1: Replace old dual-stream card-grid styles**

Style goals:
- keep current cockpit center panel footprint
- large main video region
- preview pinned top-right
- minimal overlay badges
- no bulky internal status-card layout

- [ ] **Step 2: Run targeted tests**

Run: `node --test frontend/src/pages/page-web/projects/__tests__/leadership-cockpit-live-layout.test.mjs`

Expected: PASS

- [ ] **Step 3: Run frontend build**

Run: `cd frontend && npm run build`

Expected: build succeeds; existing Sass deprecation warnings may remain.
