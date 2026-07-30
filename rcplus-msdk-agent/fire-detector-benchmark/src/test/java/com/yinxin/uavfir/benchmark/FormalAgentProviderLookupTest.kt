package com.yinxin.uavfir.benchmark

import org.junit.Assert.assertEquals
import org.junit.Test

class FormalAgentProviderLookupTest {
    @Test
    fun unlockedDeviceLookupDoesNotFilterOutDirectBootUnawareProvider() {
        var observedAuthority = ""
        var observedFlags = -1
        val lookup = FormalAgentProviderLookup { authority, flags ->
            observedAuthority = authority
            observedFlags = flags
            AgentHealthProviderDescriptor(
                authority = "com.yinxin.uavfir.sdk-health",
                packageName = "com.yinxin.uavfir",
                exported = true,
                readPermission = "com.yinxin.uavfir.permission.READ_SDK_HEALTH",
            )
        }

        lookup.requireTrustedProvider()

        assertEquals("com.yinxin.uavfir.sdk-health", observedAuthority)
        assertEquals(0, observedFlags)
    }

    @Test(expected = IllegalStateException::class)
    fun lookupRejectsProviderWithWrongAuthorityOrPermission() {
        FormalAgentProviderLookup { _, _ ->
            AgentHealthProviderDescriptor(
                authority = "attacker.provider",
                packageName = "com.yinxin.uavfir",
                exported = true,
                readPermission = null,
            )
        }.requireTrustedProvider()
    }
}
