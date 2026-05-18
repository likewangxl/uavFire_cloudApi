package com.yinxin.uavfir

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

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
        services = AppServices(this)
        services.setActiveDroneSn(LOCAL_DRONE_SN)
        Log.i(TAG, "application created, runtime loop ready for ${LOCAL_DRONE_SN}")
        if (runtimeLoopLifecyclePolicy.startOnApplicationCreate) {
            Log.i(TAG, "application created, starting runtime loop for ${LOCAL_DRONE_SN}")
            services.runtimeLoop.start(LOCAL_DRONE_SN)
        }
        // 让 agent 在 Pilot 2 切前台时仍被 OS 视为 IMPORTANCE_FOREGROUND_SERVICE，
        // 实验 MSDK 是否因此继续供给 video frame（验证 SDK 是看 process importance 还是 activity state）
        val svc = Intent(this, AgentForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc)
        } else {
            startService(svc)
        }
    }

    override fun onTerminate() {
        services.shutdown()
        super.onTerminate()
    }

    companion object {
        private const val TAG = "UavfireApp"
        const val LOCAL_DRONE_SN = "RC_PLUS_LOCAL"
    }
}
