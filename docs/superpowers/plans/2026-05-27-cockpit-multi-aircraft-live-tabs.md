# Cockpit Multi-Aircraft Live Tabs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add three cockpit visual tabs, rename the existing livestream tab to fire monitoring, add a polished multi-aircraft stream selector, and add an FC100 delivery execution livestream/status tab.

**Architecture:** Keep the existing `leadership-cockpit.vue` playback path intact for the fire monitoring tab, then add focused child components for stream target selection and FC100 delivery execution. Use a shared target shape so fire-monitor aircraft and delivery aircraft can both be selected without changing the cockpit layout later.

**Tech Stack:** Vue 3 `<script setup>`, TypeScript, Ant Design Vue, existing ZLM WebRTC playback logic, existing `/manage/api/v1/dual-stream/*` APIs, existing `/api/fire/delivery/*` APIs.

---

## File Structure

- Create `frontend/src/components/cockpit/CockpitAircraftStreamSelector.vue`
  - Reusable dropdown-style selector for fire-monitor and delivery stream targets.
- Create `frontend/src/components/cockpit/CockpitDeliveryExecutionPanel.vue`
  - FC100 delivery livestream/status panel for the new tab.
- Modify `frontend/src/api/fire/delivery.ts`
  - Add `DeliveryDeviceLiveDTO` and `deviceLive(deviceSn)` client wrapper.
- Modify `frontend/src/pages/page-web/projects/leadership-cockpit.vue`
  - Change tab model to `map | fire-monitor | delivery-execution`.
  - Rename live copy to fire monitoring.
  - Add fire-monitor selector and selected aircraft state.
  - Add delivery execution tab using `CockpitDeliveryExecutionPanel`.
  - Keep current dual-stream player lifecycle and AI event polling.
- Modify `frontend/scripts/leadership-cockpit-livestream.test.mjs`
  - Update expectations for new tab keys and copy.
- Create `frontend/scripts/leadership-cockpit-multi-aircraft.test.mjs`
  - Static tests for selector, delivery panel, and new API wrapper.

## Task 1: Stream Target Selector Component

**Files:**
- Create: `frontend/src/components/cockpit/CockpitAircraftStreamSelector.vue`
- Test: `frontend/scripts/leadership-cockpit-multi-aircraft.test.mjs`

- [ ] Write a static test that checks the selector exposes `CockpitStreamTarget`, groups by `role`, renders `stream-target-trigger`, `stream-target-menu`, `火情监测飞机`, `投放执行飞机`, and emits `update:value`.
- [ ] Implement `CockpitAircraftStreamSelector.vue` with a compact trigger, grouped dropdown cards, online/status badges, progress text, and disabled state for empty targets.
- [ ] Run `node --test frontend/scripts/leadership-cockpit-multi-aircraft.test.mjs`.

## Task 2: FC100 Delivery Live API and Panel

**Files:**
- Modify: `frontend/src/api/fire/delivery.ts`
- Create: `frontend/src/components/cockpit/CockpitDeliveryExecutionPanel.vue`
- Test: `frontend/scripts/leadership-cockpit-multi-aircraft.test.mjs`

- [ ] Add `DeliveryDeviceLiveDTO` with `deviceSn`, `streamStatus`, `playUrl`, `source`, and `message`.
- [ ] Add `deliveryApi.deviceLive(deviceSn)` pointing at `/api/fire/delivery/devices/${deviceSn}/live`.
- [ ] Implement `CockpitDeliveryExecutionPanel.vue` with props `target`, `deliveryTargets`, `loading`, emits `refresh-targets`, and local polling for `deviceProps`, `deviceLive`, and optional `waylineTaskStatus`.
- [ ] Render a ZLM-compatible video frame placeholder using `playUrl`, status KPI cards, task message, and device telemetry.
- [ ] Run `node --test frontend/scripts/leadership-cockpit-multi-aircraft.test.mjs`.

## Task 3: Cockpit Tab Integration

**Files:**
- Modify: `frontend/src/pages/page-web/projects/leadership-cockpit.vue`
- Modify: `frontend/scripts/leadership-cockpit-livestream.test.mjs`
- Test: `frontend/scripts/leadership-cockpit-multi-aircraft.test.mjs`

- [ ] Change visual tabs to `态势图`, `火情监测画面`, `投放执行画面`.
- [ ] Replace `activeVisualTab === 'live'` checks with `activeVisualTab === 'fire-monitor'` where they refer to the existing dual-stream player.
- [ ] Add fire-monitor target state from MSDK devices, dual-stream group, store current SN, and `FIELD_AGENT_AIRCRAFT_SN`.
- [ ] Render `CockpitAircraftStreamSelector` in fire-monitor mode and use the selected target SN for `loadDualStreamState()` and `resolveFireDetectionDroneSn()`.
- [ ] Render `CockpitDeliveryExecutionPanel` in delivery-execution mode.
- [ ] Add tab-specific KPI grids for map, fire monitoring, and delivery execution.
- [ ] Run `node --test frontend/scripts/leadership-cockpit-livestream.test.mjs frontend/scripts/leadership-cockpit-multi-aircraft.test.mjs`.

## Task 4: Verification and Build

**Files:**
- No planned source edits unless verification finds defects.

- [ ] Run `node --test frontend/scripts/leadership-cockpit-ai-events.test.mjs frontend/scripts/leadership-cockpit-livestream.test.mjs frontend/scripts/leadership-cockpit-multi-aircraft.test.mjs frontend/scripts/fc100-delivery-ui.test.mjs`.
- [ ] Run the frontend build command used by this repo, preferring `npm run build` from `frontend/` if available.
- [ ] Fix any regressions without reverting unrelated dirty worktree changes.
- [ ] Summarize changed files and known backend dependency: `/api/fire/delivery/devices/{deviceSn}/live` must exist or be added server-side.

## Self-Review

- Spec coverage: The plan covers three visual tabs, fire-monitor rename, multi-aircraft dropdown selection, FC100 delivery execution tab, live API wrapper, and tests.
- Placeholder scan: No TBD/TODO placeholders remain.
- Type consistency: `CockpitStreamTarget` and `DeliveryDeviceLiveDTO` are defined once and consumed by the cockpit and panel.
