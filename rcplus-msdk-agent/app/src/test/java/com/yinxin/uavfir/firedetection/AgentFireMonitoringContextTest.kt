package com.yinxin.uavfir.firedetection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentFireMonitoringContextTest {
    @Test
    fun `authoritative aircraft and active mission remain independent exact identities`() {
        val context = authoritativeFireMonitoringContext(
            monitoringEnabled = true,
            activeDroneSn = "1581F7K3D249C00AEK3P",
            activeStreamDroneSn = "1581F7K3D249C00AEK3P",
            activeTaskId = "inspection-mission-8472",
            sourceGeneration = 11,
        )

        assertEquals("1581F7K3D249C00AEK3P", context?.droneSn)
        assertEquals("inspection-mission-8472", context?.taskId)
        assertEquals(11L, context?.sourceGeneration)
    }

    @Test
    fun `missing task aircraft or stream mismatch fails closed before confirmation`() {
        fun resolve(drone: String?, stream: String?, task: String?) = authoritativeFireMonitoringContext(
            monitoringEnabled = true,
            activeDroneSn = drone,
            activeStreamDroneSn = stream,
            activeTaskId = task,
            sourceGeneration = 11,
        )
        assertNull(resolve("drone-1", "drone-1", null))
        assertNull(resolve(null, "drone-1", "task-1"))
        assertNull(resolve("drone-1", "drone-2", "task-1"))
        assertNull(authoritativeFireMonitoringContext(false, "drone-1", "drone-1", "task-1", 11))
    }
}
