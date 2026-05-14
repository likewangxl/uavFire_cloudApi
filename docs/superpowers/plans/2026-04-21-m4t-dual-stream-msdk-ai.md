# M4T 双流 MSDK 执行层与 AI 服务 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在当前仓库内新增 `rcplus-msdk-agent/` 和 `ai-service/` 两个独立工程，先落地可运行骨架、核心状态模型、接口契约和本地验证能力，为后续真机双流 PoC 与火情识别迭代提供工程基础。

**Architecture:** `rcplus-msdk-agent/` 采用 Kotlin + Android + DJI MSDK v5 的分层结构，用 mock provider 固化双流状态机与后端交互边界；`ai-service/` 采用 FastAPI + OpenCV + YOLO 的模块化服务骨架，先实现任务管理、视频源抽象、可见光/红外分析接口与融合事件输出的最小闭环。

**Tech Stack:** Kotlin, Android Gradle, DJI MSDK v5, Coroutines/Flow, Retrofit/OkHttp, Python 3.11, FastAPI, Pydantic, OpenCV, Ultralytics YOLO, pytest.

---

### Task 1: 建立专项工程目录与仓库级说明

**Files:**
- Create: `rcplus-msdk-agent/settings.gradle.kts`
- Create: `rcplus-msdk-agent/build.gradle.kts`
- Create: `rcplus-msdk-agent/gradle.properties`
- Create: `rcplus-msdk-agent/README.md`
- Create: `ai-service/pyproject.toml`
- Create: `ai-service/README.md`
- Create: `ai-service/.env.example`
- Modify: `README.md`
- Test: none

- [ ] **Step 1: 写出仓库级接入说明**

在根目录 `README.md` 增加一节，说明这次新增的两个子工程定位和当前边界：

```md
## 双流 PoC 子工程

- `rcplus-msdk-agent/`：RC Plus 2 Android / DJI MSDK v5 执行层骨架
- `ai-service/`：双流火情识别服务骨架

当前阶段目标是先固化接口、状态机和本地验证能力，不代表已完成真机双流联调或正式商用能力。
```

- [ ] **Step 2: 新建 Android 工程级骨架文件**

创建 `rcplus-msdk-agent/settings.gradle.kts`、`build.gradle.kts`、`gradle.properties`，至少包含应用模块 `:app`：

```kotlin
// rcplus-msdk-agent/settings.gradle.kts
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "rcplus-msdk-agent"
include(":app")
```

- [ ] **Step 3: 新建 AI 服务工程级骨架文件**

创建 `ai-service/pyproject.toml` 与 `.env.example`，先锁定基础依赖：

```toml
[project]
name = "ai-service"
version = "0.1.0"
requires-python = ">=3.11"
dependencies = [
  "fastapi>=0.115.0,<1.0.0",
  "uvicorn[standard]>=0.30.0,<1.0.0",
  "pydantic>=2.8.0,<3.0.0",
  "opencv-python-headless>=4.10.0.84,<5.0.0",
  "ultralytics>=8.3.0,<9.0.0",
]
```

- [ ] **Step 4: 为两个工程分别写 README**

在 `rcplus-msdk-agent/README.md`、`ai-service/README.md` 中写明：
- 当前定位
- 目录结构
- 本地启动命令
- 当前不承诺项

- [ ] **Step 5: 提交**

```bash
git add README.md rcplus-msdk-agent ai-service
git commit -m "chore: scaffold dual-stream subprojects"
```

### Task 2: 为 Android 执行层写出最小可编译应用壳

**Files:**
- Create: `rcplus-msdk-agent/app/build.gradle.kts`
- Create: `rcplus-msdk-agent/app/src/main/AndroidManifest.xml`
- Create: `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/App.kt`
- Create: `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/MainActivity.kt`
- Create: `rcplus-msdk-agent/app/src/main/res/layout/activity_main.xml`
- Create: `rcplus-msdk-agent/app/src/main/res/values/strings.xml`
- Create: `rcplus-msdk-agent/app/src/main/res/values/themes.xml`
- Test: `rcplus-msdk-agent/app/build.gradle.kts`

