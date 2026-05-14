package com.yinxin.uavfir

import android.app.Application
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Test

class MsdkCompatHelperInstallerTest {

    @Test
    fun installInvokesHelperUsingApplicationSignature() {
        val app = Application()

        MsdkCompatHelperInstaller.install(
            application = app,
            helperClassName = FakeHelper::class.java.name,
        )

        assertSame(app, FakeHelper.installedWith)
    }

    @Test
    fun installReturnsFailureWhenHelperClassIsMissing() {
        val result = MsdkCompatHelperInstaller.install(
            application = Application(),
            helperClassName = "com.example.missing.Helper",
        )

        assertTrue(result.isFailure)
    }

    object FakeHelper {
        var installedWith: Application? = null

        @JvmStatic
        fun install(application: Application) {
            installedWith = application
        }
    }
}
