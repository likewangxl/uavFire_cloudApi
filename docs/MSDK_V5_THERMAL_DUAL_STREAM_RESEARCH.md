# MSDK v5 热成像第二路真导出方案 — 调研记录（2026-04-25）

任务来源：`WORK_RECORD.md` §0.7 / `HANDOFF_2026-04-24_LOCAL_ZLM_RCPLUS_DUAL_STREAM.md` §三 — 当前 RC Plus 2 + M4T 真机上 `DjiMsdkStreamBinder.bindThermal()` 显式抛
`UnsupportedOperationException("msdk-v5-camera-stream-manager-does-not-expose-simultaneous-visible-and-thermal-stream-binding")`，需要确认这个判断是否绝对、有没有真双路导出方案。

## 1. 一句话结论

**M4T + RC Plus 2 + MSDK v5 当前组合下，确实没有"同时绑定 visible + thermal 两条独立 raw 流"的官方 API**。
但有 **3 条能落地的非"硬抛 UnsupportedOperationException"路径**，应该把当前实现从"显式失败"升级成"最佳可用降级"。

## 2. 调研到的事实

### 2.1 ICameraStreamManager.addReceiveStreamListener 没有禁止多次注册

来源：`developer.dji.com/api-reference-v5/android-api/Components/IMediaDataCenter/ICameraStreamManager.html`

签名：

```java
void addReceiveStreamListener(@NonNull ComponentIndexType cameraIndex, @NonNull ReceiveStreamListener listener)
```

要点：
- API 文档**没有任何**关于"不能用不同 ComponentIndexType 同时注册多个 listener"的限制说明
- 文档仅说"Through this listener, you can receive the raw video stream data of the specified camera"
- 这意味着理论上 `LEFT_OR_MAIN` 和 `RIGHT` / `UPPER` / `FPV` 等可以并行绑定

### 2.2 V5 Sample 的设计就是 per-camera-fragment

来源：`Mobile-SDK-Android-V5/SampleCode-V5/.../pages/CameraStreamDetailFragment.kt`（dev-sdk-main 分支）

V5 官方 sample 里：
- 每条流是一个独立 Fragment 实例
- Fragment 用 `cameraIndex: ComponentIndexType` 参数构造
- 同时显示多路就是同时挂多个 Fragment

间接证据：SDK 是按 per-cameraIndex 模型设计的。**多路并行注册在架构上是预期用法**。

### 2.3 M4T 单 gimbal 多镜头不暴露为多个 ComponentIndexType

M4T 的 visible / thermal / 长焦三镜头**共用一个 gimbal**，在 MSDK v5 里只暴露为 **一个** `ComponentIndexType.LEFT_OR_MAIN`。三个镜头是同一 cameraIndex 下的不同 `CameraVideoStreamSourceType`：

- `CameraVideoStreamSourceType.WIDE_CAMERA`
- `CameraVideoStreamSourceType.ZOOM_CAMERA`
- `CameraVideoStreamSourceType.INFRARED_CAMERA`

切换镜头通过：

```kotlin
KeyManager.getInstance().setValue(
    KeyTools.createKey(CameraKey.KeyCameraVideoStreamSource, ComponentIndexType.LEFT_OR_MAIN),
    CameraVideoStreamSourceType.INFRARED_CAMERA,
    callback,
)
```

这是 **liveview source 的切换**，不是 raw stream 的并行绑定。

### 2.4 `KeyCameraVideoStreamSource = INFRARED_CAMERA` 在某些设备/固件上直接 UNSUPPORTED

来源：`github.com/dji-sdk/Mobile-SDK-Android-V5/issues/576`（2025-06，未关闭）

复现代码（与本仓库 `DjiMsdkStreamBinder.focusThermal` 等价）：

```java
KeyManager.getInstance().setValue(
    KeyTools.createKey(CameraKey.KeyCameraVideoStreamSource),
    CameraVideoStreamSourceType.INFRARED_CAMERA,
    new CommonCallbacks.CompletionCallback() { ... });
```

