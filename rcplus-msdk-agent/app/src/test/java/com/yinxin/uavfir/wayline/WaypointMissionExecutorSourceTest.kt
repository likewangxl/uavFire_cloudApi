package com.yinxin.uavfir.wayline

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class WaypointMissionExecutorSourceTest {
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
            "nadir gimbal pitch should be fixed at -90 degrees",
            source.contains("NADIR_GIMBAL_PITCH_DEGREES: Double = -90.0"),
        )
    }
}
