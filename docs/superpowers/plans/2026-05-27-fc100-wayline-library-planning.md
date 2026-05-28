# FC100 Wayline Library Planning Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an FC100 planning surface inside the existing wayline library, matching the M4T planning workflow while using FC100 Delivery APIs for task creation and execution.

**Architecture:** Reuse the current `wayline.vue` planner state, saved planned-wayline cards, KMZ generation, map preview, and detail modal. Add an FC100 mode that imports a generated KMZ into FC100 Delivery, creates a task, starts it after device preflight checks, and refreshes task status through `deliveryApi`.

**Tech Stack:** Vue 3, Ant Design Vue, Vite, existing frontend static Node tests, existing `/api/fire/delivery/*` endpoints.

---

### Task 1: Frontend Contract Test

**Files:**
- Modify: `frontend/scripts/planned-wayline-api.test.mjs`
- Read: `frontend/src/pages/page-web/projects/wayline.vue`
- Read: `frontend/src/api/fire/delivery.ts`

- [ ] **Step 1: Write the failing test**

Add a test that asserts the wayline library imports `deliveryApi`, renders FC100 planning controls, tracks FC100 devices, imports generated KMZ through `deliveryApi.importCreateWaylineTask`, and starts/status-checks tasks through `deliveryApi.startWaylineTask` and `deliveryApi.waylineTaskStatus`.

- [ ] **Step 2: Run test to verify it fails**

Run: `node frontend/scripts/planned-wayline-api.test.mjs`

Expected: FAIL because `wayline.vue` does not yet contain the FC100 planning UI or handlers.

### Task 2: FC100 Planning State And Handlers

**Files:**
- Modify: `frontend/src/pages/page-web/projects/wayline.vue`
- Read: `frontend/src/api/fire/delivery.ts`

- [ ] **Step 1: Import Delivery API symbols**

Import `deliveryApi`, `DeliveryDeviceDTO`, `DeliveryDeviceProperties`, `DeliveryTaskStatus`, and `DeliveryTaskOperationResult` from `/@/api/fire/delivery`.

- [ ] **Step 2: Add FC100 state**

Add a reactive state object for devices, selected device SN, device props, current FC100 task ID, task status, result text, loading action, and whether the FC100 panel is expanded.

- [ ] **Step 3: Add handlers**

Add handlers for refresh devices, select device, refresh device props, import generated KMZ and create task, start task, refresh task status, and build preflight warnings.

- [ ] **Step 4: Keep the existing M4T path unchanged**

Do not change the existing M4T `generatePlannedWaylineFile`, `preparePlannedWaylineTask`, `executePlannedWaylineTask`, and `cancelPlannedWaylineTask` behavior.

### Task 3: FC100 Planning UI

**Files:**
- Modify: `frontend/src/pages/page-web/projects/wayline.vue`

- [ ] **Step 1: Add an FC100 panel near saved planned-waylines**

Render `FC100规划航线`, device selection, selected generated KMZ state, task ID, task status, and result messages.

- [ ] **Step 2: Add record-level FC100 actions**

For saved planned-wayline cards and detail modal, add an action that uses the generated KMZ URL for FC100 import/create. Disable it when the record has no generated KMZ.

- [ ] **Step 3: Add scoped styles**

Use the existing dark compact style from the wayline page. Keep the panel dense and operational, without redesigning the whole page.

### Task 4: Verification

**Files:**
- Verify: `frontend/scripts/planned-wayline-api.test.mjs`
- Verify: `frontend/scripts/fc100-delivery-ui.test.mjs`
- Verify: frontend build if time permits

- [ ] **Step 1: Run focused static tests**

Run: `node frontend/scripts/planned-wayline-api.test.mjs`

Expected: PASS.

- [ ] **Step 2: Run FC100 Delivery contract tests**

Run: `node frontend/scripts/fc100-delivery-ui.test.mjs`

Expected: PASS.

- [ ] **Step 3: Run frontend build**

Run: `npm run build` in `frontend/`.

Expected: Vite build exits 0.
