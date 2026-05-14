# Wayline Planning Save And Publish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add save/edit/delete/publish support for click-to-fly planned waylines, keep editable source records separate from formal wayline files, and make published waylines selectable from the existing task workflow.

**Architecture:** Introduce a new backend `planned_wayline` persistence slice under the existing `wayline` module, expose CRUD + publish APIs, then extend the frontend wayline page to manage saved planned-wayline records while keeping the existing formal wayline file list unchanged. Publishing converts one saved source record into a formal wayline-file asset and refreshes the existing wayline library/UI path.

**Tech Stack:** Vue 3 + Vuex + Ant Design Vue, Spring Boot + MyBatis Plus, existing wayline module/service patterns, MySQL, existing OSS-backed wayline-file flow.

---

## File Map

### Backend

- Create: `backend/uavfire/src/main/java/com/yx/uavfire/wayline/model/entity/PlannedWaylineEntity.java`
- Create: `backend/uavfire/src/main/java/com/yx/uavfire/wayline/model/dto/PlannedWaypointDTO.java`
- Create: `backend/uavfire/src/main/java/com/yx/uavfire/wayline/model/dto/PlannedWaylineDTO.java`
- Create: `backend/uavfire/src/main/java/com/yx/uavfire/wayline/model/param/CreatePlannedWaylineParam.java`
- Create: `backend/uavfire/src/main/java/com/yx/uavfire/wayline/model/param/UpdatePlannedWaylineParam.java`
- Create: `backend/uavfire/src/main/java/com/yx/uavfire/wayline/model/param/PublishPlannedWaylineResponse.java`
- Create: `backend/uavfire/src/main/java/com/yx/uavfire/wayline/dao/IPlannedWaylineMapper.java`
- Create: `backend/uavfire/src/main/java/com/yx/uavfire/wayline/service/IPlannedWaylineService.java`
- Create: `backend/uavfire/src/main/java/com/yx/uavfire/wayline/service/impl/PlannedWaylineServiceImpl.java`
- Create: `backend/uavfire/src/main/java/com/yx/uavfire/wayline/controller/PlannedWaylineController.java`
- Modify: `backend/uavfire/src/main/java/com/yx/uavfire/wayline/service/impl/WaylineFileServiceImpl.java`
- Modify: `backend/uavfire/src/main/resources/application.yml` only if a new mapper scan or serialization setting is required.

### Frontend

- Modify: `frontend/src/types/wayline.ts`
- Modify: `frontend/src/types/enums.ts`
- Modify: `frontend/src/store/index.ts`
- Modify: `frontend/src/api/wayline.ts`
- Modify: `frontend/src/hooks/use-wayline-planning.ts`
- Modify: `frontend/src/pages/page-web/projects/wayline.vue`

### Docs / verification

- Modify: `WORK_RECORD.md`
- Modify: `HANDOFF_2026-04-21_M4T_DEVICE_AND_BACKEND_NEXT_PHASE.md` only if the implementation changes current handoff status.

---

### Task 1: Backend Planned-Wayline Model And CRUD Service

**Files:**
- Create: `backend/uavfire/src/main/java/com/yx/uavfire/wayline/model/entity/PlannedWaylineEntity.java`
- Create: `backend/uavfire/src/main/java/com/yx/uavfire/wayline/model/dto/PlannedWaypointDTO.java`
- Create: `backend/uavfire/src/main/java/com/yx/uavfire/wayline/model/dto/PlannedWaylineDTO.java`
- Create: `backend/uavfire/src/main/java/com/yx/uavfire/wayline/model/param/CreatePlannedWaylineParam.java`
- Create: `backend/uavfire/src/main/java/com/yx/uavfire/wayline/model/param/UpdatePlannedWaylineParam.java`
- Create: `backend/uavfire/src/main/java/com/yx/uavfire/wayline/dao/IPlannedWaylineMapper.java`
- Create: `backend/uavfire/src/main/java/com/yx/uavfire/wayline/service/IPlannedWaylineService.java`
- Create: `backend/uavfire/src/main/java/com/yx/uavfire/wayline/service/impl/PlannedWaylineServiceImpl.java`
- Create: `backend/uavfire/src/main/java/com/yx/uavfire/wayline/controller/PlannedWaylineController.java`

