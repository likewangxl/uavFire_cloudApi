# M4T Runtime Closure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有骨架基础上补齐后端协调器、`rcplus-msdk-agent` 真机最小闭环、`ai-service` 任务运行器三条主线，使双流 PoC 从“接口和占位实现”升级为“具备最小运行时闭环”。

**Architecture:** 后端 `dual-stream` 层升级为真正的 runtime coordinator，负责命令发放、命令确认、任务状态与事件聚合；`rcplus-msdk-agent` 增加命令拉取和命令执行回报入口，并把真实设备初始化与流启动串起来；`ai-service` 增加 task runner，把任务启动、检测事件生成与后端回传连接成持续运行链路。

**Tech Stack:** Spring Boot, Redis, Jackson, JUnit 5, Kotlin Coroutines/Flow, Retrofit/OkHttp, FastAPI, pytest, pydantic-settings.

---

### Task 1: 将 backend dual-stream 从状态缓存升级为运行时协调器

**Files:**
- Create: `backend/sample/src/main/java/com/dji/sample/manage/model/dto/DualStreamCommandAckDTO.java`
- Create: `backend/sample/src/main/java/com/dji/sample/manage/model/dto/DualStreamTaskDTO.java`
- Modify: `backend/sample/src/main/java/com/dji/sample/manage/model/dto/DualStreamLiveGroupDTO.java`
- Modify: `backend/sample/src/main/java/com/dji/sample/manage/service/IDualStreamService.java`
- Modify: `backend/sample/src/main/java/com/dji/sample/manage/service/impl/DualStreamServiceImpl.java`
- Modify: `backend/sample/src/main/java/com/dji/sample/manage/controller/DualStreamController.java`
- Modify: `backend/sample/src/test/java/com/dji/sample/manage/service/DualStreamServiceImplTest.java`
- Modify: `backend/sample/src/test/java/com/dji/sample/manage/controller/DualStreamControllerTest.java`
- Test: `backend/sample/src/test/java/com/dji/sample/manage/service/DualStreamServiceImplTest.java`
- Test: `backend/sample/src/test/java/com/dji/sample/manage/controller/DualStreamControllerTest.java`

- [ ] **Step 1: 先写失败测试，锁定命令会进入 pending 队列并可被 agent 拉取**

```java
@Test
void buildCommand_enqueuesPendingCommandForDrone() {
    DualStreamServiceImpl service = new DualStreamServiceImpl();

    DualStreamCommandDTO command = service.issueCommand("DRONE-001", "start");
    DualStreamCommandDTO pending = service.pollCommand("DRONE-001");

    assertEquals("start", command.getAction());
    assertNotNull(command.getCommandId());
    assertEquals(command.getCommandId(), pending.getCommandId());
    assertEquals("pending", pending.getStatus());
}
```

- [ ] **Step 2: 再写失败测试，锁定 ack 不会擦掉 group/task 现有状态**

```java
@Test
void acknowledgeCommand_updatesCommandStatusWithoutErasingGroupState() {
    DualStreamServiceImpl service = new DualStreamServiceImpl();
    service.acceptHeartbeat("DRONE-001", new DualStreamAgentHeartbeatDTO()
            .setDroneSn("DRONE-001")
            .setConnectionState("STREAMING")
            .setSessionState("RUNNING"));

    DualStreamCommandDTO command = service.issueCommand("DRONE-001", "focus-visible");
    service.acknowledgeCommand("DRONE-001", new DualStreamCommandAckDTO()
            .setCommandId(command.getCommandId())
            .setStatus("applied")
            .setMessage("visible channel active"));

    assertEquals("RUNNING", service.getGroup("DRONE-001").getSessionState());
    assertEquals("applied", service.pollCommand("DRONE-001").getStatus());
}
```

- [ ] **Step 3: 运行 service 测试，确认红灯**

