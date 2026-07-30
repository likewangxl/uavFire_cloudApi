package com.yinxin.uavfir.benchmark

import org.junit.Assert.assertEquals
import org.junit.Test

class FormalAgentTrustTest {
    @Test
    fun parse_acceptsBuildGeneratedFormalAgentIdentity() {
        val trust = FormalAgentTrust.parse(
            """{"packageName":"com.yinxin.uavfir","apkSha256":"${"a".repeat(64)}","signingCertificateSha256":"${"b".repeat(64)}"}""",
        )

        assertEquals("a".repeat(64), trust.apkSha256)
        assertEquals("b".repeat(64), trust.signingCertificateSha256)
    }

    @Test(expected = IllegalStateException::class)
    fun parse_rejectsOperatorSelectedOrIncompleteIdentity() {
        FormalAgentTrust.parse("""{"packageName":"com.yinxin.uavfir","apkSha256":"operator-value"}""")
    }
}
