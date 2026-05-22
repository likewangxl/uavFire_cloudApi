# MSDK-First TSA Device Control Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the `/tsa` page's Cloud API device/OSD/control dependency with an MSDK Agent first data plane so online aircraft, map position, OSD panels, and flight controls work without Pilot 2 Cloud API.

**Architecture:** RC Plus MSDK Agent becomes the source of truth for aircraft online state, telemetry, capabilities, and control execution. Backend exposes stable MSDK-native REST/SSE/WebSocket contracts and stores latest device state in Redis; frontend `/tsa` consumes these contracts directly instead of pretending the aircraft is a Cloud API device.

**Tech Stack:** Android Kotlin DJI MSDK v5, Spring Boot Java 11, Redis, Vue 3/Vuex, Ant Design Vue, existing AMap map layer.

---

## Scope And Rules

- Remove `/tsa` runtime dependency on Cloud API TSA topology, Cloud API live capacity, Cloud API DRC control, and Cloud API device online state.
- Do not remove Cloud API code globally in one sweep; isolate and stop using it from the MSDK-first `/tsa` path first.
- MSDK standard means command names, telemetry fields, and capability flags should describe real MSDK capabilities, not DJI Cloud API topic names.
- The first production path is one RC Plus controlling one M4T aircraft.
- Keep compatibility only at UI layout level. Do not build new backend APIs that merely mimic Cloud API request names.

## Target Contracts

### MSDK Device State

Create an MSDK-native backend DTO:

```json
{
  "gatewaySn": "RC_PLUS_LOCAL",
  "aircraftSn": "1581F7K3D249E00AM3Q3",
  "online": true,
  "connectionState": "CONNECTED",
  "model": "M4T",
  "mode": "MANUAL",
  "latitude": 34.123456,
  "longitude": 108.123456,
  "height": 25.4,
  "elevation": 422.1,
  "homeDistance": 31.2,
  "horizontalSpeed": 0.1,
  "verticalSpeed": 0.0,
  "windSpeed": 0.0,
  "batteryPercent": 82,
  "gpsCount": 21,
  "rtkCount": 0,
  "positionFixed": true,
  "updatedAt": 1779380000000,
  "capabilities": {
    "takeoff": true,
    "returnHome": true,
    "flyToPoint": true,
    "gimbal": true,
    "camera": true,
    "visibleStream": true,
    "thermalFocus": true,
    "thermalSecondStream": false
  }
}
```

### MSDK Control Command

Use one backend endpoint family:

```http
POST /manage/api/v1/msdk/devices/{aircraftSn}/commands
```

Example body:

```json
{
  "command": "fly_to_point",
  "params": {
    "latitude": 34.123456,
    "longitude": 108.123456,
    "height": 50.0,
    "speed": 5.0
  }
}
```

