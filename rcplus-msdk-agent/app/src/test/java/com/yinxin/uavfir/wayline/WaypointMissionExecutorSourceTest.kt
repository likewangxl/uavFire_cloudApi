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
    fun startMissionFailsClosedBeforeCallingMsdkWhenAircraftHasNoWaylineIds() {
        val source = File("src/main/java/com/yinxin/uavfir/wayline/WaypointMissionExecutor.kt").readText()
        val body = source.substringAfter("fun startMission(").substringBefore("fun pauseMission()")

        val guardIndex = body.indexOf("WaypointMissionStartGuard.selectWaylineIds")
        val rejectIndex = body.indexOf("if (!selection.canStart)")
        val msdkStartIndex = body.indexOf("WaypointMissionManager.getInstance().startMission")

        assertTrue("aircraft-confirmed IDs must be checked", guardIndex >= 0)
        assertTrue("unavailable missions must be rejected", rejectIndex > guardIndex)
        assertTrue("the guard must run before MSDK startMission", msdkStartIndex > rejectIndex)
    }

    @Test
    fun productionRouterUsesDownloadedKmzPathForAvailableWaylineDiagnostics() {
        val source = File("src/main/java/com/yinxin/uavfir/wayline/WaylineAgentCommandRouter.kt").readText()
        val startCall = source.substringAfter("val startRejection = executor.startMission(")
            .substringBefore("if (startRejection != null)")

        assertTrue(
            "getAvailableWaylineIDs must receive the downloaded KMZ path, while startMission receives the basename",
            startCall.contains("downloadResult.file.absolutePath"),
        )
    }
}
