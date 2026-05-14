# M4T 真机接入与后端对接 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在当前仓库现有 `rcplus-msdk-agent/` 与 `ai-service/` 骨架之上，补齐“真机接入前置能力 + 后端协调接口 + AI 结果回传”这一轮可继续推进的基础集成层。

**Architecture:** 后端新增一个 `dual-stream` 协调层，负责保存 agent 状态、下发 start/stop/focus 命令、暴露 web 可读的 live group 视图，并接收 AI 事件；`rcplus-msdk-agent` 从“只会组装 DTO”升级为“能初始化真机网关、发现能力、周期上报”；`ai-service` 从本地自循环升级为“可将识别事件回传给后端”的最小闭环。整轮仍以“真机接入前置能力 + 服务边界打通”为目标，不承诺真实双流视频已跑通。

**Tech Stack:** Spring Boot, Redis, JUnit 5, Android Kotlin, Coroutines/Flow, Retrofit/OkHttp, FastAPI, pytest, pydantic-settings.

---

### Task 1: 在后端建立 dual-stream 协调域模型与 Redis 状态服务

**Files:**
- Create: `backend/uavfire/src/main/java/com/yx/uavfire/manage/model/dto/DualStreamAgentHeartbeatDTO.java`
- Create: `backend/uavfire/src/main/java/com/yx/uavfire/manage/model/dto/DualStreamAgentStatusDTO.java`
- Create: `backend/uavfire/src/main/java/com/yx/uavfire/manage/model/dto/DualStreamAgentCapabilityDTO.java`
- Create: `backend/uavfire/src/main/java/com/yx/uavfire/manage/model/dto/DualStreamLiveGroupDTO.java`
- Create: `backend/uavfire/src/main/java/com/yx/uavfire/manage/model/dto/DualStreamCommandDTO.java`
- Create: `backend/uavfire/src/main/java/com/yx/uavfire/manage/service/IDualStreamService.java`
- Create: `backend/uavfire/src/main/java/com/yx/uavfire/manage/service/impl/DualStreamServiceImpl.java`
- Create: `backend/uavfire/src/test/java/com/yx/uavfire/manage/service/DualStreamServiceImplTest.java`
- Test: `backend/uavfire/src/test/java/com/yx/uavfire/manage/service/DualStreamServiceImplTest.java`

- [ ] **Step 1: 先写失败测试，锁定 heartbeat/status/capability 会被合并成 live group 视图**

```java
@Test
void mergeAgentState_buildsLiveGroupSnapshot() {
    DualStreamServiceImpl service = new DualStreamServiceImpl();

    service.acceptHeartbeat("DRONE-001", new DualStreamAgentHeartbeatDTO()
            .setDroneSn("DRONE-001")
            .setConnectionState("STREAMING")
            .setSessionState("RUNNING"));
    service.acceptCapability("DRONE-001", new DualStreamAgentCapabilityDTO()
            .setDroneSn("DRONE-001")
            .setVisibleSupported(true)
            .setThermalSupported(true));

    DualStreamLiveGroupDTO group = service.getGroup("DRONE-001");

    assertEquals("DRONE-001", group.getDroneSn());
    assertEquals("STREAMING", group.getConnectionState());
    assertEquals("RUNNING", group.getSessionState());
    assertTrue(group.getVisibleSupported());
    assertTrue(group.getThermalSupported());
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl backend/uavfire -Dtest=DualStreamServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL，提示 `DualStreamServiceImpl` / `DualStreamAgentHeartbeatDTO` / `DualStreamLiveGroupDTO` 等类型不存在。

- [ ] **Step 3: 写最小 DTO 和服务接口**

```java
public class DualStreamAgentHeartbeatDTO {
    private String droneSn;
    private String connectionState;
    private String sessionState;
}

public class DualStreamAgentCapabilityDTO {
    private String droneSn;
    private Boolean visibleSupported;
    private Boolean thermalSupported;
}