- [ ] **Step 1: Write the failing backend tests first**

Create `backend/uavfire/src/test/java/com/yx/uavfire/wayline/PlannedWaylineControllerTest.java` covering:

```java
@Test
void createPlannedWaylineShouldPersistDraftRecord() {}

@Test
void updatePlannedWaylineShouldOverwriteEditableFields() {}

@Test
void deletePlannedWaylineShouldRemoveWorkspaceOwnedRecord() {}

@Test
void listPlannedWaylinesShouldReturnWorkspaceScopedRecords() {}
```

Also create `backend/uavfire/src/test/java/com/yx/uavfire/wayline/PlannedWaylineServiceTest.java` covering JSON waypoint serialization and status defaults:

```java
@Test
void createShouldDefaultStatusToDraft() {}

@Test
void dtoRoundTripShouldPreserveWaypointOrderAndCoordinates() {}
```

- [ ] **Step 2: Run the backend tests and verify RED**

Run:

```bash
cd /Users/likewang/uavfire/backend
mvn -pl uavfire -Dtest=PlannedWaylineControllerTest,PlannedWaylineServiceTest test
```

Expected:

- FAIL because planned-wayline classes, controller, and service do not exist yet

- [ ] **Step 3: Implement the minimal planned-wayline model and CRUD service**

Implement:

- `PlannedWaylineEntity` with fields:
  - `id`
  - `plannedWaylineId`
  - `workspaceId`
  - `name`
  - `aircraftModelKey`
  - `gatewaySn`
  - `aircraftSn`
  - `defaultHeight`
  - `maxSpeed`
  - `waypointsJson`
  - `status`
  - `publishedWaylineId`
  - `creator`
  - `createTime`
  - `updateTime`
- `PlannedWaypointDTO` with:
  - `order`
  - `gcjLng`
  - `gcjLat`
  - `wgsLng`
  - `wgsLat`
  - `height`
- `PlannedWaylineDTO` with denormalized list field `waypoints`
- `CreatePlannedWaylineParam` and `UpdatePlannedWaylineParam`
- `IPlannedWaylineMapper extends BaseMapper<PlannedWaylineEntity>`
- `IPlannedWaylineService` methods:
  - `PaginationData<PlannedWaylineDTO> getByWorkspace(String workspaceId, long page, long pageSize)`
  - `PlannedWaylineDTO create(String workspaceId, String username, CreatePlannedWaylineParam param)`
  - `PlannedWaylineDTO update(String workspaceId, String id, UpdatePlannedWaylineParam param)`
  - `void delete(String workspaceId, String id)`
  - `Optional<PlannedWaylineDTO> getOne(String workspaceId, String id)`
- `PlannedWaylineController` routes:
  - `GET /wayline/api/v1/workspaces/{workspace_id}/planned-waylines`
  - `POST /wayline/api/v1/workspaces/{workspace_id}/planned-waylines`
  - `PUT /wayline/api/v1/workspaces/{workspace_id}/planned-waylines/{id}`
  - `DELETE /wayline/api/v1/workspaces/{workspace_id}/planned-waylines/{id}`

Implementation rules:

- Default `status` to `"draft"`
- Keep records workspace-scoped
- Serialize waypoint list with Jackson `ObjectMapper`
- Reject empty waypoint arrays and blank names in params

- [ ] **Step 4: Run the backend tests and verify GREEN**

Run:

```bash
cd /Users/likewang/uavfire/backend
mvn -pl uavfire -Dtest=PlannedWaylineControllerTest,PlannedWaylineServiceTest test
```

Expected:

- PASS

- [ ] **Step 5: Commit**

