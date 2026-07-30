package com.yinxin.uavfir.firedetection

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibleFireNativeContractTest {
    @Test
    fun shapeHelperAcceptsOnlyChannelMajorSixBy18900() {
        assertTrue(VisibleNcnnOutputShape.isExpected(dims = 2, width = 18_900, height = 6))
        assertFalse(VisibleNcnnOutputShape.isExpected(dims = 2, width = 6, height = 18_900))
        assertFalse(VisibleNcnnOutputShape.isExpected(dims = 1, width = 18_900, height = 6))
        assertFalse(VisibleNcnnOutputShape.isExpected(dims = 3, width = 18_900, height = 6))
    }

    @Test
    fun bridgeSerializesGlobalVulkanLifecycleAndValidatesExactAxes() {
        val source = File("src/main/cpp/visible_fire_ncnn_bridge.cpp").readText()

        assertTrue(source.contains("std::mutex g_runtime_mutex"))
        assertTrue(source.contains("std::unordered_set<Model*> g_active_models"))
        assertTrue(source.contains("int g_gpu_session_count = 0"))
        assertTrue(source.contains("std::lock_guard<std::mutex> lock(g_runtime_mutex)"))
        val resetIndex = source.indexOf("model.reset()")
        val rollbackIndex = source.indexOf("release_gpu_session_locked()", resetIndex)
        assertTrue(resetIndex >= 0 && rollbackIndex > resetIndex)
        assertTrue(source.contains("release_gpu_session_locked()"))
        assertTrue(
            source.contains(
                "if (g_gpu_session_count == 0) ncnn::destroy_gpu_instance();",
            ),
        )
        assertTrue(source.contains("output.dims == 2"))
        assertTrue(source.contains("output.w == kCandidateCount"))
        assertTrue(source.contains("output.h == kOutputChannels"))
        assertFalse(source.contains("output.total() != kOutputElements"))
    }
}
