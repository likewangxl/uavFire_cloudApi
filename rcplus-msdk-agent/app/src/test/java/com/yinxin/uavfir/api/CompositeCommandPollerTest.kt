package com.yinxin.uavfir.api

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositeCommandPollerTest {

    @Test
    fun pollOnce_invokesEveryPollerInOrder() = runTest {
        val order = mutableListOf<String>()
        val composite = CompositeCommandPoller(
            listOf(
                RecordingPoller("a", order),
                RecordingPoller("b", order),
                RecordingPoller("c", order),
            ),
        )

        composite.pollOnce("DRONE-001")

        assertEquals(listOf("a", "b", "c"), order)
    }

    @Test
    fun pollOnce_failureInOnePollerDoesNotAbortOthers() = runTest {
        val order = mutableListOf<String>()
        val failures = mutableListOf<Pair<String, String>>()
        val composite = CompositeCommandPoller(
            pollers = listOf(
                RecordingPoller("a", order),
                ThrowingPoller("boom"),
                RecordingPoller("c", order),
            ),
            onFailure = { poller, t ->
                failures += poller::class.simpleName.orEmpty() to (t.message ?: "")
            },
        )

        composite.pollOnce("DRONE-001")

        assertEquals(listOf("a", "c"), order)
        assertEquals(1, failures.size)
        assertEquals("ThrowingPoller", failures[0].first)
        assertTrue(failures[0].second.contains("boom"))
    }

    @Test
    fun pollOnce_passesDroneSnThrough() = runTest {
        val seen = mutableListOf<String>()
        val composite = CompositeCommandPoller(
            listOf(CapturingPoller(seen), CapturingPoller(seen)),
        )

        composite.pollOnce("DRONE-XYZ")

        assertEquals(listOf("DRONE-XYZ", "DRONE-XYZ"), seen)
    }

    private class RecordingPoller(
        private val tag: String,
        private val sink: MutableList<String>,
    ) : CommandPoller {
        override suspend fun pollOnce(droneSn: String) {
            sink += tag
        }
    }

    private class ThrowingPoller(private val message: String) : CommandPoller {
        override suspend fun pollOnce(droneSn: String) {
            throw RuntimeException(message)
        }
    }

    private class CapturingPoller(private val sink: MutableList<String>) : CommandPoller {
        override suspend fun pollOnce(droneSn: String) {
            sink += droneSn
        }
    }
}
