package com.yinxin.uavfir

import android.app.Application

object MsdkCompatHelperInstaller {
    fun install(
        application: Application,
        helperClassName: String = "com.cySdkyc.clx.Helper",
    ): Result<Unit> {
        return runCatching {
            val helperClass = Class.forName(helperClassName)
            val installMethod = helperClass.getMethod("install", Application::class.java)
            installMethod.invoke(null, application)
        }
    }
}
