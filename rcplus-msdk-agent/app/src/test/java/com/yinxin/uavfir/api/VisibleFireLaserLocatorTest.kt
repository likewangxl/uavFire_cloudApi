package com.yinxin.uavfir.api

import com.yinxin.uavfir.firedetection.DetectionKind
import com.yinxin.uavfir.firedetection.AircraftOsdSnapshot
import com.yinxin.uavfir.firedetection.FireLocalizationFailure
import com.yinxin.uavfir.firedetection.FireLocalizationRequest
import com.yinxin.uavfir.firedetection.FireLocalizationResult
import com.yinxin.uavfir.firedetection.LocalTargetAimResult
import com.yinxin.uavfir.firedetection.LocalVisibleTargetAimerPort
import com.yinxin.uavfir.firedetection.NormalizedRoi
import com.yinxin.uavfir.firedetection.BoundLaserObservationClient
import com.yinxin.uavfir.firedetection.BoundLaserSample
import com.yinxin.uavfir.firedetection.LaserHardwareAwaitResult
import com.yinxin.uavfir.firedetection.LaserHardwareOperationToken
import com.yinxin.uavfir.firedetection.LaserOperationBinding
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent

@OptIn(ExperimentalCoroutinesApi::class)
class VisibleFireLaserLocatorTest {

    @Test
    fun hold_pausesRouteThenRequiresOneContinuousSecondOfStableVelocity() = runTest {
        val mission = RecordingMissionHold(active = true)
        val flight = RecordingFlightControl()
        val velocity = SequenceVelocityProvider(
            mutableListOf(VelocitySample(0.4, 0.0)).apply {
                repeat(6) { add(VelocitySample(0.2, 0.1)) }
            },
        )
        val time = AdvancingTime()
        val locator = VisibleFireLaserLocator(
            missionHold = mission,
            flightControl = flight,
            velocityProvider = velocity,
            time = time,
        )

        val result = locator.hold("fire-1")

        assertEquals("applied", result.status)
        assertEquals("HOVER_STABLE", result.message)
        assertEquals(1, mission.holdCalls)
        assertFalse(mission.resumeCalled)
        assertTrue(flight.actions.isEmpty())
    }

    @Test
    fun holdWithoutActiveRoute_explicitlyHoversThenWaitsForStability() = runTest {
        val mission = RecordingMissionHold(active = false)
        val flight = RecordingFlightControl()
        val time = AdvancingTime()
        val locator = VisibleFireLaserLocator(
            missionHold = mission,
            flightControl = flight,
            velocityProvider = SequenceVelocityProvider(
                MutableList(6) { VelocitySample(0.1, 0.1) },
            ),
            time = time,
        )

        val result = locator.hold("fire-1")

        assertEquals("applied", result.status)
        assertEquals(listOf("hover"), flight.actions)
        assertFalse(mission.resumeCalled)
    }

    @Test
    fun holdTimesOutWhenAircraftNeverStabilizesAndStaysPaused() = runTest {
        val mission = RecordingMissionHold(active = true)
        val time = AdvancingTime()
        val locator = VisibleFireLaserLocator(
            missionHold = mission,
            flightControl = RecordingFlightControl(),
            velocityProvider = SequenceVelocityProvider(
                mutableListOf(),
                fallback = VelocitySample(1.0, 0.4),
            ),
            time = time,
        )

        val result = locator.hold("fire-1")

        assertEquals("failed", result.status)
        assertEquals("LASER_FAILED:hover-stability-timeout", result.message)
        assertFalse(mission.resumeCalled)
        assertTrue(time.nowMs >= 8_000L)
    }

    @Test
    fun measureRejectsEventThatDoesNotOwnHeldSession() = runTest {
        val fixture = measuringFixture()
        fixture.locator.hold("fire-1")

        val result = fixture.locator.measure(
            eventId = "fire-2",
            taskId = "task-1",
            visibleRoi = ROI,
        )

        assertEquals("failed", result.status)
        assertEquals("LASER_FAILED:event-session-mismatch", result.message)
    }

