package com.yinxin.uavfir.benchmark

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NcnnStagingContractTest {
    @Test
    fun stagingAlwaysRunsToRemoveStaleLibrariesAndFailsClosedForNcnnCandidates() {
        val buildScript = File("build.gradle.kts").readText()

        assertTrue(buildScript.contains("outputs.upToDateWhen { false }"))
        assertFalse(buildScript.contains("onlyIf { measurementCandidate in setOf(\"ncnn\", \"benchmark\")"))
        assertTrue(buildScript.contains("deleteRecursively() || !outputDirectory.exists()"))
        assertTrue(buildScript.contains("if (!requiresNcnnRuntime) return@doLast"))
        assertTrue(buildScript.contains("NCNN packaging requires -PncnnPackageDir"))
        assertTrue(buildScript.contains("copy {"))
    }
}
