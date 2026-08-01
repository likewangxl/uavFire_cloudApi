package com.yinxin.uavfir.firedetection.store

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.yinxin.uavfir.firedetection.AgentFireConfirmationEnvelope
import com.yinxin.uavfir.firedetection.CoordinatorManualHoldReason
import com.yinxin.uavfir.firedetection.CoordinatorRecoverySession
import com.yinxin.uavfir.firedetection.CoordinatorSession
import com.yinxin.uavfir.firedetection.CoordinatorStorePort
import com.yinxin.uavfir.firedetection.CoordinatorWrite
import com.yinxin.uavfir.firedetection.FireLocalizationResult
import com.yinxin.uavfir.firedetection.FireSessionState
import com.yinxin.uavfir.firedetection.InitialPersistenceRequest
import com.yinxin.uavfir.firedetection.TerminalPersistenceRequest
import kotlin.math.max

/**
 * Typed boundary between the Task 9 coordinator and the Task 6 transactional
 * store. Payload construction remains injectable so the adapter never derives
 * model identity, evidence timestamps, or coordinates from untyped JSON.
 */
class SqliteCoordinatorStoreAdapter(
    private val store: SqliteFireSessionStore,
    private val records: CoordinatorStoreRecordFactory,
) : CoordinatorStorePort {
    override suspend fun persistInitial(
        session: CoordinatorSession,
        envelope: AgentFireConfirmationEnvelope,
        request: InitialPersistenceRequest,
    ): CoordinatorWrite = CoordinatorWrite(
        store.persistInitialConfirmation(records.initial(session, envelope, request)),
        INITIAL_SEQUENCE,
    )

    override suspend fun persistStage(
        session: CoordinatorSession,
        state: FireSessionState,
        recoveryProof: MissionRecoveryProofV1?,
    ): CoordinatorWrite = store.persistNextStage { sequence ->
        records.stage(session, state, sequence, reason = null, recoveryProof = recoveryProof)
    }.toCoordinatorWrite()

    override suspend fun persistTerminal(
        session: CoordinatorSession,
        request: TerminalPersistenceRequest,
        result: FireLocalizationResult,
    ): CoordinatorWrite = store.persistNextTerminal { sequence ->
        records.terminal(session, request, result, sequence)
    }.toCoordinatorWrite()

    override suspend fun persistManualHold(
        session: CoordinatorSession,
        reason: CoordinatorManualHoldReason,
    ): CoordinatorWrite = store.persistNextStage { sequence ->
        records.stage(session, FireSessionState.MANUAL_HOLD, sequence, reason.toStoreReason())
    }.toCoordinatorWrite()

    override suspend fun recordTerminalAck(session: CoordinatorSession, sequence: Long): Boolean =
        store.isOutboxAcknowledged(session.eventId, sequence)

    override suspend fun loadRecoverySessions(): List<CoordinatorRecoverySession> =
        store.loadForCoordinatorRecovery().activeSessions.map { durable ->
            CoordinatorRecoverySession(
                session = CoordinatorSession(
                    durable.sessionId,
                    durable.eventId,
                    durable.taskId,
                    durable.detectionKind,
                    durable.coordinatorGeneration,
                    durable.initialRoi,
                    durable.sourceGeneration,
                    store.loadEvidence(durable.eventId).take(2),
                ),
                persistedState = durable.state,
                terminalResultDurable = durable.state == FireSessionState.RESULT_DURABLE ||
                    durable.state == FireSessionState.RESUME_REQUESTED,
                recoveryProof = durable.recoveryProof,
            )
        }

    private companion object { const val INITIAL_SEQUENCE = 1L }
}

interface CoordinatorStoreRecordFactory {
    fun initial(
        session: CoordinatorSession,
        envelope: AgentFireConfirmationEnvelope,
        request: InitialPersistenceRequest,
    ): InitialConfirmationRecord

    fun stage(
        session: CoordinatorSession,
        state: FireSessionState,
        sequence: Long,
        reason: StagePersistenceReason?,
        recoveryProof: MissionRecoveryProofV1? = null,
    ): StagePersistenceRecord

    fun terminal(
        session: CoordinatorSession,
        request: TerminalPersistenceRequest,
        result: FireLocalizationResult,
        sequence: Long,
    ): TerminalResultRecord
}