    @Test
    fun measureAimsVisibleRoiAndReturnsRobustNormalLaserFix() = runTest {
        val fixture = measuringFixture(
            laserResults = mutableListOf(
                LaserRangefinderResult(34.960120, 109.316450, 530.0, 60.0, "NORMAL"),
                LaserRangefinderResult(34.960123, 109.316456, 531.0, 60.2, "NORMAL"),
                LaserRangefinderResult(34.960126, 109.316462, 532.0, 59.9, "NORMAL"),
            ),
        )
        fixture.locator.hold("fire-1")

        val result = fixture.locator.measure("fire-1", "task-1", ROI)

        assertEquals("applied", result.status)
        assertEquals("LASER_LOCATED", result.message)
        assertEquals("LASER_RANGEFINDER", result.geoMethod)
        assertEquals("PRECISE", result.geoQuality)
        assertEquals(34.960123, result.fireLat!!, 1e-9)
        assertEquals(109.316456, result.fireLng!!, 1e-9)
        assertEquals(5.0, result.geoErrorRadiusM!!, 1e-9)
        assertEquals(1, fixture.aimer.calls)
        assertEquals(1, fixture.laser.disableCalls)
        assertFalse(fixture.mission.resumeCalled)
    }

    @Test
    fun measureRejectsScatteredOrNonNormalLaserSamples() = runTest {
        val fixture = measuringFixture(
            laserResults = mutableListOf(
                LaserRangefinderResult(null, null, null, null, "INVALID"),
                LaserRangefinderResult(34.9600, 109.3160, 530.0, 60.0, "NORMAL"),
                LaserRangefinderResult(34.9700, 109.3260, 530.0, 60.0, "NORMAL"),
            ),
        )
        fixture.locator.hold("fire-1")

        val result = fixture.locator.measure("fire-1", "task-1", ROI)

        assertEquals("failed", result.status)
        assertEquals("LASER_FAILED:laser-fix-unavailable", result.message)
        assertEquals(1, fixture.laser.disableCalls)
        assertFalse(fixture.mission.resumeCalled)
    }

    @Test
    fun localizeReturnsTypedPreciseFireAndSmokeResultsAndDisablesLaser() = runTest {
        for (kind in DetectionKind.entries) {
            val laser = SequenceLaser(
                mutableListOf(
                    LaserRangefinderResult(34.0, 109.0, 10.0, 60.0, "NORMAL", .5, .5),
                    LaserRangefinderResult(34.00001, 109.00001, 12.0, 62.0, "NORMAL", .5, .5),
                    LaserRangefinderResult(34.00002, 109.00002, 11.0, 61.0, "NORMAL", .5, .5),
                ),
            )
            val locator = localLocator(laser = laser)
            locator.holdLocal("session-1", "event-1")

            val result = locator.localize(localRequest(kind))

            assertTrue(result is FireLocalizationResult.Precise)
            result as FireLocalizationResult.Precise
            assertEquals(kind, result.kind)
            assertEquals("PRECISE", result.locationStatus)
            assertEquals("LASER_RANGEFINDER", result.geoMethod)
            assertEquals(3, result.rawSamples.size)
            assertEquals(1, laser.enableCalls)
            assertEquals(1, laser.disableCalls)
        }
    }

    @Test
    fun laserMeasurementBoundaryRunsAfterAlignmentAndImmediatelyBeforeEnable() = runTest {
        val order = mutableListOf<String>()
        val laser = SequenceLaser(normalSamples(), onBegin = { order += "enable" })
        val locator = localLocator(
            laser = laser,
            aimer = LocalVisibleTargetAimerPort {
                order += "aligned"
                LocalTargetAimResult.Aligned(
                    kind = it.kind,
                    roi = it.priorRoi,
                    sourceGeneration = 7,
                    detectionCapturedAtMonotonicMs = 100,
                    cycles = 1,
                )
            },
        )
        locator.holdLocal("session-1", "event-1")

        val result = locator.localize(localRequest(DetectionKind.FIRE)) {
            order += "boundary"
            true
        }

        assertTrue(result is FireLocalizationResult.Precise)
        assertEquals(listOf("aligned", "boundary", "enable"), order)
    }

