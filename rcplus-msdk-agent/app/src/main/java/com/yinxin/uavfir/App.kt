package com.yinxin.uavfir

import android.app.Application
import android.content.Context
import android.util.Log
import dji.v5.common.utils.GeoidManager
import dji.v5.ux.core.communication.DefaultGlobalPreferences
import dji.v5.ux.core.communication.GlobalPreferencesManager
import dji.v5.ux.core.util.UxSharedPreferencesUtil
import com.yinxin.uavfir.sdk.AgentSdkHealthState

class App : Application() {
    lateinit var services: AppServices
        private set
    private val runtimeLoopLifecyclePolicy = RuntimeLoopLifecyclePolicy()

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        MsdkCompatHelperInstaller.install(this)
            .onFailure { throwable ->
                Log.e(TAG, "MSDK helper install failed", throwable)
            }
    }

    override fun onCreate() {
        super.onCreate()
        AppContextHolder.initialize(this)
        initializeUxSdkDefaults()
        services = AppServices(this)
        Log.i(TAG, "application created, runtime loop ready for dynamic MSDK identity")
        if (runtimeLoopLifecyclePolicy.startOnApplicationCreate) {
            Log.i(TAG, "application created, starting runtime loop")
            services.runtimeLoop.start(LOCAL_DRONE_SN)
        }
    }

    private fun initializeUxSdkDefaults() {
        UxSharedPreferencesUtil.initialize(this)
        GlobalPreferencesManager.initialize(DefaultGlobalPreferences(this))
        GeoidManager.getInstance().init(this)
        AgentSdkHealthState.tracker.markUxSdkInitialized(
            sourceSha256 = BuildConfig.UXSDK_SOURCE_SHA256,
            runtimeClassPresent = runCatching {
                Class.forName("dji.v5.ux.core.widget.fpv.FPVWidget")
            }.isSuccess,
        )
    }

    override fun onTerminate() {
        services.shutdown()
        super.onTerminate()
    }

    companion object {
        private const val TAG = "UavfireApp"
        val LOCAL_DRONE_SN: String = BuildConfig.AGENT_AIRCRAFT_SN
            .takeIf { it.isNotBlank() }
            ?: ""
    }
}
