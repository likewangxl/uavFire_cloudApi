package com.yinxin.uavfir.benchmark

import org.junit.Assert.assertEquals
import org.junit.Test

class FormalAgentHealthContractTest {
    private val trust = FormalAgentTrust.parse(
        """{"schemaVersion":2,"packageName":"com.yinxin.uavfir","apkSha256":"${"a".repeat(64)}","signingCertificateSha256":"${"b".repeat(64)}","versionName":"0.1.3","versionCode":4,"buildId":"uavfire-agent-0.1.3-4"}""",
    )

    @Test
    fun parse_acceptsHealthyRuntimeResponseBoundToTrustedRelease() {
        val health = FormalAgentHealthContract.parse(
            mapOf(
                "schemaVersion" to 1,
                "sdkRegistered" to true,
                "uxsdkReal" to true,
                "msdkReady" to true,
                "buildId" to "uavfire-agent-0.1.3-4",
                "versionName" to "0.1.3",
                "versionCode" to 4L,
            ),
            trust,
        )

        assertEquals("uavfire-agent-0.1.3-4", health.buildId)
    }

    @Test(expected = IllegalStateException::class)
    fun parse_rejectsMetadataOnlyClaimBeforeMsdkRegistration() {
        FormalAgentHealthContract.parse(
            mapOf(
                "schemaVersion" to 1,
                "sdkRegistered" to false,
                "uxsdkReal" to true,
                "msdkReady" to true,
                "buildId" to "uavfire-agent-0.1.3-4",
                "versionName" to "0.1.3",
                "versionCode" to 4L,
            ),
            trust,
        )
    }
}
