package com.yinxin.uavfir.sdk

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import dji.v5.manager.SDKManager

class AgentSdkHealthProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        check(method == METHOD_HEALTH) { "Unsupported Agent health method" }
        val liveRegistered = runCatching { SDKManager.getInstance().isRegistered }
            .getOrElse {
                AgentSdkHealthState.tracker.markMsdkUnavailable()
                false
            }
        val health = AgentSdkHealthState.tracker.snapshot(liveSdkRegistered = liveRegistered)
        return Bundle().apply {
            putInt("schemaVersion", health.schemaVersion)
            putBoolean("sdkRegistered", health.sdkRegistered)
            putBoolean("uxsdkReal", health.uxsdkReal)
            putBoolean("msdkReady", health.msdkReady)
            putString("buildId", health.buildId)
            putString("versionName", health.versionName)
            putLong("versionCode", health.versionCode)
            putLong("observedAtElapsedRealtimeMillis", health.observedAtElapsedRealtimeMillis)
            putLong("maxAgeMillis", health.maxAgeMillis)
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "com.yinxin.uavfir.sdk-health"
        const val READ_PERMISSION = "com.yinxin.uavfir.permission.READ_SDK_HEALTH"
        const val METHOD_HEALTH = "health-v1"
    }
}