data class CoordinatorModelIdentity(
    val modelVersion: String,
    val modelSha256: String,
    val inputSize: Int,
    val runtime: String,
) {
    init {
        require(modelVersion.isNotBlank() && runtime.isNotBlank())
        require(modelSha256.matches(Regex("^[0-9a-fA-F]{64}$")))
        require(inputSize > 0)
    }
}

fun interface CoordinatorEvidenceProvider {
    fun evidence(eventId: String, sequence: Long): List<FireEvidenceReference>
}

/** Canonical production record builder; no image bytes or secrets enter SQLite. */
class CanonicalCoordinatorStoreRecordFactory(
    private val clock: StoreClock,
    private val model: CoordinatorModelIdentity,
    private val evidence: CoordinatorEvidenceProvider = CoordinatorEvidenceProvider { _, _ -> emptyList() },
    private val agentId: String = "uavfire-agent",
    private val policyVersion: String = "agent-visible-v1",
) : CoordinatorStoreRecordFactory {
    init { require(agentId.isNotBlank() && policyVersion.isNotBlank()) }
    override fun initial(
        session: CoordinatorSession,
        envelope: AgentFireConfirmationEnvelope,
        request: InitialPersistenceRequest,
    ): InitialConfirmationRecord {
        require(envelope.sessionId == request.sessionId && envelope.eventId == request.eventId)
        val wall = monotonicToWall(envelope.confirmation.secondFrameTimestampMillis)
        require(session.sessionId == envelope.sessionId && session.eventId == envelope.eventId)
        val root = identity(session, 1, wall, FireSessionState.VISUAL_CONFIRMED)
        root.addProperty("detectionKind", envelope.confirmation.kind.name)
        root.addProperty("confidence", envelope.confirmation.confidence)
        root.add("visibleRoi", roi(envelope.confirmation.roi))
        root.addProperty("locationStatus", "LASER_LOCATING")
        root.addProperty("modelVersion", model.modelVersion)
        root.addProperty("modelHash", model.modelSha256.lowercase())
        root.addProperty("policyVersion", envelope.confirmation.policyVersion)
        root.addProperty("inputSize", model.inputSize)
        root.addProperty("runtime", model.runtime)
        return InitialConfirmationRecord(
            request,
            wall,
            model.modelVersion,
            model.modelSha256,
            model.inputSize,
            model.runtime,
            taskId = session.taskId,
            sourceGeneration = session.sourceGeneration,
            coordinatorGeneration = session.generation,
            initialRoi = session.initialRoi,
            payload = root.toString(),
            evidence = (envelope.evidence + evidence.evidence(envelope.eventId, 1)).distinctBy { it.path }.take(2),
        )
    }

    override fun stage(
        session: CoordinatorSession,
        state: FireSessionState,
        sequence: Long,
        reason: StagePersistenceReason?,
        recoveryProof: MissionRecoveryProofV1?,
    ): StagePersistenceRecord {
        val wall = clock.wallTimeMillis()
        val flightStatus = FLIGHT_STATUS[state]
        val locationStatus = if (state in PRE_TERMINAL) com.yinxin.uavfir.firedetection.LocationStatus.LASER_LOCATING else null
        val root = identity(session, sequence, wall, state)
        flightStatus?.let { root.addProperty("flightStatus", it) }
        locationStatus?.let { root.addProperty("locationStatus", it.name) }
        reason?.let { root.addProperty("reason", it.name) }
        return StagePersistenceRecord(
            session.sessionId,
            session.eventId,
            sequence,
            wall,
            state,
            flightStatus,
            locationStatus,
            reason,
            root.toString(),
            recoveryProof,
        )
    }

    override fun terminal(
        session: CoordinatorSession,
        request: TerminalPersistenceRequest,
        result: FireLocalizationResult,
        sequence: Long,
    ): TerminalResultRecord {
        require(session.sessionId == request.sessionId && session.eventId == request.eventId)
        val timeAnchor = timeAnchor()
        val wall = timeAnchor.wallMillis
        val root = identity(session, sequence, wall, FireSessionState.RESULT_DURABLE)
        root.addProperty("locationStatus", request.locationStatus.name)
        root.addProperty("geoMethod", request.geoMethod.name)
        val report = when (result) {
            is FireLocalizationResult.Precise -> {
                require(result.kind == session.kind)
                val samples = result.rawSamples.sortedBy { it.sampledAtMonotonicMs }.map { sample ->
                    val measurement = sample.measurement
                    LaserReportSample(
                        status = "NORMAL",
                        rangeMeters = requireNotNull(measurement.distanceM),
                        point = ReportGeoPoint(
                            requireNotNull(measurement.latitude),
                            requireNotNull(measurement.longitude),
                            requireNotNull(measurement.altitude),
                        ),
                        eventTimestampWallMillis = timeAnchor.toWall(sample.sampledAtMonotonicMs),
                    )
                }
                root.addProperty("fireLat", result.fireLatitude)
                root.addProperty("fireLng", result.fireLongitude)
                root.addProperty("fireAlt", result.fireAltitude)
                root.addProperty("errorRadiusMeters", result.errorRadiusM)
                root.add("laserSamples", JsonArray().apply {
                    samples.forEach { sample -> add(JsonObject().apply {
                        addProperty("status", sample.status)
                        addProperty("rangeMeters", sample.rangeMeters)
                        addProperty("lat", sample.point.latitude)
                        addProperty("lng", sample.point.longitude)
                        addProperty("alt", sample.point.altitudeMeters)
                        addProperty("eventTimestamp", sample.eventTimestampWallMillis)
                    }) }
                })
                PreciseTerminalReport(
                    ReportGeoPoint(result.fireLatitude, result.fireLongitude, result.fireAltitude),
                    result.errorRadiusM,
                    samples,
                )
            }
            is FireLocalizationResult.DegradedOsd -> {
                require(result.kind == session.kind && result.aircraftOsd.valid)
                val aircraft = ReportGeoPoint(
                    result.aircraftOsd.latitude,
                    result.aircraftOsd.longitude,
                    result.aircraftOsd.altitude,
                )
                root.add("aircraft", point(aircraft))
                val reason = FireLocalizationDegradedReason.from(result.reason)
                root.addProperty("degradedReason", reason.name)
                DegradedTerminalReport(aircraft, reason)
            }
            is FireLocalizationResult.ManualHold ->
                throw IllegalArgumentException("Manual hold is not a terminal location report")
        }
        return TerminalResultRecord(
            effect = com.yinxin.uavfir.firedetection.FireSessionEffect.PersistTerminalResult(request),
            sequence = sequence,
            eventTimestampWallMillis = wall,
            report = report,
            payload = root.toString(),
            evidence = (session.evidence + evidence.evidence(session.eventId, sequence)).distinctBy { it.path }.take(2),
        )
    }

    private fun monotonicToWall(observedAt: Long): Long {
        return timeAnchor().toWall(observedAt)
    }

    private fun timeAnchor() = TimeAnchor(clock.elapsedRealtimeMillis(), clock.wallTimeMillis())

    private data class TimeAnchor(val elapsedMillis: Long, val wallMillis: Long) {
        fun toWall(observedAt: Long): Long {
            require(observedAt in 0..elapsedMillis) { "Monotonic observation is from the future" }
            return max(0L, wallMillis - (elapsedMillis - observedAt))
        }
    }

    private fun identity(session: CoordinatorSession, sequence: Long, wall: Long, state: FireSessionState) =
        JsonObject().apply {
            addProperty("agentId", agentId)
            addProperty("droneSn", session.taskId.removePrefix("fire-").ifBlank { session.taskId })
            addProperty("eventId", session.eventId)
            addProperty("sessionId", session.sessionId)
            addProperty("taskId", session.taskId)
            addProperty("sourceGeneration", session.sourceGeneration)
            addProperty("coordinatorGeneration", session.generation)
            addProperty("sequence", sequence)
            addProperty("eventTimestamp", wall)
            addProperty("state", state.name)
            addProperty("detectionKind", session.kind.name)
            add("visibleRoi", roi(session.initialRoi))
            addProperty("modelVersion", model.modelVersion)
            addProperty("modelHash", model.modelSha256.lowercase())
            addProperty("policyVersion", policyVersion)
            addProperty("inputSize", model.inputSize)
            addProperty("runtime", model.runtime)
        }

    private fun roi(value: com.yinxin.uavfir.firedetection.NormalizedRoi) = JsonObject().apply {
        addProperty("x", value.left)
        addProperty("y", value.top)
        addProperty("width", value.right - value.left)
        addProperty("height", value.bottom - value.top)
    }

    private fun point(value: ReportGeoPoint) = JsonObject().apply {
        addProperty("lat", value.latitude)
        addProperty("lng", value.longitude)
        addProperty("alt", value.altitudeMeters)
    }

    private companion object {
        val PRE_TERMINAL = setOf(
            FireSessionState.HOLD_REQUESTED,
            FireSessionState.HOVER_VERIFYING,
            FireSessionState.TARGET_ALIGNING,
            FireSessionState.LASER_MEASURING,
        )
        val FLIGHT_STATUS = mapOf(
            FireSessionState.HOLD_REQUESTED to "HOLD_REQUESTED",
            FireSessionState.HOVER_VERIFYING to "HOVERING",
            FireSessionState.TARGET_ALIGNING to "TARGET_ALIGNING",
            FireSessionState.LASER_MEASURING to "LASER_MEASURING",
            FireSessionState.RESUME_REQUESTED to "RESUME_REQUESTED",
            FireSessionState.MISSION_RESUMED to "MISSION_RESUMED",
            FireSessionState.SCANNING to "SCANNING",
            FireSessionState.MANUAL_HOLD to "MANUAL_HOLD",
        )
    }
}

