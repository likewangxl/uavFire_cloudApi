package com.yinxin.uavfir.benchmark

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.net.Uri

internal class FormalAgentHealthClient(
    private val context: Context,
    private val trust: FormalAgentTrust,
) {
    fun requireHealthy(): FormalAgentHealth {
        val provider = context.packageManager.resolveContentProvider(AUTHORITY, PackageManager.MATCH_DIRECT_BOOT_AWARE)
            ?: error("Formal Agent SDK health provider is unavailable")
        check(
            provider.packageName == FORMAL_AGENT_PACKAGE &&
                provider.exported &&
                provider.readPermission == READ_PERMISSION,
        ) { "Formal Agent SDK health provider is not signature protected" }
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
        return FormalAgentHealthContract.parse(values, trust)
    }

    private companion object {
        const val FORMAL_AGENT_PACKAGE = "com.yinxin.uavfir"
        const val AUTHORITY = "com.yinxin.uavfir.sdk-health"
        const val READ_PERMISSION = "com.yinxin.uavfir.permission.READ_SDK_HEALTH"
        const val METHOD_HEALTH = "health-v1"
    }
}
