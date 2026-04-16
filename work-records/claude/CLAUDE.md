# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

---

## Project Overview

**智能集群大载重无人机灭火系统** — An intelligent cluster heavy-lift UAV firefighting system. The project integrates DJI Cloud API (Java/Spring Boot) with custom fire-mission dispatch, simulation, and Android remote-control capabilities.

---

## Sub-Project Map & Commands

### `DJI-Cloud-API-Demo-main/` — DJI Cloud API Server (Port 6789)
Java 11, Spring Boot 2.7.12, two Maven modules: `cloud-sdk` (library) + `sample` (application).

```bash
# Build
mvn package

# Run (from repo root, uses preconfigured MQTT broker addresses)
.\run_sample.ps1

# Or run directly
mvn -pl sample spring-boot:run \
  --mqtt.BASIC.host=192.168.50.10 --mqtt.BASIC.port=1883 \
  --mqtt.DRC.host=192.168.50.10 --mqtt.DRC.port=8083

# Test
mvn test
```

Key config: `sample/src/main/resources/application.yml`  
Database: MySQL `192.168.50.83:3306/cloud_sample` (user/pass: `g_byzt`)  
Redis: `localhost:6379`

### `Cloud-API-Demo-Web-main/` — DJI Web Frontend (Port 8080)
Vue 3 + Vite + Ant Design Vue 2, targets the Cloud API Demo server at port 6789.

```bash
npm install
npm run serve        # dev server → http://localhost:8080
npm run build        # production build
npm run build:test   # staging build
npm run lint         # eslint --fix
```

Key config: `env/.env` sets `VITE_APP_APIGATEWAY_BACKEND_HOST`.  
AMap key: `src/api/http/config.ts` → `CURRENT_CONFIG.amapKey`.

### `cloud_server/` — Custom Business Server (Port 8200)
Java 17, Spring Boot 3.3.2, MyBatis + MySQL. Receives telemetry from DJI Cloud API Demo and dispatches fire-suppression missions.

```bash
mvn -DskipTests package
.\run_cloud_server.ps1   # builds then runs with bundled JDK 17

# Or run JAR
java -jar target/cloud_server-0.1.0-SNAPSHOT.jar

mvn test
```

Key config: `src/main/resources/application.yml`  
Database: MySQL `192.168.50.83:3306/cloud_server` (user/pass: `g_byzt`)  
Schema: `sql/cloud_server_schema.sql`

### `AI/uav-fire-sim/` — UAV Simulation Backend (Port 8080)
Java 17, Spring Boot 3.3.8, multi-module Maven (`common`, `sim`, `server`). Provides pure-Java UAV physics simulation or DJI device ingress.

```bash
mvn package
mvn -pl server spring-boot:run   # or run built JAR

mvn test
```

Key config: `server/src/main/resources/application.yml`  
Toggle sim vs real DJI: `uav.provider: sim` (or `dji`).

### `AI/uav-fire-sim-frontend/` — Simulation Frontend (Port 5173)
Vue 3 + Leaflet, targets `uav-fire-sim` at port 8080.

```bash
npm install
npm run dev      # → http://localhost:5173
npm run build
npm run preview
```

### `backend/` — Python FastAPI Prototype (Port 8000)
In-memory prototype; no database.

```bash
pip install -r requirements.txt
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
# API docs: http://localhost:8000/docs
```

### `AI/android-gateway-msdkv5/` — Android MSDK V5 Gateway
DJI MSDK V5 (5.17.0) Android app acting as a hardware gateway.  
Build with Android Studio or `.\run-gradle.ps1`.  
App Key: `e52fff7ef65b3b904a05926c`. Backend URL: `http://10.0.2.2:8080` (AVD loopback to `uav-fire-sim`).

---

## Architecture

### Service Topology

```
DJI Hardware (Matrice 4T / FlyCart 100)
  ↓ MQTT plain 1883 (BASIC broker)
  ↓ MQTT WebSocket 8083 (DRC broker, real-time joystick)
DJI-Cloud-API-Demo-main  (port 6789)
  ↓ REST forward (optional, cloud-server.enabled=true)
cloud_server  (port 8200)  ←→  MySQL 192.168.50.83:3306/cloud_server

Android MSDK V5 Gateway
  ↓ HTTP REST
AI/uav-fire-sim  (port 8080)
  ↓ WebSocket /ws/telemetry (250 ms push)
AI/uav-fire-sim-frontend  (port 5173)

Cloud-API-Demo-Web (port 8080 dev)  →  DJI-Cloud-API-Demo-main (port 6789)
```

Shared local MQTT broker: Mosquitto in `tools/mosquitto/` at `192.168.50.10:1883`.

### `cloud-sdk` — Spring Integration Channel Architecture

`DJI-Cloud-API-Demo-main/cloud-sdk` is a library that wraps DJI's MQTT protocol as typed Spring Integration flows. Understanding its channel model is essential for adding new event handlers:

- **`ChannelName.java`** — single source of truth for all channel name constants.
- **`EventsMethodEnum.java`** — maps each MQTT `events` method string to its Spring channel. **Every** method listed here requires a corresponding `@ServiceActivator(inputChannel = ...)` bean in the `sample` module, otherwise the router throws `NoSuchBeanDefinitionException` at runtime.
- **`OsdRouter`** — fans OSD data (1 Hz telemetry) from `INBOUND_OSD` into device-type-specific sub-channels. Extends `AbstractDeviceService`, `AbstractControlService`, etc.
- Two MQTT connections are maintained simultaneously: `BASIC` (plain, port 1883) and `DRC` (WebSocket, port 8083). Configured via `MqttUseEnum` in `application.yml`.
- `INBOUND` and `INBOUND_OSD` are `ExecutorChannel` (multi-threaded). All others are `DirectChannel` (synchronous).

