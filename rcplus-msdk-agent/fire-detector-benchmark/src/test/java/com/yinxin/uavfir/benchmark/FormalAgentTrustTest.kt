package com.yinxin.uavfir.benchmark

import org.junit.Assert.assertEquals
import org.junit.Test

class FormalAgentTrustTest {
    @Test
    fun parse_acceptsBuildGeneratedFormalAgentIdentity() {
        val trust = FormalAgentTrust.parse(
            """{"schemaVersion":2,"packageName":"com.yinxin.uavfir","apkSha256":"${"a".repeat(64)}","signingCertificateSha256":"${"b".repeat(64)}","versionName":"0.1.3","versionCode":4,"buildId":"uavfire-agent-0.1.3-4"}""",
        )

        assertEquals("a".repeat(64), trust.apkSha256)
        assertEquals("b".repeat(64), trust.signingCertificateSha256)
        assertEquals("0.1.3", trust.versionName)
        assertEquals(4L, trust.versionCode)
        assertEquals("uavfire-agent-0.1.3-4", trust.buildId)
    }

    @Test(expected = IllegalStateException::class)
    fun parse_rejectsOperatorSelectedOrIncompleteIdentity() {
        FormalAgentTrust.parse("""{"packageName":"com.yinxin.uavfir","apkSha256":"operator-value"}""")
    }
}
