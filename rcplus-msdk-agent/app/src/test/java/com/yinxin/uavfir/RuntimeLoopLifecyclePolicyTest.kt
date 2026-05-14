package com.yinxin.uavfir

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeLoopLifecyclePolicyTest {
    @Test
    fun runtimeLoopRunsForApplicationProcess_notProcessForegroundLifecycle() {
        val policy = RuntimeLoopLifecyclePolicy()

        assertTrue(policy.startOnApplicationCreate)
        assertFalse(policy.stopOnProcessStop)
    }
}
