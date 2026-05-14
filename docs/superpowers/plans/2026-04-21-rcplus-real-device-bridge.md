# RCPlus Real Device Bridge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `rcplus-msdk-agent` 中补齐真机前置接入能力，使 SDK 初始化、连接态/能力读取和 visible/thermal 双路流绑定入口从占位实现升级为可验证的真实桥接层。

**Architecture:** 保持 `sdk/` 与 `stream/` 两层解耦：`DjiSdkGatewayImpl` 负责真实设备状态与能力适配，`RealMsdkStreamProvider` 负责双路流绑定/解绑入口，`DjiDeviceSession` 与 `DualStreamSessionManager` 只消费标准化状态与成功/失败结果。实现先走“可插拔真机桥接”模式，避免把 App 生命周期、推流或前端链路卷入本轮。

**Tech Stack:** Kotlin, Android Gradle Plugin, JUnit 4, kotlinx-coroutines-test.

---

### Task 1: 让 `DjiSdkGatewayImpl` 脱离固定返回值，占位为可桥接的真机状态适配层

**Files:**
- Create: `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/sdk/DjiRuntimeAdapter.kt`
- Modify: `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/sdk/DjiSdkGatewayImpl.kt`
- Modify: `rcplus-msdk-agent/app/src/test/java/com/uavfire/rcplus/sdk/DjiDeviceSessionTest.kt`
- Create: `rcplus-msdk-agent/app/src/test/java/com/uavfire/rcplus/sdk/DjiSdkGatewayImplTest.kt`
- Test: `rcplus-msdk-agent/app/src/test/java/com/uavfire/rcplus/sdk/DjiSdkGatewayImplTest.kt`

- [ ] **Step 1: 先写失败测试，锁定 gateway 能透传 runtime adapter 的初始化、连接态和 capability**

```kotlin
@Test
fun gateway_readsValuesFromRuntimeAdapter() = runTest {
    val gateway = DjiSdkGatewayImpl(
        runtimeAdapter = FakeDjiRuntimeAdapter(
            initializeResult = true,
            connected = true,
            capability = CameraCapability(
                visibleSupported = true,
                thermalSupported = true,
            ),
        ),
    )

    assertTrue(gateway.initialize())
    assertTrue(gateway.isAircraftConnected())
    assertEquals(true, gateway.loadCapability().thermalSupported)
}
```

- [ ] **Step 2: 运行 focused test，确认红灯**

Run: `cd rcplus-msdk-agent && export JAVA_HOME=/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/usr/local/share/android-commandlinetools ANDROID_SDK_ROOT=/usr/local/share/android-commandlinetools && ./gradlew :app:testDebugUnitTest --tests "*DjiSdkGatewayImplTest"`
Expected: FAIL，提示 `runtimeAdapter` / `DjiRuntimeAdapter` / `FakeDjiRuntimeAdapter` 相关符号不存在。

- [ ] **Step 3: 写最小 runtime adapter 抽象**

```kotlin
interface DjiRuntimeAdapter {
    suspend fun initialize(): Boolean

    suspend fun isAircraftConnected(): Boolean

    suspend fun loadCapability(): CameraCapability
}
```

- [ ] **Step 4: 让 `DjiSdkGatewayImpl` 依赖 adapter，而不是固定返回值**

```kotlin
class DjiSdkGatewayImpl(
    private val runtimeAdapter: DjiRuntimeAdapter = StubDjiRuntimeAdapter(),
) : DjiSdkGateway {
    override suspend fun initialize(): Boolean = runtimeAdapter.initialize()

    override suspend fun isAircraftConnected(): Boolean = runtimeAdapter.isAircraftConnected()

    override suspend fun loadCapability(): CameraCapability = runtimeAdapter.loadCapability()
}
```

- [ ] **Step 5: 用保守 stub 保持当前无真机环境可运行**

