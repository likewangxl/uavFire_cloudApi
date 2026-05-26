package com.yinxin.uavfir

import android.content.Intent
import android.os.Bundle
import android.os.StatFs
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.yinxin.uavfir.sdk.DjiStorageStatus
import com.yinxin.uavfir.ui.ValidationConsoleController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var statusText: TextView
    private lateinit var flightLimitText: TextView
    private lateinit var taskSpaceText: TextView
    private lateinit var aircraftStatusText: TextView
    private lateinit var fireTaskButton: View
    private lateinit var refreshButton: View
    private lateinit var probeWaypointButton: View
    private lateinit var deviceStatusButton: View
    private lateinit var executeLocalKmzButton: View
    private lateinit var openSampleToolsButton: View
    private val controller: ValidationConsoleController
        get() = (application as App).services.validationController
    private val waypointProbe: com.yinxin.uavfir.wayline.WaypointProbeController
        get() = (application as App).services.waypointProbe
    private val localKmzMission: com.yinxin.uavfir.wayline.LocalKmzMissionController
        get() = (application as App).services.localKmzMission

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
        flightLimitText = findViewById(R.id.flightLimitText)
        taskSpaceText = findViewById(R.id.taskSpaceText)
        aircraftStatusText = findViewById(R.id.aircraftStatusText)
        fireTaskButton = findViewById(R.id.leftRoot)
        refreshButton = findViewById(R.id.refreshButton)
        probeWaypointButton = findViewById(R.id.probeWaypointButton)
        deviceStatusButton = findViewById(R.id.deviceStatusButton)
        executeLocalKmzButton = findViewById(R.id.executeLocalKmzButton)
        openSampleToolsButton = findViewById(R.id.openSampleToolsButton)

        probeWaypointButton.setOnClickListener {
            runAction("probe-waypoint-push") {
                val r = waypointProbe.probePush()
                ValidationConsoleController.UiResult(statusText = r.message)
            }
        }
        executeLocalKmzButton.setOnClickListener {
            runAction("execute-local-kmz") {
                val r = localKmzMission.executeLocalKmz()
                ValidationConsoleController.UiResult(statusText = r.message)
            }
        }
        deviceStatusButton.setOnClickListener {
            runAction("refresh-device-status") {
                controller.refreshDeviceStatus(App.LOCAL_DRONE_SN)
            }
        }
        openSampleToolsButton.setOnClickListener {
            startActivity(Intent(this, dji.v5.ux.sample.showcase.defaultlayout.DefaultLayoutActivity::class.java))
        }

        startHomeStatusRefresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        uiScope.cancel()
    }

    private fun runAction(
        action: String,
        work: suspend () -> ValidationConsoleController.UiResult,
    ) {
        fireTaskButton.isEnabled = false
        refreshButton.isEnabled = false
        probeWaypointButton.isEnabled = false
        deviceStatusButton.isEnabled = false
        executeLocalKmzButton.isEnabled = false
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
            fireTaskButton.isEnabled = true
            refreshButton.isEnabled = true
            probeWaypointButton.isEnabled = true
            deviceStatusButton.isEnabled = true
            executeLocalKmzButton.isEnabled = true
            openSampleToolsButton.isEnabled = true
        }
    }

    private fun startHomeStatusRefresh() {
        uiScope.launch {
            while (true) {
                refreshHomeStatus()
                delay(HOME_STATUS_REFRESH_MS)
            }
        }
    }

    private suspend fun refreshHomeStatus() {
        val deviceState = runCatching { controller.readDeviceState() }
            .getOrElse {
                Log.w(TAG, "home status device read failed: ${it.message}", it)
                null
            }
        val homeStatus = ValidationConsoleController.buildHomeStatus(
            deviceState = deviceState ?: com.yinxin.uavfir.sdk.DjiDeviceState(
                connectionState = com.yinxin.uavfir.session.AgentConnectionState.ERROR,
            ),
            storageStatus = readLocalStorageStatus(),
        )
        flightLimitText.text = homeStatus.flightLimitText
        taskSpaceText.text = homeStatus.taskSpaceText
        aircraftStatusText.text = homeStatus.aircraftStatusText
    }

    private fun readLocalStorageStatus(): DjiStorageStatus {
        return runCatching {
            val root = getExternalFilesDir(null) ?: filesDir
            val stat = StatFs(root.absolutePath)
            DjiStorageStatus(
                freeBytes = stat.availableBytes,
                totalBytes = stat.totalBytes,
            )
        }.getOrElse {
            Log.w(TAG, "home status storage read failed: ${it.message}", it)
            DjiStorageStatus(freeBytes = null, totalBytes = null)
        }
    }

    companion object {
        private const val TAG = "ValidationConsole"
        private const val HOME_STATUS_REFRESH_MS = 2_000L
    }
}