Example response:

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "commandId": "msdk-1779380000000-001",
    "status": "ACCEPTED"
  }
}
```

## File Structure

- Modify `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/sdk/OsdReporter.kt`: collect and report MSDK telemetry with explicit fields.
- Modify `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/api/AgentBackendClient.kt`: send device state and command acknowledgements to backend.
- Modify `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/api/AgentRuntimeLoop.kt`: poll MSDK commands and execute them.
- Create `backend/uavfire/src/main/java/com/yx/uavfire/msdk/model/MsdkDeviceStateDTO.java`: backend device state DTO.
- Create `backend/uavfire/src/main/java/com/yx/uavfire/msdk/model/MsdkCommandParam.java`: backend command request DTO.
- Create `backend/uavfire/src/main/java/com/yx/uavfire/msdk/model/MsdkCommandDTO.java`: backend command state DTO.
- Create `backend/uavfire/src/main/java/com/yx/uavfire/msdk/service/MsdkDeviceStateService.java`: stores latest MSDK state and broadcasts frontend updates.
- Create `backend/uavfire/src/main/java/com/yx/uavfire/msdk/controller/MsdkDeviceController.java`: frontend-facing and agent-facing REST APIs.
- Create `frontend/src/api/msdk-device.ts`: TypeScript API client for MSDK device state and commands.
- Modify `frontend/src/store/index.ts`: add MSDK device state module or mutations.
- Modify `frontend/src/pages/page-web/projects/tsa.vue`: render online devices from MSDK state, not Cloud API store only.
- Modify `frontend/src/hooks/use-g-map-tsa.ts`: move marker by MSDK latitude/longitude.
- Modify `frontend/src/components/g-map/DroneControlPanel.vue`: route control buttons to MSDK command endpoint.
- Add tests under `backend/uavfire/src/test/java/com/yx/uavfire/msdk/`.
- Add frontend static/behavior tests under `frontend/scripts/tsa-msdk-device.test.mjs`.
- Add Android unit tests under `rcplus-msdk-agent/app/src/test/java/com/yinxin/uavfir/`.

---

### Task 1: Backend MSDK Device State API

**Files:**
- Create: `backend/uavfire/src/main/java/com/yx/uavfire/msdk/model/MsdkDeviceStateDTO.java`
- Create: `backend/uavfire/src/main/java/com/yx/uavfire/msdk/service/MsdkDeviceStateService.java`
- Create: `backend/uavfire/src/main/java/com/yx/uavfire/msdk/controller/MsdkDeviceController.java`
- Test: `backend/uavfire/src/test/java/com/yx/uavfire/msdk/MsdkDeviceStateServiceTest.java`

- [ ] **Step 1: Write the failing service test**

```java
package com.yx.uavfire.msdk;

import com.yx.uavfire.msdk.model.MsdkDeviceStateDTO;
import com.yx.uavfire.msdk.service.MsdkDeviceStateService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MsdkDeviceStateServiceTest {
    @Test
    void upsertAndListOnlineAircraftState() {
        MsdkDeviceStateService service = new MsdkDeviceStateService();
        MsdkDeviceStateDTO state = new MsdkDeviceStateDTO();
        state.setGatewaySn("RC_PLUS_LOCAL");
        state.setAircraftSn("1581F7K3D249E00AM3Q3");
        state.setOnline(true);
        state.setConnectionState("CONNECTED");
        state.setLatitude(34.123456);
        state.setLongitude(108.123456);
        state.setBatteryPercent(82);
        state.setUpdatedAt(1779380000000L);

        service.upsert(state);

        assertEquals(1, service.listOnline().size());
        MsdkDeviceStateDTO restored = service.get("1581F7K3D249E00AM3Q3").orElseThrow();
        assertEquals("RC_PLUS_LOCAL", restored.getGatewaySn());
        assertEquals(34.123456, restored.getLatitude());
        assertEquals(108.123456, restored.getLongitude());
        assertEquals(82, restored.getBatteryPercent());
    }

    @Test
    void staleOrDisconnectedDeviceIsNotListedOnline() {
        MsdkDeviceStateService service = new MsdkDeviceStateService();
        MsdkDeviceStateDTO state = new MsdkDeviceStateDTO();
        state.setAircraftSn("AIRCRAFT-1");
        state.setOnline(false);
        state.setConnectionState("DISCONNECTED");
        state.setUpdatedAt(1779380000000L);

        service.upsert(state);

        assertTrue(service.listOnline().isEmpty());
    }
}
```

- [ ] **Step 2: Run the failing test**

Run:

```bash
cd backend
JAVA_HOME=/usr/local/opt/openjdk@11 mvn -pl uavfire -Dtest=MsdkDeviceStateServiceTest test
```

Expected: FAIL because `MsdkDeviceStateService` and DTOs do not exist.

- [ ] **Step 3: Implement DTO and service**

Create `MsdkDeviceStateDTO.java`:

```java
package com.yx.uavfire.msdk.model;

import lombok.Data;

import java.util.Map;

@Data
public class MsdkDeviceStateDTO {
    private String gatewaySn;
    private String aircraftSn;
    private Boolean online;
    private String connectionState;
    private String model;
    private String mode;
    private Double latitude;
    private Double longitude;
    private Double height;
    private Double elevation;
    private Double homeDistance;
    private Double horizontalSpeed;
    private Double verticalSpeed;
    private Double windSpeed;
    private Integer batteryPercent;
    private Integer gpsCount;
    private Integer rtkCount;
    private Boolean positionFixed;
    private Long updatedAt;
    private Map<String, Boolean> capabilities;
}
```

Create `MsdkDeviceStateService.java`:

```java
package com.yx.uavfire.msdk.service;