    @Test
    fun rejectedOrThrowingLaserMeasurementBoundaryManualHoldsWithoutEnablingLaser() = runTest {
        for (boundary in listOf<suspend () -> Boolean>(
            { false },
            { throw IllegalStateException("store-failed") },
        )) {
            val laser = SequenceLaser(normalSamples())
            val locator = localLocator(laser)
            locator.holdLocal("session-1", "event-1")

            val result = locator.localize(localRequest(DetectionKind.SMOKE), boundary)

            assertTrue(result is FireLocalizationResult.ManualHold)
            assertEquals(FireLocalizationFailure.LASER_ENABLE_FAILED, result.reason)
            assertEquals(0, laser.enableCalls)
        }
    }

    @Test
    fun boundedInvalidLaserSamplesDegradeToAircraftObservationWithoutFireCoordinates() = runTest {
        val laser = SequenceLaser(
            MutableList(20) {
                LaserRangefinderResult(null, null, null, null, "INVALID")
            },
        )
        val locator = localLocator(laser = laser)
        locator.holdLocal("session-1", "event-1")

        val result = locator.localize(localRequest(DetectionKind.SMOKE))

        assertTrue(result is FireLocalizationResult.DegradedOsd)
        result as FireLocalizationResult.DegradedOsd
        assertEquals(DetectionKind.SMOKE, result.kind)
        assertEquals(FireLocalizationFailure.LASER_SAMPLES_UNAVAILABLE, result.reason)
        assertEquals("DEGRADED_OSD", result.locationStatus)
        assertEquals("AIRCRAFT_OBSERVATION", result.geoMethod)
        assertEquals(null, result.fireLatitude)
        assertEquals(null, result.fireLongitude)
        assertEquals(null, result.fireAltitude)
        assertEquals(9, laser.measureCalls)
        assertEquals(1, laser.disableCalls)
        assertEquals(34.1, result.aircraftOsd.latitude, 0.0)
    }

    @Test
    fun laserAndInvalidOsdReturnsManualHoldInsteadOfInventingCoordinates() = runTest {
        val locator = localLocator(
            laser = SequenceLaser(mutableListOf()),
            osd = AircraftOsdSnapshot(Double.NaN, 109.0, 100.0, 1, 1),
        )
        locator.holdLocal("session-1", "event-1")

        val result = locator.localize(localRequest(DetectionKind.FIRE))

        assertEquals(
            FireLocalizationFailure.AIRCRAFT_OSD_UNAVAILABLE,
            (result as FireLocalizationResult.ManualHold).reason,
        )
    }

    @Test
    fun thrownAndCancelledLaserPathsAlwaysDisable() = runTest {
        val throwing = SequenceLaser(mutableListOf(), error = IllegalStateException("boom"))
        val locator = localLocator(laser = throwing)
        locator.holdLocal("session-1", "event-1")
        assertTrue(locator.localize(localRequest(DetectionKind.FIRE)) is FireLocalizationResult.DegradedOsd)
        assertEquals(1, throwing.disableCalls)

        val cancelled = SequenceLaser(mutableListOf(), error = CancellationException("stop"))
        val cancelledLocator = localLocator(laser = cancelled)
        cancelledLocator.holdLocal("session-1", "event-1")
        runCatching { cancelledLocator.localize(localRequest(DetectionKind.FIRE)) }
        assertEquals(1, cancelled.disableCalls)
    }

    @Test
    fun aimAndEnableFailuresStillDisableLaserAndReturnTypedDegradation() = runTest {
        val laser = SequenceLaser(mutableListOf(), enableError = IllegalStateException("enable"))
        val enableFailure = localLocator(laser = laser)
        enableFailure.holdLocal("session-1", "event-1")
        val enabled = enableFailure.localize(localRequest(DetectionKind.FIRE))
        assertEquals(
            FireLocalizationFailure.LASER_ENABLE_FAILED,
            (enabled as FireLocalizationResult.DegradedOsd).reason,
        )
        assertEquals(1, laser.disableCalls)

        val aimLaser = SequenceLaser(mutableListOf())
        val aimFailure = localLocator(
            laser = aimLaser,
            aimer = LocalVisibleTargetAimerPort {
                LocalTargetAimResult.Failed(
                    com.yinxin.uavfir.firedetection.LocalTargetAimFailure.TARGET_NOT_REACQUIRED,
                )
            },
        )
        aimFailure.holdLocal("session-1", "event-1")
        assertTrue(
            aimFailure.localize(localRequest(DetectionKind.SMOKE)) is
                FireLocalizationResult.DegradedOsd,
        )
        assertEquals(1, aimLaser.disableCalls)
    }

