package com.yinxin.uavfir.benchmark

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.net.Uri
import android.os.SystemClock

internal class FormalAgentHealthClient(
    private val context: Context,
    private val trust: FormalAgentTrust,
) {
    fun requireHealthy(): FormalAgentHealth {
        FormalAgentProviderLookup { authority, flags ->
            context.packageManager.resolveContentProvider(authority, flags)?.let { provider ->
                AgentHealthProviderDescriptor(
                    authority = provider.authority,
                    packageName = provider.packageName,
                    exported = provider.exported,
                    readPermission = provider.readPermission,
                )
            }
        }.requireTrustedProvider()
        val permission = context.packageManager.getPermissionInfo(READ_PERMISSION, 0)
        check(permission.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE == PermissionInfo.PROTECTION_SIGNATURE) {
            "Formal Agent SDK health permission is not signature-level"
        }
        check(context.checkSelfPermission(READ_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            "Benchmark APK is not signed by the approved Formal Agent signer"
        }
        val response = context.contentResolver.call(
            Uri.parse("content://$AUTHORITY"),
            METHOD_HEALTH,
            null,
            null,
        ) ?: error("Formal Agent SDK health provider returned no response")
        val values = response.keySet().associateWith { response.get(it) }
        return FormalAgentHealthContract.parse(values, trust, SystemClock.elapsedRealtime())
    }

    private companion object {
        const val AUTHORITY = "com.yinxin.uavfir.sdk-health"
        const val READ_PERMISSION = "com.yinxin.uavfir.permission.READ_SDK_HEALTH"
        const val METHOD_HEALTH = "health-v1"
    }
}