import com.yx.uavfire.msdk.model.MsdkDeviceStateDTO;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MsdkDeviceStateService {
    private final Map<String, MsdkDeviceStateDTO> latestByAircraftSn = new ConcurrentHashMap<>();

    public void upsert(MsdkDeviceStateDTO state) {
        if (state == null || !StringUtils.hasText(state.getAircraftSn())) {
            return;
        }
        latestByAircraftSn.put(state.getAircraftSn(), state);
    }

    public Optional<MsdkDeviceStateDTO> get(String aircraftSn) {
        if (!StringUtils.hasText(aircraftSn)) {
            return Optional.empty();
        }
        return Optional.ofNullable(latestByAircraftSn.get(aircraftSn));
    }

    public List<MsdkDeviceStateDTO> listOnline() {
        List<MsdkDeviceStateDTO> result = new ArrayList<>();
        for (MsdkDeviceStateDTO state : latestByAircraftSn.values()) {
            if (Boolean.TRUE.equals(state.getOnline()) && !"DISCONNECTED".equalsIgnoreCase(state.getConnectionState())) {
                result.add(state);
            }
        }
        return result;
    }
}
```

- [ ] **Step 4: Add controller**

Create `MsdkDeviceController.java`:

```java
package com.yx.uavfire.msdk.controller;

import com.dji.sdk.common.HttpResultResponse;
import com.yx.uavfire.msdk.model.MsdkDeviceStateDTO;
import com.yx.uavfire.msdk.service.MsdkDeviceStateService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("${url.manage.prefix}${url.manage.version}/msdk/devices")
public class MsdkDeviceController {
    private final MsdkDeviceStateService stateService;

    public MsdkDeviceController(MsdkDeviceStateService stateService) {
        this.stateService = stateService;
    }

    @PostMapping("/state")
    public HttpResultResponse upsertState(@RequestBody MsdkDeviceStateDTO state) {
        stateService.upsert(state);
        return HttpResultResponse.success();
    }

    @GetMapping
    public HttpResultResponse<List<MsdkDeviceStateDTO>> listOnline() {
        return HttpResultResponse.success(stateService.listOnline());
    }

