# Fire Event Latest History Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make fire event list display latest event state while preserving per-hit history in a detail drawer.

**Architecture:** Add a `fire_event_history` persistence model and API endpoint. On every create or merge, write a history snapshot; on merge, update the main `fire_event` row to latest confidence/level while keeping notification version upgrade-only.

**Tech Stack:** Spring Boot, MyBatis Plus, MySQL, Vue 3, Ant Design Vue.

---

### Task 1: Backend history persistence

- [ ] Add failing tests in `FireEventServiceImplMergeTest` for history creation and latest-state downgrade.
- [ ] Add `FireEventHistoryEntity`, mapper, DTO, service API, controller endpoint.
- [ ] Update create/merge to insert history snapshots.

### Task 2: Frontend history drawer

- [ ] Add API/type for history endpoint.
- [ ] Add operation column and drawer table in `FireEventList.vue`.

### Task 3: Verify and run

- [ ] Run focused Maven tests.
- [ ] Apply DB DDL for local database.
- [ ] Run frontend smoke tests and restart services.
