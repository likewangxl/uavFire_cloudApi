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
import com.yinxin.uavfir.api.DjiAircraftLocationProvider
import com.yinxin.uavfir.api.DjiFlightControlActionClient
import com.yinxin.uavfir.api.DualStreamMsdkCommandExecutor
import com.yinxin.uavfir.api.FireConfirmationProcessor
import com.yinxin.uavfir.api.FireConfirmationRequest
import com.yinxin.uavfir.api.FireConfirmationResult
import com.yinxin.uavfir.api.LegacyCommandDeduplicator
import com.yinxin.uavfir.api.MissionHoldControl
import com.yinxin.uavfir.api.StartupAuthorityReconciliationGuard
import com.yinxin.uavfir.api.StartupVirtualStickReleaseOutcome
import com.yinxin.uavfir.api.VisibleFireLaserLocator
import com.yinxin.uavfir.api.BackendVisibleTargetAimer
import com.yinxin.uavfir.api.DjiLaserRangefinderClient
import com.yinxin.uavfir.api.DjiTapZoomClient
import com.yinxin.uavfir.api.ThermalHotspotMonitor
import com.yinxin.uavfir.firedetection.OnDeviceFireDetectionCoordinator
import com.yinxin.uavfir.firedetection.VisibleAiControlResult
import com.yinxin.uavfir.firedetection.VisibleDetectionReporter
import com.yinxin.uavfir.firedetection.outbox.AndroidFireEventOutboxStore
import com.yinxin.uavfir.firedetection.outbox.FireEventIngestApi
import com.yinxin.uavfir.firedetection.outbox.FireEventOutboxCoordinator
import com.yinxin.uavfir.firedetection.outbox.RetrofitFireEventSender
import com.yinxin.uavfir.sdk.DjiDeviceIdentity
import com.yinxin.uavfir.sdk.DjiDeviceSession
import com.yinxin.uavfir.sdk.DjiSdkGatewayImpl
import com.yinxin.uavfir.sdk.HmsReporter
import com.yinxin.uavfir.sdk.OsdReporter
import com.yinxin.uavfir.session.DualStreamSessionManager
import com.yinxin.uavfir.session.DualStreamSessionState
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