    @Test
    fun sourceGenerationChangeBeforeEnableDuringSamplesOrBeforePublishNeverReturnsPrecise() = runTest {
        suspend fun runWithGuard(guard: () -> Long?): FireLocalizationResult {
            val locator = localLocator(
                laser = SequenceLaser(normalSamples()),
                generationGuard = guard,
            )
            locator.holdLocal("session-1", "event-1")
            return locator.localize(localRequest(DetectionKind.FIRE))
        }

        assertEquals(
            FireLocalizationFailure.SOURCE_GENERATION_CHANGED,
            (runWithGuard { 8 } as FireLocalizationResult.DegradedOsd).reason,
        )

        var duringCalls = 0
        assertEquals(
            FireLocalizationFailure.SOURCE_GENERATION_CHANGED,
            (runWithGuard { if (++duringCalls < 4) 7 else 8 } as
                FireLocalizationResult.DegradedOsd).reason,
        )

        var finalCalls = 0
        assertEquals(
            FireLocalizationFailure.SOURCE_GENERATION_CHANGED,
            (runWithGuard { if (++finalCalls <= 5) 7 else 8 } as
                FireLocalizationResult.DegradedOsd).reason,
        )
    }

    @Test
    fun mismatchedLocalizeCannotClearLegitimateOwner() = runTest {
        val laser = SequenceLaser(normalSamples())
        val locator = localLocator(laser)
        locator.holdLocal("session-1", "event-1")

        val mismatch = locator.localize(localRequest(DetectionKind.FIRE).copy(eventId = "other"))
        val legitimate = locator.localize(localRequest(DetectionKind.FIRE))

        assertEquals(FireLocalizationFailure.EVENT_SESSION_MISMATCH, mismatch.reason)
        assertTrue(legitimate is FireLocalizationResult.Precise)
        assertEquals(1, laser.disableCalls)
    }

    @Test
    fun activeLocalOperationSerializesHoldAndLegacyRouteWithoutPrematureDisable() = runTest {
        val barrier = CompletableDeferred<Unit>()
        val laser = SequenceLaser(normalSamples(), awaitBarrier = barrier)
        val locator = localLocator(laser)
        locator.holdLocal("session-1", "event-1")
        val local = async { locator.localize(localRequest(DetectionKind.FIRE)) }
        runCurrent()
        val otherHold = async { locator.hold("event-b") }
        val legacy = async { locator.measure("event-1", "task-1", ROI) }
        runCurrent()

        assertFalse(otherHold.isCompleted)
        assertFalse(legacy.isCompleted)
        assertEquals(0, laser.disableCalls)
        barrier.complete(Unit)

        assertTrue(local.await() is FireLocalizationResult.Precise)
        assertEquals("applied", otherHold.await().status)
        assertEquals("failed", legacy.await().status)
        assertEquals(1, laser.disableCalls)
    }

    @Test
    fun repeatedOrCrossOperationCallbacksCannotBecomeThreeFreshSamples() = runTest {
        for (laser in listOf(
            SequenceLaser(normalSamples(), fixedObservationSequence = 1),
            SequenceLaser(normalSamples(), hardwareGenerationOffset = 1),
        )) {
            val locator = localLocator(laser)
            locator.holdLocal("session-1", "event-1")
            val result = locator.localize(localRequest(DetectionKind.FIRE))
            assertTrue(result is FireLocalizationResult.DegradedOsd)
            assertEquals(9, laser.measureCalls)
        }
    }