```kotlin
private class StubDjiRuntimeAdapter : DjiRuntimeAdapter {
    override suspend fun initialize(): Boolean = true

    override suspend fun isAircraftConnected(): Boolean = false

    override suspend fun loadCapability(): CameraCapability = CameraCapability(
        visibleSupported = false,
        thermalSupported = false,
    )
}
```

- [ ] **Step 6: 补 `DjiSdkGatewayImplTest` 的 fake adapter 与断言**

```kotlin
private class FakeDjiRuntimeAdapter(
    private val initializeResult: Boolean,
    private val connected: Boolean,
    private val capability: CameraCapability,
) : DjiRuntimeAdapter {
    override suspend fun initialize(): Boolean = initializeResult

    override suspend fun isAircraftConnected(): Boolean = connected

    override suspend fun loadCapability(): CameraCapability = capability
}
```

- [ ] **Step 7: 重跑 gateway + session focused tests**

Run: `cd rcplus-msdk-agent && export JAVA_HOME=/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/usr/local/share/android-commandlinetools ANDROID_SDK_ROOT=/usr/local/share/android-commandlinetools && ./gradlew :app:testDebugUnitTest --tests "*DjiSdkGatewayImplTest" --tests "*DjiDeviceSessionTest"`
Expected: PASS

- [ ] **Step 8: 提交**

```bash
git add rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/sdk/DjiRuntimeAdapter.kt rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/sdk/DjiSdkGatewayImpl.kt rcplus-msdk-agent/app/src/test/java/com/uavfire/rcplus/sdk/DjiSdkGatewayImplTest.kt rcplus-msdk-agent/app/src/test/java/com/uavfire/rcplus/sdk/DjiDeviceSessionTest.kt
git commit -m "feat: add rcplus sdk runtime adapter"
```

### Task 2: 为 `RealMsdkStreamProvider` 增加双路流绑定入口和本地绑定状态

**Files:**
- Create: `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/stream/BoundStreamState.kt`
- Create: `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/stream/MsdkStreamBinder.kt`
- Modify: `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/stream/RealMsdkStreamProvider.kt`
- Create: `rcplus-msdk-agent/app/src/test/java/com/uavfire/rcplus/stream/RealMsdkStreamProviderTest.kt`
- Test: `rcplus-msdk-agent/app/src/test/java/com/uavfire/rcplus/stream/RealMsdkStreamProviderTest.kt`

- [ ] **Step 1: 先写失败测试，锁定双路绑定成功后 provider 会记录已绑定状态**

```kotlin
@Test
fun start_bindsVisibleAndThermalStreams() = runTest {
    val provider = RealMsdkStreamProvider(
        binder = RecordingMsdkStreamBinder(),
    )

    provider.start("DRONE-001")

    assertEquals(BoundStreamState.BOUND, provider.visibleState)
    assertEquals(BoundStreamState.BOUND, provider.thermalState)
}
```

- [ ] **Step 2: 再写失败测试，锁定热成像绑定失败会抛错且不会伪装成成功**

```kotlin
@Test(expected = IllegalStateException::class)
fun start_throwsWhenThermalBindingFails() = runTest {
    val provider = RealMsdkStreamProvider(
        binder = RecordingMsdkStreamBinder(
            bindThermalFailure = IllegalStateException("thermal unavailable"),
        ),
    )

    provider.start("DRONE-001")
}
```

- [ ] **Step 3: 运行 focused test，确认红灯**

Run: `cd rcplus-msdk-agent && export JAVA_HOME=/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/usr/local/share/android-commandlinetools ANDROID_SDK_ROOT=/usr/local/share/android-commandlinetools && ./gradlew :app:testDebugUnitTest --tests "*RealMsdkStreamProviderTest"`
Expected: FAIL，提示 `MsdkStreamBinder` / `BoundStreamState` / `visibleState` / `thermalState` 不存在。

- [ ] **Step 4: 定义绑定状态与 binder 抽象**