    @GetMapping("/{aircraftSn}")
    public HttpResultResponse<MsdkDeviceStateDTO> get(@PathVariable String aircraftSn) {
        return stateService.get(aircraftSn)
                .map(HttpResultResponse::success)
                .orElseGet(() -> HttpResultResponse.error("device not found"));
    }
}
```

- [ ] **Step 5: Run test**

Run:

```bash
cd backend
JAVA_HOME=/usr/local/opt/openjdk@11 mvn -pl uavfire -Dtest=MsdkDeviceStateServiceTest test
```

Expected: PASS.

### Task 2: Agent Reports MSDK State

**Files:**
- Modify: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/api/AgentBackendClient.kt`
- Modify: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/sdk/OsdReporter.kt`
- Test: `rcplus-msdk-agent/app/src/test/java/com/yinxin/uavfir/api/AgentBackendClientTest.kt`

- [ ] **Step 1: Add failing test for `/msdk/devices/state` payload**

```kotlin
@Test
fun `reports msdk device state to backend`() = runTest {
    val transport = RecordingTransport()
    val client = AgentBackendClient(baseUrl = "http://backend", transport = transport)

    client.reportMsdkDeviceState(
        aircraftSn = "1581F7K3D249E00AM3Q3",
        gatewaySn = "RC_PLUS_LOCAL",
        online = true,
        connectionState = "CONNECTED",
        latitude = 34.123456,
        longitude = 108.123456,
        batteryPercent = 82,
    )

    assertEquals("/manage/api/v1/msdk/devices/state", transport.lastPath)
    assertEquals("1581F7K3D249E00AM3Q3", transport.lastJson["aircraftSn"])
    assertEquals("RC_PLUS_LOCAL", transport.lastJson["gatewaySn"])
    assertEquals(true, transport.lastJson["online"])
    assertEquals(34.123456, transport.lastJson["latitude"])
    assertEquals(108.123456, transport.lastJson["longitude"])
    assertEquals(82, transport.lastJson["batteryPercent"])
}
```

- [ ] **Step 2: Run failing Android unit test**

Run:

```bash
cd rcplus-msdk-agent
./gradlew testDebugUnitTest --tests '*AgentBackendClientTest*'
```

Expected: FAIL because `reportMsdkDeviceState` does not exist.

- [ ] **Step 3: Implement AgentBackendClient method**

Add method:

```kotlin
suspend fun reportMsdkDeviceState(
    aircraftSn: String,
    gatewaySn: String,
    online: Boolean,
    connectionState: String,
    latitude: Double?,
    longitude: Double?,
    batteryPercent: Int?,
) {
    post(
        path = "/manage/api/v1/msdk/devices/state",
        body = mapOf(
            "gatewaySn" to gatewaySn,
            "aircraftSn" to aircraftSn,
            "online" to online,
            "connectionState" to connectionState,
            "latitude" to latitude,
            "longitude" to longitude,
            "batteryPercent" to batteryPercent,
            "updatedAt" to System.currentTimeMillis(),
            "capabilities" to mapOf(
                "takeoff" to true,
                "returnHome" to true,
                "flyToPoint" to true,
                "gimbal" to true,
                "camera" to true,
                "visibleStream" to true,
                "thermalFocus" to true,
                "thermalSecondStream" to false,
            ),
        ),
    )
}
```

- [ ] **Step 4: Wire OsdReporter to call it**

Map MSDK telemetry to these fields:

```kotlin
reporter.reportMsdkDeviceState(
    aircraftSn = aircraftSn,
    gatewaySn = "RC_PLUS_LOCAL",
    online = true,
    connectionState = "CONNECTED",
    latitude = latitude,
    longitude = longitude,
    batteryPercent = batteryPercent,
)
```

- [ ] **Step 5: Run tests**

Run:

```bash
cd rcplus-msdk-agent
./gradlew testDebugUnitTest --tests '*AgentBackendClientTest*'
```

Expected: PASS.

### Task 3: Frontend MSDK Device Store And TSA List

**Files:**
- Create: `frontend/src/api/msdk-device.ts`
- Modify: `frontend/src/store/index.ts`
- Modify: `frontend/src/pages/page-web/projects/tsa.vue`
- Test: `frontend/scripts/tsa-msdk-device.test.mjs`

- [ ] **Step 1: Write failing frontend static test**

```js
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const tsaSource = readFileSync(new URL('../src/pages/page-web/projects/tsa.vue', import.meta.url), 'utf8')
const storeSource = readFileSync(new URL('../src/store/index.ts', import.meta.url), 'utf8')
const apiSource = readFileSync(new URL('../src/api/msdk-device.ts', import.meta.url), 'utf8')

test('tsa loads online devices from msdk device api', () => {
  assert.match(apiSource, /listMsdkDevices/)
  assert.match(apiSource, /\\/msdk\\/devices/)
  assert.match(storeSource, /SET_MSDK_DEVICE_STATE/)
  assert.match(tsaSource, /listMsdkDevices/)
  assert.match(tsaSource, /msdkOnlineDevices/)
  assert.doesNotMatch(tsaSource, /topology.*Cloud API/i)
})
```

- [ ] **Step 2: Run failing test**

Run:

```bash
node --test frontend/scripts/tsa-msdk-device.test.mjs
```

Expected: FAIL because API/store wiring does not exist.

- [ ] **Step 3: Add frontend API**

Create `frontend/src/api/msdk-device.ts`:

```ts
import request, { IWorkspaceResponse } from '/@/api/http/request'

const HTTP_PREFIX = '/manage/api/v1'

export interface MsdkDeviceState {
  gatewaySn: string
  aircraftSn: string
  online: boolean
  connectionState: string
  model?: string
  mode?: string
  latitude?: number
  longitude?: number
  height?: number
  elevation?: number
  homeDistance?: number
  horizontalSpeed?: number
  verticalSpeed?: number
  windSpeed?: number
  batteryPercent?: number
  gpsCount?: number
  rtkCount?: number
  positionFixed?: boolean
  updatedAt?: number
  capabilities?: Record<string, boolean>
}