public interface IDualStreamService {
    void acceptHeartbeat(String droneSn, DualStreamAgentHeartbeatDTO heartbeat);
    void acceptStatus(String droneSn, DualStreamAgentStatusDTO status);
    void acceptCapability(String droneSn, DualStreamAgentCapabilityDTO capability);
    DualStreamLiveGroupDTO getGroup(String droneSn);
}
```

- [ ] **Step 4: 写最小内存版实现，先别急着接 Redis**

```java
@Service
public class DualStreamServiceImpl implements IDualStreamService {
    private final Map<String, DualStreamLiveGroupDTO> groups = new ConcurrentHashMap<>();

    @Override
    public void acceptHeartbeat(String droneSn, DualStreamAgentHeartbeatDTO heartbeat) {
        DualStreamLiveGroupDTO group = groups.computeIfAbsent(droneSn, sn -> new DualStreamLiveGroupDTO().setDroneSn(sn));
        group.setConnectionState(heartbeat.getConnectionState());
        group.setSessionState(heartbeat.getSessionState());
    }
}
```

- [ ] **Step 5: 在同一个实现里补 status / capability 合并逻辑**

```java
@Override
public void acceptCapability(String droneSn, DualStreamAgentCapabilityDTO capability) {
    DualStreamLiveGroupDTO group = groups.computeIfAbsent(droneSn, sn -> new DualStreamLiveGroupDTO().setDroneSn(sn));
    group.setVisibleSupported(Boolean.TRUE.equals(capability.getVisibleSupported()));
    group.setThermalSupported(Boolean.TRUE.equals(capability.getThermalSupported()));
}

@Override
public DualStreamLiveGroupDTO getGroup(String droneSn) {
    return groups.get(droneSn);
}
```

- [ ] **Step 6: 重跑测试确认通过**

Run: `mvn -pl backend/uavfire -Dtest=DualStreamServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS

- [ ] **Step 7: 再把存储从纯内存切到 Redis Key + 内存 fallback**

```java
private static final String GROUP_KEY_PREFIX = "dual-stream:group:";

private void saveSnapshot(String droneSn, DualStreamLiveGroupDTO group) {
    groups.put(droneSn, group);
    if (stringRedisTemplate != null) {
        stringRedisTemplate.opsForValue().set(GROUP_KEY_PREFIX + droneSn, objectMapper.writeValueAsString(group));
    }
}
```

- [ ] **Step 8: 重跑测试并确认 fallback 逻辑不受影响**