Run: `cd backend && mvn -pl sample -Dtest=DualStreamServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL，提示 `issueCommand` / `pollCommand` / `acknowledgeCommand` / `DualStreamCommandAckDTO` / `commandId` / `status` 等不存在。

- [ ] **Step 4: 补最小 DTO 和接口签名**

```java
public class DualStreamCommandAckDTO {
    private String commandId;
    private String status;
    private String message;
}

public class DualStreamTaskDTO {
    private String taskId;
    private String droneSn;
    private String status;
}

public interface IDualStreamService {
    DualStreamCommandDTO issueCommand(String droneSn, String action);
    DualStreamCommandDTO pollCommand(String droneSn);
    void acknowledgeCommand(String droneSn, DualStreamCommandAckDTO ack);
}
```

- [ ] **Step 5: 在 `DualStreamCommandDTO` 与 `DualStreamLiveGroupDTO` 中补运行时字段**

```java
private String commandId;
private String status;
private String message;
private Long issuedAt;
private Long ackedAt;
```

```java
private String activeTaskId;
private String lastCommandAction;
private String lastCommandStatus;
```

- [ ] **Step 6: 在 `DualStreamServiceImpl` 中先实现内存版 command queue**

```java
private final Map<String, DualStreamCommandDTO> commandByDrone = new ConcurrentHashMap<>();

@Override
public DualStreamCommandDTO issueCommand(String droneSn, String action) {
    DualStreamCommandDTO command = new DualStreamCommandDTO()
            .setCommandId(UUID.randomUUID().toString())
            .setDroneSn(droneSn)
            .setAction(action)
            .setStatus("pending")
            .setIssuedAt(System.currentTimeMillis());
    commandByDrone.put(droneSn, command);
    return copyCommand(command);
}
```

- [ ] **Step 7: 补 `pollCommand` / `acknowledgeCommand`，并同步 group 状态**

```java
@Override
public DualStreamCommandDTO pollCommand(String droneSn) {
    return copyCommand(commandByDrone.get(droneSn));
}

@Override
public void acknowledgeCommand(String droneSn, DualStreamCommandAckDTO ack) {
    commandByDrone.computeIfPresent(droneSn, (sn, existing) -> {
        if (!Objects.equals(existing.getCommandId(), ack.getCommandId())) {
            return existing;
        }
        existing.setStatus(ack.getStatus())
                .setMessage(ack.getMessage())
                .setAckedAt(System.currentTimeMillis());
        mergeGroup(sn, group -> group
                .setLastCommandAction(existing.getAction())
                .setLastCommandStatus(existing.getStatus()));
        return existing;
    });
}
```

- [ ] **Step 8: 重跑 service 测试，确认转绿**

Run: `cd backend && mvn -pl sample -Dtest=DualStreamServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS

- [ ] **Step 9: 写 controller 失败测试，锁定命令拉取与 ack 路由**

```java
@Test
void commandEndpoints_supportIssuePollAndAck() throws Exception {
    when(dualStreamService.issueCommand("DRONE-001", "start"))
            .thenReturn(new DualStreamCommandDTO().setDroneSn("DRONE-001").setAction("start").setStatus("pending"));
    when(dualStreamService.pollCommand("DRONE-001"))
            .thenReturn(new DualStreamCommandDTO().setDroneSn("DRONE-001").setAction("start").setStatus("pending"));

    mockMvc.perform(post("/manage/api/v1/dual-stream/groups/DRONE-001/start"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("pending"));

    mockMvc.perform(get("/manage/api/v1/dual-stream/agents/DRONE-001/command"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.action").value("start"));
}
```

- [ ] **Step 10: 在 controller 中补命令拉取和 ack 路由**

```java
@GetMapping("/agents/{drone_sn}/command")
public HttpResultResponse<DualStreamCommandDTO> pollCommand(@PathVariable("drone_sn") String droneSn) {
    return HttpResultResponse.success(dualStreamService.pollCommand(droneSn));
}

@PostMapping("/agents/{drone_sn}/command/ack")
public HttpResultResponse<Void> acknowledgeCommand(@PathVariable("drone_sn") String droneSn,
                                                   @RequestBody DualStreamCommandAckDTO body) {
    dualStreamService.acknowledgeCommand(droneSn, body);
    return HttpResultResponse.success();
}
```

