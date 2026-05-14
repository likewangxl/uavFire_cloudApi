# AI Risk Event Panel Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show recent AI fire-risk recognition events in the existing leadership cockpit so operators can see suspected-risk records from the web UI.

**Architecture:** Reuse the existing backend event endpoint `GET /manage/api/v1/dual-stream/tasks/{task_id}/events`. Add a typed frontend API helper, then add a compact polling panel to `leadership-cockpit.vue` that displays recent event time, channel, risk level, scores, and review status. Keep this version list-based; bounding boxes and snapshots require new AI payload fields and are outside this first pass.

**Tech Stack:** Vue 3 Composition API, existing `request` wrapper, Node script tests, Vite staging build.

---

### Task 1: Add Frontend API Helper

**Files:**
- Modify: `frontend/src/api/manage.ts`
- Test: `frontend/scripts/leadership-cockpit-ai-events.test.mjs`

- [ ] **Step 1: Write the failing API test**

Create `frontend/scripts/leadership-cockpit-ai-events.test.mjs` with assertions that `manage.ts` exports `DualStreamEvent` and `getDualStreamTaskEvents`, and that the helper calls `/dual-stream/tasks/${taskId}/events`.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && node scripts/leadership-cockpit-ai-events.test.mjs`

Expected: FAIL because `getDualStreamTaskEvents` does not exist.

- [ ] **Step 3: Implement the API helper**

In `frontend/src/api/manage.ts`, add:

```ts
export interface DualStreamEvent {
  taskId?: string
  droneSn?: string
  sourceTs?: number
  visibleScore?: number
  thermalScore?: number
  fusionScore?: number
  riskLevel?: string
  analysisChannel?: string
  reviewStatus?: string
}

export const getDualStreamTaskEvents = async function (taskId: string): Promise<IWorkspaceResponse<DualStreamEvent[]>> {
  const url = `${HTTP_PREFIX}/dual-stream/tasks/${taskId}/events`
  const result = await request.get(url)
  return result.data
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && node scripts/leadership-cockpit-ai-events.test.mjs`

Expected: PASS.

### Task 2: Add Cockpit Event Panel

**Files:**
- Modify: `frontend/src/pages/page-web/projects/leadership-cockpit.vue`
- Test: `frontend/scripts/leadership-cockpit-ai-events.test.mjs`

- [ ] **Step 1: Extend the failing UI test**

Update `frontend/scripts/leadership-cockpit-ai-events.test.mjs` to assert that the cockpit page imports `getDualStreamTaskEvents`, defines an AI event polling timer, renders `AI 风险识别记录`, shows score labels, and highlights `VISIBLE_SUSPECTED`, `THERMAL_CONFIRMED`, and `THERMAL_REJECTED`.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && node scripts/leadership-cockpit-ai-events.test.mjs`

Expected: FAIL because the cockpit page has no AI event panel.

- [ ] **Step 3: Implement the panel**

In `leadership-cockpit.vue`:

- Import `DualStreamEvent` and `getDualStreamTaskEvents`.
- Add `AI_EVENT_TASK_ID = 'manual-ai-001'` as the initial task id.
- Add reactive state for loading, error, and events.
- Poll events every 2 seconds while the component is mounted.
- Render a compact panel below the live/map visual area with the latest 10 records.
- Show a placeholder when no events exist.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && node scripts/leadership-cockpit-ai-events.test.mjs`

Expected: PASS.

### Task 3: Verify Existing Livestream Tests and Build

**Files:**
- Test: `frontend/scripts/leadership-cockpit-livestream.test.mjs`
- Test: `frontend/scripts/dual-stream-group-normalizer.test.mjs`

- [ ] **Step 1: Run focused frontend tests**

Run:

```bash
cd frontend
node scripts/leadership-cockpit-ai-events.test.mjs
node scripts/leadership-cockpit-livestream.test.mjs
node scripts/dual-stream-group-normalizer.test.mjs
```

Expected: all focused tests pass.

- [ ] **Step 2: Run staging build**

Run: `cd frontend && npm run build:test`

Expected: Vite build exits 0.
