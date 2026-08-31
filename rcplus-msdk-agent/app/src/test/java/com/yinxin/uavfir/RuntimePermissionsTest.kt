package com.yinxin.uavfir

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

class RuntimePermissionsTest {
    @Test
    fun declaresRuntimePermissionsRequiredByMsdkAndAgent() {
        assertEquals(6, RuntimePermissions.required.toSet().size)
        assertTrue(RuntimePermissions.required.contains("android.permission.ACCESS_FINE_LOCATION"))
        assertTrue(RuntimePermissions.required.contains("android.permission.READ_PHONE_STATE"))
        assertTrue(RuntimePermissions.required.contains("android.permission.WRITE_EXTERNAL_STORAGE"))
        assertTrue(RuntimePermissions.required.contains("android.permission.RECORD_AUDIO"))
    }

    @Test
    fun manifestDeclaresEveryRequestedRuntimePermission() {
        val manifest = String(Files.readAllBytes(Paths.get("src/main/AndroidManifest.xml")))

        RuntimePermissions.required.forEach { permission ->
            assertTrue("manifest missing $permission", manifest.contains(permission))
        }
    }
}