- [ ] **Step 1: 先写应用模块 Gradle 配置**

在 `rcplus-msdk-agent/app/build.gradle.kts` 配置 Android 应用、Kotlin、基础依赖和测试依赖：

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.uavfire.rcplus"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.uavfire.rcplus"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
}
```

- [ ] **Step 2: 写 AndroidManifest 和 Application**

保证有 `MainActivity` 和 `App` 入口：

```xml
<application
    android:name=".App"
    android:label="@string/app_name"
    android:theme="@style/Theme.RcPlusAgent">
    <activity android:name=".MainActivity" android:exported="true">
        <intent-filter>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LAUNCHER" />
        </intent-filter>
    </activity>
</application>
```

- [ ] **Step 3: 写最小调试 UI**

`MainActivity.kt` 和 `activity_main.xml` 只显示当前工程定位和后续状态占位：

```kotlin
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }
}
```

- [ ] **Step 4: 运行 Android 构建验证**

Run: `cd rcplus-msdk-agent && ./gradlew :app:assembleDebug`

Expected: PASS，生成最小 debug apk；如果本机缺少 Android SDK，则先记录缺失项，不继续宣称执行层已可编译。

- [ ] **Step 5: 提交**

```bash
git add rcplus-msdk-agent/app
git commit -m "feat: add rcplus android app shell"
```

### Task 3: 固化 RC 执行层状态模型与 mock 会话服务

**Files:**
- Create: `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/session/AgentConnectionState.kt`
- Create: `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/session/DualStreamSessionState.kt`
- Create: `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/stream/StreamChannelType.kt`
- Create: `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/stream/StreamProvider.kt`
- Create: `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/stream/MockStreamProvider.kt`
- Create: `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/session/DualStreamSessionManager.kt`
- Create: `rcplus-msdk-agent/app/src/test/java/com/uavfire/rcplus/session/DualStreamSessionManagerTest.kt`
- Test: `rcplus-msdk-agent/app/src/test/java/com/uavfire/rcplus/session/DualStreamSessionManagerTest.kt`

- [ ] **Step 1: 先写失败测试，锁定 mock provider 下的状态机**

```kotlin
@Test
fun startSession_movesFromInitToRunning() = runTest {
    val manager = DualStreamSessionManager(MockStreamProvider())
    manager.start("DRONE-001")
    assertEquals(DualStreamSessionState.RUNNING, manager.state.value)
}

