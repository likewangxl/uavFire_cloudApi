package com.yinxin.uavfir.sdk

import android.content.Context
import dji.v5.common.error.IDJIError
import dji.v5.common.register.DJISDKInitEvent
import dji.v5.manager.SDKManager
import dji.v5.manager.interfaces.SDKManagerCallback
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

interface MsdkSdkClient {
    suspend fun initialize(): Boolean

    suspend fun registerApp(): Boolean
}

class RealMsdkSdkClient(
    private val context: Context,
) : MsdkSdkClient {
    @Volatile
    private var initialized = false

    @Volatile
    private var registered = false

    private var initContinuation: kotlinx.coroutines.CancellableContinuation<Boolean>? = null
    private var registerContinuation: kotlinx.coroutines.CancellableContinuation<Boolean>? = null

    private val callback = object : SDKManagerCallback {
        override fun onInitProcess(event: DJISDKInitEvent, totalProcess: Int) {
            if (event == DJISDKInitEvent.INITIALIZE_COMPLETE) {
                initialized = true
                initContinuation?.takeIf { it.isActive }?.resume(true)
                initContinuation = null
            }
        }

        override fun onRegisterSuccess() {
            registered = true
            registerContinuation?.takeIf { it.isActive }?.resume(true)
            registerContinuation = null
        }

        override fun onRegisterFailure(error: IDJIError) {
            registerContinuation?.takeIf { it.isActive }?.resume(false)
            registerContinuation = null
        }

        override fun onProductDisconnect(productId: Int) = Unit

        override fun onProductConnect(productId: Int) = Unit

        override fun onProductChanged(productId: Int) = Unit

        override fun onDatabaseDownloadProgress(current: Long, total: Long) = Unit
    }

    override suspend fun initialize(): Boolean {
        if (initialized) {
            return true
        }

        return suspendCancellableCoroutine { continuation ->
            initContinuation = continuation
            SDKManager.getInstance().init(context, callback)
        }
    }

    override suspend fun registerApp(): Boolean {
        if (registered || SDKManager.getInstance().isRegistered) {
            registered = true
            return true
        }
        if (!initialized) {
            return false
        }

        return suspendCancellableCoroutine { continuation ->
            registerContinuation = continuation
            SDKManager.getInstance().registerApp()
        }
    }
}
