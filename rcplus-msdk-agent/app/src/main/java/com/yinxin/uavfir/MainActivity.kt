package com.yinxin.uavfir

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.StatFs
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.yinxin.uavfir.firedetection.FireDetectionObservation
import com.yinxin.uavfir.firedetection.FireDetectionObservationBus
import com.yinxin.uavfir.firedetection.FireDetectionObservationListener
import com.yinxin.uavfir.sdk.DjiStorageStatus
import com.yinxin.uavfir.ui.ValidationConsoleController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), FireDetectionObservationListener {
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var statusText: TextView
    private lateinit var flightLimitText: TextView
    private lateinit var taskSpaceText: TextView
    private lateinit var aircraftStatusText: TextView
    private lateinit var aiDetectionSwitch: SwitchCompat
    private lateinit var aiDetectionStatusText: TextView
    private lateinit var fireTaskButton: View
    private lateinit var refreshButton: View
    private lateinit var probeWaypointButton: View
    private lateinit var deviceStatusButton: View
    private lateinit var executeLocalKmzButton: View
    private lateinit var openSampleToolsButton: View
    private var aiDetectionToggleBusy = false
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
        aiDetectionSwitch = findViewById(R.id.aiDetectionSwitch)
        aiDetectionStatusText = findViewById(R.id.aiDetectionStatusText)
        fireTaskButton = findViewById(R.id.leftRoot)
        refreshButton = findViewById(R.id.refreshButton)
        probeWaypointButton = findViewById(R.id.probeWaypointButton)
        deviceStatusButton = findViewById(R.id.deviceStatusButton)
        executeLocalKmzButton = findViewById(R.id.executeLocalKmzButton)
        openSampleToolsButton = findViewById(R.id.openSampleToolsButton)

        val app = application as App
        app.trialExpired.observe(this) { expired ->
            if (expired) showTrialExpiredState()
        }
        if (app.isTrialExpired()) {
            showTrialExpiredState()
            return
        }

        ensureRuntimePermissions()

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
            startActivity(Intent(this, AgentFlightActivity::class.java))
        }
        aiDetectionSwitch.setOnClickListener {
            setManualFireDetectionEnabled(aiDetectionSwitch.isChecked)
        }

        startHomeStatusRefresh()
    }

    override fun onStart() {
        super.onStart()
        if ((application as App).isTrialExpired()) return
        FireDetectionObservationBus.addListener(this)
    }

    override fun onStop() {
        FireDetectionObservationBus.removeListener(this)
        super.onStop()
    }

    override fun onObservation(observation: FireDetectionObservation) {
        if ((application as App).isTrialExpired()) return
        runOnUiThread {
            aiDetectionSwitch.isChecked = observation.active
            aiDetectionSwitch.isEnabled = observation.enabled && !aiDetectionToggleBusy
            aiDetectionStatusText.text = describeFireDetectionObservation(observation)
        }
    }

    private fun ensureRuntimePermissions() {
        val app = application as App
        if (RuntimePermissions.areGranted(this)) {
            app.startRuntimeLoopIfPermitted()
            return
        }
        requestPermissions(RuntimePermissions.required, RUNTIME_PERMISSION_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != RUNTIME_PERMISSION_REQUEST_CODE) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            (application as App).startRuntimeLoopIfPermitted()
        } else {
            Log.w(TAG, "required MSDK runtime permissions were denied")
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
        if ((application as App).isTrialExpired()) {
            showTrialExpiredState()
            return
        }
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
            if (!(application as App).isTrialExpired()) {
                fireTaskButton.isEnabled = true
                refreshButton.isEnabled = true
                probeWaypointButton.isEnabled = true
                deviceStatusButton.isEnabled = true
                executeLocalKmzButton.isEnabled = true
                openSampleToolsButton.isEnabled = true
            }
        }
    }

    private fun setManualFireDetectionEnabled(enabled: Boolean) {
        if ((application as App).isTrialExpired()) {
            showTrialExpiredState()
            return
        }
        if (aiDetectionToggleBusy) return
        aiDetectionToggleBusy = true
        aiDetectionSwitch.isEnabled = false
        aiDetectionStatusText.text = getString(
            if (enabled) R.string.ai_detection_status_starting else R.string.ai_detection_status_stopping,
        )
        uiScope.launch {
            val result = runCatching {
                (application as App).services.setManualFireDetectionEnabled(enabled)
            }.getOrElse { throwable ->
                Log.e(TAG, "manual fire detection toggle failed", throwable)
                com.yinxin.uavfir.firedetection.VisibleAiControlResult(
                    applied = false,
                    message = throwable.message ?: "visible-ai-command-failed",
                )
            }
            aiDetectionToggleBusy = false
            aiDetectionSwitch.isEnabled = BuildConfig.AGENT_FIRE_ONNX_ENABLED
            if (!result.applied) {
                aiDetectionSwitch.isChecked = false
                aiDetectionStatusText.text = describeFireDetectionFailure(result.message)
            }
            Log.i(TAG, "manual-fire-detection enabled=$enabled applied=${result.applied} message=${result.message}")
        }
    }

    private fun describeFireDetectionObservation(observation: FireDetectionObservation): String {
        observation.failureMessage?.let { return describeFireDetectionFailure(it) }
        if (!observation.enabled) return getString(R.string.ai_detection_status_unavailable)
        if (!observation.active) return getString(
            R.string.ai_detection_status_off,
            observation.modelInputSize,
            observation.modelInputSize,
        )
        if (observation.sourceTs <= 0L) return getString(R.string.ai_detection_status_waiting_frame)
        val inferenceMs = observation.inferenceMs ?: 0L
        return if (observation.detections.isEmpty()) {
            getString(R.string.ai_detection_status_running, inferenceMs)
        } else {
            getString(R.string.ai_detection_status_detected, observation.detections.size, inferenceMs)
        }
    }

    private fun describeFireDetectionFailure(message: String): String = when {
        message == "aircraft-not-connected" || message == "drone-sn-required" ->
            getString(R.string.ai_detection_error_aircraft)
        message == "agent-fire-onnx-disabled" ->
            getString(R.string.ai_detection_status_unavailable)
        message == "agent-fire-onnx-prepare-failed" ->
            getString(R.string.ai_detection_error_model)
        message.contains("stream", ignoreCase = true) ->
            getString(R.string.ai_detection_error_stream)
        else -> getString(R.string.ai_detection_error_generic, message)
    }

    private fun startHomeStatusRefresh() {
        uiScope.launch {
            while (!(application as App).isTrialExpired()) {
                refreshHomeStatus()
                delay(HOME_STATUS_REFRESH_MS)
            }
        }
    }

    private fun showTrialExpiredState() {
        statusText.text = getString(R.string.trial_expired_message)
        flightLimitText.text = getString(R.string.trial_expired_short)
        taskSpaceText.text = getString(R.string.trial_expired_short)
        aircraftStatusText.text = getString(R.string.trial_expired_short)
        aiDetectionSwitch.isChecked = false
        aiDetectionSwitch.isEnabled = false
        aiDetectionStatusText.text = getString(R.string.trial_expired_short)
        fireTaskButton.isEnabled = false
        refreshButton.isEnabled = false
        probeWaypointButton.isEnabled = false
        deviceStatusButton.isEnabled = false
        executeLocalKmzButton.isEnabled = false
        openSampleToolsButton.isEnabled = false
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
        private const val RUNTIME_PERMISSION_REQUEST_CODE = 300
    }
}
