package com.yinxin.uavfir.benchmark

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkAssetBindingContractTest {
    @Test
    fun stagingPackagesVisibleLabelsForDeviceHashVerification() {
        val buildScript = File("build.gradle.kts").readText()

        assertTrue(buildScript.contains("include(\"benchmark-set/labels/**\")"))
    }
}
