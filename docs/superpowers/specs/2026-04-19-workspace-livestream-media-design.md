# Workspace Livestream And Media Design

Date: 2026-04-19
Scope: Web workspace only
Status: Draft for review

## 1. Goal

Integrate usable `Livestream` and `Media` entries into the existing web `workspace` so the command-side operator can:

- open livestream inside the workspace
- select a device and camera source
- start and stop live streaming from the browser
- view media file lists and download uploaded media files

This phase is intentionally limited to the minimum workflow needed for the command-side firefighting scenario. It does not attempt to finish multi-screen command-center features, recording, screenshots, media preview, or external video-platform integration.

## 2. Why This Is Needed

The requirement document emphasizes:

- command-center real-time viewing
- low-latency video return
- browser-based web command terminal
- later data replay and media download

The current repository already contains most of the technical pieces, but they are not integrated into a stable workspace flow:

- the sidebar already exposes `Livestream` and `Media Files`
- the router already defines `/livestream` and `/media`
- `MediaPanel.vue` already lists and downloads media
- `livestream-agora.vue` and `livestream-others.vue` already contain demo playback logic
- `workspace.vue` currently treats media as a simple right-side overlay, while livestream still uses a draggable demo window

The missing part is a coherent workspace-facing integration that matches the command workflow.

## 3. Recommended Transport Choice

### Recommendation

Use `Agora/WebRTC` as the primary livestream path for the workspace phase.

### Reasoning

This project is a firefighting command system, not a traditional security-video aggregation platform. The requirement document favors:

- browser playback
- low latency
- rapid command decisions
- multi-terminal command access

For this phase, `Agora/WebRTC` is the best fit because:

- it is browser-friendly
- it is lower-latency than RTMP/RTSP style playback
- the repository already has a working integration skeleton
- it minimizes first-phase infrastructure work

### Why Not Use RTMP/GB28181 First

`RTMP/RTSP/GB28181` is still valuable, but not as the primary first-phase workspace playback path.

It is better suited for:

- integration with existing video platforms
- long-chain forwarding and archival
- government or monitoring center interoperability

If used first inside workspace, it adds extra conversion and playback complexity before the command workflow is stable.

### Final Direction

Phase 1:

- workspace playback uses `Agora/WebRTC`
- workspace media uses the existing media list and download APIs

Phase 2:

- add `RTMP/GB28181` as optional secondary transport or external-platform integration

## 4. Current Codebase Baseline

### Existing Entry Points

- `frontend/src/components/common/sidebar.vue`
  - already has `Livestream` and `Media Files`
- `frontend/src/router/index.ts`
  - already has `LIVESTREAM` and `MEDIA` routes under `WORKSPACE`

### Existing Workspace Shell

- `frontend/src/pages/page-web/projects/workspace.vue`
  - already renders the main map area
  - already overlays `MediaPanel` for the `MEDIA` route
  - does not yet provide a first-class workspace livestream panel

### Existing Media Pieces

- `frontend/src/components/MediaPanel.vue`
  - already supports list pagination
  - already supports media download
- `frontend/src/pages/page-web/projects/media.vue`
  - currently empty

### Existing Livestream Pieces

- `frontend/src/pages/page-web/projects/livestream.vue`
  - currently acts like a demo launcher for draggable windows
- `frontend/src/components/livestream-agora.vue`
  - already contains Agora-based live capacity and playback flow
- `frontend/src/components/livestream-others.vue`
  - already contains alternative playback flow
- `frontend/src/api/manage.ts`
  - already exposes `getLiveCapacity`, `startLivestream`, `stopLivestream`, `setLivestreamQuality`, `changeLivestreamLens`

## 5. Design Choice

### Chosen Structure

Keep the existing `workspace` shell and convert both livestream and media into formal right-side workspace business panels.

This means:

- the map remains the primary situational view
- when route is `LIVESTREAM`, a workspace livestream panel overlays the right side
- when route is `MEDIA`, a workspace media panel overlays the right side

