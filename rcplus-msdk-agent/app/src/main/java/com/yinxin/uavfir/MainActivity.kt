package com.yinxin.uavfir

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.yinxin.uavfir.ui.ValidationConsoleController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var statusText: TextView
    private lateinit var refreshButton: Button
    private lateinit var startButton: Button
    private val controller: ValidationConsoleController
        get() = (application as App).services.validationController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)
        refreshButton = findViewById(R.id.refreshButton)
        startButton = findViewById(R.id.startButton)

        refreshButton.setOnClickListener {
            runAction("refresh-device-status") {
                controller.refreshDeviceStatus(App.LOCAL_DRONE_SN)
            }
        }
        startButton.setOnClickListener {
            runAction("start-dual-stream") {
                controller.startDualStream(App.LOCAL_DRONE_SN)
            }
        }

        runAction("refresh-device-status") {
            controller.refreshDeviceStatus(App.LOCAL_DRONE_SN)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        uiScope.cancel()
    }

    private fun runAction(
        action: String,
        work: suspend () -> ValidationConsoleController.UiResult,
    ) {
        refreshButton.isEnabled = false
        startButton.isEnabled = false
        statusText.text = getString(R.string.app_status_running, action)
        uiScope.launch {
            runCatching { work() }
                .onSuccess {
                    statusText.text = it.statusText
                    Log.i(TAG, "$action => ${it.statusText}")
                }
                .onFailure {
                    val message = it.message ?: it::class.simpleName ?: "unknown-error"
                    val statusMessage = getString(R.string.app_status_error, action, message)
                    statusText.text = statusMessage
                    Log.e(TAG, "$action failed: $message", it)
                }
            refreshButton.isEnabled = true
            startButton.isEnabled = true
        }
    }

    companion object {
        private const val TAG = "ValidationConsole"
    }
}