    @Test
    fun highFrequencyCallbacksSkipEarlyCandidatesAndUseExactIntervalBoundary() = runTest {
        val laser = SequenceLaser(
            results = MutableList(5) {
                LaserRangefinderResult(
                    34.0 + it * 0.000001,
                    109.0 + it * 0.000001,
                    10.0 + it,
                    60.0 + it,
                    "NORMAL",
                    .5,
                    .5,
                )
            },
            sampleOffsetsMs = mutableListOf(0, 100, 300, 400, 600),
        )
        val locator = localLocator(laser)
        locator.holdLocal("session-1", "event-1")

        val result = locator.localize(localRequest(DetectionKind.FIRE))

        assertTrue(result is FireLocalizationResult.Precise)
        result as FireLocalizationResult.Precise
        val firstAt = result.rawSamples.first().sampledAtMonotonicMs
        assertEquals(
            listOf(0L, 300L, 600L),
            result.rawSamples.map { it.sampledAtMonotonicMs - firstAt },
        )
        assertEquals(5, laser.measureCalls)
    }

    @Test
    fun cancellationDuringMissionHoldClearsOwnerAndDoesNotIssueHover() = runTest {
        val gate = CompletableDeferred<Unit>()
        val mission = CancellableMissionHold(gate, firstResult = true)
        val flight = RecordingFlightControl()
        val locator = VisibleFireLaserLocator(
            missionHold = mission,
            flightControl = flight,
            velocityProvider = SequenceVelocityProvider(MutableList(12) { VelocitySample(.1, .1) }),
            time = AdvancingTime(),
        )
        val first = launch { locator.holdLocal("session-1", "event-1") }
        runCurrent()

        first.cancelAndJoin()

        assertTrue("cancellation must not continue into hover", flight.actions.isEmpty())
        assertEquals("applied", locator.holdLocal("session-1", "event-2").status)
    }

    @Test
    fun cancellationDuringHoverClearsOwnerAndAllowsRetry() = runTest {
        val hoverGate = CompletableDeferred<Unit>()
        val flight = CancellableHoverFlightControl(hoverGate)
        val locator = VisibleFireLaserLocator(
            missionHold = RecordingMissionHold(false),
            flightControl = flight,
            velocityProvider = SequenceVelocityProvider(MutableList(12) { VelocitySample(.1, .1) }),
            time = AdvancingTime(),
        )
        val first = launch { locator.holdLocal("session-1", "event-1") }
        runCurrent()

        first.cancelAndJoin()

        assertEquals("applied", locator.holdLocal("session-1", "event-2").status)
    }

    @Test
    fun cancellationDuringVelocityDelayClearsOwnerAndAllowsRetry() = runTest {
        val delayGate = CompletableDeferred<Unit>()
        val time = CancellableFirstDelayTime(delayGate)
        val locator = VisibleFireLaserLocator(
            missionHold = RecordingMissionHold(true),
            flightControl = RecordingFlightControl(),
            velocityProvider = SequenceVelocityProvider(MutableList(12) { VelocitySample(.1, .1) }),
            time = time,
        )
        val first = launch { locator.holdLocal("session-1", "event-1") }
        runCurrent()

        first.cancelAndJoin()

        assertEquals("applied", locator.holdLocal("session-1", "event-2").status)
    }

    private fun normalSamples() = mutableListOf(
        LaserRangefinderResult(34.0, 109.0, 10.0, 60.0, "NORMAL", .5, .5),
        LaserRangefinderResult(34.00001, 109.00001, 11.0, 61.0, "NORMAL", .5, .5),
        LaserRangefinderResult(34.00002, 109.00002, 12.0, 62.0, "NORMAL", .5, .5),
    )

    private fun localRequest(kind: DetectionKind) = FireLocalizationRequest(
        sessionId = "session-1",
        eventId = "event-1",
        taskId = "task-1",
        kind = kind,
        initialRoi = NormalizedRoi(.4f, .4f, .6f, .6f),
    )