```kotlin
enum class BoundStreamState {
    IDLE,
    BOUND,
}

interface MsdkStreamBinder {
    suspend fun bindVisible(droneSn: String)

    suspend fun bindThermal(droneSn: String)

    suspend fun unbindAll()
}
```

- [ ] **Step 5: 在 `RealMsdkStreamProvider` 中实现最小双路绑定与解绑逻辑**

```kotlin
class RealMsdkStreamProvider(
    private val binder: MsdkStreamBinder = StubMsdkStreamBinder(),
) : StreamProvider {
    var visibleState: BoundStreamState = BoundStreamState.IDLE
        private set
    var thermalState: BoundStreamState = BoundStreamState.IDLE
        private set

    override suspend fun start(droneSn: String) {
        binder.bindVisible(droneSn)
        visibleState = BoundStreamState.BOUND
        binder.bindThermal(droneSn)
        thermalState = BoundStreamState.BOUND
    }

    override suspend fun stop() {
        binder.unbindAll()
        visibleState = BoundStreamState.IDLE
        thermalState = BoundStreamState.IDLE
    }
}
```

- [ ] **Step 6: 用 recording binder 补齐测试**

```kotlin
private class RecordingMsdkStreamBinder(
    private val bindThermalFailure: Throwable? = null,
) : MsdkStreamBinder {
    var visibleBound = false
    var thermalBound = false
    var unbound = false

    override suspend fun bindVisible(droneSn: String) {
        visibleBound = true
    }

    override suspend fun bindThermal(droneSn: String) {
        bindThermalFailure?.let { throw it }
        thermalBound = true
    }

    override suspend fun unbindAll() {
        unbound = true
    }
}
```

- [ ] **Step 7: 重跑 stream provider focused tests**

Run: `cd rcplus-msdk-agent && export JAVA_HOME=/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/usr/local/share/android-commandlinetools ANDROID_SDK_ROOT=/usr/local/share/android-commandlinetools && ./gradlew :app:testDebugUnitTest --tests "*RealMsdkStreamProviderTest"`
Expected: PASS

- [ ] **Step 8: 提交**

```bash
git add rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/stream/BoundStreamState.kt rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/stream/MsdkStreamBinder.kt rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/stream/RealMsdkStreamProvider.kt rcplus-msdk-agent/app/src/test/java/com/uavfire/rcplus/stream/RealMsdkStreamProviderTest.kt
git commit -m "feat: add rcplus dual-stream binding bridge"
```

### Task 3: 让 `DualStreamSessionManager` 正确反映真实 provider 的成功/失败

**Files:**
- Modify: `rcplus-msdk-agent/app/src/test/java/com/uavfire/rcplus/session/DualStreamSessionManagerTest.kt`
- Modify: `rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/session/DualStreamSessionManager.kt`
- Test: `rcplus-msdk-agent/app/src/test/java/com/uavfire/rcplus/session/DualStreamSessionManagerTest.kt`

- [ ] **Step 1: 先写失败测试，锁定 provider 抛错时 session 会进入 `FAILED`**

```kotlin
@Test
fun startSession_movesToFailedWhenProviderThrows() = runTest {
    val manager = DualStreamSessionManager(
        streamProvider = object : StreamProvider {
            override suspend fun start(droneSn: String) {
                throw IllegalStateException("thermal unavailable")
            }

            override suspend fun stop() = Unit
        },
    )

    manager.start("DRONE-001")

    assertEquals(DualStreamSessionState.FAILED, manager.state.value)
}
```

- [ ] **Step 2: 运行 focused test，确认是否红灯**

Run: `cd rcplus-msdk-agent && export JAVA_HOME=/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/usr/local/share/android-commandlinetools ANDROID_SDK_ROOT=/usr/local/share/android-commandlinetools && ./gradlew :app:testDebugUnitTest --tests "*DualStreamSessionManagerTest"`
Expected: 若行为尚未锁住则 FAIL；若已是绿灯，补 `executeCommand("start")` 的失败 ack 断言再跑到红灯。