This keeps the command workflow intact while minimizing disruption to the existing structure.

### Why This Structure

It fits the firefighting command scenario better than making livestream a separate map-less page:

- operators still keep map context
- device status remains nearby
- integration cost stays low
- later migration to a larger command-center layout remains possible

## 6. Planned Components

### 6.1 WorkspaceLivestreamPanel

New component:

- wraps the Agora livestream flow into a workspace-friendly panel
- removes the current draggable demo-window interaction
- presents:
  - source selection
  - quality selection
  - start / stop actions
  - player area
  - status / error feedback

This component will initially reuse logic already present in `livestream-agora.vue`, but should expose a cleaner command-panel UI.

### 6.2 Media Route Wrapper

`media.vue` will become a real route wrapper instead of an empty shell.

It will:

- render a title / context header
- host `MediaPanel.vue`
- remain visually consistent with the workspace overlay pattern

### 6.3 Workspace Overlay Routing

`workspace.vue` will be extended so that:

- `MEDIA` route shows the media overlay
- `LIVESTREAM` route shows the livestream overlay
- both overlays use the same workspace-side framing pattern

## 7. First-Phase Functional Scope

### Livestream

Included:

- query live capacity
- select drone / camera / source
- select quality
- start livestream
- stop livestream
- play Agora stream in browser

Excluded from phase 1:

- multi-panel simultaneous viewing
- recording
- screenshots
- source persistence across reload
- playback history
- RTMP / GB28181 operator workflow

### Media

Included:

- media list
- pagination
- file download

Excluded from phase 1:

- preview player
- image lightbox
- advanced filters
- task/job correlation view
- batch download

## 8. Data Flow

### Livestream Data Flow

1. user enters workspace `LIVESTREAM`
2. frontend requests live capacity from backend
3. user selects drone, camera, quality
4. frontend calls backend livestream start API
5. backend negotiates with DJI side and returns livestream session data
6. frontend joins Agora and renders remote video
7. user can stop the livestream through backend API

### Media Data Flow

1. user enters workspace `MEDIA`
2. frontend requests paginated media list
3. user clicks a file
4. frontend requests the media file download URL
5. browser downloads the file

## 9. Error Handling

### Livestream

Must explicitly handle:

- no live capacity returned
- no device/camera selected
- start API failure
- Agora join failure
- remote stream not published
- stop API failure

UI behavior:

- keep errors inside the panel with concise messages
- do not silently fail
- do not use draggable hidden windows

### Media

Must explicitly handle:

- empty media list
- list request failure
- download URL failure
- blob download failure

UI behavior:

- show empty state clearly
- preserve pagination state after refresh when possible

## 10. Testing Strategy

### Frontend

Add focused tests for:

- workspace route overlay selection
- livestream panel state transitions
- media wrapper rendering

### Verification

At minimum:

- build passes
- route entry works
- media list request works
- media download request works
- livestream capacity request works
- livestream start/stop request path is callable

If real device-side livestream cannot be fully verified in-session, note that explicitly and keep the UI chain testable without pretending the device link was proven.

## 11. Risks

### Risk 1: Demo livestream logic is too coupled to a free-floating window

Mitigation:

- wrap it in a new workspace panel component instead of reusing the draggable page shell as-is

### Risk 2: Agora configuration may exist locally but not be valid in all environments

Mitigation:

- surface configuration failures clearly
- treat transport setup as environment dependency, not application logic success

### Risk 3: Workspace shell becomes too crowded

Mitigation:

- keep phase 1 minimal
- use separate panel components
- avoid piling livestream logic directly into `workspace.vue`

## 12. Implementation Boundaries

This work should not:

- redesign the whole workspace layout
- replace DJI-side media upload flow
- implement full video-platform interoperability
- implement command-center replay features in this phase

It should:

- make workspace `Livestream` usable
- make workspace `Media` feel like a real integrated module
- preserve the existing command-map-first interaction model