export async function listMsdkDevices (): Promise<IWorkspaceResponse<MsdkDeviceState[]>> {
  const result = await request.get(`${HTTP_PREFIX}/msdk/devices`)
  return result.data
}

export async function sendMsdkCommand (
  aircraftSn: string,
  command: string,
  params: Record<string, unknown> = {}
): Promise<IWorkspaceResponse<any>> {
  const result = await request.post(`${HTTP_PREFIX}/msdk/devices/${aircraftSn}/commands`, {
    command,
    params,
  })
  return result.data
}
```

- [ ] **Step 4: Add Vuex mutation**

Add to state:

```ts
msdkDeviceState: {
  devices: {} as Record<string, any>
}
```

Add mutation:

```ts
SET_MSDK_DEVICE_STATE (state, devices: any[]) {
  const next: Record<string, any> = {}
  for (const device of devices || []) {
    if (device.aircraftSn) {
      next[device.aircraftSn] = device
    }
  }
  state.msdkDeviceState.devices = next
}
```

- [ ] **Step 5: Wire `/tsa` to poll MSDK online devices**

In `frontend/src/pages/page-web/projects/tsa.vue`, import:

```ts
import { listMsdkDevices, type MsdkDeviceState } from '/@/api/msdk-device'
```

Add:

```ts
const msdkOnlineDevices = computed<MsdkDeviceState[]>(() => {
  return Object.values(store.state.msdkDeviceState.devices || {})
})

let msdkDeviceTimer: number | undefined

const refreshMsdkDevices = async () => {
  const res = await listMsdkDevices()
  if (res.code === 0) {
    store.commit('SET_MSDK_DEVICE_STATE', res.data || [])
  }
}

onMounted(() => {
  refreshMsdkDevices()
  msdkDeviceTimer = window.setInterval(refreshMsdkDevices, 2000)
})

