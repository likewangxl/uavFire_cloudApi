package com.yinxin.uavfir

import android.app.Application
import android.os.SystemClock
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
import com.yinxin.uavfir.api.VisibleFireLaserLocator
import com.yinxin.uavfir.api.DjiLocalTargetAlignmentAction
import com.yinxin.uavfir.api.DjiAircraftOsdTracker
import com.yinxin.uavfir.api.DjiLaserRangefinderClient
import com.yinxin.uavfir.api.DjiTapZoomClient
import com.yinxin.uavfir.api.ThermalHotspotMonitor
import com.yinxin.uavfir.firedetection.LatestVisibleFrameBuffer
import com.yinxin.uavfir.firedetection.AwaitableMissionControl
import com.yinxin.uavfir.firedetection.AgentFireClosedLoopCoordinator
import com.yinxin.uavfir.firedetection.AgentFireConfirmationBridge
import com.yinxin.uavfir.firedetection.BoundedCoordinatorOutcomeRecorder
import com.yinxin.uavfir.firedetection.BoundedVisibleEvidenceCapture
import com.yinxin.uavfir.firedetection.AgentFireMonitoringContext
import com.yinxin.uavfir.firedetection.AgentFireRecoveryCoordinator
import com.yinxin.uavfir.firedetection.ConfirmationHealth
import com.yinxin.uavfir.firedetection.CoordinatorArmingHealth
import com.yinxin.uavfir.firedetection.CoordinatorFlightObservation
import com.yinxin.uavfir.firedetection.CoordinatorFlightObservationSource
import com.yinxin.uavfir.firedetection.FlightSafetySignals
import com.yinxin.uavfir.firedetection.FlightTelemetrySample
import com.yinxin.uavfir.firedetection.FlightSafetyGate
import com.yinxin.uavfir.firedetection.OwnedResumeSafetyEvidenceProvider
import com.yinxin.uavfir.firedetection.VisibleFireDetectorArmingResult
import com.yinxin.uavfir.firedetection.VisibleFireDetectorFactory
import com.yinxin.uavfir.firedetection.VisibleFrameIngress
import com.yinxin.uavfir.firedetection.VisibleInferenceLoop
import com.yinxin.uavfir.firedetection.VisibleInferenceResultJournal
import com.yinxin.uavfir.firedetection.LocalVisibleTargetAimer
import com.yinxin.uavfir.firedetection.StoreBackedCoordinatorOutboxPort
import com.yinxin.uavfir.firedetection.Task7CoordinatorMissionPort
import com.yinxin.uavfir.firedetection.Task8CoordinatorLocalizationPort
import com.yinxin.uavfir.firedetection.VisibleConfirmationPolicy
import com.yinxin.uavfir.firedetection.VisibleConfirmationTracker
import com.yinxin.uavfir.firedetection.VisibleInferenceStatus
import com.yinxin.uavfir.firedetection.WaypointMissionControlPort
import com.yinxin.uavfir.firedetection.store.AndroidStoreClock
import com.yinxin.uavfir.firedetection.store.CanonicalCoordinatorStoreRecordFactory
import com.yinxin.uavfir.firedetection.store.CoordinatorModelIdentity
import com.yinxin.uavfir.firedetection.store.FireOutboxDispatcher
import com.yinxin.uavfir.firedetection.store.FireReportTransport
import com.yinxin.uavfir.firedetection.store.FireStoreOpenHelper
import com.yinxin.uavfir.firedetection.store.SendOutcome
import com.yinxin.uavfir.firedetection.store.SqliteCoordinatorStoreAdapter
import com.yinxin.uavfir.firedetection.store.SqliteFireSessionStore
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive

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
    private val latestVisibleFrameBuffer = LatestVisibleFrameBuffer()
    private val visibleInferenceResults = VisibleInferenceResultJournal()
    private val fireCoordinatorReference = AtomicReference<AgentFireClosedLoopCoordinator?>()
    private val cachedFireHealth = AtomicReference(ConfirmationHealth(false, false, false, false))
    private val coordinatorOutcomeRecorder = BoundedCoordinatorOutcomeRecorder()
    private val fireEvidenceCapture = BoundedVisibleEvidenceCapture(
        File(application.filesDir, "fire-evidence"),
        SystemClock::elapsedRealtime,
        System::currentTimeMillis,
    )
    private val visibleConfirmationTracker = VisibleConfirmationTracker(
        VisibleConfirmationPolicy(
            maxCenterDistance = 0.08,
            fireConfidence = 0.70f,
            smokeConfidence = 0.65f,
            nmsIou = com.yinxin.uavfir.firedetection.VisibleDetectorContract.NMS_IOU_THRESHOLD,
        ),
    )
    private val fireStoreClock = AndroidStoreClock()
    private val fireSessionStore = SqliteFireSessionStore(
        FireStoreOpenHelper(application),
        fireStoreClock,
    )
    private val coordinatorStore = SqliteCoordinatorStoreAdapter(
        fireSessionStore,
        CanonicalCoordinatorStoreRecordFactory(
            fireStoreClock,
            CoordinatorModelIdentity(
                modelVersion = VISIBLE_MODEL_VERSION,
                modelSha256 = VISIBLE_MODEL_SHA256,
                inputSize = 960,
                runtime = "NCNN",
            ),
        ),
    )
    private val coordinatorOutbox = StoreBackedCoordinatorOutboxPort(
        fireSessionStore,
        FireOutboxDispatcher(
            fireSessionStore,
            FireReportTransport {
                // Task 10 owns the staged backend transport. Never synthesize
                // a successful ACK through a legacy endpoint.
                SendOutcome.TransientFailure("task10-transport-unavailable")
            },
        ),
        appScope,
    )
    private val fireConfirmationBridge = AgentFireConfirmationBridge(
        tracker = visibleConfirmationTracker,
        monitoringContext = {
            val generation = latestVisibleFrameBuffer.currentVisibleSourceGeneration()
            val droneSn = activeThermalDroneSn()
            if (sessionManager.thermalMonitoringEnabled && generation != null && droneSn != null) {
                AgentFireMonitoringContext("fire-$droneSn", generation)
            } else null
        },
        health = cachedFireHealth::get,
        coordinator = fireCoordinatorReference::get,
        scope = appScope,
        evidenceCapture = fireEvidenceCapture,
        outcomeSink = coordinatorOutcomeRecorder,
    )
    private val visibleFireDetectorArming = VisibleFireDetectorFactory.create(application)
    private val visibleInferenceLoop: VisibleInferenceLoop? =
        (visibleFireDetectorArming as? VisibleFireDetectorArmingResult.Armed)
        ?.let {
            VisibleInferenceLoop(
                latestVisibleFrameBuffer,
                it.detector,
                resultPublisher = visibleInferenceResults,
                confirmationObserver = fireConfirmationBridge,
            )
        }
    private val visibleFrameIngress: VisibleFrameIngress =
        if (visibleInferenceLoop != null) latestVisibleFrameBuffer else VisibleFrameIngress.NO_OP
    private val thermalHotspotTriggerBridge = ThermalHotspotTriggerBridge()
    private val fireConfirmationRunnerBridge = FireConfirmationRunnerBridge()
    private val sessionManager = DualStreamSessionManager(
        RealMsdkStreamProvider(
            hotspotCandidateListener = thermalHotspotTriggerBridge,
            visibleFrameIngress = visibleFrameIngress,
        ),
        fireConfirmationRunner = fireConfirmationRunnerBridge::run,
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
    private val missionHoldControl = WaypointMissionHoldControl(waypointExecutor)
    private val flightSafetyGate = FlightSafetyGate()
    private val resumeSafetyEvidenceOwner = OwnedResumeSafetyEvidenceProvider()
    private val awaitableMissionControl = AwaitableMissionControl(
        port = WaypointMissionControlPort(waypointExecutor),
        hover = flightControlClient::hover,
        scope = appScope,
        safetyGate = flightSafetyGate,
        safetyProvider = resumeSafetyEvidenceOwner,
        monotonicNow = SystemClock::elapsedRealtime,
    )
    private val coordinatorMission = Task7CoordinatorMissionPort(
        missionControl = awaitableMissionControl,
        safetyGate = flightSafetyGate,
        safetyEvidenceOwner = resumeSafetyEvidenceOwner,
        observationSource = CoordinatorFlightObservationSource {
            val now = SystemClock.elapsedRealtime()
            val velocity = com.yinxin.uavfir.api.DjiAircraftVelocityProvider().current()
            CoordinatorFlightObservation(
                telemetry = velocity?.let {
                    FlightTelemetrySample(now, now, it.horizontalMps, it.verticalMps)
                },
                // Full manual/RTH/battery/avoidance signal adapter is not yet
                // device-proven, so the arming snapshot below stays fail-closed.
                signals = FlightSafetySignals(),
            )
        },
        monotonicNow = SystemClock::elapsedRealtime,
        competingOwnerProbe = { session ->
            visibleFireLaserLocator.hasCompetingOwnership(
                com.yinxin.uavfir.firedetection.FireControlSessionKey(
                    session.sessionId,
                    session.generation,
                ),
                session.eventId,
                session.generation,
            )
        },
    )
    private val visibleFireLaserRangefinder = DjiLaserRangefinderClient()
    private val aircraftOsdTracker = DjiAircraftOsdTracker()
    private val localVisibleTargetAimer = LocalVisibleTargetAimer(
        alignmentAction = DjiLocalTargetAlignmentAction(
            tapZoomClient = DjiTapZoomClient(),
            currentVisibleGeneration =
                latestVisibleFrameBuffer::currentVisibleSourceGeneration,
        ),
        detectionSource = visibleInferenceResults,
    )
    private val visibleFireLaserLocator = VisibleFireLaserLocator(
        missionHold = missionHoldControl,
        flightControl = flightControlClient,
        localTargetAimer = localVisibleTargetAimer,
        aircraftOsdProvider = aircraftOsdTracker,
        laserRangefinder = visibleFireLaserRangefinder,
        laserObservationClient = visibleFireLaserRangefinder,
        sourceGenerationGuard =
            latestVisibleFrameBuffer::currentVisibleSourceGeneration,
    ).also(sessionManager::attachVisibleFireLaserLocator)
    private val coordinatorLocalization = Task8CoordinatorLocalizationPort(visibleFireLaserLocator)
    private val fireClosedLoopCoordinator = AgentFireClosedLoopCoordinator(
        store = coordinatorStore,
        delivery = coordinatorOutbox,
        mission = coordinatorMission,
        localization = coordinatorLocalization,
        armingHealth = {
            val sourceGeneration = latestVisibleFrameBuffer.currentVisibleSourceGeneration()
            val health = cachedFireHealth.get()
            CoordinatorArmingHealth(
                featureEnabled = BuildConfig.VISIBLE_FIRE_DETECTION_ENABLED,
                backendMonitoringEnabled = sessionManager.thermalMonitoringEnabled,
                visibleSourceActive = sourceGeneration != null,
                sourceGenerationValid = sourceGeneration != null,
                detectorHealthy = health.modelHealthy && health.runtimeHealthy,
                storeHealthy = health.storeHealthy,
                // Task 10 has not supplied the staged ACK transport yet.
                outboxHealthy = false,
                missionAdaptersHealthy = true,
                // RC Plus is unavailable; full DJI safety-signal evidence is
                // deliberately not represented as healthy.
                safetyAdaptersHealthy = false,
                manualHoldActive = runCatching {
                    fireSessionStore.hasActiveManualHold()
                }.getOrDefault(true),
                competingOwnerActive = visibleFireLaserLocator.hasActiveOwnership(),
            )
        },
        runtimeHealth = {
            val health = cachedFireHealth.get()
            com.yinxin.uavfir.firedetection.CoordinatorRuntimeHealth(
                detectorHealthy = health.modelHealthy,
                runtimeHealthy = health.runtimeHealthy,
                storeHealthy = health.storeHealthy,
            )
        },
    )
    private val fireRecoveryCoordinator = AgentFireRecoveryCoordinator(
        coordinatorStore,
        coordinatorOutbox,
        coordinatorLocalization,
        coordinatorMission,
    )
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
        fireCoordinatorReference.set(fireClosedLoopCoordinator)
        appScope.launch {
            while (isActive) {
                val detectorHealthy = visibleInferenceLoop?.snapshot()?.status ==
                    VisibleInferenceStatus.HEALTHY
                cachedFireHealth.set(
                    ConfirmationHealth(
                        frameHealthy = latestVisibleFrameBuffer.currentVisibleSourceGeneration() != null,
                        modelHealthy = detectorHealthy,
                        runtimeHealthy = detectorHealthy,
                        storeHealthy = runCatching {
                            fireSessionStore.loadActiveSessions()
                            true
                        }.getOrDefault(false),
                    ),
                )
                delay(FIRE_HEALTH_REFRESH_MS)
            }
        }
        appScope.launch {
            runCatching { fireRecoveryCoordinator.recover() }.fold(
                onSuccess = {
                    // Startup force-safe/reconciliation always precedes new
                    // inference and therefore new flight-control ownership.
                    visibleInferenceLoop?.start(appScope)
                },
                onFailure = { Log.e(TAG, "fire recovery failed closed", it) },
            )
        }
        fireConfirmationRunnerBridge.runner = fireConfirmationProcessor::run
        thermalHotspotTriggerBridge.trigger = {
            activeThermalDroneSn()?.let { droneSn ->
                thermalHotspotMonitor.onFrameHotspotCandidate(droneSn)
            }
        }
        Log.i(TAG, "initialized backend=${AgentBackendConfig.DEFAULT_BASE_URL}")
        Log.i(
            TAG,
            "visible detector=${visibleFireDetectorArming::class.java.simpleName} " +
                "armed=${visibleInferenceLoop != null}",
        )
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
        fireCoordinatorReference.set(null)
        awaitableMissionControl.close()
        aircraftOsdTracker.close()
        visibleInferenceLoop?.close() ?: latestVisibleFrameBuffer.close()
        osdReporter.stop()
        hmsReporter.stop()
        waypointExecutor.detach()
        runtimeLoop.stop()
        mqttPublisher.disconnect()
        appScope.cancel()
        fireSessionStore.close()
    }

    companion object {
        private const val TAG = "AppServices"
        private const val AUTO_START_DELAY_MS: Long = 6_000
        private const val FIRE_HEALTH_REFRESH_MS: Long = 250
        private const val VISIBLE_MODEL_VERSION = "visible-fire-wechat-best2-20260728"
        private const val VISIBLE_MODEL_SHA256 =
            "957bec7a567ce1f57f9a57187a6b085c7c95149b889773479d018e3ed5e9f650"
    }
}

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
