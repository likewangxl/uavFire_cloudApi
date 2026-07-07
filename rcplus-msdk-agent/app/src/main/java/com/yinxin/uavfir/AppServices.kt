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
import com.yinxin.uavfir.api.DjiFlightControlActionClient
import com.yinxin.uavfir.api.DualStreamMsdkCommandExecutor
import com.yinxin.uavfir.api.LegacyCommandDeduplicator
import com.yinxin.uavfir.api.ThermalHotspotMonitor
import com.yinxin.uavfir.sdk.DjiDeviceIdentity
import com.yinxin.uavfir.sdk.DjiDeviceSession
import com.yinxin.uavfir.sdk.DjiSdkGatewayImpl
import com.yinxin.uavfir.sdk.HmsReporter
import com.yinxin.uavfir.sdk.OsdReporter
import com.yinxin.uavfir.session.DualStreamSessionManager
import com.yinxin.uavfir.stream.RealMsdkStreamProvider
import com.yinxin.uavfir.stream.ThermalHotspotCandidateListener
import com.yinxin.uavfir.stream.ThermalMeasureRegion
import com.yinxin.uavfir.ui.ValidationConsoleController
import com.yinxin.uavfir.wayline.WaylineAgentApi
import com.yinxin.uavfir.wayline.WaylineAgentClient
import com.yinxin.uavfir.wayline.WaylineAgentCommandRouter
import com.yinxin.uavfir.wayline.WaylineEventForwarder
import com.yinxin.uavfir.wayline.WaylineKmzDownloader
import com.yinxin.uavfir.wayline.WaylineMqttPublisher
import com.yinxin.uavfir.wayline.WaypointMissionExecutor
import com.yinxin.uavfir.wayline.WaypointProbeController
import com.yinxin.uavfir.wayline.LocalKmzMissionController
import com.yinxin.uavfir.wayline.WaypointLocalKmzExecutor
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class AppServices(
    application: Application,
) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val kmzCacheDir = File(application.getExternalFilesDir(null), "wayline-kmz")
    private val localKmzDir = File(application.filesDir, "wayline-local")
    private val api = AgentBackendApiFactory.create()
    private val backendClient = AgentBackendClient(api)
    private val reporter = AgentReporter(backendClient)
    private val deviceSession = DjiDeviceSession(DjiSdkGatewayImpl())
    private val thermalHotspotTriggerBridge = ThermalHotspotTriggerBridge()
    private val sessionManager = DualStreamSessionManager(
        RealMsdkStreamProvider(hotspotCandidateListener = thermalHotspotTriggerBridge),
    )
    private val flightControlClient = DjiFlightControlActionClient()
    private val msdkCommandExecutor = DualStreamMsdkCommandExecutor(
        dualStreamExecutor = sessionManager,
        flightControlClient = flightControlClient,
    )
    private val legacyCommandDeduplicator = LegacyCommandDeduplicator()
    private val dualStreamPoller = CommandPollingCoordinator(
        client = backendClient,
        sessionManager = sessionManager,
        commandExecutor = msdkCommandExecutor,
        pollMsdk = false,
        commandExecutionDeduplicator = legacyCommandDeduplicator,
    )
    private val msdkControlPoller = CommandPollingCoordinator(
        client = backendClient,
        sessionManager = sessionManager,
        commandExecutor = msdkCommandExecutor,
        pollLegacyDualStreamUrgentOnly = true,
        commandExecutionDeduplicator = legacyCommandDeduplicator,
    )
    private val thermalHotspotMonitor = ThermalHotspotMonitor(
        client = backendClient,
        sessionManager = sessionManager,
        visibleConfirmationScope = appScope,
    )

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
    private val waypointExecutor = WaypointMissionExecutor(
        listener = eventForwarder,
        gimbalActionClient = flightControlClient,
        scope = appScope,
    )
    private val kmzHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    private val kmzDownloader = WaylineKmzDownloader(kmzHttpClient, kmzCacheDir)
    private val waylineRouter = WaylineAgentCommandRouter(waylineClient, waypointExecutor, kmzDownloader, eventForwarder)

    /**
     * Periodically publishes aircraft OSD telemetry on the Cloud SDK topic
     * `thing/product/{aircraftSn}/osd` so the backend treats the agent as a
     * drop-in Pilot 2 replacement. Disabled when AGENT_AIRCRAFT_SN is empty
     * (e.g. unit/dev builds without a paired aircraft).
     */
    private val osdReporter = OsdReporter(mqttPublisher, appScope)

    /**
     * Periodically publishes HMS heartbeat (empty list) on the Cloud SDK
     * events topic. Real alarm translation is a phase-2 TODO; see HmsReporter.
     */
    private val hmsReporter = HmsReporter(mqttPublisher, appScope)

    val waypointProbe = WaypointProbeController(
        executor = waypointExecutor,
        probeKmzBytes = application.resources.openRawResource(R.raw.m4t_probe).use { it.readBytes() },
        // External app dir (Pilot 2 / MSDK can reach here without legacy storage perms).
        cacheDir = File(application.getExternalFilesDir(null), "wayline-probe"),
    )
    val localKmzMission = LocalKmzMissionController(
        executor = WaypointLocalKmzExecutor(waypointExecutor),
        stagingDir = localKmzDir,
        defaultKmzFile = File(localKmzDir, "Kmz2.kmz"),
    )

    private val commandPoller = CompositeCommandPoller(listOf(thermalHotspotMonitor, dualStreamPoller, waylineRouter))
    private var activeReporterIdentity: DjiDeviceIdentity? = null
    private val autoStartedStreamAircraft = mutableSetOf<String>()

    val validationController = ValidationConsoleController(
        deviceSession = deviceSession,
        commandExecutor = sessionManager,
    )

    val runtimeLoop = AgentRuntimeLoop(
        deviceSession = deviceSession,
        reporter = reporter,
        commandPoller = commandPoller,
        urgentCommandPoller = msdkControlPoller,
        sessionManager = sessionManager,
        scope = appScope,
        onIdentityActivated = { identity ->
            activateDynamicIdentity(identity)
        },
        onError = { stage, throwable ->
            Log.e(TAG, "runtime loop $stage failed", throwable)
        },
    )

    init {
        thermalHotspotTriggerBridge.trigger = {
            activeThermalDroneSn()?.let { droneSn ->
                thermalHotspotMonitor.onFrameHotspotCandidate(droneSn)
            }
        }
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

    private fun activateDynamicIdentity(identity: DjiDeviceIdentity) {
        if (!identity.isValid()) {
            return
        }
        setActiveDroneSn(identity.aircraftSn)
        if (activeReporterIdentity != identity) {
            osdReporter.stop()
            hmsReporter.stop()
            Log.i(TAG, "starting OSD+HMS reporters aircraftSn=${identity.aircraftSn} gatewaySn=${identity.gatewaySn}")
            osdReporter.start(identity.aircraftSn, identity.gatewaySn)
            hmsReporter.start(identity.aircraftSn, identity.gatewaySn)
            // Listeners attached at app boot (before MSDK product link) never fire;
            // re-register now that the aircraft identity is active and connected.
            waypointExecutor.reattach()
            activeReporterIdentity = identity
        }
        if (autoStartedStreamAircraft.add(identity.aircraftSn)) {
            startDualStreamOnBoot(identity.aircraftSn)
        }
    }

    private fun activeThermalDroneSn(): String? {
        return activeReporterIdentity?.aircraftSn
            ?: BuildConfig.AGENT_AIRCRAFT_SN.takeIf { it.isNotBlank() }
    }

    /**
     * Start Cloud SDK protocol reporters (OSD now, HMS coming in Task #4)
     * so backend sees device telemetry from the agent the same way it sees
     * it from Pilot 2. No-op if BuildConfig SN fields are empty.
     */
    fun startReportersOnBoot() {
        val aircraftSn = BuildConfig.AGENT_AIRCRAFT_SN
        val gatewaySn = BuildConfig.AGENT_GATEWAY_SN
        if (aircraftSn.isBlank() || gatewaySn.isBlank()) {
            Log.i(TAG, "OSD reporter waits for dynamic MSDK aircraft/RC identity")
            return
        }
        Log.i(TAG, "starting OSD+HMS reporters aircraftSn=$aircraftSn gatewaySn=$gatewaySn")
        osdReporter.start(aircraftSn, gatewaySn)
        hmsReporter.start(aircraftSn, gatewaySn)
    }

    /**
     * Auto-start dual-stream session shortly after boot so the agent begins
     * pushing RTMP to ZLM without needing a manual UI tap. Failure is
     * non-fatal — backend command queue or operator can still start it later.
     *
     * The 6s delay gives RuntimeLoop one tick to finish DjiDeviceSession
     * initialize() and reach CONNECTED before we try to bind streams.
     */
    fun startDualStreamOnBoot(droneSn: String) {
        appScope.launch {
            delay(AUTO_START_DELAY_MS)
            val outcome = runCatching { sessionManager.executeCommand(droneSn, "start") }
            outcome.onSuccess { result ->
                Log.i(TAG, "auto-start dual-stream droneSn=$droneSn status=${result.status} message=${result.message ?: "(ok)"}")
            }.onFailure { throwable ->
                Log.w(TAG, "auto-start dual-stream failed droneSn=$droneSn: ${throwable.message}", throwable)
            }
        }
    }

    fun shutdown() {
        osdReporter.stop()
        hmsReporter.stop()
        waypointExecutor.detach()
        runtimeLoop.stop()
        mqttPublisher.disconnect()
        appScope.cancel()
    }

    companion object {
        private const val TAG = "AppServices"
        private const val AUTO_START_DELAY_MS: Long = 6_000
    }
}

private class ThermalHotspotTriggerBridge : ThermalHotspotCandidateListener {
    @Volatile
    var trigger: (() -> Unit)? = null

    override fun onThermalHotspotCandidate(regions: List<ThermalMeasureRegion>, timestampMs: Long) {
        trigger?.invoke()
    }
}
