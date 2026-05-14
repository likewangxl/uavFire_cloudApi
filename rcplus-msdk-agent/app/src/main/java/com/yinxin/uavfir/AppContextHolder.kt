package com.yinxin.uavfir

import android.content.Context

object AppContextHolder {
    @Volatile
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun get(): Context? = appContext

    fun require(): Context = checkNotNull(appContext) {
        "Application context is not initialized"
    }
}
