package com.yinxin.uavfir.wayline

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class WaypointMissionExecutorSourceTest {
    @Test
    fun progressListenerImplementsDjiInterruptReasonCallback() {
        val source = File("src/main/java/com/yinxin/uavfir/wayline/WaypointMissionExecutor.kt").readText()

        assertTrue(
            "DJI 5.18 may invoke the interrupt callback as an abstract interface method at runtime",
            source.contains("override fun onWaylineExecutingInterruptReasonUpdate(error: IDJIError)"),
        )
    }

    @Test
    fun attachInitializesWaypointMissionManagerBeforeRegisteringListeners() {
        val source = File("src/main/java/com/yinxin/uavfir/wayline/WaypointMissionExecutor.kt").readText()
        val attachBody = source.substringAfter("fun attach()").substringBefore("fun detach()")

        val initIndex = attachBody.indexOf(".init()")
        val stateListenerIndex = attachBody.indexOf("addWaypointMissionExecuteStateListener")
        val progressListenerIndex = attachBody.indexOf("addWaylineExecutingInfoListener")

        assertTrue("attach should initialize WaypointMissionManager", initIndex >= 0)
        assertTrue("init should happen before state listener registration", initIndex < stateListenerIndex)
        assertTrue("init should happen before progress listener registration", initIndex < progressListenerIndex)
    }

    @Test
    fun startMissionSuccessTiltsGimbalToNadir() {
        val source = File("src/main/java/com/yinxin/uavfir/wayline/WaypointMissionExecutor.kt").readText()
        val callbackBody = source.substringAfter("private fun simpleCallback").substringBefore("companion object")

        assertTrue(
            "startMission success should trigger automatic nadir gimbal adjustment",
            callbackBody.contains("tiltGimbalToNadir(missionId)"),
        )
        assertTrue(
            "monitoring gimbal pitch should be fixed at -45 degrees",
            source.contains("NADIR_GIMBAL_PITCH_DEGREES: Double = -45.0"),
        )
    }

    @Test
    fun legacyPauseResumeCallbacksAndExplicitInterruptImplementationRemainPresent() {
        val source = File("src/main/java/com/yinxin/uavfir/wayline/WaypointMissionExecutor.kt").readText()
        val awaitable = File("src/main/java/com/yinxin/uavfir/firedetection/AwaitableMissionControl.kt").readText()

        assertTrue(source.contains("fun pauseMission()"))
        assertTrue(source.contains("fun resumeMission()"))
        assertTrue(source.contains("fun pauseMission(onComplete: (IDJIError?) -> Unit)"))
        assertTrue(source.contains("breakpoint: BreakPointInfo"))
        assertTrue(source.contains("override fun onWaylineExecutingInterruptReasonUpdate(error: IDJIError)"))
        assertTrue(awaitable.contains("suspendCancellableCoroutine"))
    }

    @Test
    fun missionIdentityStateAndGenerationsUseOneAtomicSnapshot() {
        val source = File("src/main/java/com/yinxin/uavfir/wayline/WaypointMissionExecutor.kt").readText()

        assertTrue(source.contains("AtomicReference(MissionExecutionSnapshot("))
        assertTrue(source.contains("missionGeneration"))
        assertTrue(source.contains("commandGeneration"))
        assertTrue(source.contains("observeMissionExecution"))
        assertTrue(
            "observers must receive a full generation-bound snapshot",
            source.contains("(MissionExecutionSnapshot) -> Unit"),
        )
        assertTrue(!source.contains("private val activeMission ="))
        assertTrue(!source.contains("private val lastState ="))
    }
}
