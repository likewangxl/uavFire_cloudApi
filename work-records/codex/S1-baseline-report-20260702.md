# S1 Baseline Report - 2026-07-02

## Scope

- Read root `README.md`; repo is DJI Cloud API derived, with `frontend/` Vue 3 + Vite, `backend/` Java 11 Spring Boot Maven, `rcplus-msdk-agent/`, and `ai-service/`.
- Inspected `backend/uavfire/src/main/java/com/yx/uavfire/fc100/` module structure: `common`, `deliverysync`, `event`, `mission`, `payload`, `review`, `route`, `safety`, `waypoint`.
- Did not modify `backend/cloud-sdk`, `rcplus-msdk-agent`, `ai-service`, or `FireMissionStatus.java`.
- No git commit was created.

## Change Summary

Backend release policy:

- Added DB migration `backend/sql/migrations/2026-07-02-fc100-mission-release-policy.sql`.
- Updated `backend/uavfire/sql/fc100_init.sql` to include `release_policy` and `release_execution_mode`.
- Added enums `ReleasePolicy` and `ReleaseExecutionMode`.
- Added release fields to fire mission entity/DTO/create param, defaulting task creation to `MANUAL_CONFIRM` and `OFFICIAL_HOOK_MANUAL`.
- Added config `fc100.release.controlled-test-auto-enabled`, defaulting to `false`.
- Added release-policy business errors and 4xx handling.
- Added `PayloadReleasePolicyService` to centralize operator/confirmation, dry-run, controlled-test-auto, delivery-sync capability, and mission-log auditing.
- Routed both `PayloadController` and `DeliveryController.releaseHook` through the same release policy guard.
- Closed `autoReleaseHookWhenDeliveryReady` by default; it now proceeds only for `CONTROLLED_TEST_AUTO` with the config switch enabled and logs policy blocks.

Frontend policy tests:

- Added `frontend` script `test:policies`.
- Added `frontend/scripts/run-policy-tests.mjs` to run all `src/pages/page-web/projects/**/*.test.mjs` policy tests in one command.
- Updated drifted `.mjs` policy tests to current component/source contracts without deleting assertions.

## Validation Commands

Backend focused S1 tests:

```text
cd backend
mvn -pl uavfire "-Dtest=FireEventServiceImplMergeTest,PayloadServiceImplReleasePolicyTest,DeliveryControllerApifoxWorkflowTest" test
```

Result:

```text
Tests run: 45, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Backend compile:

```text
cd backend
mvn -pl uavfire -DskipTests compile
```

Result:

```text
BUILD SUCCESS
```

Notes: Maven still reports existing warnings for `org.jetbrains:annotations` version `LATEST/RELEASE`, invalid `com.alibaba:druid:1.2.6` systemPath entries, relocated MySQL connector artifact, and missing profile `hacoud`.

Backend full module tests:

```text
cd backend
mvn -pl uavfire test
```

Result:

```text
Exit code: 1
classes=42 tests=322 failures=0 errors=5 skipped=0
```

Passing S1-related classes in the full run:

```text
DeliveryControllerApifoxWorkflowTest: Tests run: 28, Failures: 0, Errors: 0, Skipped: 0
FireEventServiceImplMergeTest: Tests run: 15, Failures: 0, Errors: 0, Skipped: 0
PayloadServiceImplReleasePolicyTest: Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
```

Full-run existing failures/errors:

```text
HgtTerrainElevationServiceTest: Tests run: 6, Failures: 0, Errors: 4
Cause: java.io.IOException: Failed to delete temp directory ... N39E115.hgt

StreamSplitterServiceTest: Tests run: 1, Failures: 0, Errors: 1
Cause: java.nio.file.NoSuchFileException: ... ffmpeg-count.txt
```

Frontend policy tests:

```text
cd frontend
npm.cmd run test:policies
```

Result:

```text
tests 76
pass 76
fail 0
cancelled 0
skipped 0
todo 0
```

Frontend build:

```text
cd frontend
npm.cmd run build
```

Result:

```text
vite v2.9.18 building for production...
2987 modules transformed.
```

Notes: build passed; Vite emitted existing chunk-size warnings for large chunks over 500 KiB.

Frontend lint:

```text
cd frontend
npm.cmd run lint
```

Result:

```text
eslint --fix
Exit code: 0
```

## Acceptance Status

- SQL migration and init schema updated: PASS.
- ReleasePolicy / ReleaseExecutionMode enums added under `fc100/mission/model/enums`: PASS.
- Entity, DTO, create param, and create service defaults wired: PASS.
- Manual release requires operator plus confirmation marker and audits rejection: PASS.
- DRY_RUN records audit and does not send real release command: PASS.
- CONTROLLED_TEST_AUTO is blocked while config switch is false: PASS.
- DELIVERY_SYNC_REMOTE returns capability-unconfirmed business error: PASS.
- `DeliveryController.releaseHook` shares the same hard validation as payload release: PASS.
- Existing automatic release path is default-closed and audited when blocked: PASS.
- Frontend policy tests have a unified script and pass: PASS.
- Backend compile passes: PASS.
- Backend full test baseline: FAIL, due to existing unrelated test errors listed above.
- Frontend build passes: PASS.
- Frontend lint passes: PASS.

## Worktree Notes

- Existing untracked files left untouched: `frontend/codex-left-panel-preview*.png`, `work-records/codex/S1-task-prompt-20260702.md`.
- Generated `frontend/dist/` and backend `target/` outputs are not staged/tracked.