回调回报：`UNSUPPORTED`。

意味着即便 listener 那边能接两路，**source 切换这一步本身在 M3E + M4T 组合下也可能直接被设备拒**。本仓库 RC Plus 2 + M4T 上目前能成功设置 `INFRARED_CAMERA`（agent 已经能进 `focus-thermal`），但**不能假设固件升级或换设备后还能继续工作**。

### 2.5 PIP `SIDE_BY_SIDE` 给的是一条复合视频流

当前 `DjiMsdkStreamBinder.focusThermal()` 已经在做：

```kotlin
KeyThermalDisplayMode = PIP
KeyThermalPIPPosition = SIDE_BY_SIDE
```

这条路径**是设备真支持的**，结果是 liveview source 仍是同一 `ComponentIndexType.LEFT_OR_MAIN` 的一条流，**画面里左右并排放可见光和热成像**。这就是 backend `playback_status = shared-side-by-side-preview` 的语义来源。

## 3. 当前 `DjiMsdkStreamBinder.bindThermal()` 的判断对不对

代码：

```kotlin
override suspend fun bindThermal(droneSn: String) {
    val availableSources = KeyManager.getInstance().getValue(
        KeyTools.createKey(CameraKey.KeyCameraVideoStreamSourceRange, ComponentIndexType.LEFT_OR_MAIN)
    ) as? List<*>
    val thermalSupported = availableSources.orEmpty().any { it?.toString() == "INFRARED_CAMERA" }
    if (!thermalSupported) {
        throw IllegalStateException("thermal-stream-source-unavailable")
    }
    throw UnsupportedOperationException(
        "msdk-v5-camera-stream-manager-does-not-expose-simultaneous-visible-and-thermal-stream-binding",
    )
}
```

**结论：判断方向对，但措辞过强**。

- 对的部分：当前 M4T 下，没有"raw visible + raw thermal 同时拉两条独立 listener"的官方 API
- 过强的部分：错把"现 M4T 不支持"说成了"MSDK v5 camera stream manager 不支持"。MSDK v5 本身的 API 没有这个限制（参考 §2.1），是 M4T 设备/固件层面没暴露第二条 ComponentIndexType
- 缺失的部分：没有给出可降级路径，直接抛 UnsupportedOperationException 让 `RealMsdkStreamProvider.start()` 走 catch 分支后再调 `focusThermal()`，但 catch 内信息丢了一层语义

## 4. 三条能落地的真双路降级路径

### 4.1 路径 A — PIP 帧切片（推荐先做）

最现实可用的"伪双流"。

做法：
1. agent 保持 `bindVisible(LEFT_OR_MAIN)` 不变
2. 设置 `KeyCameraVideoStreamSource = INFRARED_CAMERA` + `KeyThermalDisplayMode = PIP` + `KeyThermalPIPPosition = SIDE_BY_SIDE`
3. agent 内一条 listener 拿到的就是"左可见光 + 右热成像"的复合 YUV 帧
4. 在 agent 或下游消费侧（例如 ai-service `VideoSource` 解码后）按宽度对半切，得到 visible 半帧 + thermal 半帧

代价：
- 两路都是复合流的一半分辨率
- thermal 区是伪彩 RGB，不是温度矩阵；不能做精确测温
- 但**真的能给 ai-service 两路独立帧做检测和融合**

适配本仓库改造点：
- `DjiMsdkStreamBinder` 增加一个 `bindCompositePip(droneSn): CompositeBinding` 方法
- `RealMsdkStreamProvider` 在 thermal 不能独立绑时，转走 PIP composite，回报 `playback_status = shared-side-by-side-preview` + `composite_layout = side-by-side`
- ai-service 侧 `OpenCvVideoSource` 可以增加一个 `CompositeSliceVideoSource` 装饰器，从一条 RTMP/RTSP 拉流得到的帧上切两半，分别贴 channel="visible" / "thermal" 输出 FramePacket

### 4.2 路径 B — Liveview source 时分复用 + 关键帧采样