- [ ] **Step 11: 把 `start/stop/focus` 入口改成 `issueCommand`，不再只返回临时快照**

```java
@PostMapping("/groups/{drone_sn}/start")
public HttpResultResponse<DualStreamCommandDTO> start(@PathVariable("drone_sn") String droneSn) {
    return HttpResultResponse.success(dualStreamService.issueCommand(droneSn, "start"));
}
```

- [ ] **Step 12: 运行 controller + service 两组测试**

Run: `cd backend && mvn -pl sample -Dtest=DualStreamControllerTest,DualStreamServiceImplTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS

- [ ] **Step 13: 提交**

```bash
git add backend/sample/src/main/java/com/dji/sample/manage/model/dto/DualStream*.java backend/sample/src/main/java/com/dji/sample/manage/service/IDualStreamService.java backend/sample/src/main/java/com/dji/sample/manage/service/impl/DualStreamServiceImpl.java backend/sample/src/main/java/com/dji/sample/manage/controller/DualStreamController.java backend/sample/src/test/java/com/dji/sample/manage/service/DualStreamServiceImplTest.java backend/sample/src/test/java/com/dji/sample/manage/controller/DualStreamControllerTest.java
git commit -m "feat: add dual-stream runtime coordinator"
```

### Task 2: 让 rcplus-msdk-agent 形成“命令拉取 + 真机初始化 + 执行回报”最小闭环

**Files:**
- Create: `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/api/AgentCommandAckRequest.kt`
- Create: `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/api/AgentCommandResponse.kt`
- Create: `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/api/CommandPollingCoordinator.kt`
- Modify: `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/api/DualStreamApi.kt`
- Modify: `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/api/AgentBackendClient.kt`
- Modify: `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/api/AgentReporter.kt`
- Modify: `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/sdk/DjiSdkGatewayImpl.kt`
- Modify: `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/session/DualStreamSessionManager.kt`
- Create: `rcplus-msdk-agent/app/src/test/java/com/uavfire/rcplus/api/CommandPollingCoordinatorTest.kt`
- Test: `rcplus-msdk-agent/app/src/test/java/com/uavfire/rcplus/api/CommandPollingCoordinatorTest.kt`

- [ ] **Step 1: 先写失败测试，锁定 agent 能拉到 start 命令并回 ack**

```kotlin
@Test
fun coordinator_pollsStartCommand_andAcknowledgesApplied() = runTest {
    val api = RecordingDualStreamApi(
        nextCommand = AgentCommandResponse(
            commandId = "cmd-1",
            droneSn = "DRONE-001",
            action = "start",
            status = "pending",
        ),
    )
    val coordinator = CommandPollingCoordinator(
        client = AgentBackendClient(api),
        sessionManager = DualStreamSessionManager(MockStreamProvider()),
    )

    coordinator.pollOnce("DRONE-001")

    assertEquals("cmd-1", api.lastAck?.commandId)
    assertEquals("applied", api.lastAck?.status)
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd rcplus-msdk-agent && export JAVA_HOME=/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/usr/local/share/android-commandlinetools ANDROID_SDK_ROOT=/usr/local/share/android-commandlinetools && ./gradlew :app:testDebugUnitTest --tests "*CommandPollingCoordinatorTest"`
Expected: FAIL，因为 `AgentCommandResponse` / `CommandPollingCoordinator` / `pollCommand` / `ackCommand` 尚不存在。

- [ ] **Step 3: 在 API 层补命令拉取与 ack 契约**

```kotlin
@GET("/manage/api/v1/dual-stream/agents/{droneSn}/command")
suspend fun pollCommand(@Path("droneSn") droneSn: String): AgentCommandResponse?

@POST("/manage/api/v1/dual-stream/agents/{droneSn}/command/ack")
suspend fun ackCommand(@Path("droneSn") droneSn: String, @Body body: AgentCommandAckRequest)
```

- [ ] **Step 4: 在 `AgentBackendClient` 中补 `pollCommand` 与 `ackCommand`**

```kotlin
suspend fun pollCommand(droneSn: String): AgentCommandResponse? = api.pollCommand(droneSn)

suspend fun ackCommand(droneSn: String, commandId: String, status: String, message: String?) {
    api.ackCommand(droneSn, AgentCommandAckRequest(commandId, status, message))
}
```

- [ ] **Step 5: 写 `CommandPollingCoordinator`，先只做单次 poll**

```kotlin
class CommandPollingCoordinator(
    private val client: AgentBackendClient,
    private val sessionManager: DualStreamSessionManager,
) {
    suspend fun pollOnce(droneSn: String) {
        val command = client.pollCommand(droneSn) ?: return
        when (command.action) {
            "start" -> sessionManager.start(droneSn)
            "stop" -> sessionManager.stop()
        }
        client.ackCommand(droneSn, command.commandId, "applied", "command executed")
    }
}
```

- [ ] **Step 6: 在 `DjiSdkGatewayImpl` 中补最小真机入口日志和占位返回**

```kotlin
override suspend fun initialize(): Boolean {
    // Reserved for real DJI SDK bootstrap. Current step only exposes the runtime entrypoint.
    return true
}
```

- [ ] **Step 7: 在 `DualStreamSessionManager` 中补 start/stop 与 reporter 的串联入口**

```kotlin
suspend fun executeStart(droneSn: String, reporter: AgentReporter) {
    start(droneSn)
    reporter.reportHeartbeat(droneSn, AgentConnectionState.STREAMING, state.value)
}
```

- [ ] **Step 8: 重跑 rcplus focused tests**

Run: `cd rcplus-msdk-agent && export JAVA_HOME=/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/usr/local/share/android-commandlinetools ANDROID_SDK_ROOT=/usr/local/share/android-commandlinetools && ./gradlew :app:testDebugUnitTest --tests "*CommandPollingCoordinatorTest" --tests "*AgentBackendClientTest" --tests "*AgentReporterTest" --tests "*DualStreamSessionManagerTest" --tests "*DjiDeviceSessionTest"`
Expected: PASS

- [ ] **Step 9: 提交**

```bash
git add rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/api rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/sdk rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/session rcplus-msdk-agent/app/src/test/java/com/uavfire/rcplus/api
git commit -m "feat: add rcplus runtime command loop"
```

### Task 3: 让 ai-service 具备最小任务运行器与持续事件回传

**Files:**
- Create: `ai-service/app/services/task_runner.py`
- Modify: `ai-service/app/services/task_registry.py`
- Modify: `ai-service/app/api/routes.py`
- Modify: `ai-service/app/fusion/service.py`
- Modify: `ai-service/app/models/event.py`
- Create: `ai-service/tests/test_task_runner.py`
- Modify: `ai-service/tests/test_routes.py`
- Test: `ai-service/tests/test_task_runner.py`

- [ ] **Step 1: 先写失败测试，锁定 start 会启动一次检测循环并记录 detection event**

```python
def test_runner_start_generates_detection_event():
    registry = TaskRegistry(backend_client=RecordingBackendClient())
    runner = TaskRunner(
        registry=registry,
        visible_detector=FakeVisibleDetector(score=0.81),
        thermal_analyzer=FakeThermalAnalyzer(score=0.74),
        fusion_service=DualStreamFusionService(),
    )
    registry.create(TaskCreateRequest(
        task_id="task-200",
        drone_sn="DRONE-200",
        visible_stream_url="visible",
        thermal_stream_url="thermal",
    ))

    runner.run_once("task-200")

    events = registry.list_events("task-200")
    assert events[-1].event_type == "detection"
    assert events[-1].risk_level == "HIGH"
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd ai-service && ./.venv/bin/python -m pytest tests/test_task_runner.py -q`
Expected: FAIL，因为 `TaskRunner` 与 fake detector 协调路径尚不存在。

- [ ] **Step 3: 写 `TaskRunner` 的最小单次执行实现**

```python
class TaskRunner:
    def __init__(self, registry, visible_detector, thermal_analyzer, fusion_service) -> None:
        self._registry = registry
        self._visible_detector = visible_detector
        self._thermal_analyzer = thermal_analyzer
        self._fusion_service = fusion_service

    def run_once(self, task_id: str) -> EventRecord:
        visible_score = self._visible_detector.detect(task_id)
        thermal_score = self._thermal_analyzer.analyze(task_id)
        event = self._fusion_service.combine(
            visible_score=visible_score,
            thermal_score=thermal_score,
            source_ts=1710000000,
        )
        return self._registry.record_detection_event(task_id, event)
```

- [ ] **Step 4: 在 `TaskRegistry` 中补 runner 绑定和 start 路径触发**

```python
def bind_runner(self, runner: "TaskRunner") -> None:
    self._runner = runner

def start(self, task_id: str) -> TaskRecord:
    task = self.get(task_id)
    task.status = DualStreamTaskStatus.RUNNING
    ...
    if self._runner is not None:
        self._runner.run_once(task_id)
    return task
```

- [ ] **Step 5: 在 routes 中复用 registry 的真实 start 路径，不做额外旁路**

```python
@router.post("/api/v1/dual-stream/tasks/{task_id}/start", response_model=TaskRecord)
def start_task(task_id: str) -> TaskRecord:
    return registry.start(task_id)
```

- [ ] **Step 6: 重跑 ai-service focused tests**

Run: `cd ai-service && ./.venv/bin/python -m pytest tests/test_task_runner.py tests/test_task_registry_backend_reporting.py tests/test_routes.py tests/test_task_lifecycle.py tests/test_fusion_service.py -q`
Expected: PASS

- [ ] **Step 7: 运行 ai-service 全量测试与编译检查**

Run: `cd ai-service && ./.venv/bin/python -m pytest tests -q && ./.venv/bin/python -m compileall app`
Expected: PASS

- [ ] **Step 8: 提交**

```bash
git add ai-service/app/services ai-service/app/api ai-service/app/models ai-service/tests
git commit -m "feat: add ai service runtime task loop"
```

### Task 4: 跑三条主线的收口验证并更新交接文档

**Files:**
- Modify: `WORK_RECORD.md`
- Modify: `HANDOFF_2026-04-21_M4T_DEVICE_AND_BACKEND_NEXT_PHASE.md`
- Test: none

- [ ] **Step 1: 运行 backend focused 验证**

Run: `cd backend && mvn -pl sample -Dtest=DualStreamControllerTest,DualStreamServiceImplTest,CloudControlAuthStateResolverTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS

- [ ] **Step 2: 运行 rcplus 全量单测**

Run: `cd rcplus-msdk-agent && export JAVA_HOME=/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/usr/local/share/android-commandlinetools ANDROID_SDK_ROOT=/usr/local/share/android-commandlinetools && ./gradlew :app:testDebugUnitTest`
Expected: PASS

- [ ] **Step 3: 运行 ai-service 全量验证**

Run: `cd ai-service && ./.venv/bin/python -m pytest tests -q && ./.venv/bin/python -m compileall app`
Expected: PASS

- [ ] **Step 4: 更新工作记录，明确已完成项与仍未做项**

```md
- backend dual-stream 已具备命令发放 / 拉取 / ack 最小闭环
- rcplus-msdk-agent 已具备命令轮询执行入口，但真实视频流仍是占位
- ai-service 已具备最小 task runner 单次执行链路，但真实视频持续消费仍未接入
```

- [ ] **Step 5: 更新交接文档，明确下一阶段只剩真机双流和前端双通道直播**

```md
当前主线剩余重点：
1. 真机双流采集与双路推流
2. 前端双通道主画面 + 小窗直播
3. AI 真实视频持续消费与效果验证
```

- [ ] **Step 6: 提交**

```bash
git add WORK_RECORD.md HANDOFF_2026-04-21_M4T_DEVICE_AND_BACKEND_NEXT_PHASE.md
git commit -m "docs: update m4t runtime closure status"
```
