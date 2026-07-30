package com.yinxin.uavfir.benchmark

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentTrustAnchorTest {
    @Test(expected = IllegalStateException::class)
    fun repositoryAnchorFailsClosedUntilAFormalReleaseIsReviewed() {
        val anchor = AgentTrustAnchor.parse(File("agent-trust-anchor.json").readText())

        anchor.requireApproved(
            signerSha256 = "a".repeat(64),
            apkSha256 = "b".repeat(64),
            versionName = "0.1.3",
            versionCode = 4,
            buildId = "uavfire-agent-0.1.3-4",
        )
    }

    @Test
    fun approvedReleaseRequiresSignerApkVersionAndBuildIdentity() {
        val anchor = AgentTrustAnchor.parse(
            """
            {
              "schemaVersion":1,
              "packageName":"com.yinxin.uavfir",
              "healthContract":"agent-sdk-health-v1",
              "allowedSigningCertificates":["${"a".repeat(64)}"],
              "approvedReleases":[{
                "signingCertificateSha256":"${"a".repeat(64)}",
                "apkSha256":"${"b".repeat(64)}",
                "versionName":"0.1.3",
                "versionCode":4,
                "buildId":"uavfire-agent-0.1.3-4"
              }]
            }
            """.trimIndent(),
        )

        val release = anchor.requireApproved(
            signerSha256 = "a".repeat(64),
            apkSha256 = "b".repeat(64),
            versionName = "0.1.3",
            versionCode = 4,
            buildId = "uavfire-agent-0.1.3-4",
        )

        assertEquals("uavfire-agent-0.1.3-4", release.buildId)
    }
}