```bash
git add backend/uavfire/src/main/java/com/yx/uavfire/wayline/model/entity/PlannedWaylineEntity.java \
  backend/uavfire/src/main/java/com/yx/uavfire/wayline/model/dto/PlannedWaypointDTO.java \
  backend/uavfire/src/main/java/com/yx/uavfire/wayline/model/dto/PlannedWaylineDTO.java \
  backend/uavfire/src/main/java/com/yx/uavfire/wayline/model/param/CreatePlannedWaylineParam.java \
  backend/uavfire/src/main/java/com/yx/uavfire/wayline/model/param/UpdatePlannedWaylineParam.java \
  backend/uavfire/src/main/java/com/yx/uavfire/wayline/dao/IPlannedWaylineMapper.java \
  backend/uavfire/src/main/java/com/yx/uavfire/wayline/service/IPlannedWaylineService.java \
  backend/uavfire/src/main/java/com/yx/uavfire/wayline/service/impl/PlannedWaylineServiceImpl.java \
  backend/uavfire/src/main/java/com/yx/uavfire/wayline/controller/PlannedWaylineController.java \
  backend/uavfire/src/test/java/com/yx/uavfire/wayline/PlannedWaylineControllerTest.java \
  backend/uavfire/src/test/java/com/yx/uavfire/wayline/PlannedWaylineServiceTest.java
git commit -m "feat: add planned wayline CRUD"
```

---

### Task 2: Backend Publish Flow From Planned Record To Formal Wayline File

**Files:**
- Create: `backend/uavfire/src/main/java/com/yx/uavfire/wayline/model/param/PublishPlannedWaylineResponse.java`
- Modify: `backend/uavfire/src/main/java/com/yx/uavfire/wayline/service/IPlannedWaylineService.java`
- Modify: `backend/uavfire/src/main/java/com/yx/uavfire/wayline/service/impl/PlannedWaylineServiceImpl.java`
- Modify: `backend/uavfire/src/main/java/com/yx/uavfire/wayline/controller/PlannedWaylineController.java`
- Modify: `backend/uavfire/src/main/java/com/yx/uavfire/wayline/service/impl/WaylineFileServiceImpl.java`

- [ ] **Step 1: Write the failing publish tests**

Add tests:

```java
@Test
void publishShouldCreateFormalWaylineAndMarkDraftPublished() {}

@Test
void publishFailureShouldKeepOriginalDraftState() {}
```

The publish-success test must assert:

- response returns `published_wayline_id`
- the planned record still exists
- the planned record status becomes `"published"`
- `publishedWaylineId` is stored back on the planned record

- [ ] **Step 2: Run the publish tests and verify RED**

Run:

```bash
cd /Users/likewang/uavfire/backend
mvn -pl uavfire -Dtest=PlannedWaylineServiceTest test
```

Expected:

- FAIL because `publish` behavior and response type do not exist

- [ ] **Step 3: Implement minimal publish behavior**

Extend `IPlannedWaylineService` with:

- `PublishPlannedWaylineResponse publish(String workspaceId, String id)`

Minimal implementation:

- Load the planned record
- Validate non-empty waypoints and legal coordinates/heights
- Create a formal wayline-file record via existing `WaylineFileServiceImpl` integration point
- Persist returned `waylineId`
- Update the planned record status to `"published"`
- Return:

```java
@Data
@Builder
public class PublishPlannedWaylineResponse {
    private String plannedWaylineId;
    private String publishedWaylineId;
    private String publishedWaylineName;
}
```

If there is no reusable KMZ-generation helper yet, create the narrowest temporary internal seam in `WaylineFileServiceImpl` needed to accept a generated object key + metadata without changing current import behavior. Do not broaden the wayline-file module beyond the publish path.

- [ ] **Step 4: Run the publish tests and verify GREEN**

Run:

```bash
cd /Users/likewang/uavfire/backend
mvn -pl uavfire -Dtest=PlannedWaylineServiceTest test
```

Expected:

- PASS

- [ ] **Step 5: Commit**