private fun SequencedDurableWrite.toCoordinatorWrite() = CoordinatorWrite(result, sequence)

private fun CoordinatorManualHoldReason.toStoreReason(): StagePersistenceReason = when (this) {
    CoordinatorManualHoldReason.PAUSE_TIMEOUT -> StagePersistenceReason.PAUSE_TIMEOUT
    CoordinatorManualHoldReason.PAUSE_FAILED -> StagePersistenceReason.PAUSE_FAILED
    CoordinatorManualHoldReason.HOVER_TIMEOUT -> StagePersistenceReason.HOVER_TIMEOUT
    CoordinatorManualHoldReason.MANUAL_TAKEOVER -> StagePersistenceReason.MANUAL_TAKEOVER
    CoordinatorManualHoldReason.LOW_BATTERY -> StagePersistenceReason.LOW_BATTERY
    CoordinatorManualHoldReason.RETURN_TO_HOME -> StagePersistenceReason.RETURN_TO_HOME
    CoordinatorManualHoldReason.OBSTACLE_AVOIDANCE -> StagePersistenceReason.OBSTACLE_AVOIDANCE
    CoordinatorManualHoldReason.FLIGHT_ERROR -> StagePersistenceReason.FLIGHT_ERROR
    CoordinatorManualHoldReason.UNKNOWN_MISSION_STATE -> StagePersistenceReason.UNKNOWN_MISSION_STATE
    CoordinatorManualHoldReason.MISSING_BREAKPOINT -> StagePersistenceReason.MISSING_BREAKPOINT
    CoordinatorManualHoldReason.BREAKPOINT_MISMATCH -> StagePersistenceReason.BREAKPOINT_MISMATCH
    CoordinatorManualHoldReason.ROI_OR_LASER_FAILURE -> StagePersistenceReason.ROI_OR_LASER_FAILURE
    CoordinatorManualHoldReason.AIRCRAFT_OSD_UNAVAILABLE -> StagePersistenceReason.AIRCRAFT_OSD_UNAVAILABLE
    CoordinatorManualHoldReason.STORAGE_FAILURE -> StagePersistenceReason.STORAGE_FAILURE
    CoordinatorManualHoldReason.DETECTOR_FAILURE -> StagePersistenceReason.DETECTOR_FAILURE
    CoordinatorManualHoldReason.LASER_DISABLE_UNCERTAIN -> StagePersistenceReason.LASER_DISABLE_UNCERTAIN
    CoordinatorManualHoldReason.RESUME_FAILURE -> StagePersistenceReason.RESUME_FAILURE
    CoordinatorManualHoldReason.CANCELLED_AFTER_FLIGHT_SUBMISSION ->
        StagePersistenceReason.CANCELLED_AFTER_FLIGHT_SUBMISSION
    CoordinatorManualHoldReason.CANCELLED_AFTER_DURABLE_HOLD_INTENT ->
        StagePersistenceReason.CANCELLED_AFTER_DURABLE_HOLD_INTENT
    CoordinatorManualHoldReason.STARTUP_RECOVERY_UNCERTAIN ->
        StagePersistenceReason.STARTUP_RECOVERY_UNCERTAIN
    CoordinatorManualHoldReason.STALE_SESSION_EVIDENCE -> StagePersistenceReason.STALE_SESSION_EVIDENCE
}