onBeforeUnmount(() => {
  if (msdkDeviceTimer) {
    window.clearInterval(msdkDeviceTimer)
  }
})
```

Render `msdkOnlineDevices` in the online aircraft list when Cloud API `devices` is empty.

- [ ] **Step 6: Run frontend test**

Run:

```bash
node --test frontend/scripts/tsa-msdk-device.test.mjs
```

Expected: PASS.

### Task 4: Map Marker Uses MSDK Position

**Files:**
- Modify: `frontend/src/hooks/use-g-map-tsa.ts`
- Modify: `frontend/src/pages/page-web/projects/tsa.vue`
- Test: `frontend/scripts/tsa-msdk-device.test.mjs`

- [ ] **Step 1: Add failing test**

Append:

```js
test('tsa moves map markers from msdk latitude and longitude', () => {
  assert.match(tsaSource, /deviceTsaUpdate/)
  assert.match(tsaSource, /device\\.longitude/)
  assert.match(tsaSource, /device\\.latitude/)
  assert.match(tsaSource, /moveTo\\(device\\.aircraftSn/)
})
```

- [ ] **Step 2: Implement marker update**

In `/tsa` watch `msdkOnlineDevices`:

```ts
const tsaMap = deviceTsaUpdate()

watch(msdkOnlineDevices, (devices) => {
  for (const device of devices) {
    if (!device.aircraftSn || !device.longitude || !device.latitude) {
      continue
    }
    tsaMap.moveTo(device.aircraftSn, device.longitude, device.latitude)
  }
}, { deep: true })
```

- [ ] **Step 3: Run test**

Run:

```bash
node --test frontend/scripts/tsa-msdk-device.test.mjs
```

Expected: PASS.

### Task 5: MSDK Command Queue Backend

**Files:**
- Create: `backend/uavfire/src/main/java/com/yx/uavfire/msdk/model/MsdkCommandParam.java`
- Create: `backend/uavfire/src/main/java/com/yx/uavfire/msdk/model/MsdkCommandDTO.java`
- Modify: `backend/uavfire/src/main/java/com/yx/uavfire/msdk/service/MsdkDeviceStateService.java`
- Modify: `backend/uavfire/src/main/java/com/yx/uavfire/msdk/controller/MsdkDeviceController.java`
- Test: `backend/uavfire/src/test/java/com/yx/uavfire/msdk/MsdkCommandQueueTest.java`

- [ ] **Step 1: Write failing command queue test**

```java
package com.yx.uavfire.msdk;

import com.yx.uavfire.msdk.model.MsdkCommandDTO;
import com.yx.uavfire.msdk.model.MsdkCommandParam;
import com.yx.uavfire.msdk.service.MsdkDeviceStateService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MsdkCommandQueueTest {
    @Test
    void enqueueAndPollPendingCommand() {
        MsdkDeviceStateService service = new MsdkDeviceStateService();
        MsdkCommandParam param = new MsdkCommandParam();
        param.setCommand("return_home");
        param.setParams(Map.of());

        MsdkCommandDTO queued = service.enqueueCommand("AIRCRAFT-1", param);
        MsdkCommandDTO polled = service.pollCommand("AIRCRAFT-1").orElseThrow();

        assertEquals(queued.getCommandId(), polled.getCommandId());
        assertEquals("return_home", polled.getCommand());
        assertEquals("PENDING", polled.getStatus());
        assertTrue(service.pollCommand("AIRCRAFT-1").isEmpty());
    }
}
```

- [ ] **Step 2: Implement command DTOs and queue**

Create `MsdkCommandParam.java`:

```java
package com.yx.uavfire.msdk.model;

import lombok.Data;

import java.util.Map;

@Data
public class MsdkCommandParam {
    private String command;
    private Map<String, Object> params;
}
```

Create `MsdkCommandDTO.java`:

```java
package com.yx.uavfire.msdk.model;

import lombok.Data;

import java.util.Map;

@Data
public class MsdkCommandDTO {
    private String commandId;
    private String aircraftSn;
    private String command;
    private Map<String, Object> params;
    private String status;
    private Long createdAt;
    private Long updatedAt;
    private String message;
}
```

Add service methods:

```java
public MsdkCommandDTO enqueueCommand(String aircraftSn, MsdkCommandParam param) {
    MsdkCommandDTO dto = new MsdkCommandDTO();
    dto.setCommandId("msdk-" + System.currentTimeMillis());
    dto.setAircraftSn(aircraftSn);
    dto.setCommand(param.getCommand());
    dto.setParams(param.getParams());
    dto.setStatus("PENDING");
    dto.setCreatedAt(System.currentTimeMillis());
    dto.setUpdatedAt(dto.getCreatedAt());
    commandQueues.computeIfAbsent(aircraftSn, key -> new ConcurrentLinkedQueue<>()).add(dto);
    commandById.put(dto.getCommandId(), dto);
    return dto;
}

public Optional<MsdkCommandDTO> pollCommand(String aircraftSn) {
    Queue<MsdkCommandDTO> queue = commandQueues.get(aircraftSn);
    return queue == null ? Optional.empty() : Optional.ofNullable(queue.poll());
}
```

- [ ] **Step 3: Add controller endpoints**

```java
@PostMapping("/{aircraftSn}/commands")
public HttpResultResponse<MsdkCommandDTO> enqueueCommand(@PathVariable String aircraftSn,
                                                         @RequestBody MsdkCommandParam param) {
    return HttpResultResponse.success(stateService.enqueueCommand(aircraftSn, param));
}

@PostMapping("/{aircraftSn}/commands/poll")
public HttpResultResponse<MsdkCommandDTO> pollCommand(@PathVariable String aircraftSn) {
    return stateService.pollCommand(aircraftSn)
            .map(HttpResultResponse::success)
            .orElseGet(() -> HttpResultResponse.success(null));
}
```

- [ ] **Step 4: Run backend test**

Run:

```bash
cd backend
JAVA_HOME=/usr/local/opt/openjdk@11 mvn -pl uavfire -Dtest=MsdkCommandQueueTest test
```

Expected: PASS.

### Task 6: Agent Executes MSDK Commands

**Files:**
- Modify: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/api/AgentRuntimeLoop.kt`
- Modify: `rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/session/DualStreamSessionManager.kt`
- Test: `rcplus-msdk-agent/app/src/test/java/com/yinxin/uavfir/api/AgentRuntimeLoopTest.kt`

- [ ] **Step 1: Add command polling test**

```kotlin
@Test
fun `runtime loop polls and dispatches msdk command`() = runTest {
    val backend = FakeAgentBackendClient(
        nextCommand = AgentCommand(
            commandId = "cmd-1",
            command = "return_home",
            params = emptyMap(),
        )
    )
    val executor = RecordingMsdkCommandExecutor()
    val loop = AgentRuntimeLoop(backendClient = backend, commandExecutor = executor)

    loop.tickOnce("1581F7K3D249E00AM3Q3")

    assertEquals("return_home", executor.lastCommand)
    assertEquals("cmd-1", backend.lastAckCommandId)
    assertEquals("APPLIED", backend.lastAckStatus)
}
```

- [ ] **Step 2: Implement command executor mapping**

Supported first commands:

```kotlin
when (command.command) {
    "return_home" -> flightController.startGoHome()
    "cancel_return_home" -> flightController.stopGoHome()
    "takeoff" -> flightController.startTakeoff()
    "fly_to_point" -> missionOperator.flyToPoint(
        latitude = command.params["latitude"] as Double,
        longitude = command.params["longitude"] as Double,
        height = command.params["height"] as Double,
        speed = command.params["speed"] as Double,
    )
    "focus_visible" -> dualStreamSessionManager.focusVisible()
    "focus_thermal" -> dualStreamSessionManager.focusThermal()
    else -> throw IllegalArgumentException("unsupported-msdk-command:${command.command}")
}
```

- [ ] **Step 3: Run Android tests**

Run:

```bash
cd rcplus-msdk-agent
./gradlew testDebugUnitTest --tests '*AgentRuntimeLoopTest*'
```

Expected: PASS.

### Task 7: Route TSA Control Buttons To MSDK Commands

**Files:**
- Modify: `frontend/src/components/g-map/DroneControlPanel.vue`
- Modify: `frontend/src/components/g-map/use-drone-control.ts`
- Test: `frontend/scripts/tsa-msdk-device.test.mjs`

- [ ] **Step 1: Add failing frontend test**

```js
test('drone control panel sends msdk commands instead of cloud api drone control', () => {
  const controlSource = readFileSync(new URL('../src/components/g-map/use-drone-control.ts', import.meta.url), 'utf8')
  assert.match(controlSource, /sendMsdkCommand/)
  assert.doesNotMatch(controlSource, /postTakeoffToPoint/)
  assert.doesNotMatch(controlSource, /postFlyToPoint/)
})
```

- [ ] **Step 2: Replace control hook**

Update `use-drone-control.ts`:

```ts
import { sendMsdkCommand } from '/@/api/msdk-device'

export function useDroneControl () {
  async function flyToPoint (sn: string, body: { latitude: number; longitude: number; height: number; speed?: number }) {
    return sendMsdkCommand(sn, 'fly_to_point', {
      latitude: body.latitude,
      longitude: body.longitude,
      height: body.height,
      speed: body.speed ?? 5,
    })
  }

  async function takeoffToPoint (sn: string, body: { latitude: number; longitude: number; height: number; speed?: number }) {
    await sendMsdkCommand(sn, 'takeoff', {})
    return sendMsdkCommand(sn, 'fly_to_point', {
      latitude: body.latitude,
      longitude: body.longitude,
      height: body.height,
      speed: body.speed ?? 5,
    })
  }

  async function returnHome (sn: string) {
    return sendMsdkCommand(sn, 'return_home', {})
  }

  return {
    flyToPoint,
    takeoffToPoint,
    returnHome,
  }
}
```

- [ ] **Step 3: Run frontend test**

Run:

```bash
node --test frontend/scripts/tsa-msdk-device.test.mjs
```

Expected: PASS.

### Task 8: Remove TSA Cloud API Runtime Dependencies

**Files:**
- Modify: `frontend/src/pages/page-web/projects/tsa.vue`
- Modify: `frontend/src/hooks/use-g-map-tsa.ts`
- Modify: `backend/uavfire/src/main/java/com/yx/uavfire/manage/controller/TopologyController.java`
- Test: `frontend/scripts/tsa-msdk-device.test.mjs`

- [ ] **Step 1: Add no-Cloud-API test**

```js
test('tsa page does not depend on cloud api topology or drc control path', () => {
  assert.doesNotMatch(tsaSource, /Topology/)
  assert.doesNotMatch(tsaSource, /getTopology/)
  assert.doesNotMatch(tsaSource, /drc/i)
  assert.doesNotMatch(tsaSource, /Cloud API/i)
})
```

- [ ] **Step 2: Remove topology bootstrapping from `/tsa`**

Delete calls that fetch topology for online devices. Keep static map layer loading and workspace element loading.

- [ ] **Step 3: Keep backend Cloud API controller unused**

Do not delete `TopologyController` in this task. Add `@Deprecated` and comment:

```java
@Deprecated
// Deprecated for MSDK-first RC Plus runtime. Do not use from /tsa.
```

- [ ] **Step 4: Run frontend test**

Run:

```bash
node --test frontend/scripts/tsa-msdk-device.test.mjs
```

Expected: PASS.

### Task 9: End-To-End Verification

**Files:**
- Modify: `RUNBOOK.md`
- Modify: `docs/MSDK_MIGRATION_PLAN.md`

- [ ] **Step 1: Start services**

Run:

```bash
screen -dmS uavfire-backend zsh -lc 'cd /Users/likewang/uavfire/backend && JAVA_HOME=/usr/local/opt/openjdk@11 mvn -pl uavfire spring-boot:run 2>&1 | tee /Users/likewang/uavfire/logs/backend-dev.log'
screen -dmS uavfire-frontend zsh -lc 'cd /Users/likewang/uavfire/frontend && npm run serve 2>&1 | tee /Users/likewang/uavfire/logs/frontend-dev.log'
```

Expected: backend listens on `6789`; frontend listens on `8080`.

- [ ] **Step 2: Confirm backend API**

Run:

```bash
curl -s http://127.0.0.1:6789/manage/api/v1/msdk/devices
```

Expected with token in browser: list contains the RC Plus aircraft after Agent is running.

- [ ] **Step 3: Confirm `/tsa` behavior**

Manual checks:

- Online device list shows aircraft SN `1581F7K3D249E00AM3Q3`.
- Device card shows battery, mode, height, GPS/RTK values.
- Map marker appears at aircraft longitude/latitude and moves as OSD updates.
- Control button sends `/manage/api/v1/msdk/devices/{sn}/commands`.
- Backend command queue receives the command.
- Agent executes or rejects the command with an explicit status.

- [ ] **Step 4: Run regression tests**

Run:

```bash
cd backend
JAVA_HOME=/usr/local/opt/openjdk@11 mvn -pl uavfire -Dtest=MsdkDeviceStateServiceTest,MsdkCommandQueueTest test
cd /Users/likewang/uavfire
node --test frontend/scripts/tsa-msdk-device.test.mjs
cd rcplus-msdk-agent
./gradlew testDebugUnitTest
```

Expected: all pass.

## Migration Result

After this plan, `/tsa` no longer needs these Cloud API assumptions:

- TSA topology to discover online aircraft.
- Cloud API OSD topics to populate aircraft state.
- DRC or Cloud API control endpoints for basic flight controls.
- Cloud API live capacity to decide whether aircraft exists.

The remaining Cloud API code can stay for old pages until explicitly deleted, but `/tsa` should become MSDK-first and usable with only RC Plus Agent plus backend plus frontend.

## Risks

- MSDK exact telemetry APIs may vary by aircraft model and firmware. If a telemetry field is unavailable, send `null` and do not block online status.
- Flight control commands require careful safety gates. Backend should reject commands when aircraft is disconnected, position is invalid, battery is too low, or command params are outside configured bounds.
- Browser `/tsa` currently assumes Cloud API-shaped `DeviceOsd`; the first frontend pass should adapt MSDK fields directly instead of backfilling fake Cloud API objects.
- Real-device command execution must be verified outdoors or in a DJI-approved safe test environment.