- [ ] **Step 3: 补 `executeCommand` 失败场景测试**

```kotlin
@Test
fun executeCommand_returnsFailedWhenStartCannotBindStreams() = runTest {
    val manager = DualStreamSessionManager(
        streamProvider = object : StreamProvider {
            override suspend fun start(droneSn: String) {
                throw IllegalStateException("visible unavailable")
            }

            override suspend fun stop() = Unit
        },
    )

    val result = manager.executeCommand("DRONE-001", "start")

    assertEquals("failed", result.status)
    assertTrue(result.message?.contains("FAILED") == true)
}
```

- [ ] **Step 4: 如有必要，最小调整 manager 以保留 provider 异常导致的标准状态**

```kotlin
runCatching {
    streamProvider.start(droneSn)
}.onSuccess {
    _state.value = DualStreamSessionState.RUNNING
}.onFailure {
    _state.value = DualStreamSessionState.FAILED
}
```

- [ ] **Step 5: 重跑 session focused tests**

Run: `cd rcplus-msdk-agent && export JAVA_HOME=/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/usr/local/share/android-commandlinetools ANDROID_SDK_ROOT=/usr/local/share/android-commandlinetools && ./gradlew :app:testDebugUnitTest --tests "*DualStreamSessionManagerTest"`
Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add rcplus-msdk-agent/app/src/main/java/com/uavfire/rcplus/session/DualStreamSessionManager.kt rcplus-msdk-agent/app/src/test/java/com/uavfire/rcplus/session/DualStreamSessionManagerTest.kt
git commit -m "test: cover rcplus real-device session failures"
```

### Task 4: 跑 rcplus 收口验证并更新交接文档

**Files:**
- Modify: `WORK_RECORD.md`
- Modify: `HANDOFF_2026-04-21_M4T_DEVICE_AND_BACKEND_NEXT_PHASE.md`
- Test: none

- [ ] **Step 1: 运行 rcplus focused tests**

Run: `cd rcplus-msdk-agent && export JAVA_HOME=/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/usr/local/share/android-commandlinetools ANDROID_SDK_ROOT=/usr/local/share/android-commandlinetools && ./gradlew :app:testDebugUnitTest --tests "*DjiSdkGatewayImplTest" --tests "*DjiDeviceSessionTest" --tests "*RealMsdkStreamProviderTest" --tests "*DualStreamSessionManagerTest" --tests "*CommandPollingCoordinatorTest"`
Expected: PASS

- [ ] **Step 2: 运行 rcplus 全量单测**

Run: `cd rcplus-msdk-agent && export JAVA_HOME=/usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/usr/local/share/android-commandlinetools ANDROID_SDK_ROOT=/usr/local/share/android-commandlinetools && ./gradlew :app:testDebugUnitTest`
Expected: PASS

- [ ] **Step 3: 更新工作记录**

```md
- `DjiSdkGatewayImpl` 已改成 runtime adapter 结构，不再是固定返回值
- `RealMsdkStreamProvider` 已具备真实双路流绑定入口和本地绑定状态
- 当前仍未完成推流、前端播放和持续后台轮询
```

- [ ] **Step 4: 更新交接文档**

```md
当前 `rcplus-msdk-agent` 已完成真机前置接入：
1. SDK 初始化入口
2. 连接态 / capability 读取入口
3. visible / thermal 双路绑定入口

仍未完成：
1. 真正媒体推流
2. App 生命周期内的持续命令轮询/状态上报
3. 前端双通道播放
```

- [ ] **Step 5: 提交**

```bash
git add WORK_RECORD.md HANDOFF_2026-04-21_M4T_DEVICE_AND_BACKEND_NEXT_PHASE.md
git commit -m "docs: update rcplus real-device bridge status"
```
