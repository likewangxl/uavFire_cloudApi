# Codex 任务书 TC1A.1：云台对中改用角度相对旋转（修正速度接口误用）

## 背景（评审发现）

TC1-A 的对中闭环把角度增量（度）传给了 `GimbalActionClient.rotateGimbal(pitch, yaw, roll)`，而该方法底层是 `KeyRotateBySpeed`/`GimbalSpeedRotation`（`MsdkCommandExecutor.kt:214-221`）——参数语义是**度/秒的速度**，单次命令实际转动角度 = 速度 × 生效时长，实机上不可控（可能几乎不动，也可能过冲）。闭环迭代无法可靠收敛。

正确原语：MSDK 的 `DJIGimbalKey.KeyRotateByAngle` + `GimbalAngleRotationMode.RELATIVE_ANGLE`（角度相对旋转，确定性增量）。该 key 与枚举已在 `MsdkCommandExecutor.kt` 中使用（:191、:204、:227、:240 均为 ABSOLUTE_ANGLE 用法，照葫芦画瓢即可）。

## 仓库与范围

- 仓库：`D:\likewangx\项目材料\智能集群大载重无人机灭火系统\uavFire_cloudApi_publish`，分支 `feature/fire-precision-and-realtime-detection`
- 只改：`rcplus-msdk-agent/app/src/main/java/com/yinxin/uavfir/api/MsdkCommandExecutor.kt`、`FireConfirmationProcessor.kt`、`app/src/test` 下相应测试
- **不要动** TC1-A 其余已验收内容（稳健激光统计、门限、median 逻辑）；不要动后端（另一任务正在改后端，严禁触碰 backend/ 目录）
- 不执行 git commit

## 实现要求

1. `GimbalActionClient` 接口新增：

```kotlin
/** 角度相对旋转（RELATIVE_ANGLE）：pitchDelta/yawDelta 单位为度，正负表示方向 */
suspend fun rotateGimbalBy(pitchDelta: Double, yawDelta: Double)
```

2. `DjiFlightControlActionClient` 实现：`GimbalAngleRotation(GimbalAngleRotationMode.RELATIVE_ANGLE, pitchDelta, roll=0, yaw=yawDelta, ...)` + `KeyTools.createKey(DJIGimbalKey.KeyRotateByAngle, ComponentIndexType.LEFT_OR_MAIN)`，构造参数逐一对照 :191-204 的 ABSOLUTE_ANGLE 用法（duration 等字段取同样的处理方式）。
3. `FireConfirmationProcessor.aimAtThermalHotspot` 中 `gimbalControl.rotateGimbal(pitchDelta, yawDelta, 0.0)` 改为 `gimbalControl.rotateGimbalBy(pitchDelta, yawDelta)`；更新注释（删除"speed rotation suitable for closed-loop"的错误论述，写明 RELATIVE_ANGLE 语义）。
4. 每次相对旋转后加短暂稳定等待 `aimSettleMs`（构造参数，默认 500ms）再重新测温。

## 测试要求

1. 更新既有 `aim_converges_within_iterations` 等对中测试：断言改为 `rotateGimbalBy` 调用（方向断言保留：ROI 偏右 dx>0 → yawDelta>0；ROI 偏下 dy>0 → pitchDelta<0）。
2. 所有测试 fake（RecordingGimbalControl 等）补 `rotateGimbalBy` 实现并记录调用。
3. agent 全量无回归（179 基数）。

## 验证方式（必须实际执行并粘贴数字）

```bash
MSYS_NO_PATHCONV=1 robocopy "D:\likewangx\项目材料\智能集群大载重无人机灭火系统\uavFire_cloudApi_publish\rcplus-msdk-agent\app\src" "C:\Users\51799\uavfire-verify\rcplus-msdk-agent\app\src" /MIR /NFL /NDL /NJH /NP
cd /c/Users/51799/uavfire-verify/rcplus-msdk-agent
JAVA_HOME="C:\Program Files\Java\jdk-17.0.18" ANDROID_HOME="C:\Users\51799\AppData\Local\Android\Sdk" ./gradlew.bat :app:testDebugUnitTest
```

## 完成后输出

改动文件列表、测试数字、偏差说明。