做法：
1. 周期性切换 `KeyCameraVideoStreamSource`：`WIDE_CAMERA` → 抓一帧 → `INFRARED_CAMERA` → 抓一帧 → 循环
2. 对外 fake 成两路独立流（visible 高 FPS、thermal 低 FPS，或两路都低 FPS）

代价：
- 切换有几百 ms 延时
- thermal 路 FPS 极低，火势变化捕捉差
- 但**纯软件实现，不依赖 PIP**，是 PIP 路径不可用时的 backup

适配本仓库改造点：基本是 ai-service 侧的事，agent 提供"切 source → 抓帧"的 RPC 即可

### 4.3 路径 C — 走 DJI Pilot 2 的云直播双路

做法：
- 不通过自研 `rcplus-msdk-agent`，让用户直接用 DJI Pilot 2 / Smart Controller 自带的"主码流 + 子码流"上传到 GB28181/RTMP
- backend 直接消费 Pilot 2 的两路流

代价：
- 抛弃自研 agent 的可控性
- 但**不需要再啃 MSDK v5 API**，对老固件兼容性最好
- 现实中很多 DJI 行业客户就是这条路

适配本仓库改造点：
- backend `LiveStreamServiceImpl` / `livestream.url.gb28181` 已经有支持
- 关键是 RC Plus 2 上能不能同时跑自研 agent 和 Pilot 2 — 实测看结果

## 5. 推荐落地顺序

1. **改 `DjiMsdkStreamBinder.bindThermal()` 措辞和路径**：把"硬抛 UnsupportedOperationException"改成显式枚举三种降级（`pip-slice` / `time-multiplex` / `pilot2-cloud`），现阶段先返回 `pip-slice` 但不真切片
2. **agent 侧落 PIP composite 推流路径**：保留 `bindVisible(LEFT_OR_MAIN)` 一条 RTMP 推流到 ZLM `live/{droneSn}-0`，在该 source 已经是 `INFRARED_CAMERA + PIP SIDE_BY_SIDE` 时，附加一条 metadata 说明这是复合流
3. **ai-service 侧加 `CompositeSliceVideoSource`**：从 ZLM 拉一条流，按宽度对半切出 visible / thermal FramePacket
4. **driving cockpit 侧 thermal 卡片**：从纯状态卡升级为"显示 PIP 复合流右半部分"的局部裁剪，让用户实际能看到热成像
5. （后续）路径 B 时分复用作为 fallback；路径 C 留作"不依赖自研 agent 的备选方案"

## 6. 暂不要再做的事

- 不要再尝试在 `bindVisible` 之后立即对 `LEFT_OR_MAIN` 加第二个 listener 期望它返回 thermal raw —— 文档没禁止但 M4T 不暴露第二个 cameraIndex
- 不要把 `KeyCameraVideoStreamSource = INFRARED_CAMERA` 当成"切换后能拿独立 thermal raw 流"——它只是切 liveview source，不是开第二路 listener
- 不要继续用 "msdk-v5-camera-stream-manager-does-not-expose-simultaneous-visible-and-thermal-stream-binding" 这个 status_reason 字符串，会误导后续阅读者认为 MSDK 本身有这个限制；改成 "m4t-single-gimbal-only-exposes-single-component-index" 更准确

## 7. 参考链接

- ICameraStreamManager API：https://developer.dji.com/api-reference-v5/android-api/Components/IMediaDataCenter/ICameraStreamManager.html
- KeyCameraVideoStreamSource UNSUPPORTED issue：https://github.com/dji-sdk/Mobile-SDK-Android-V5/issues/576
- V5 Sample 仓库：https://github.com/dji-sdk/Mobile-SDK-Android-V5
- V5 Sample CameraStreamDetailFragment.kt：https://github.com/dji-sdk/Mobile-SDK-Android-V5/blob/dev-sdk-main/SampleCode-V5/android-sdk-v5-sample/src/main/java/dji/sampleV5/aircraft/pages/CameraStreamDetailFragment.kt
