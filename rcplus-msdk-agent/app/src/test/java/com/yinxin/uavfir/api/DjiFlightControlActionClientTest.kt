package com.yinxin.uavfir.api

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DjiFlightControlActionClientTest {

    @Test
    fun virtualStickHorizontalCommandsUseAircraftBodyAxes() {
        val source = File("src/main/java/com/yinxin/uavfir/api/MsdkCommandExecutor.kt").readText()
        val buildVirtualStickParamBody = source.substringAfter("private fun buildVirtualStickParam")
            .substringBefore("companion object")

        assertTrue(
            "Horizontal TSA displacement must use BODY coordinates so KeyW/A/S/D mean forward/left/back/right relative to aircraft heading.",
            buildVirtualStickParamBody.contains("FlightCoordinateSystem.BODY"),
        )
    }

    @Test
    fun virtualStickHorizontalCommandsCompensateMsdkPitchRollAxisMapping() {
        val source = File("src/main/java/com/yinxin/uavfir/api/MsdkCommandExecutor.kt").readText()
        val constructorArgs = source.substringAfter("return VirtualStickFlightControlParam(")
            .substringBefore("VerticalControlMode.VELOCITY")
            .replace("\r\n", "\n")

        assertTrue(
            "Real M4T/RC Plus testing showed the MSDK constructor's first horizontal value drives lateral motion; pass rollVelocity first and pitchVelocity second so W/S become forward/back.",
            constructorArgs.contains("rollVelocity,\n            pitchVelocity,"),
        )
    }

    @Test
    fun virtualStickDurationCapAllowsConfiguredAxisDistanceRange() {
        val source = File("src/main/java/com/yinxin/uavfir/api/MsdkCommandExecutor.kt").readText()
        val maxDuration = Regex("""MAX_VIRTUAL_STICK_DURATION_MS:\s*Long\s*=\s*([0-9_]+)""")
            .find(source)
            ?.groupValues
            ?.get(1)
            ?.replace("_", "")
            ?.toLong()

        assertTrue(
            "TSA distance input supports up to 200 m at 800 ms/m, so the Agent must not clamp timed virtual-stick motion to the old 5 s cap.",
            maxDuration != null && maxDuration >= 160_000L,
        )
    }

    @Test
    fun commandCoordinatorTimeoutAllowsLongestVirtualStickCommandToFinish() {
        val source = File("src/main/java/com/yinxin/uavfir/api/CommandPollingCoordinator.kt").readText()
        val timeout = Regex("""DEFAULT_COMMAND_TIMEOUT_MS:\s*Long\s*=\s*([0-9_]+)""")
            .find(source)
            ?.groupValues
            ?.get(1)
            ?.replace("_", "")
            ?.toLong()

        assertTrue(
            "Outer MSDK command timeout must exceed the longest TSA virtual-stick displacement plus DJI action overhead.",
            timeout != null && timeout >= 180_000L,
        )
    }

    @Test
    fun gimbalNadirUsesAbsoluteAngleRotation() {
        val source = File("src/main/java/com/yinxin/uavfir/api/MsdkCommandExecutor.kt").readText()

        assertTrue(source.contains("fun rotateGimbalToPitch("))
        assertTrue(source.contains("GimbalAngleRotationMode.ABSOLUTE_ANGLE"))
        assertTrue(source.contains("KeyRotateByAngle"))
    }

    @Test
    fun gimbalRelativeAimCorrectionUsesAngleRotation() {
        val source = File("src/main/java/com/yinxin/uavfir/api/MsdkCommandExecutor.kt").readText()
        val body = source.substringAfter("override suspend fun rotateGimbalBy(")
            .substringBefore("override suspend fun rotateGimbalToPitch")

        assertTrue(source.contains("fun rotateGimbalBy("))
        assertTrue(body.contains("GimbalAngleRotationMode.RELATIVE_ANGLE"))
        assertTrue(body.contains("KeyRotateByAngle"))
        assertTrue(body.contains("pitchDelta"))
        assertTrue(body.contains("yawDelta"))
        // 忽略标志必须为 pitchIgnored=false, rollIgnored=true, yawIgnored=false：
        // yawIgnored=true 时水平对中修正会被云台静默丢弃（评审抓到过此回归）。
        assertTrue(
            "rotateGimbalBy ignore flags must keep pitch+yaw active (roll ignored only)",
            Regex("""yawDelta,\s*false,\s*true,\s*false,""").containsMatchIn(body),
        )
    }
}
