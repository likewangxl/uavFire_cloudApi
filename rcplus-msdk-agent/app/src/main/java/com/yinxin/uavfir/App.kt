package com.yinxin.uavfir

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import dji.v5.common.utils.GeoidManager
import dji.v5.ux.core.communication.DefaultGlobalPreferences
import dji.v5.ux.core.communication.GlobalPreferencesManager
import dji.v5.ux.core.util.UxSharedPreferencesUtil
import java.util.concurrent.atomic.AtomicBoolean

class App : Application() {
    lateinit var services: AppServices
        private set
    private val runtimeLoopLifecyclePolicy = RuntimeLoopLifecyclePolicy()
    private val trialPolicy = TrialExpirationPolicy()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val expirationStarted = AtomicBoolean(false)
    private val mutableTrialExpired = MutableLiveData(false)
    val trialExpired: LiveData<Boolean> = mutableTrialExpired
    private val expirationCheck = object : Runnable {
        override fun run() {
            if (trialPolicy.isExpired()) {
                expireApplication()
                return
            }
            scheduleExpirationCheck()
        }
    }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        if (TrialExpirationPolicy().isExpired()) {
            Log.e(TAG, "trial expired; skipping MSDK compatibility initialization")
            return
        }
        MsdkCompatHelperInstaller.install(this)
            .onFailure { throwable ->
                Log.e(TAG, "MSDK helper install failed", throwable)
            }
    }

    override fun onCreate() {
        super.onCreate()
        mutableTrialExpired.value = trialPolicy.isExpired()
        if (isTrialExpired()) {
            Log.e(TAG, "trial expired at ${TrialExpirationPolicy.EXPIRES_AT_DISPLAY}; services disabled")
            return
        }
        AppContextHolder.initialize(this)
        initializeUxSdkDefaults()
        services = AppServices(this, ::expireApplication)
        Log.i(TAG, "application created, runtime loop ready for dynamic MSDK identity")
        scheduleExpirationCheck()
        if (runtimeLoopLifecyclePolicy.startOnApplicationCreate) {
            startRuntimeLoopIfPermitted()
        }
    }

    fun startRuntimeLoopIfPermitted(): Boolean {
        if (trialPolicy.isExpired()) {
            expireApplication()
            return false
        }
        if (!RuntimePermissions.areGranted(this)) {
            Log.w(TAG, "runtime permissions missing, deferring MSDK runtime loop")
            return false
        }
        Log.i(TAG, "runtime permissions granted, starting runtime loop")
        services.runtimeLoop.start(LOCAL_DRONE_SN)
        return true
    }

    fun isTrialExpired(): Boolean = mutableTrialExpired.value == true || trialPolicy.isExpired()

    private fun scheduleExpirationCheck() {
        mainHandler.removeCallbacks(expirationCheck)
        val delayMs = trialPolicy.remainingMs().coerceAtMost(MAX_EXPIRATION_CHECK_DELAY_MS)
        mainHandler.postDelayed(expirationCheck, delayMs.coerceAtLeast(1L))
    }

    private fun expireApplication() {
        if (!expirationStarted.compareAndSet(false, true)) return
        mutableTrialExpired.postValue(true)
        mainHandler.removeCallbacks(expirationCheck)
        Log.e(TAG, "trial expired at ${TrialExpirationPolicy.EXPIRES_AT_DISPLAY}; stopping Agent services")
        if (::services.isInitialized) {
            Thread(
                { services.shutdown() },
                "uavfire-trial-shutdown",
            ).start()
        }
    }

    private fun initializeUxSdkDefaults() {
        UxSharedPreferencesUtil.initialize(this)
        GlobalPreferencesManager.initialize(DefaultGlobalPreferences(this))
        GeoidManager.getInstance().init(this)
    }

    override fun onTerminate() {
        mainHandler.removeCallbacks(expirationCheck)
        if (::services.isInitialized) services.shutdown()
        super.onTerminate()
    }

    companion object {
        private const val TAG = "UavfireApp"
        private const val MAX_EXPIRATION_CHECK_DELAY_MS = 6 * 60 * 60 * 1_000L
        val LOCAL_DRONE_SN: String = BuildConfig.AGENT_AIRCRAFT_SN
            .takeIf { it.isNotBlank() }
            ?: ""
    }
}