@Test
fun stopSession_movesToStopped() = runTest {
    val manager = DualStreamSessionManager(MockStreamProvider())
    manager.start("DRONE-001")
    manager.stop()
    assertEquals(DualStreamSessionState.STOPPED, manager.state.value)
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd rcplus-msdk-agent && ./gradlew :app:testDebugUnitTest --tests "*DualStreamSessionManagerTest"`

Expected: FAIL，因为 `DualStreamSessionManager` 和 `MockStreamProvider` 尚不存在。

- [ ] **Step 3: 写最小状态模型与 mock 实现**

核心代码至少包括：

```kotlin
enum class AgentConnectionState { IDLE, SDK_READY, AIRCRAFT_CONNECTED, CAPABILITY_READY, STREAMING, DEGRADED, ERROR }
enum class DualStreamSessionState { INIT, STARTING, RUNNING, STOPPING, STOPPED, FAILED }
enum class StreamChannelType { VISIBLE, THERMAL }

interface StreamProvider {
    suspend fun start(droneSn: String)
    suspend fun stop()
}
```

`MockStreamProvider` 直接返回成功；`DualStreamSessionManager` 用 `MutableStateFlow` 驱动状态迁移。

- [ ] **Step 4: 重跑测试确认通过**

Run: `cd rcplus-msdk-agent && ./gradlew :app:testDebugUnitTest --tests "*DualStreamSessionManagerTest"`

Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus rcplus-msdk-agent/app/src/test/java/com/uavfire/rcplus
git commit -m "feat: add rcplus dual-stream mock session manager"
```

### Task 4: 接入 MSDK 适配层占位与能力查询接口

**Files:**
- Create: `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/sdk/DjiSdkGateway.kt`
- Create: `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/sdk/DjiSdkGatewayImpl.kt`
- Create: `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/sdk/CameraCapability.kt`
- Create: `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/sdk/CapabilityRepository.kt`
- Create: `rcplus-msdk-agent/app/src/test/java/com/uavfire/rcplus/sdk/CapabilityRepositoryTest.kt`
- Modify: `rcplus-msdk-agent/app/build.gradle.kts`
- Test: `rcplus-msdk-agent/app/src/test/java/com/uavfire/rcplus/sdk/CapabilityRepositoryTest.kt`

- [ ] **Step 1: 给 app 模块补上网络与 flow 依赖**

在 `app/build.gradle.kts` 增加：

```kotlin
implementation("com.squareup.retrofit2:retrofit:2.11.0")
implementation("com.squareup.okhttp3:okhttp:4.12.0")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
```

- [ ] **Step 2: 写失败测试，定义能力查询输出**

```kotlin
@Test
fun capabilityRepository_returnsVisibleAndThermalFlags() = runTest {
    val repo = CapabilityRepository(FakeDjiSdkGateway(visible = true, thermal = true))
    val capability = repo.load()
    assertTrue(capability.visibleSupported)
    assertTrue(capability.thermalSupported)
}
```

- [ ] **Step 3: 写最小 MSDK 适配接口**

```kotlin
data class CameraCapability(
    val visibleSupported: Boolean,
    val thermalSupported: Boolean,
)

interface DjiSdkGateway {
    suspend fun initialize(): Boolean
    suspend fun loadCapability(): CameraCapability
}
```

`DjiSdkGatewayImpl` 第一版只保留 `TODO("wait for device integration")` 之外的安全占位返回，不直接伪造真机能力。

- [ ] **Step 4: 重跑测试确认通过**

Run: `cd rcplus-msdk-agent && ./gradlew :app:testDebugUnitTest --tests "*CapabilityRepositoryTest"`

Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add rcplus-msdk-agent/app/build.gradle.kts rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/sdk rcplus-msdk-agent/app/src/test/java/com/uavfire/rcplus/sdk
git commit -m "feat: add rcplus capability gateway abstraction"
```

### Task 5: 建立 RC 执行层与后端的 HTTP 契约

**Files:**
- Create: `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/api/DualStreamApi.kt`
- Create: `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/api/AgentHeartbeatRequest.kt`
- Create: `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/api/AgentStatusRequest.kt`
- Create: `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/api/CapabilityReportRequest.kt`
- Create: `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/api/AgentBackendClient.kt`
- Create: `rcplus-msdk-agent/app/src/test/java/com/uavfire/rcplus/api/AgentBackendClientTest.kt`
- Test: `rcplus-msdk-agent/app/src/test/java/com/uavfire/rcplus/api/AgentBackendClientTest.kt`

- [ ] **Step 1: 先写失败测试，锁定请求体结构**

```kotlin
@Test
fun heartbeatPayload_containsDroneSnAndSessionState() {
    val payload = AgentHeartbeatRequest(
        droneSn = "DRONE-001",
        connectionState = AgentConnectionState.STREAMING.name,
        sessionState = DualStreamSessionState.RUNNING.name,
    )
    assertEquals("DRONE-001", payload.droneSn)
    assertEquals("RUNNING", payload.sessionState)
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd rcplus-msdk-agent && ./gradlew :app:testDebugUnitTest --tests "*AgentBackendClientTest"`

Expected: FAIL，因为 DTO 与 client 尚不存在。

- [ ] **Step 3: 写 Retrofit 接口与 client**

至少包含：

```kotlin
interface DualStreamApi {
    @POST("/internal/dual-stream/agents/{droneSn}/heartbeat")
    suspend fun heartbeat(@Path("droneSn") droneSn: String, @Body body: AgentHeartbeatRequest)
}
```

`AgentBackendClient` 负责组装 heartbeat/status/capability 三类请求，但第一版不直接轮询发送。

- [ ] **Step 4: 重跑测试确认通过**

Run: `cd rcplus-msdk-agent && ./gradlew :app:testDebugUnitTest --tests "*AgentBackendClientTest"`

Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/api rcplus-msdk-agent/app/src/test/java/com/uavfire/rcplus/api
git commit -m "feat: add rcplus backend api contract"
```

### Task 6: 建立 AI 服务 FastAPI 入口与任务状态模型

**Files:**
- Create: `ai-service/app/__init__.py`
- Create: `ai-service/app/main.py`
- Create: `ai-service/app/api/__init__.py`
- Create: `ai-service/app/api/routes.py`
- Create: `ai-service/app/models/__init__.py`
- Create: `ai-service/app/models/task.py`
- Create: `ai-service/app/services/__init__.py`
- Create: `ai-service/app/services/task_registry.py`
- Create: `ai-service/tests/test_routes.py`
- Test: `ai-service/tests/test_routes.py`

- [ ] **Step 1: 先写失败测试，锁定最小 API**

```python
from fastapi.testclient import TestClient
from app.main import app

client = TestClient(app)

def test_healthz():
    response = client.get("/healthz")
    assert response.status_code == 200
    assert response.json()["status"] == "ok"

def test_create_task():
    response = client.post("/api/v1/dual-stream/tasks", json={
        "task_id": "task-001",
        "drone_sn": "DRONE-001",
        "visible_stream_url": "rtsp://visible",
        "thermal_stream_url": "rtsp://thermal"
    })
    assert response.status_code == 201
    assert response.json()["task_id"] == "task-001"
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd ai-service && python -m pytest tests/test_routes.py -q`

Expected: FAIL，因为 FastAPI app 和任务路由尚不存在。

- [ ] **Step 3: 写最小 FastAPI 入口和任务注册表**

核心代码至少包括：

```python
class DualStreamTaskCreate(BaseModel):
    task_id: str
    drone_sn: str
    visible_stream_url: str
    thermal_stream_url: str

app = FastAPI()
app.include_router(router)
```

任务先保存在进程内 `TaskRegistry`。

- [ ] **Step 4: 重跑测试确认通过**

Run: `cd ai-service && python -m pytest tests/test_routes.py -q`

Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add ai-service/app ai-service/tests/test_routes.py ai-service/pyproject.toml
git commit -m "feat: add ai service task api skeleton"
```

### Task 7: 固化 AI 双流事件模型与 start/stop/query 行为

**Files:**
- Create: `ai-service/app/models/event.py`
- Modify: `ai-service/app/models/task.py`
- Modify: `ai-service/app/api/routes.py`
- Modify: `ai-service/app/services/task_registry.py`
- Create: `ai-service/tests/test_task_lifecycle.py`
- Test: `ai-service/tests/test_task_lifecycle.py`

- [ ] **Step 1: 先写失败测试，锁定任务生命周期**

```python
def test_task_start_stop_and_query(client: TestClient):
    client.post("/api/v1/dual-stream/tasks", json={
        "task_id": "task-002",
        "drone_sn": "DRONE-002",
        "visible_stream_url": "rtsp://visible",
        "thermal_stream_url": "rtsp://thermal"
    })
    started = client.post("/api/v1/dual-stream/tasks/task-002/start")
    assert started.status_code == 200
    assert started.json()["status"] == "running"

    queried = client.get("/api/v1/dual-stream/tasks/task-002")
    assert queried.json()["status"] == "running"

    stopped = client.post("/api/v1/dual-stream/tasks/task-002/stop")
    assert stopped.json()["status"] == "stopped"
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd ai-service && python -m pytest tests/test_task_lifecycle.py -q`

Expected: FAIL，因为 start/stop/query 路由和尚未定义的状态迁移不存在。

- [ ] **Step 3: 写最小生命周期实现**

给任务模型增加：

```python
class DualStreamTaskStatus(str, Enum):
    CREATED = "created"
    RUNNING = "running"
    STOPPED = "stopped"
    FAILED = "failed"
```

并在 `TaskRegistry` 里实现 `create/start/stop/get/list_events`。

- [ ] **Step 4: 重跑测试确认通过**

Run: `cd ai-service && python -m pytest tests/test_routes.py tests/test_task_lifecycle.py -q`

Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add ai-service/app/models ai-service/app/api/routes.py ai-service/app/services/task_registry.py ai-service/tests/test_task_lifecycle.py
git commit -m "feat: add ai service task lifecycle"
```

### Task 8: 加入视频源抽象、可见光检测与红外热点分析占位

**Files:**
- Create: `ai-service/app/video/source.py`
- Create: `ai-service/app/inference/visible/detector.py`
- Create: `ai-service/app/inference/thermal/analyzer.py`
- Create: `ai-service/app/fusion/service.py`
- Create: `ai-service/app/models/frame.py`
- Create: `ai-service/app/models/event.py`
- Create: `ai-service/tests/test_fusion_service.py`
- Test: `ai-service/tests/test_fusion_service.py`

- [ ] **Step 1: 先写失败测试，定义融合输出**

```python
def test_fusion_service_combines_visible_and_thermal_scores():
    fusion = DualStreamFusionService()
    event = fusion.combine(
        visible_score=0.82,
        thermal_score=0.74,
        source_ts=1710000000,
    )
    assert event.risk_level == "HIGH"
    assert event.fusion_score > 0.7
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd ai-service && python -m pytest tests/test_fusion_service.py -q`

Expected: FAIL，因为融合服务与事件模型尚不存在。

- [ ] **Step 3: 写最小视频与推理抽象**

接口级代码至少包括：

```python
class VideoSource(Protocol):
    def open(self) -> None: ...
    def read(self) -> FramePacket | None: ...

class VisibleDetector:
    def detect(self, frame: FramePacket) -> float:
        return 0.0

class ThermalAnalyzer:
    def analyze(self, frame: FramePacket) -> float:
        return 0.0
```

第一版允许 `VisibleDetector` 和 `ThermalAnalyzer` 返回规则/占位分值，不强行接真模型。

- [ ] **Step 4: 写最小融合评分服务**

```python
class DualStreamFusionService:
    def combine(self, visible_score: float, thermal_score: float, source_ts: int) -> DualStreamEvent:
        fusion_score = round((visible_score * 0.6) + (thermal_score * 0.4), 3)
        risk_level = "HIGH" if fusion_score >= 0.7 else "MEDIUM" if fusion_score >= 0.4 else "LOW"
        return DualStreamEvent(...)
```

- [ ] **Step 5: 重跑测试确认通过**

Run: `cd ai-service && python -m pytest tests/test_fusion_service.py -q`

Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add ai-service/app/video ai-service/app/inference ai-service/app/fusion ai-service/app/models ai-service/tests/test_fusion_service.py
git commit -m "feat: add ai dual-stream analysis abstractions"
```

### Task 9: 为 AI 服务补充本地运行入口与健康验证

**Files:**
- Create: `ai-service/app/config/settings.py`
- Create: `ai-service/scripts/run-dev.sh`
- Modify: `ai-service/README.md`
- Create: `ai-service/tests/test_settings.py`
- Test: `ai-service/tests/test_settings.py`

- [ ] **Step 1: 先写失败测试，锁定配置默认值**

```python
def test_settings_default_task_limit():
    settings = Settings()
    assert settings.max_concurrent_tasks == 2
    assert settings.log_level == "INFO"
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd ai-service && python -m pytest tests/test_settings.py -q`

Expected: FAIL，因为 `Settings` 尚不存在。

- [ ] **Step 3: 写设置模型和开发启动脚本**

```python
class Settings(BaseSettings):
    host: str = "0.0.0.0"
    port: int = 9000
    log_level: str = "INFO"
    max_concurrent_tasks: int = 2
```

`scripts/run-dev.sh` 内容：

```bash
#!/usr/bin/env bash
set -euo pipefail
uvicorn app.main:app --host 0.0.0.0 --port 9000 --reload
```

- [ ] **Step 4: 重跑测试确认通过**

Run: `cd ai-service && python -m pytest tests/test_settings.py -q`

Expected: PASS

- [ ] **Step 5: 本地启动服务验证**

Run: `cd ai-service && python -m uvicorn app.main:app --host 127.0.0.1 --port 9000`

Expected: 服务正常启动，`GET /healthz` 返回 `{"status":"ok"}`。

- [ ] **Step 6: 提交**

```bash
git add ai-service/app/config ai-service/scripts/run-dev.sh ai-service/README.md ai-service/tests/test_settings.py
git commit -m "chore: add ai service runtime config"
```

### Task 10: 做跨工程验证并补充专项文档引用

**Files:**
- Modify: `docs/superpowers/specs/2026-04-21-m4t-dual-stream-msdk-ai-design.md`
- Modify: `docs/superpowers/plans/2026-04-21-m4t-dual-stream-msdk-ai.md`
- Test: `rcplus-msdk-agent/app/src/test/java/com/uavfire/rcplus/session/DualStreamSessionManagerTest.kt`
- Test: `rcplus-msdk-agent/app/src/test/java/com/uavfire/rcplus/sdk/CapabilityRepositoryTest.kt`
- Test: `rcplus-msdk-agent/app/src/test/java/com/uavfire/rcplus/api/AgentBackendClientTest.kt`
- Test: `ai-service/tests/test_routes.py`
- Test: `ai-service/tests/test_task_lifecycle.py`
- Test: `ai-service/tests/test_fusion_service.py`
- Test: `ai-service/tests/test_settings.py`

- [ ] **Step 1: 跑 Android 单元测试**

Run: `cd rcplus-msdk-agent && ./gradlew :app:testDebugUnitTest`

Expected: PASS；如果缺少 Android SDK，仅记录环境阻塞，不把阻塞误写成代码失败。

- [ ] **Step 2: 跑 AI 服务测试**

Run: `cd ai-service && python -m pytest tests -q`

Expected: PASS

- [ ] **Step 3: 做 AI 服务导入检查**

Run: `cd ai-service && python -m compileall app`

Expected: PASS

- [ ] **Step 4: 更新文档中的验证结论**

在专项 spec 和 plan 中补一段“当前已验证范围”，明确：
- Android 侧当前为骨架和 mock 测试通过
- AI 侧当前为 API/生命周期/融合逻辑本地通过
- 真机双流、MSDK 真连、真实模型效果不在本轮验证范围

- [ ] **Step 5: 提交**

```bash
git add docs/superpowers/specs/2026-04-21-m4t-dual-stream-msdk-ai-design.md docs/superpowers/plans/2026-04-21-m4t-dual-stream-msdk-ai.md
git commit -m "docs: finalize dual-stream msdk and ai plan"
```

---

## 当前已验证范围

截至 2026-04-21，当前计划对应实现已经完成以下验证：

- Android 侧：
  - `rcplus-msdk-agent` 可执行 `:app:assembleDebug`
  - `:app:testDebugUnitTest` 已通过
  - 已覆盖 `DualStreamSessionManagerTest`、`CapabilityRepositoryTest`、`AgentBackendClientTest`
- AI 侧：
  - `tests/test_routes.py`
  - `tests/test_task_lifecycle.py`
  - `tests/test_fusion_service.py`
  - `tests/test_settings.py`
  - `python -m compileall app`
  - 本地 `uvicorn` 启动后 `/healthz` 返回 `{"status":"ok"}`

本轮验证结论只覆盖：

- Android 侧骨架、mock 状态机、能力抽象与请求契约
- AI 侧 API、生命周期、融合评分占位和本地运行入口

本轮明确不覆盖：

- 真机双流采集与推流
- DJI MSDK 真连与真实设备能力
- 真实模型推理效果
- 生产级任务调度、持久化与并发治理