**Pattern for new event types**: Create a `@Service` class extending the relevant `Abstract*Service` from `cloud-sdk`. The abstract class already declares the `@ServiceActivator` — the concrete `@Service` bean activates it. See `SDKAirsenseService`, `SDKControlService`, `SDKRemoteDebug` for examples.

### `cloud_server` — Fire Dispatch Logic

Key class: `MissionAssignmentService.assignMission(FireEvent)`. On fire event receipt:
1. Queries eligible FC100/FLYCART100 devices (`status IN [IDLE, ACTIVE]`, `battery >= 30`, has GPS location).
2. Ranks by: IDLE > closer Haversine distance > higher battery.
3. Creates `Mission`, `MissionDevice`, and `MissionWaypoint` records, fires suppression.

`CloudPayloadMapper` handles field-name aliasing for raw DJI JSON payloads (tries `deviceId`, `device_id`, `sn`, `gatewaySn`, nested `data.*` etc.). See `PAYLOAD_MAPPING.md` for full alias tables.

### `GatewayTypeEnum.RC2` — Custom RC Plus 2 Split

**Non-obvious critical addition**: The original DJI SDK treats RC and RC Plus 2 identically. This codebase adds `GatewayTypeEnum.RC2` to distinguish RC Plus 2 hardware. When modifying `SDKDeviceService.updateTopoOnline()` or gateway type checks, always handle `RC2` alongside `RC`.

The `updateTopoOffline` method has a null-guard for `type == null` because M4T sends offline events with `null` type. In that case it reuses the existing SDK registration from `SDKManager`.

### MQTT Topic Conventions (DJI)

| Purpose | Topic Pattern |
|---|---|
| Device status on/offline | `sys/product/{sn}/status` |
| OSD telemetry | `thing/product/{sn}/osd` |
| Service calls (commands) | `thing/product/{sn}/services` |
| Service replies | `thing/product/{sn}/services_reply` |
| Events (async results) | `thing/product/{sn}/events` |
| Device requests | `thing/product/{sn}/requests` |

### Map Coordinate System

AMap (高德地图) uses **GCJ-02** (China's offset coordinate system). Drone OSD GPS data uses **WGS-84**. Conversions between the two are required when placing markers or drawing routes. The frontend helper `updateCoordinates('gcj02-wgs84', req)` performs this in map element creation.

---

## Important Domain Enums

### `cloud_server`

| Enum | Values |
|---|---|
| `DeviceRole` | `M4T, M4E, M3TD, FC100, FLYCART100, REMOTE_CONTROL, DOCK, DRONE, GROUND_RTK, CHARGE_TRUCK, MESH, UNKNOWN` |
| `DeviceStatus` | `IDLE, ACTIVE, CHARGING, FAULT, OFFLINE` |
| `MissionType` | `RECON, FIRE_SUPPRESSION, SUPPLY` |
| `MissionStatus` | `PLANNED, DISPATCHED, IN_PROGRESS, COMPLETED, FAILED` |
| `AlertSeverity` | `LOW, MEDIUM, HIGH, CRITICAL` (≥350 °C thermal → CRITICAL; confidence ≥ 0.9 → HIGH) |

Only `FC100` and `FLYCART100` roles are eligible for fire suppression dispatch.

### `uav-fire-sim`

| Enum | Values |
|---|---|
| `UavMode` | `IDLE, TAKING_OFF, AIRBORNE, GOING_TO_TARGET, DROPPING, RETURNING_HOME, LANDING, ERROR` |
| `ControlCommand.Type` | `TAKEOFF, LAND, GO_HOME, GOTO, WAYPOINTS, DROP_PAYLOAD` |

Sim physics: cruise 15 m/s ±15%, climb 3.5 m/s, descent 3.0 m/s, 6 drops per drone, auto-drop within `max(10m, fire.radiusMeters)`, each drop reduces fire intensity by 0.35.

---

## Key Files Quick Reference

| File | Purpose |
|---|---|
| `DJI-Cloud-API-Demo-main/cloud-sdk/src/main/java/com/dji/sdk/mqtt/ChannelName.java` | All Spring Integration channel name constants |
| `DJI-Cloud-API-Demo-main/cloud-sdk/src/main/java/com/dji/sdk/mqtt/events/EventsMethodEnum.java` | Maps MQTT event method → channel; every entry needs a sample `@ServiceActivator` |
| `DJI-Cloud-API-Demo-main/sample/src/main/java/com/dji/sample/manage/service/impl/SDKDeviceService.java` | Device online/offline, topo updates, OSD dispatch — most-edited file |
| `DJI-Cloud-API-Demo-main/sample/src/main/java/com/dji/sample/control/service/impl/ControlServiceImpl.java` | takeoff_to_point, fly_to_point, authority grab |
| `Cloud-API-Demo-Web-main/src/pages/page-web/projects/tsa.vue` | Main UAV control panel (device list, takeoff, DRC, OSD) |
| `Cloud-API-Demo-Web-main/src/components/g-map/use-drone-control-ws-event.ts` | WebSocket DRC event handlers (JoystickInvalidNotify, DrcStatusNotify, etc.) |
| `Cloud-API-Demo-Web-main/src/hooks/use-g-map.ts` | AMap initialization with localStorage center persistence |
| `cloud_server/src/main/java/.../service/MissionAssignmentService.java` | Fire dispatch algorithm |
| `cloud_server/PAYLOAD_MAPPING.md` | Raw DJI payload field alias documentation |
| `AI/uav-fire-sim/server/src/main/resources/application.yml` | Sim config (provider, home coords, timeouts) |