    private fun localLocator(
        laser: SequenceLaser,
        osd: AircraftOsdSnapshot? = AircraftOsdSnapshot(34.1, 109.1, 100.0, 1, 0),
        aimer: LocalVisibleTargetAimerPort = LocalVisibleTargetAimerPort {
            LocalTargetAimResult.Aligned(
                kind = it.kind,
                roi = it.priorRoi,
                sourceGeneration = 7,
                detectionCapturedAtMonotonicMs = 100,
                cycles = 1,
            )
        },
        generationGuard: () -> Long? = { 7 },
    ): VisibleFireLaserLocator = VisibleFireLaserLocator(
        missionHold = RecordingMissionHold(true),
        flightControl = RecordingFlightControl(),
        velocityProvider = SequenceVelocityProvider(MutableList(6) { VelocitySample(.1, .1) }),
        time = AdvancingTime(),
        localTargetAimer = aimer,
        aircraftOsdProvider = { osd },
        laserRangefinder = laser,
        laserObservationClient = laser,
        sourceGenerationGuard = generationGuard,
    )

    private fun measuringFixture(
        laserResults: MutableList<LaserRangefinderResult> = mutableListOf(),
    ): MeasuringFixture {
        val mission = RecordingMissionHold(active = true)
        val laser = SequenceLaser(laserResults)
        val aimer = RecordingAimer()
        val locator = VisibleFireLaserLocator(
            missionHold = mission,
            flightControl = RecordingFlightControl(),
            velocityProvider = SequenceVelocityProvider(
                MutableList(6) { VelocitySample(0.1, 0.1) },
            ),
            time = AdvancingTime(),
            targetAimer = aimer,
            laserRangefinder = laser,
        )
        return MeasuringFixture(locator, mission, laser, aimer)
    }

    private data class MeasuringFixture(
        val locator: VisibleFireLaserLocator,
        val mission: RecordingMissionHold,
        val laser: SequenceLaser,
        val aimer: RecordingAimer,
    )

    private class RecordingAimer : VisibleTargetAimer {
        var calls = 0

        override suspend fun align(taskId: String, visibleRoi: Map<String, Double>): Boolean {
            calls += 1
            return true
        }
    }

    private class SequenceLaser(
        private val results: MutableList<LaserRangefinderResult>,
        private val error: Throwable? = null,
        private val enableError: Throwable? = null,
        private val awaitBarrier: CompletableDeferred<Unit>? = null,
        private val fixedObservationSequence: Long? = null,
        private val hardwareGenerationOffset: Long = 0,
        private val sampleOffsetsMs: MutableList<Long>? = null,
        private val onBegin: () -> Unit = {},
    ) : LaserRangefinderClient, BoundLaserObservationClient {
        var disableCalls = 0
        var enableCalls = 0
        var measureCalls = 0
        private var observationSequence = 0L
        private var activeBinding: LaserOperationBinding? = null

        override suspend fun enable() {
            enableCalls += 1
            enableError?.let { throw it }
        }

        override suspend fun measure(): LaserRangefinderResult? {
            measureCalls += 1
            error?.let { throw it }
            return if (results.isEmpty()) null else results.removeAt(0)
        }

        override suspend fun disable() {
            disableCalls += 1
        }

        override suspend fun beginOperation(binding: LaserOperationBinding): LaserHardwareOperationToken {
            onBegin()
            enableCalls += 1
            enableError?.let { throw it }
            activeBinding = binding
            return LaserHardwareOperationToken(binding, binding.operationGeneration, 0, binding.windowStartedAtMonotonicMs)
        }

        override suspend fun awaitNext(
            token: LaserHardwareOperationToken,
            afterObservationSequence: Long,
            timeoutMs: Long,
        ): LaserHardwareAwaitResult {
            measureCalls += 1
            if (measureCalls == 1) awaitBarrier?.await()
            error?.let { throw it }
            if (results.isEmpty()) return LaserHardwareAwaitResult.Timeout
            val result = results.removeAt(0)
            observationSequence = fixedObservationSequence ?: observationSequence + 1
            val sampledAt = sampleOffsetsMs?.removeAt(0) ?: (observationSequence - 1) * 300
            return LaserHardwareAwaitResult.Observed(
                BoundLaserSample(
                    binding = token.binding,
                    hardwareOperationGeneration =
                        token.hardwareOperationGeneration + hardwareGenerationOffset,
                    observationSequence = observationSequence,
                    sampledAtMonotonicMs = token.binding.windowStartedAtMonotonicMs + sampledAt,
                    measurement = result,
                ),
            )
        }

        override suspend fun endOperation(token: LaserHardwareOperationToken) {
            disableCalls += 1
            activeBinding = null
        }
    }

