package com.yinxin.uavfir.benchmark

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class FormalAgentBuildContractTest {
    @Test
    fun benchmarkBuildDerivesTrustFromALocalSignedFormalAgentApk() {
        val buildScript = File("build.gradle.kts").readText()

        assertTrue(buildScript.contains("formalAgentApk"))
        assertTrue(buildScript.contains("writeFormalAgentTrust"))
        assertTrue(buildScript.contains("apksigner"))
        assertTrue(buildScript.contains("--print-certs"))
        assertTrue(buildScript.contains("formal-agent-trust.json"))
    }

    @Test
    fun agentApkMarksRealUxsdkAndHealthContractFromTheSelectedProject() {
        val appBuild = File("../app/build.gradle.kts").readText()
        val manifest = File("../app/src/main/AndroidManifest.xml").readText()

        assertTrue(appBuild.contains("realUxsdkBuild"))
        assertTrue(appBuild.contains("project(\":uxsdk\").projectDir"))
        assertTrue(manifest.contains("com.yinxin.uavfir.REAL_UXSDK"))
        assertTrue(manifest.contains("com.yinxin.uavfir.HEALTH_CONTRACT"))
    }
}