```bash
git add backend/uavfire/src/main/java/com/yx/uavfire/wayline/model/param/PublishPlannedWaylineResponse.java \
  backend/uavfire/src/main/java/com/yx/uavfire/wayline/service/IPlannedWaylineService.java \
  backend/uavfire/src/main/java/com/yx/uavfire/wayline/service/impl/PlannedWaylineServiceImpl.java \
  backend/uavfire/src/main/java/com/yx/uavfire/wayline/controller/PlannedWaylineController.java \
  backend/uavfire/src/main/java/com/yx/uavfire/wayline/service/impl/WaylineFileServiceImpl.java \
  backend/uavfire/src/test/java/com/yx/uavfire/wayline/PlannedWaylineServiceTest.java
git commit -m "feat: publish planned waylines to wayline library"
```

---

### Task 3: Frontend API, Types, And Planning-Editor State

**Files:**
- Modify: `frontend/src/types/wayline.ts`
- Modify: `frontend/src/types/enums.ts`
- Modify: `frontend/src/store/index.ts`
- Modify: `frontend/src/api/wayline.ts`
- Modify: `frontend/src/hooks/use-wayline-planning.ts`

- [ ] **Step 1: Write failing frontend tests for planned-wayline state and API mapping**

Create:

- `frontend/src/hooks/__tests__/use-wayline-planning.spec.ts`
- `frontend/src/api/__tests__/wayline.spec.ts`

Cover:

```ts
it('builds a save payload from the current planned waypoints')
it('hydrates planner state from a saved planned wayline record')
it('maps publish API response fields correctly')
```

- [ ] **Step 2: Run the frontend tests and verify RED**

Run:

```bash
cd /Users/likewang/uavfire/frontend
npx vitest run src/hooks/__tests__/use-wayline-planning.spec.ts src/api/__tests__/wayline.spec.ts
```

Expected:

- FAIL because planned-wayline API/types/state helpers do not exist yet

- [ ] **Step 3: Implement minimal frontend API/types/state**

Add `frontend/src/types/wayline.ts` types:

- `PlannedWaypoint`
- `PlannedWaylineRecord`
- `CreatePlannedWaylineBody`
- `UpdatePlannedWaylineBody`
- `PublishPlannedWaylineResult`

Add `frontend/src/api/wayline.ts` functions:

- `getPlannedWaylines`
- `createPlannedWayline`
- `updatePlannedWayline`
- `deletePlannedWayline`
- `publishPlannedWayline`

Extend `use-wayline-planning.ts` with narrow helpers:

- `buildPlannedWaylineBody()`
- `loadPlannedWayline(record)`
- `resetForNewDraft()`

Track current editing record in Vuex or planner module:

- selected planned-wayline id
- current edit mode: `new` or `editing`

Do not couple saved planned-wayline state to the existing formal `waylineInfo` selection used by `Create Plan`.

- [ ] **Step 4: Run the frontend tests and verify GREEN**

Run:

```bash
cd /Users/likewang/uavfire/frontend
npx vitest run src/hooks/__tests__/use-wayline-planning.spec.ts src/api/__tests__/wayline.spec.ts
```

Expected:

- PASS

- [ ] **Step 5: Commit**

```bash
git add frontend/src/types/wayline.ts \
  frontend/src/types/enums.ts \
  frontend/src/store/index.ts \
  frontend/src/api/wayline.ts \
  frontend/src/hooks/use-wayline-planning.ts \
  frontend/src/hooks/__tests__/use-wayline-planning.spec.ts \
  frontend/src/api/__tests__/wayline.spec.ts
git commit -m "feat: add planned wayline frontend state"
```

---

### Task 4: Wayline Page UI For Save / Edit / Delete / Publish

**Files:**
- Modify: `frontend/src/pages/page-web/projects/wayline.vue`

- [ ] **Step 1: Write failing component tests for the new saved-wayline UI**

Create:

- `frontend/src/pages/page-web/projects/__tests__/wayline.spec.ts`

Cover:

```ts
it('renders a saved planned-wayline section above the formal wayline library')
it('loads a saved record back into the planner when clicked')
it('calls save for editing mode and save-as for new mode')
it('shows publish action and refreshes the formal wayline list after publish')
```

- [ ] **Step 2: Run the component tests and verify RED**

Run:

```bash
cd /Users/likewang/uavfire/frontend
npx vitest run src/pages/page-web/projects/__tests__/wayline.spec.ts
```

Expected:

