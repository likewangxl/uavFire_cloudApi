package com.yinxin.uavfir.benchmark

internal data class AgentHealthProviderDescriptor(
    val authority: String,
    val packageName: String,
    val exported: Boolean,
    val readPermission: String?,
)

internal fun interface AgentHealthProviderResolver {
    fun resolve(authority: String, flags: Int): AgentHealthProviderDescriptor?
}

internal class FormalAgentProviderLookup(
    private val resolver: AgentHealthProviderResolver,
) {
    fun requireTrustedProvider(): AgentHealthProviderDescriptor {
        val provider = resolver.resolve(AUTHORITY, UNLOCKED_USER_FLAGS)
            ?: error("Formal Agent SDK health provider is unavailable")
        check(
            provider.authority == AUTHORITY &&
                provider.packageName == FORMAL_AGENT_PACKAGE &&
                provider.exported &&
                provider.readPermission == READ_PERMISSION,
        ) { "Formal Agent SDK health provider is not signature protected" }
        return provider
    }

    companion object {
        const val UNLOCKED_USER_FLAGS = 0
        const val FORMAL_AGENT_PACKAGE = "com.yinxin.uavfir"
        const val AUTHORITY = "com.yinxin.uavfir.sdk-health"
        const val READ_PERMISSION = "com.yinxin.uavfir.permission.READ_SDK_HEALTH"
    }
}