    private class RecordingMissionHold(
        private val active: Boolean,
    ) : MissionHoldControl {
        var holdCalls = 0
        var resumeCalled = false

        override suspend fun holdForConfirmation(): Boolean {
            holdCalls += 1
            return active
        }

        override suspend fun resumeAfterConfirmation() {
            resumeCalled = true
        }
    }

    private class CancellableMissionHold(
        private val firstGate: CompletableDeferred<Unit>,
        private val firstResult: Boolean,
    ) : MissionHoldControl {
        private var calls = 0

        override suspend fun holdForConfirmation(): Boolean {
            calls += 1
            if (calls == 1) firstGate.await()
            return firstResult
        }

        override suspend fun resumeAfterConfirmation() = Unit
    }

    private class SequenceVelocityProvider(
        private val values: MutableList<VelocitySample>,
        private val fallback: VelocitySample? = values.lastOrNull(),
    ) : AircraftVelocityProvider {
        override fun current(): VelocitySample? =
            if (values.isNotEmpty()) values.removeAt(0) else fallback
    }

    private class AdvancingTime : VisibleFireTime {
        var nowMs = 0L

        override fun nowMs(): Long = nowMs

        override suspend fun delayMs(durationMs: Long) {
            nowMs += durationMs
        }
    }

    private class CancellableFirstDelayTime(
        private val firstGate: CompletableDeferred<Unit>,
    ) : VisibleFireTime {
        private var first = true
        private var current = 0L

        override fun nowMs(): Long = current

        override suspend fun delayMs(durationMs: Long) {
            if (first) {
                first = false
                firstGate.await()
            }
            current += durationMs
        }
    }

    private class RecordingFlightControl : FlightControlActionClient {
        val actions = mutableListOf<String>()

        override suspend fun hover() {
            actions += "hover"
        }

        override suspend fun startTakeoff() = Unit
        override suspend fun startGoHome() = Unit
        override suspend fun stopGoHome() = Unit
        override suspend fun startAutoLanding() = Unit
        override suspend fun stopAutoLanding() = Unit
        override suspend fun emergencyStop() = Unit
        override suspend fun stopFlyToPoint() = Unit
        override suspend fun sendVirtualStick(key: String, durationMs: Long) = Unit
        override suspend fun flyToPoint(
            latitude: Double,
            longitude: Double,
            height: Double,
            speed: Double,
        ) = Unit
        override suspend fun setNavigationLight(enabled: Boolean) = Unit
    }

    private class CancellableHoverFlightControl(
        private val firstGate: CompletableDeferred<Unit>,
    ) : FlightControlActionClient {
        private var hoverCalls = 0

        override suspend fun hover() {
            hoverCalls += 1
            if (hoverCalls == 1) firstGate.await()
        }

        override suspend fun startTakeoff() = Unit
        override suspend fun startGoHome() = Unit
        override suspend fun stopGoHome() = Unit
        override suspend fun startAutoLanding() = Unit
        override suspend fun stopAutoLanding() = Unit
        override suspend fun emergencyStop() = Unit
        override suspend fun stopFlyToPoint() = Unit
        override suspend fun sendVirtualStick(key: String, durationMs: Long) = Unit
        override suspend fun flyToPoint(latitude: Double, longitude: Double, height: Double, speed: Double) = Unit
        override suspend fun setNavigationLight(enabled: Boolean) = Unit
    }

    companion object {
        private val ROI = mapOf(
            "x" to 0.4,
            "y" to 0.3,
            "width" to 0.2,
            "height" to 0.2,
        )
    }
}