- FAIL because the saved planned-wayline UI and actions are not rendered yet

- [ ] **Step 3: Implement the minimal page UI**

In `wayline.vue`:

- Add top-level buttons for:
  - `保存`
  - `另存为`
- Add a “已保存规划航线” section between the planner panel and the formal wayline library
- Render each saved record with:
  - name
  - update time
  - waypoint count
  - default height
  - max speed
  - status
  - publish / delete actions
- On click:
  - call `loadPlannedWayline(record)`
  - switch the planner into editing mode
- On save:
  - if editing an existing record, call `updatePlannedWayline`
  - otherwise prompt for name and call `createPlannedWayline`
- On save-as:
  - always prompt for a new name and call `createPlannedWayline`
- On publish:
  - call `publishPlannedWayline`
  - refresh both saved records and formal wayline library

Keep existing KMZ import/download/delete UI unchanged.

- [ ] **Step 4: Run the component tests and verify GREEN**

Run:

```bash
cd /Users/likewang/uavfire/frontend
npx vitest run src/pages/page-web/projects/__tests__/wayline.spec.ts
```

Expected:

- PASS

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/page-web/projects/wayline.vue \
  frontend/src/pages/page-web/projects/__tests__/wayline.spec.ts
git commit -m "feat: add planned wayline save and publish UI"
```

---

### Task 5: End-To-End Verification And Documentation

**Files:**
- Modify: `WORK_RECORD.md`
- Modify: `HANDOFF_2026-04-21_M4T_DEVICE_AND_BACKEND_NEXT_PHASE.md` if current handoff state changes

- [ ] **Step 1: Run backend regression for the wayline slice**

Run:

```bash
cd /Users/likewang/uavfire/backend
mvn -pl uavfire -Dtest=PlannedWaylineControllerTest,PlannedWaylineServiceTest test
```

Expected:

- PASS

- [ ] **Step 2: Run frontend regression for the wayline slice**

Run:

```bash
cd /Users/likewang/uavfire/frontend
npx vitest run src/hooks/__tests__/use-wayline-planning.spec.ts \
  src/api/__tests__/wayline.spec.ts \
  src/pages/page-web/projects/__tests__/wayline.spec.ts
```

Expected:

- PASS

- [ ] **Step 3: Run a production build check for the frontend**

Run:

```bash
cd /Users/likewang/uavfire/frontend
npm run build
```

Expected:

- PASS
- existing Sass deprecation warnings may remain

- [ ] **Step 4: Manually verify the browser flow**

Checklist:

- Start backend and frontend
- Open `/wayline`
- Create a click-to-fly draft with at least 2 waypoints
- Save it
- Refresh the page and confirm the record is still listed
- Click the saved record and confirm the planner + map rehydrate
- Update one waypoint height and save again
- Use `另存为` to create a second record
- Publish one record and confirm it appears in the formal wayline library section
- Open `/task/create-plan` and confirm the published formal wayline can be selected

- [ ] **Step 5: Update records and commit**

Add the implementation result and remaining known gaps to `WORK_RECORD.md`, and update handoff docs only if this changes the next-step narrative.

```bash
git add WORK_RECORD.md HANDOFF_2026-04-21_M4T_DEVICE_AND_BACKEND_NEXT_PHASE.md
git commit -m "docs: record planned wayline save and publish delivery"
```

---

## Self-Review

### Spec coverage

- Saved planned-wayline source records: covered by Task 1 and Task 4
- Separate saved-record list and formal wayline library: covered by Task 4
- Save / update / delete / save-as: covered by Task 1, Task 3, Task 4
- Publish into the formal wayline library: covered by Task 2 and Task 4
- Published formal waylines usable from `Create Plan`: covered by Task 5 manual verification

### Placeholder scan

- No `TBD`, `TODO`, or deferred implementation markers remain in the plan
- Each task has concrete files, commands, and expected outcomes

### Type consistency

- Backend uses `plannedWaylineId` / `publishedWaylineId`
- Frontend uses `PlannedWaylineRecord` and `PublishPlannedWaylineResult`
- Planner state remains distinct from formal `WaylineFile`
