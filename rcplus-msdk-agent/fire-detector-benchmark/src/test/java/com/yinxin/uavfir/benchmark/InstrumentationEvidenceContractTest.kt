package com.yinxin.uavfir.benchmark

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstrumentationEvidenceContractTest {
    private val source = File(
        "src/androidTest/java/com/yinxin/uavfir/benchmark/FireDetectorBenchmarkInstrumentedTest.kt",
    ).readText()

    @Test
    fun sessionIdentityIsHarnessGeneratedFreshAndBootBound() {
        assertTrue(source.contains("UUID.randomUUID()"))
        assertTrue(source.contains("/proc/sys/kernel/random/boot_id"))
        assertTrue(source.contains("requireFreshSession"))
        assertTrue(source.contains("clearPriorGateEvidence"))
        assertFalse(source.contains("""arguments.getString("runId")"""))
    }

    @Test
    fun agentAndEngineIdentityCannotBeSelfAttestedByInstrumentationArguments() {
        assertTrue(source.contains("FormalAgentTrust.load(context)"))
        assertTrue(source.contains("requireFormalAgentRunning()"))
        assertTrue(source.contains("candidateApkSha256"))
        assertTrue(source.contains("ncnnBridgeSourceSha256"))
        assertFalse(source.contains("""arguments.getString("agentApkSha256")"""))
    }
}
