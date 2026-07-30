package com.yinxin.uavfir.firedetection

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
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
        assertTrue(
            source.contains(
                "std::unordered_map<uint64_t, std::unique_ptr<Model>> g_active_models",
            ),
        )
        assertTrue(source.contains("uint64_t g_next_model_token = 1"))
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

    @Test
    fun bridgeUsesMonotonicTokensInsteadOfModelAddresses() {
        val source = File("src/main/cpp/visible_fire_ncnn_bridge.cpp").readText()

        assertTrue(source.contains("bool allocate_model_token_locked(uint64_t* token)"))
        assertTrue(source.contains("if (g_next_model_token == 0) return false;"))
        assertTrue(source.contains("*token = g_next_model_token++;"))
        assertTrue(source.contains("token_from_handle(handle)"))
        assertTrue(source.contains("handle_from_token(token)"))
        assertTrue(source.contains("g_active_models.emplace(token, std::move(model))"))
        assertTrue(source.contains("auto* model = active->second.get()"))
        assertTrue(source.contains("std::unique_ptr<Model> model = std::move(active->second)"))
        assertFalse(source.contains("reinterpret_cast<Model*>(handle)"))
        assertFalse(source.contains("reinterpret_cast<jlong>(model.release())"))

        val loadIndex = source.indexOf("bin_status = param_status == 0")
        val allocateIndex = source.indexOf("allocate_model_token_locked(&token)", loadIndex)
        val publishIndex = source.indexOf(
            "g_active_models.emplace(token, std::move(model))",
            allocateIndex,
        )
        assertTrue(loadIndex >= 0 && allocateIndex > loadIndex && publishIndex > allocateIndex)

        val eraseIndex = source.indexOf("g_active_models.erase(active)")
        val deleteIndex = source.indexOf("model.reset()", eraseIndex)
        val releaseIndex = source.indexOf("release_gpu_session_locked()", eraseIndex)
        assertTrue(eraseIndex >= 0 && deleteIndex > eraseIndex && releaseIndex > deleteIndex)
    }

    @Test
    fun tokenRegistryRejectsOldGenerationWithoutAffectingNewSession() {
        val registry = NativeTokenRegistryContractFixture()
        val oldToken = registry.create("old", succeeds = true)!!

        registry.close(oldToken)
        val newToken = registry.create("new", succeeds = true)!!

        assertNotEquals(oldToken, newToken)
        assertNull(registry.infer(oldToken))
        registry.close(oldToken)
        assertEquals("new", registry.infer(newToken))
        registry.close(oldToken)
        assertEquals("new", registry.infer(newToken))
        registry.close(newToken)
        assertNull(registry.infer(newToken))
    }

    @Test
    fun failedCreateDoesNotPublishOrPolluteActiveRegistry() {
        val registry = NativeTokenRegistryContractFixture()
        val liveToken = registry.create("live", succeeds = true)!!

        assertNull(registry.create("failed", succeeds = false))
        assertEquals(1, registry.activeCount)
        assertEquals("live", registry.infer(liveToken))
    }

    @Test
    fun tokenOverflowFailsClosedAndNeverUsesZero() {
        val registry = NativeTokenRegistryContractFixture(nextToken = ULong.MAX_VALUE)

        val finalToken = registry.create("last", succeeds = true)!!
        assertEquals(-1L, finalToken)
        assertNull(registry.create("must-not-wrap", succeeds = true))
        assertEquals(1, registry.activeCount)
        assertEquals("last", registry.infer(finalToken))
    }

    private class NativeTokenRegistryContractFixture(
        private var nextToken: ULong = 1uL,
    ) {
        private val active = mutableMapOf<ULong, String>()

        val activeCount: Int
            get() = active.size

        fun create(name: String, succeeds: Boolean): Long? {
            if (!succeeds || nextToken == 0uL) return null
            val token = nextToken
            nextToken += 1uL
            active[token] = name
            return token.toLong()
        }

        fun infer(handle: Long): String? = active[handle.toULong()]

        fun close(handle: Long) {
            active.remove(handle.toULong())
        }
    }
}