class AppServices(
    application: Application,
    onTrialExpired: () -> Unit = {},
) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val shutdownStarted = AtomicBoolean(false)
    private val kmzCacheDir = File(application.getExternalFilesDir(null), "wayline-kmz")
    private val localKmzDir = File(application.filesDir, "wayline-local")
    private val api = AgentBackendApiFactory.create()
    private val waylineApi = AgentBackendApiFactory.create(WaylineAgentApi::class.java)
    private val waylineClient = WaylineAgentClient(
        api = waylineApi,
        sharedSecret = BuildConfig.AGENT_WAYLINE_SHARED_SECRET,
    )
    private val fireEventOutboxStore = AndroidFireEventOutboxStore(application)
    private val fireEventIngestApi = AgentBackendApiFactory.create(FireEventIngestApi::class.java)
    private val fireEventOutbox = FireEventOutboxCoordinator(
        scope = appScope,
        store = fireEventOutboxStore,
        sender = RetrofitFireEventSender(fireEventIngestApi, waylineClient),
    )
    private val backendClient = AgentBackendClient(
        api = api,
        outboxHealthProvider = fireEventOutbox::health,
    )
    private val reporter = AgentReporter(backendClient)
    private val deviceSession = DjiDeviceSession(DjiSdkGatewayImpl())
    private val thermalHotspotTriggerBridge = ThermalHotspotTriggerBridge()
    private val fireConfirmationRunnerBridge = FireConfirmationRunnerBridge()
    private val onDeviceFireDetectionCoordinator = OnDeviceFireDetectionCoordinator(
        context = application,
        scope = appScope,
        enabled = BuildConfig.AGENT_FIRE_ONNX_ENABLED,
        reporter = VisibleDetectionReporter(fireEventOutbox::enqueue),
    )
    private val sessionManager = DualStreamSessionManager(
        RealMsdkStreamProvider(
            hotspotCandidateListener = thermalHotspotTriggerBridge,
            visibleFrameConsumer = onDeviceFireDetectionCoordinator,
        ),
        fireConfirmationRunner = fireConfirmationRunnerBridge::run,
        visibleAiControl = onDeviceFireDetectionCoordinator,
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
    // Wayline-agent control plane (HTTP) + event plane (MQTT).
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
    private val missionHoldControl = WaypointMissionHoldControl(waypointExecutor)
    private val visibleFireLaserRangefinder = DjiLaserRangefinderClient()
    private val visibleFireLaserLocator = VisibleFireLaserLocator(
        missionHold = missionHoldControl,
        flightControl = flightControlClient,
        targetAimer = BackendVisibleTargetAimer(
            visibleRoiProvider = { taskId, afterSourceTs ->
                backendClient.latestVisibleRoi(taskId, afterSourceTs)
            },
            tapZoomClient = DjiTapZoomClient(),
            laserRangefinder = visibleFireLaserRangefinder,
        ),
        laserRangefinder = visibleFireLaserRangefinder,
    ).also(sessionManager::attachVisibleFireLaserLocator)
    private val fireConfirmationProcessor = FireConfirmationProcessor(
        sessionManager = sessionManager,
        flightControl = flightControlClient,
        gimbalControl = flightControlClient,
        cameraControl = flightControlClient,
        missionHold = missionHoldControl,
        client = backendClient,
        aircraftLocationProvider = { DjiAircraftLocationProvider.current() },
    )
    // 探针只喂 HUD 温度；火情触发/确认已串行化到后端（YOLO→实测温度→证据照）。
    private val thermalHotspotMonitor = ThermalHotspotMonitor(
        sessionManager = sessionManager,
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

    private val commandPoller = CompositeCommandPoller(
        listOf(thermalHotspotMonitor, dualStreamPoller),
        onFailure = { poller, error ->
            Log.e(TAG, "command poll failed poller=${poller.javaClass.simpleName}", error)
        },
    )
    // 航线下发不能依赖状态上报/图传/测温所在的普通轮询链。普通链中任一网络或
    // MSDK 调用变慢，都会让 WAYLINE_DISPATCH 长时间取不到。把航线与紧急飞控命令
    // 放进独立的 500ms job，保证下发、暂停、恢复、停止都能及时到达执行器。
    private val urgentCommandPoller = CompositeCommandPoller(
        listOf(msdkControlPoller, waylineRouter),
        onFailure = { poller, error ->
            Log.e(TAG, "urgent command poll failed poller=${poller.javaClass.simpleName}", error)
        },
    )
    @Volatile
    private var activeReporterIdentity: DjiDeviceIdentity? = null
    private val autoStartedStreamAircraft = mutableSetOf<String>()
    private val authorityReconciliationInFlight = ConcurrentHashMap.newKeySet<String>()
    private val authorityReconciledAircraft = ConcurrentHashMap.newKeySet<String>()

    val validationController = ValidationConsoleController(
        deviceSession = deviceSession,
        commandExecutor = sessionManager,
    )

    val runtimeLoop = AgentRuntimeLoop(
        deviceSession = deviceSession,
        reporter = reporter,
        commandPoller = commandPoller,
        urgentCommandPoller = urgentCommandPoller,
        sessionManager = sessionManager,
        scope = appScope,
        onIdentityActivated = { identity ->
            activateDynamicIdentity(identity)
        },
        onError = { stage, throwable ->
            Log.e(TAG, "runtime loop $stage failed", throwable)
        },
        trialExpired = TrialExpirationPolicy()::isExpired,
        onTrialExpired = onTrialExpired,
    )

    init {
        fireConfirmationRunnerBridge.runner = fireConfirmationProcessor::run
        thermalHotspotTriggerBridge.trigger = {
            activeThermalDroneSn()?.let { droneSn ->
                thermalHotspotMonitor.onFrameHotspotCandidate(droneSn)
            }
        }
        fireEventOutbox.start()
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
        reconcileStartupFlightControlAuthority(identity)
        if (identity.isPlaceholderAircraft()) {
            // 占位身份（飞机未上线）不起流：流名会挂 UNKNOWN-AIRCRAFT 前缀，
            // 前端按真机 SN 取流永远取不到——2026-07-25 飞机关机重启实测踩坑。
            return
        }
        if (autoStartedStreamAircraft.add(identity.aircraftSn)) {
            startDualStreamOnBoot(identity.aircraftSn)
        } else if (sessionManager.sessionState == DualStreamSessionState.RUNNING
            && sessionManager.activeStreamDroneSn != null
            && sessionManager.activeStreamDroneSn != identity.aircraftSn
        ) {
            // 会话在别的身份（如占位身份）下推着流：真机身份回归时按真名重推，
            // 否则驾驶舱按真机 SN 取流会一直加载失败。
            Log.i(
                TAG,
                "restarting dual-stream for identity change ${sessionManager.activeStreamDroneSn} -> ${identity.aircraftSn}",
            )
            startDualStreamOnBoot(identity.aircraftSn)
        }
    }

    private fun reconcileStartupFlightControlAuthority(identity: DjiDeviceIdentity) {
        val aircraftSn = identity.aircraftSn
        if (!StartupAuthorityReconciliationGuard.hasResolvedGateway(identity.gatewaySn)) {
            Log.i(
                TAG,
                "startup flight-control authority reconciliation deferred until RC gateway resolves " +
                    "aircraft=$aircraftSn gateway=${identity.gatewaySn}",
            )
            return
        }
        if (authorityReconciledAircraft.contains(aircraftSn) ||
            !authorityReconciliationInFlight.add(aircraftSn)
        ) {
            return
        }
        appScope.launch {
            try {
                // The first identity callback may precede RC authority synchronization.
                // Wait briefly after the real gateway appears before accepting GPS_NORMAL
                // or releasing an aircraft-side VIRTUAL_STICK owner left by an older run.
                delay(AUTHORITY_RECONCILIATION_SETTLE_MS)
                if (waypointExecutor.activeMissionId() != null) {
                    Log.w(
                        TAG,
                        "startup flight-control authority reconciliation deferred while mission active " +
                            "aircraft=$aircraftSn mission=${waypointExecutor.activeMissionId()}",
                    )
                    return@launch
                }
                when (val outcome = flightControlClient.releaseStaleVirtualStickAuthorityIfGrounded()) {
                    StartupVirtualStickReleaseOutcome.RELEASED,
                    StartupVirtualStickReleaseOutcome.NOT_NEEDED -> {
                        authorityReconciledAircraft.add(aircraftSn)
                        Log.i(TAG, "startup flight-control authority reconciled aircraft=$aircraftSn outcome=$outcome")
                    }
                    StartupVirtualStickReleaseOutcome.DEFERRED,
                    StartupVirtualStickReleaseOutcome.FAILED -> Log.w(
                        TAG,
                        "startup flight-control authority reconciliation will retry aircraft=$aircraftSn outcome=$outcome",
                    )
                }
            } finally {
                authorityReconciliationInFlight.remove(aircraftSn)
            }
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

    /**
     * Manual, operator-controlled switch for Agent-side visible-light fire detection.
     * This only controls ONNX observation. It never enables the M300 flight/fire closed loop.
     */
    suspend fun setManualFireDetectionEnabled(enabled: Boolean): VisibleAiControlResult {
        val droneSn = activeThermalDroneSn().orEmpty()
        if (!enabled) {
            return sessionManager.executeCommand(droneSn, "visible-ai-off").toVisibleAiControlResult()
        }
        if (droneSn.isBlank()) {
            return VisibleAiControlResult(false, "aircraft-not-connected")
        }
        if (sessionManager.sessionState != DualStreamSessionState.RUNNING) {
            val streamResult = sessionManager.executeCommand(droneSn, "start")
            if (streamResult.status != "applied") {
                return VisibleAiControlResult(
                    applied = false,
                    message = streamResult.message ?: "visible-stream-not-ready",
                )
            }
        }
        return sessionManager.executeCommand(droneSn, "visible-ai-on").toVisibleAiControlResult()
    }

    fun shutdown() {
        if (!shutdownStarted.compareAndSet(false, true)) return
        runtimeLoop.stop()
        osdReporter.stop()
        hmsReporter.stop()
        waypointExecutor.detach()
        runBlocking {
            withTimeoutOrNull(STREAM_SHUTDOWN_TIMEOUT_MS) {
                sessionManager.stop()
            }
        }
        onDeviceFireDetectionCoordinator.close()
        fireEventOutbox.close()
        mqttPublisher.disconnect()
        appScope.cancel()
    }

    companion object {
        private const val TAG = "AppServices"
        private const val AUTO_START_DELAY_MS: Long = 6_000
        private const val AUTHORITY_RECONCILIATION_SETTLE_MS: Long = 250
        private const val STREAM_SHUTDOWN_TIMEOUT_MS: Long = 10_000
    }
}

private fun DualStreamSessionManager.CommandExecutionResult.toVisibleAiControlResult() =
    VisibleAiControlResult(
        applied = status == "applied",
        message = message ?: if (status == "applied") "applied" else "visible-ai-command-failed",
    )

private class ThermalHotspotTriggerBridge : ThermalHotspotCandidateListener {
    @Volatile
    var trigger: (() -> Unit)? = null

    override fun onThermalHotspotCandidate(regions: List<ThermalMeasureRegion>, timestampMs: Long) {
        trigger?.invoke()
    }
}

private class FireConfirmationRunnerBridge {
    @Volatile
    var runner: (suspend (FireConfirmationRequest) -> FireConfirmationResult)? = null

    suspend fun run(request: FireConfirmationRequest): FireConfirmationResult {
        val delegate = runner ?: error("fire-confirmation-runner-not-wired")
        return delegate(request)
    }
}

private class WaypointMissionHoldControl(
    private val executor: WaypointMissionExecutor,
) : MissionHoldControl {
    @Volatile
    private var held = false

    override suspend fun holdForConfirmation(): Boolean {
        val missionId = executor.activeMissionId()
        if (missionId.isNullOrBlank()) {
            Log.i(TAG, "dwell hold skipped: no active waypoint mission")
            held = false
            return false
        }
        return runCatching {
            executor.pauseMission()
            held = true
            true
        }.onFailure {
            held = false
            Log.w(TAG, "dwell hold failed missionId=$missionId message=${it.message}", it)
        }.getOrDefault(false)
    }

    override suspend fun resumeAfterConfirmation() {
        if (!held) {
            return
        }
        val missionId = executor.activeMissionId()
        held = false
        runCatching {
            executor.resumeMission()
        }.onFailure {
            Log.w(TAG, "dwell resume failed missionId=$missionId message=${it.message}", it)
        }
    }

    companion object {
        private const val TAG = "WaypointMissionHoldControl"
    }
}
