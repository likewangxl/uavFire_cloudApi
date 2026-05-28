# UAVFire Current Project Status

> Updated: 2026-05-21
> Source priority: `AGENTS.md` memory summary, Claude-authored plans/specs, current working tree code, then older handoff records.

## Current Goal

The project is now a M4T + RC Plus 2 wildfire response system built on top of the DJI Cloud API sample. The current direction is to keep the existing Cloud SDK backend/frontend as the control and data hub while moving the live media, wayline execution, and device telemetry data plane toward `rcplus-msdk-agent`.

The active runtime model is:

```text
M4T / RC Plus 2
  -> rcplus-msdk-agent (DJI MSDK v5)
  -> ZLMediaKit RTMP/WebRTC
  -> backend dual-stream runtime + ai-service
  -> leadership cockpit + fire event workflow
```

Cloud SDK livestream service code is kept as a manual fallback in phase 1. The leadership cockpit no longer auto-starts Pilot 2 / Cloud SDK livestream on page mount; new live media work should target the DualStream / MSDK agent path first.

## Latest Decisions

- Pilot 2 PIP composite RTMP is not viable. The PoC in `docs/poc/pilot2-composite-stream.md` showed that Pilot 2 UI PIP does not enter the Cloud SDK livestream output.
- Cloud SDK livestream through Pilot 2 does work for a single visible-light stream in RC manual-flight mode. Dual Cloud SDK streams remain unverified.
- M4T exposes visible, zoom, and thermal as one `ComponentIndexType.LEFT_OR_MAIN` with switchable `CameraVideoStreamSourceType`. It does not expose simultaneous visible + thermal raw streams as independent MSDK camera indexes in the tested setup.
- The recommended MSDK fallback for dual-modal AI is not "two raw streams"; it is either side-by-side/PIP frame slicing, time-multiplexed source switching, or a future Cloud SDK dual-stream PoC.
- Phase 1 MSDK migration scope is limited to live media, wayline execution, and OSD/HMS telemetry. Flight control migration is deferred until real-device validation.

## Current Configuration Baseline

The current working tree is configured around the Mac LAN IP:

```text
172.20.10.7
```

Key paths using this baseline:

- Backend API: `http://172.20.10.7:6789`
- Frontend dev server: `http://172.20.10.7:8080`
- ZLMediaKit HTTP/WebRTC API: `http://172.20.10.7:58925`
- ZLMediaKit RTMP: `rtmp://172.20.10.7:1935/live/<stream>`
- Agent backend URL: `http://172.20.10.7:6789/`
- Agent media host: `172.20.10.7`
- Agent MQTT broker: `tcp://172.20.10.7:1883`

Older documents may mention previous LAN IPs; treat those as historical unless the local network has intentionally been switched again.

## Subsystem Status

### `backend/uavfire`

Implemented:

- DualStream agent endpoints for heartbeat/status/capability/command polling.
- DualStream group query, start/stop/focus command issuance, task event storage, Redis fallback.
- Agent endpoint auth bypass for `/manage/api/v1/dual-stream/agents/**`.
- ZLM WebRTC fallback URL generation for `{droneSn}-0`.
- Fire detection start/stop facade for `ai-service`.
- Wayline agent HTTP command skeleton and MSDK-agent dispatch path.
- Wayline execution now auto-starts AI detection from the agent stream URL `rtsp://<zlm-host>:8554/live/{droneSn}-0`.
- Fire event creation with OSD position fill-in.

Phase 1 in progress:

- `LiveStreamController`, `ILiveStreamService`, and `LiveStreamServiceImpl` are marked `@Deprecated` as Cloud SDK livestream fallback.
- `FireDetectionController` defaults to `rtsp://<zlm-host>:8554/live/{droneSn}-0` only when callers do not send `video_id`.

Known gaps:

- DualStream command queue is still an in-memory single slot per drone and is not production-safe.
- Cloud SDK service-reply-dependent features may time out when Pilot 2 is not running.

### `frontend`

Implemented:

- Leadership cockpit reads DualStream group state.
- Live tab uses `ZLMRTCClient.Endpoint` and real `<video>` elements for WebRTC playback.
- Focus switching sends `focus-visible` / `focus-thermal` commands.
- AI fire-detection toggle and fire-event notifications are wired.
- The cockpit no longer auto-calls `startPilotLivestreamOnce()` or patches a Pilot 2 Cloud SDK URL into DualStream state.

Known gaps:

- The cockpit still hardcodes `RC_PLUS_LOCAL` in the DualStream group fetch path.
- Thermal preview is not a true second stream. It is currently status/degraded or shared-stream semantics depending on backend state.

### `rcplus-msdk-agent`

Implemented:

- Android/Kotlin project builds with Java 17 and DJI MSDK v5.17.0.
- MSDK init/register/helper install path exists.
- Device session, capability read, runtime loop, backend heartbeat/status/capability, command polling, and command ack are implemented.
- Visible-first stream start path exists.
- `DjiLiveStreamController` pushes RTMP to ZLM as `{effectiveSn}-0`, using `AGENT_AIRCRAFT_SN` when configured.
- App boot starts runtime loop, delayed dual-stream start, OSD reporter, and HMS reporter scaffold.
- Wayline command routing, KMZ download, waypoint executor, event forwarding, and probe controller exist.
- Thermal bind failure now uses the clearer M4T single-gimbal / single-component-index limitation string.
- `WaylineMqttPublisher` has focused tests for Cloud SDK OSD and HMS/event envelope shape, including explicit null serialization for `mode_code`.

Known gaps:

- OSD field semantics (`height`, `mode_code`) need real-device validation.
- `HmsReporter` is intentionally disabled by default because backend empty-list semantics are not audited.
- Boot auto-start needs field validation on the actual RC Plus + M4T setup.

### `ai-service`

Implemented:

- FastAPI health, task lifecycle, start/stop/query/events endpoints.
- Continuous OpenCV runner, optional YOLO visible detector, color heuristic fallback, thermal hotspot analyzer.
- Backend event reporting and fire-event snapshot/reporting path.
- RTSP pulling guidance and TCP capture option.

Known gaps:

- It still receives explicit stream URLs from backend/frontend callers. It does not yet discover DualStream group URLs by `droneSn`.
- No composite side-by-side slicer exists yet.
- Runtime task state is in memory.
- Model quality and production performance remain PoC-level.

### `deployment/zlmediakit`

Implemented:

- Repo-local Docker Compose scaffold.
- Current live path is a single stream named `{droneSn}-0`, not the older `{droneSn}_visible` / `{droneSn}_thermal` design.

Known gaps:

- ZLM source presence, WebRTC signaling, and browser playback still require end-to-end validation on the active LAN/IP.
- If ZLM `externIP` and backend playback host drift from the active LAN IP, WebRTC playback will fail.

## Most Relevant Current Documents

- `docs/MSDK_MIGRATION_PLAN.md`
- `docs/poc/pilot2-composite-stream.md`
- `docs/MSDK_V5_THERMAL_DUAL_STREAM_RESEARCH.md`
- `docs/WAYLINE_AGENT_CONTRACT.md`
- `docs/WAYLINE_L1_L2_CONTRACT.md`
- `docs/COCKPIT_VISIBLE_LIVESTREAM_E2E_CHECKLIST.md`
- `AGENTS.md`

## Recommended Next Work

1. Run one full E2E validation on real hardware: agent RTMP -> ZLM -> backend DualStream URL -> cockpit WebRTC -> ai-service RTSP -> fire event.
2. Confirm `RC_PLUS_LOCAL` versus real aircraft SN alignment across agent heartbeat, ZLM stream id, backend DualStream group, cockpit group fetch, and AI task URL.
3. Validate OSD field semantics (`height`, `elevation`, `mode_code`, battery shape) with real RC Plus + M4T MQTT payloads.
4. Decide the dual-modal AI route: side-by-side slicer first, Cloud SDK dual-stream PoC first, or accept visible-only phase 1.
5. Replace the in-memory DualStream command slot with a durable command queue if the agent path moves beyond field PoC.
