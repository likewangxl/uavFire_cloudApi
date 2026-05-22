package com.yinxin.uavfir

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
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
    private lateinit var refreshButton: View
    private lateinit var startButton: View
    private lateinit var probeWaypointButton: View
    private lateinit var openSampleToolsButton: View
    private val controller: ValidationConsoleController
        get() = (application as App).services.validationController
    private val waypointProbe: com.yinxin.uavfir.wayline.WaypointProbeController
        get() = (application as App).services.waypointProbe

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        supportActionBar?.hide()
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)
        refreshButton = findViewById(R.id.refreshButton)
        startButton = findViewById(R.id.startButton)
        probeWaypointButton = findViewById(R.id.probeWaypointButton)
        openSampleToolsButton = findViewById(R.id.openSampleToolsButton)

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
        probeWaypointButton.setOnClickListener {
            runAction("probe-waypoint-push") {
                val r = waypointProbe.probePush()
                ValidationConsoleController.UiResult(statusText = r.message)
            }
        }
        openSampleToolsButton.setOnClickListener {
            startActivity(Intent(this, dji.v5.ux.sample.showcase.defaultlayout.DefaultLayoutActivity::class.java))
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
        probeWaypointButton.isEnabled = false
        openSampleToolsButton.isEnabled = false
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
            probeWaypointButton.isEnabled = true
            openSampleToolsButton.isEnabled = true
        }
    }

    companion object {
        private const val TAG = "ValidationConsole"
    }
}