Run: `mvn -pl backend/uavfire -Dtest=DualStreamServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS

- [ ] **Step 9: 提交**

```bash
git add backend/uavfire/src/main/java/com/yx/uavfire/manage/model/dto/DualStream*.java backend/uavfire/src/main/java/com/yx/uavfire/manage/service/IDualStreamService.java backend/uavfire/src/main/java/com/yx/uavfire/manage/service/impl/DualStreamServiceImpl.java backend/uavfire/src/test/java/com/yx/uavfire/manage/service/DualStreamServiceImplTest.java
git commit -m "feat: add backend dual-stream coordination state"
```

### Task 2: 为后端补 agent 回调入口与 web 侧 live group 查询/命令入口

**Files:**
- Create: `backend/uavfire/src/main/java/com/yx/uavfire/manage/controller/DualStreamController.java`
- Create: `backend/uavfire/src/test/java/com/yx/uavfire/manage/controller/DualStreamControllerTest.java`
- Modify: `backend/uavfire/src/main/java/com/yx/uavfire/manage/service/IDualStreamService.java`
- Modify: `backend/uavfire/src/main/java/com/yx/uavfire/manage/service/impl/DualStreamServiceImpl.java`
- Test: `backend/uavfire/src/test/java/com/yx/uavfire/manage/controller/DualStreamControllerTest.java`

- [ ] **Step 1: 先写失败测试，锁定 internal callback + web group 接口**

```java
@Test
void heartbeatEndpoint_updatesGroupSnapshot() throws Exception {
    mockMvc.perform(post("/manage/api/v1/dual-stream/agents/DRONE-001/heartbeat")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"droneSn\":\"DRONE-001\",\"connectionState\":\"STREAMING\",\"sessionState\":\"RUNNING\"}"))
            .andExpect(status().isOk());

    mockMvc.perform(get("/manage/api/v1/dual-stream/groups/DRONE-001"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.droneSn").value("DRONE-001"))
            .andExpect(jsonPath("$.data.sessionState").value("RUNNING"));
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -pl backend/uavfire -Dtest=DualStreamControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL，因为 `DualStreamController` 和对应路由尚不存在。

- [ ] **Step 3: 补 internal agent callback 路由**

```java
@RestController
@RequestMapping("${url.manage.prefix}${url.manage.version}/dual-stream")
public class DualStreamController {

    @Autowired
    private IDualStreamService dualStreamService;

    @PostMapping("/agents/{drone_sn}/heartbeat")
    public HttpResultResponse<Void> heartbeat(@PathVariable("drone_sn") String droneSn,
                                              @RequestBody DualStreamAgentHeartbeatDTO body) {
        dualStreamService.acceptHeartbeat(droneSn, body);
        return HttpResultResponse.success();
    }
}
```

- [ ] **Step 4: 补 status / capability / group 查询 / start-stop-focus 命令入口**

```java
@PostMapping("/agents/{drone_sn}/status")
public HttpResultResponse<Void> status(@PathVariable("drone_sn") String droneSn,
                                       @RequestBody DualStreamAgentStatusDTO body) { ... }

@PostMapping("/agents/{drone_sn}/capability")
public HttpResultResponse<Void> capability(@PathVariable("drone_sn") String droneSn,
                                           @RequestBody DualStreamAgentCapabilityDTO body) { ... }

@GetMapping("/groups/{drone_sn}")
public HttpResultResponse<DualStreamLiveGroupDTO> getGroup(@PathVariable("drone_sn") String droneSn) { ... }

@PostMapping("/groups/{drone_sn}/start")
public HttpResultResponse<DualStreamCommandDTO> start(@PathVariable("drone_sn") String droneSn) { ... }
```

- [ ] **Step 5: 在 service 里提供最小命令快照**

```java
DualStreamCommandDTO buildCommand(String droneSn, String action) {
    return new DualStreamCommandDTO()
            .setDroneSn(droneSn)
            .setAction(action)
            .setIssuedAt(System.currentTimeMillis());
}
```

- [ ] **Step 6: 重跑测试确认通过**

Run: `mvn -pl backend/uavfire -Dtest=DualStreamControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS

- [ ] **Step 7: 补一个 focused service test，确认 start/stop/focus 不会覆盖 live group 已有状态**

```java
@Test
void commandSnapshot_doesNotEraseGroupState() {
    DualStreamServiceImpl service = new DualStreamServiceImpl();
    service.acceptHeartbeat("DRONE-001", new DualStreamAgentHeartbeatDTO()
            .setDroneSn("DRONE-001")
            .setConnectionState("STREAMING")
            .setSessionState("RUNNING"));

    service.buildCommand("DRONE-001", "focus-visible");

    assertEquals("RUNNING", service.getGroup("DRONE-001").getSessionState());
}
```

- [ ] **Step 8: 运行 controller + service 两组测试**

Run: `mvn -pl backend/uavfire -Dtest=DualStreamControllerTest,DualStreamServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS

- [ ] **Step 9: 提交**

```bash
git add backend/uavfire/src/main/java/com/yx/uavfire/manage/controller/DualStreamController.java backend/uavfire/src/main/java/com/yx/uavfire/manage/service/IDualStreamService.java backend/uavfire/src/main/java/com/yx/uavfire/manage/service/impl/DualStreamServiceImpl.java backend/uavfire/src/test/java/com/yx/uavfire/manage/controller/DualStreamControllerTest.java backend/uavfire/src/test/java/com/yx/uavfire/manage/service/DualStreamServiceImplTest.java
git commit -m "feat: add backend dual-stream agent endpoints"
```

### Task 3: 让 rcplus-msdk-agent 从“组装 DTO”升级到“主动上报后端”

**Files:**
- Create: `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/api/AgentBackendConfig.kt`
- Create: `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/api/AgentReporter.kt`
- Create: `rcplus-msdk-agent/app/src/test/java/com/uavfire/rcplus/api/AgentReporterTest.kt`
- Modify: `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/api/AgentBackendClient.kt`
- Modify: `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/api/DualStreamApi.kt`
- Test: `rcplus-msdk-agent/app/src/test/java/com/uavfire/rcplus/api/AgentReporterTest.kt`

- [ ] **Step 1: 先写失败测试，锁定 heartbeat/status/capability 会真正走到 API**

```kotlin
@Test
fun reporter_postsHeartbeatToBackend() = runTest {
    val api = RecordingDualStreamApi()
    val client = AgentBackendClient(api)
    val reporter = AgentReporter(client)

    reporter.reportHeartbeat(
        droneSn = "DRONE-001",
        connectionState = AgentConnectionState.STREAMING,
        sessionState = DualStreamSessionState.RUNNING,
    )

    assertEquals("DRONE-001", api.lastHeartbeatDroneSn)
    assertEquals("RUNNING", api.lastHeartbeatBody?.sessionState)
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd rcplus-msdk-agent && export JAVA_HOME=/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/usr/local/share/android-commandlinetools ANDROID_SDK_ROOT=/usr/local/share/android-commandlinetools && ./gradlew :app:testDebugUnitTest --tests "*AgentReporterTest"`
Expected: FAIL，因为 `AgentReporter` 尚不存在。

- [ ] **Step 3: 在 Retrofit 接口上补真正的 suspend 调用**

```kotlin
interface DualStreamApi {
    @POST("/manage/api/v1/dual-stream/agents/{droneSn}/heartbeat")
    suspend fun heartbeat(@Path("droneSn") droneSn: String, @Body body: AgentHeartbeatRequest)

    @POST("/manage/api/v1/dual-stream/agents/{droneSn}/status")
    suspend fun status(@Path("droneSn") droneSn: String, @Body body: AgentStatusRequest)

    @POST("/manage/api/v1/dual-stream/agents/{droneSn}/capability")
    suspend fun capability(@Path("droneSn") droneSn: String, @Body body: CapabilityReportRequest)
}
```

- [ ] **Step 4: 在 `AgentBackendClient` 中补 `sendHeartbeat/sendStatus/sendCapability`**

```kotlin
suspend fun sendHeartbeat(droneSn: String, connectionState: AgentConnectionState, sessionState: DualStreamSessionState) {
    val body = buildHeartbeatRequest(droneSn, connectionState, sessionState)
    api.heartbeat(droneSn, body)
}
```

- [ ] **Step 5: 写最小 `AgentReporter`，先只做显式调用，不做后台定时器**

```kotlin
class AgentReporter(
    private val client: AgentBackendClient,
) {
    suspend fun reportHeartbeat(droneSn: String, connectionState: AgentConnectionState, sessionState: DualStreamSessionState) {
        client.sendHeartbeat(droneSn, connectionState, sessionState)
    }
}
```

- [ ] **Step 6: 重跑测试确认通过**

Run: `cd rcplus-msdk-agent && export JAVA_HOME=/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/usr/local/share/android-commandlinetools ANDROID_SDK_ROOT=/usr/local/share/android-commandlinetools && ./gradlew :app:testDebugUnitTest --tests "*AgentReporterTest"`
Expected: PASS

- [ ] **Step 7: 再补 capability/status 的 focused 测试**

```kotlin
@Test
fun reporter_postsCapabilityFlags() = runTest {
    val api = RecordingDualStreamApi()
    val reporter = AgentReporter(AgentBackendClient(api))

    reporter.reportCapability(
        droneSn = "DRONE-001",
        capability = CameraCapability(true, true),
    )

    assertTrue(api.lastCapabilityBody?.visibleSupported == true)
    assertTrue(api.lastCapabilityBody?.thermalSupported == true)
}
```

- [ ] **Step 8: 运行完整 api 测试**

Run: `cd rcplus-msdk-agent && export JAVA_HOME=/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/usr/local/share/android-commandlinetools ANDROID_SDK_ROOT=/usr/local/share/android-commandlinetools && ./gradlew :app:testDebugUnitTest --tests "*AgentBackendClientTest" --tests "*AgentReporterTest"`
Expected: PASS

- [ ] **Step 9: 提交**

```bash
git add rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/api rcplus-msdk-agent/app/src/test/java/com/uavfire/rcplus/api
git commit -m "feat: add rcplus backend reporting flow"
```

### Task 4: 在 rcplus-msdk-agent 内补真机接入前置能力查询与会话编排入口

**Files:**
- Create: `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/sdk/DjiDeviceState.kt`
- Create: `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/sdk/DjiDeviceSession.kt`
- Create: `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/stream/RealMsdkStreamProvider.kt`
- Create: `rcplus-msdk-agent/app/src/test/java/com/uavfire/rcplus/sdk/DjiDeviceSessionTest.kt`
- Modify: `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/sdk/DjiSdkGateway.kt`
- Modify: `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/sdk/DjiSdkGatewayImpl.kt`
- Modify: `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/session/DualStreamSessionManager.kt`
- Test: `rcplus-msdk-agent/app/src/test/java/com/uavfire/rcplus/sdk/DjiDeviceSessionTest.kt`

- [ ] **Step 1: 先写失败测试，锁定“网关初始化成功后可返回 capability-ready 状态”**

```kotlin
@Test
fun deviceSession_marksCapabilityReadyAfterGatewayInit() = runTest {
    val session = DjiDeviceSession(
        gateway = FakeDjiSdkGateway(
            initialized = true,
            capability = CameraCapability(true, true),
        ),
    )

    val state = session.initialize()

    assertEquals(AgentConnectionState.CAPABILITY_READY, state.connectionState)
    assertTrue(state.capability.visibleSupported)
    assertTrue(state.capability.thermalSupported)
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd rcplus-msdk-agent && export JAVA_HOME=/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/usr/local/share/android-commandlinetools ANDROID_SDK_ROOT=/usr/local/share/android-commandlinetools && ./gradlew :app:testDebugUnitTest --tests "*DjiDeviceSessionTest"`
Expected: FAIL，因为 `DjiDeviceSession` / `DjiDeviceState` 尚不存在。

- [ ] **Step 3: 给 gateway 接口补上最小“设备接入态”查询**

```kotlin
interface DjiSdkGateway {
    suspend fun initialize(): Boolean
    suspend fun loadCapability(): CameraCapability
    suspend fun isAircraftConnected(): Boolean
}
```

- [ ] **Step 4: 写最小 `DjiDeviceSession` 和 `DjiDeviceState`**

```kotlin
data class DjiDeviceState(
    val connectionState: AgentConnectionState,
    val capability: CameraCapability,
)

class DjiDeviceSession(
    private val gateway: DjiSdkGateway,
) {
    suspend fun initialize(): DjiDeviceState {
        if (!gateway.initialize() || !gateway.isAircraftConnected()) {
            return DjiDeviceState(AgentConnectionState.SDK_READY, CameraCapability(false, false))
        }
        val capability = gateway.loadCapability()
        return DjiDeviceState(AgentConnectionState.CAPABILITY_READY, capability)
    }
}
```

- [ ] **Step 5: 写 `RealMsdkStreamProvider` 占位类，显式注明等待真机视频 SDK 接入**

```kotlin
class RealMsdkStreamProvider : StreamProvider {
    override suspend fun start(droneSn: String) {
        // Reserved for true MSDK video feed binding.
    }

    override suspend fun stop() {
        // Reserved for true MSDK video feed unbinding.
    }
}
```

- [ ] **Step 6: 重跑测试确认通过**

Run: `cd rcplus-msdk-agent && export JAVA_HOME=/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/usr/local/share/android-commandlinetools ANDROID_SDK_ROOT=/usr/local/share/android-commandlinetools && ./gradlew :app:testDebugUnitTest --tests "*DjiDeviceSessionTest"`
Expected: PASS

- [ ] **Step 7: 把会话初始化和 reporter 串起来，但先只做到“显式入口可调用”**

```kotlin
suspend fun initializeAndReport(droneSn: String, session: DjiDeviceSession, reporter: AgentReporter) {
    val state = session.initialize()
    reporter.reportCapability(droneSn, state.capability)
    reporter.reportStatus(droneSn, state.connectionState, "device-session-ready")
}
```

- [ ] **Step 8: 运行完整 Android 单元测试**

Run: `cd rcplus-msdk-agent && export JAVA_HOME=/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/usr/local/share/android-commandlinetools ANDROID_SDK_ROOT=/usr/local/share/android-commandlinetools && ./gradlew :app:testDebugUnitTest`
Expected: PASS

- [ ] **Step 9: 提交**

```bash
git add rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/sdk rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/stream rcplus-msdk-agent/app/src/test/java/com/uavfire/rcplus/sdk
git commit -m "feat: add rcplus device integration entrypoint"
```

### Task 5: 让 ai-service 能把识别事件回传给后端

**Files:**
- Create: `ai-service/app/clients/__init__.py`
- Create: `ai-service/app/clients/backend_client.py`
- Create: `ai-service/tests/test_backend_client.py`
- Create: `backend/uavfire/src/main/java/com/yx/uavfire/manage/model/dto/DualStreamEventDTO.java`
- Modify: `backend/uavfire/src/main/java/com/yx/uavfire/manage/controller/DualStreamController.java`
- Modify: `backend/uavfire/src/main/java/com/yx/uavfire/manage/service/IDualStreamService.java`
- Modify: `backend/uavfire/src/main/java/com/yx/uavfire/manage/service/impl/DualStreamServiceImpl.java`
- Test: `ai-service/tests/test_backend_client.py`
- Test: `backend/uavfire/src/test/java/com/yx/uavfire/manage/controller/DualStreamControllerTest.java`

- [ ] **Step 1: 先写失败测试，锁定 AI 事件上报请求结构**

```python
def test_backend_client_posts_dual_stream_event():
    transport = RecordingTransport()
    client = BackendClient(base_url="http://backend", transport=transport)

    client.report_event(
        task_id="task-001",
        payload={
            "drone_sn": "DRONE-001",
            "fusion_score": 0.712,
            "risk_level": "HIGH",
        },
    )

    assert transport.last_path == "/manage/api/v1/dual-stream/tasks/task-001/events"
    assert transport.last_json["risk_level"] == "HIGH"
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd ai-service && ./.venv/bin/python -m pytest tests/test_backend_client.py -q`
Expected: FAIL，因为 `BackendClient` 尚不存在。

- [ ] **Step 3: 写最小 `BackendClient`**

```python
class BackendClient:
    def __init__(self, base_url: str, transport: httpx.Client | None = None) -> None:
        self._base_url = base_url.rstrip("/")
        self._transport = transport or httpx.Client(base_url=self._base_url)

    def report_event(self, task_id: str, payload: dict) -> None:
        self._transport.post(f"/manage/api/v1/dual-stream/tasks/{task_id}/events", json=payload)
```

- [ ] **Step 4: 在后端 controller 补事件接收入口**

```java
@PostMapping("/tasks/{task_id}/events")
public HttpResultResponse<Void> acceptEvent(@PathVariable("task_id") String taskId,
                                            @RequestBody DualStreamEventDTO body) {
    dualStreamService.acceptEvent(taskId, body);
    return HttpResultResponse.success();
}
```

- [ ] **Step 5: 在 service 里把事件追加到 group 快照下**

```java
public void acceptEvent(String taskId, DualStreamEventDTO event) {
    events.computeIfAbsent(taskId, key -> new ArrayList<>()).add(event);
}
```

- [ ] **Step 6: 重跑 Python + Java 测试**

Run: `cd ai-service && ./.venv/bin/python -m pytest tests/test_backend_client.py -q`
Expected: PASS

Run: `mvn -pl backend/uavfire -Dtest=DualStreamControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS

- [ ] **Step 7: 提交**

```bash
git add ai-service/app/clients ai-service/tests/test_backend_client.py backend/uavfire/src/main/java/com/yx/uavfire/manage/model/dto/DualStreamEventDTO.java backend/uavfire/src/main/java/com/yx/uavfire/manage/controller/DualStreamController.java backend/uavfire/src/main/java/com/yx/uavfire/manage/service/IDualStreamService.java backend/uavfire/src/main/java/com/yx/uavfire/manage/service/impl/DualStreamServiceImpl.java backend/uavfire/src/test/java/com/yx/uavfire/manage/controller/DualStreamControllerTest.java
git commit -m "feat: connect ai service events to backend"
```

### Task 6: 做跨工程验证、更新 handoff，并记录真机前置条件

**Files:**
- Modify: `WORK_RECORD.md`
- Create: `HANDOFF_2026-04-21_M4T_DEVICE_AND_BACKEND_NEXT_PHASE.md`
- Test: `backend/uavfire/src/test/java/com/yx/uavfire/manage/service/DualStreamServiceImplTest.java`
- Test: `backend/uavfire/src/test/java/com/yx/uavfire/manage/controller/DualStreamControllerTest.java`
- Test: `rcplus-msdk-agent/app/src/test/java/com/uavfire/rcplus/api/AgentReporterTest.kt`
- Test: `rcplus-msdk-agent/app/src/test/java/com/uavfire/rcplus/sdk/DjiDeviceSessionTest.kt`
- Test: `ai-service/tests/test_backend_client.py`

- [ ] **Step 1: 跑后端 focused 测试**

Run: `mvn -pl backend/uavfire -Dtest=DualStreamServiceImplTest,DualStreamControllerTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS

- [ ] **Step 2: 跑 Android focused 测试**

Run: `cd rcplus-msdk-agent && export JAVA_HOME=/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/usr/local/share/android-commandlinetools ANDROID_SDK_ROOT=/usr/local/share/android-commandlinetools && ./gradlew :app:testDebugUnitTest --tests "*AgentReporterTest" --tests "*DjiDeviceSessionTest"`
Expected: PASS

- [ ] **Step 3: 跑 AI focused 测试**

Run: `cd ai-service && ./.venv/bin/python -m pytest tests/test_backend_client.py tests/test_routes.py tests/test_task_lifecycle.py tests/test_fusion_service.py tests/test_settings.py -q`
Expected: PASS

- [ ] **Step 4: 把“已完成 / 已验证 / 未验证 / 现场前置条件”写进 handoff**

```md
## 已完成
- rcplus mock 状态机 / capability / api contract
- ai-service FastAPI / lifecycle / fusion / settings

## 已验证
- Android unit tests
- AI local tests
- /healthz

## 未验证
- 真机视频流
- MSDK 真机 capability
- 后端 dual-stream agent endpoints

## 现场前置条件
- RC Plus 2 安装真机 app
- M4T 已连控
- DRC 权限已开
```

- [ ] **Step 5: 更新 `WORK_RECORD.md` 追加下一轮计划入口**

```md
### 9.x 下一轮建议
- 优先做 backend dual-stream coordination
- 再做 rcplus reporter + true-device session
- 最后做 ai-service backend callback
```

- [ ] **Step 6: 提交**

```bash
git add WORK_RECORD.md HANDOFF_2026-04-21_M4T_DEVICE_AND_BACKEND_NEXT_PHASE.md
git commit -m "docs: add next phase device and backend handoff"
```
