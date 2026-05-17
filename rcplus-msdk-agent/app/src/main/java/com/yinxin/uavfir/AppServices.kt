package com.yinxin.uavfir

import android.app.Application
import android.util.Log
import com.yinxin.uavfir.api.AgentBackendConfig
import com.yinxin.uavfir.api.AgentBackendApiFactory
import com.yinxin.uavfir.api.AgentBackendClient
import com.yinxin.uavfir.api.AgentReporter
import com.yinxin.uavfir.api.AgentRuntimeLoop
import com.yinxin.uavfir.api.CommandPollingCoordinator
import com.yinxin.uavfir.api.CompositeCommandPoller
import com.yinxin.uavfir.sdk.DjiDeviceSession
import com.yinxin.uavfir.sdk.DjiSdkGatewayImpl
import com.yinxin.uavfir.session.DualStreamSessionManager
import com.yinxin.uavfir.stream.RealMsdkStreamProvider
import com.yinxin.uavfir.ui.ValidationConsoleController
import com.yinxin.uavfir.wayline.WaylineAgentApi
import com.yinxin.uavfir.wayline.WaylineAgentClient
import com.yinxin.uavfir.wayline.WaylineAgentCommandRouter
import com.yinxin.uavfir.wayline.WaylineEventForwarder
import com.yinxin.uavfir.wayline.WaylineKmzDownloader
import com.yinxin.uavfir.wayline.WaylineMqttPublisher
import com.yinxin.uavfir.wayline.WaypointMissionExecutor
import com.yinxin.uavfir.wayline.WaypointProbeController
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class AppServices(
    application: Application,
) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val kmzCacheDir = File(application.cacheDir, "wayline-kmz")
    private val api = AgentBackendApiFactory.create()
    private val backendClient = AgentBackendClient(api)
    private val reporter = AgentReporter(backendClient)
    private val deviceSession = DjiDeviceSession(DjiSdkGatewayImpl())
    private val sessionManager = DualStreamSessionManager(RealMsdkStreamProvider())
    private val dualStreamPoller = CommandPollingCoordinator(backendClient, sessionManager)

    // Wayline-agent control plane (HTTP) + event plane (MQTT).
    private val waylineApi = AgentBackendApiFactory.create(WaylineAgentApi::class.java)
    private val waylineClient = WaylineAgentClient(
        api = waylineApi,
        sharedSecret = BuildConfig.AGENT_WAYLINE_SHARED_SECRET,
    )
    private val mqttPublisher = WaylineMqttPublisher(
        brokerUrl = BuildConfig.AGENT_MQTT_BROKER_URL,
        clientIdPrefix = "wayline-agent",
        username = BuildConfig.AGENT_MQTT_BROKER_USERNAME.takeIf { it.isNotEmpty() },
        password = BuildConfig.AGENT_MQTT_BROKER_PASSWORD.takeIf { it.isNotEmpty() },
    )
    private val eventForwarder = WaylineEventForwarder(mqttPublisher, appScope)
    private val waypointExecutor = WaypointMissionExecutor(eventForwarder)
    private val kmzHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    private val kmzDownloader = WaylineKmzDownloader(kmzHttpClient, kmzCacheDir)
    private val waylineRouter = WaylineAgentCommandRouter(waylineClient, waypointExecutor, kmzDownloader, eventForwarder)

    val waypointProbe = WaypointProbeController(
        executor = waypointExecutor,
        probeKmzBytes = application.resources.openRawResource(R.raw.m4t_probe).use { it.readBytes() },
        // External app dir (Pilot 2 / MSDK can reach here without legacy storage perms).
        cacheDir = File(application.getExternalFilesDir(null), "wayline-probe"),
    )

    private val commandPoller = CompositeCommandPoller(listOf(dualStreamPoller, waylineRouter))

    val validationController = ValidationConsoleController(
        deviceSession = deviceSession,
        commandExecutor = sessionManager,
    )

    val runtimeLoop = AgentRuntimeLoop(
        deviceSession = deviceSession,
        reporter = reporter,
        commandPoller = commandPoller,
        sessionManager = sessionManager,
        scope = appScope,
        onError = { stage, throwable ->
            Log.e(TAG, "runtime loop $stage failed", throwable)
        },
    )

    init {
        Log.i(TAG, "initialized backend=${AgentBackendConfig.DEFAULT_BASE_URL}")
        waypointExecutor.attach()
    }

    /**
     * Tell wayline MQTT plane which droneSn this agent represents. Must be
     * called before the publisher emits its first event.
     */
    fun setActiveDroneSn(droneSn: String) {
        mqttPublisher.setDefaultDroneSn(droneSn)
    }

    fun shutdown() {
        waypointExecutor.detach()
        runtimeLoop.stop()
        mqttPublisher.disconnect()
        appScope.cancel()
    }

    companion object {
        private const val TAG = "AppServices"
    }
}
