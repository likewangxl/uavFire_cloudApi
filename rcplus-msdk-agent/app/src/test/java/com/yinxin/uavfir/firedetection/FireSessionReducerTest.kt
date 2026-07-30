package com.yinxin.uavfir.firedetection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FireSessionReducerTest {
    private val reducer = FireSessionReducer()
    private val requestedTransitions = listOf(
        FireSessionEvent.ArmRequested,
        FireSessionEvent.Armed,
        FireSessionEvent.CandidateObserved,
        FireSessionEvent.CandidateCleared,
        FireSessionEvent.VisualConfirmed(confirmation()),
        FireSessionEvent.InitialAlertDurable,
        FireSessionEvent.MissionPaused,
        FireSessionEvent.HoverStable,
        FireSessionEvent.TargetAligned,
        terminalReady(),
        degradedReady(),
        FireSessionEvent.TerminalResultReady(
            TerminalPersistenceRequest("invalid-locating", LocationStatus.LASER_LOCATING, GeoMethod.LASER_RANGEFINDER),
        ),
        FireSessionEvent.TerminalResultReady(
            TerminalPersistenceRequest("invalid-method", LocationStatus.PRECISE, GeoMethod.AIRCRAFT_OBSERVATION),
        ),
        FireSessionEvent.TerminalResultDurable("precise-1"),
        FireSessionEvent.ResumeRequested,
        FireSessionEvent.MissionResumeConfirmed,
        FireSessionEvent.ScanContinued,
        FireSessionEvent.RecoveryVerified,
        FireSessionEvent.DisarmRequested,
    )

    @Test
    fun canonicalNormalPathHasTypedDeterministicEffects() {
        val cases = listOf(
            Case(FireSessionState.DISARMED, FireSessionEvent.ArmRequested, FireSessionState.ARMING),
            Case(FireSessionState.ARMING, FireSessionEvent.Armed, FireSessionState.SCANNING),
            Case(FireSessionState.SCANNING, FireSessionEvent.CandidateObserved, FireSessionState.VISUAL_CONFIRMING),
            Case(
                FireSessionState.VISUAL_CONFIRMING,
                FireSessionEvent.VisualConfirmed(confirmation()),
                FireSessionState.VISUAL_CONFIRMED,
                listOf(FireSessionEffect.PersistInitialAlert(confirmation())),
            ),
            Case(
                FireSessionState.VISUAL_CONFIRMED,
                FireSessionEvent.InitialAlertDurable,
                FireSessionState.HOLD_REQUESTED,
                listOf(FireSessionEffect.PauseMission),
            ),
            Case(FireSessionState.HOLD_REQUESTED, FireSessionEvent.MissionPaused, FireSessionState.HOVER_VERIFYING),
            Case(
                FireSessionState.HOVER_VERIFYING,
                FireSessionEvent.HoverStable,
                FireSessionState.TARGET_ALIGNING,
                listOf(FireSessionEffect.AlignTarget),
            ),
            Case(
                FireSessionState.TARGET_ALIGNING,
                FireSessionEvent.TargetAligned,
                FireSessionState.LASER_MEASURING,
                listOf(FireSessionEffect.MeasureLaser),
            ),
            Case(
                FireSessionState.LASER_MEASURING,
                terminalReady(),
                FireSessionState.LASER_MEASURING,
                listOf(
                    FireSessionEffect.PersistTerminalResult(
                        terminalRequest(),
                    ),
                ),
            ),
            Case(
                FireSessionState.RESUME_REQUESTED,
                FireSessionEvent.MissionResumeConfirmed,
                FireSessionState.MISSION_RESUMED,
            ),
            Case(FireSessionState.MISSION_RESUMED, FireSessionEvent.ScanContinued, FireSessionState.SCANNING),
        )

        cases.forEach { case ->
            val actual = reducer.reduce(case.from, case.event)
            assertTrue("$case must be accepted", actual.accepted)
            assertEquals(case.to, actual.state)
            assertEquals(case.effects, actual.effects)
            assertEquals(actual, reducer.reduce(case.from, case.event))
        }

        val requested = reducer.reduce(FireSessionState.LASER_MEASURING, terminalReady())
        val durable = reducer.reduce(requested.phase, FireSessionEvent.TerminalResultDurable("precise-1"))
        assertTrue(durable.accepted)
        assertEquals(FireSessionState.RESULT_DURABLE, durable.state)
        val resume = reducer.reduce(durable.phase, FireSessionEvent.ResumeRequested)
        assertTrue(resume.accepted)
        assertEquals(listOf(FireSessionEffect.ResumeMission), resume.effects)
    }

    @Test
    fun transitionMatrixCoversEveryStateAsSourceAndRejectsEveryOtherRequestedEdge() {
        val legal = legalTransitions()
        assertEquals(FireSessionState.entries.toSet(), legal.keys)

        FireSessionState.entries.forEach { source ->
            requestedTransitions.forEach { event ->
                val result = reducer.reduce(source, event)
                val expected = legal.getValue(source)[event]
                if (expected == null) {
                    assertFalse("$source must reject $event", result.accepted)
                    assertEquals(source, result.state)
                    assertTrue(result.effects.isEmpty())
                } else {
                    assertTrue("$source must accept $event", result.accepted)
                    assertEquals(expected, result.state)
                }
            }
        }
    }

    @Test
    fun unsafeSignalsAlwaysEnterManualHoldAndManualHoldNeedsExplicitExit() {
        val failures = listOf(
            FireSessionEvent.UnsafeFailure(FireSessionFailure.UNKNOWN_MISSION_STATE),
            FireSessionEvent.UnsafeFailure(FireSessionFailure.MISSING_BREAKPOINT),
            FireSessionEvent.UnsafeFailure(FireSessionFailure.MANUAL_INTERVENTION),
            FireSessionEvent.UnsafeFailure(FireSessionFailure.STORAGE_FAILURE),
            FireSessionEvent.UnsafeFailure(FireSessionFailure.DETECTOR_FAILURE),
            FireSessionEvent.UnsafeFailure(FireSessionFailure.RESUME_FAILURE),
        )
        FireSessionState.entries.filterNot { it == FireSessionState.MANUAL_HOLD }.forEach { source ->
            failures.forEach { failure ->
                assertEquals(FireSessionState.MANUAL_HOLD, reducer.reduce(source, failure).state)
            }
        }

        assertFalse(reducer.reduce(FireSessionState.MANUAL_HOLD, FireSessionEvent.ResumeRequested).accepted)
        assertEquals(
            FireSessionState.MANUAL_HOLD,
            reducer.reduce(
                FireSessionState.MANUAL_HOLD,
                FireSessionEvent.UnsafeFailure(FireSessionFailure.MANUAL_INTERVENTION),
            ).state,
        )
        assertEquals(
            FireSessionState.SCANNING,
            reducer.reduce(FireSessionState.MANUAL_HOLD, FireSessionEvent.RecoveryVerified).state,
        )
        assertEquals(
            FireSessionState.DISARMED,
            reducer.reduce(FireSessionState.MANUAL_HOLD, FireSessionEvent.DisarmRequested).state,
        )
    }

    @Test
    fun pauseAndResumeEffectsCannotOccurBeforeDurabilitySignals() {
        val beforeInitial = reducer.reduce(FireSessionState.VISUAL_CONFIRMED, FireSessionEvent.MissionPaused)
        assertFalse(beforeInitial.accepted)
        assertFalse(beforeInitial.effects.contains(FireSessionEffect.PauseMission))

        val beforeTerminal = reducer.reduce(FireSessionState.LASER_MEASURING, FireSessionEvent.ResumeRequested)
        assertFalse(beforeTerminal.accepted)
        assertFalse(beforeTerminal.effects.contains(FireSessionEffect.ResumeMission))

        val degraded = reducer.reduce(
            FireSessionState.LASER_MEASURING,
            degradedReady(),
        )
        assertEquals(
            listOf(
                FireSessionEffect.PersistTerminalResult(
                    degradedRequest(),
                ),
            ),
            degraded.effects,
        )
    }

    private fun legalTransitions(): Map<FireSessionState, Map<FireSessionEvent, FireSessionState>> {
        val map = FireSessionState.entries.associateWith { mutableMapOf<FireSessionEvent, FireSessionState>() }
        map.getValue(FireSessionState.DISARMED)[FireSessionEvent.ArmRequested] = FireSessionState.ARMING
        map.getValue(FireSessionState.ARMING)[FireSessionEvent.Armed] = FireSessionState.SCANNING
        map.getValue(FireSessionState.SCANNING)[FireSessionEvent.CandidateObserved] =
            FireSessionState.VISUAL_CONFIRMING
        map.getValue(FireSessionState.VISUAL_CONFIRMING)[FireSessionEvent.CandidateCleared] =
            FireSessionState.SCANNING
        val matrixVisualConfirmed =
            requestedTransitions.filterIsInstance<FireSessionEvent.VisualConfirmed>().single()
        map.getValue(FireSessionState.VISUAL_CONFIRMING)[matrixVisualConfirmed] =
            FireSessionState.VISUAL_CONFIRMED
        map.getValue(FireSessionState.VISUAL_CONFIRMED)[FireSessionEvent.InitialAlertDurable] =
            FireSessionState.HOLD_REQUESTED
        map.getValue(FireSessionState.HOLD_REQUESTED)[FireSessionEvent.MissionPaused] =
            FireSessionState.HOVER_VERIFYING
        map.getValue(FireSessionState.HOVER_VERIFYING)[FireSessionEvent.HoverStable] =
            FireSessionState.TARGET_ALIGNING
        map.getValue(FireSessionState.TARGET_ALIGNING)[FireSessionEvent.TargetAligned] =
            FireSessionState.LASER_MEASURING
        map.getValue(FireSessionState.LASER_MEASURING)[terminalReady()] = FireSessionState.LASER_MEASURING
        map.getValue(FireSessionState.LASER_MEASURING)[degradedReady()] = FireSessionState.LASER_MEASURING
        map.getValue(FireSessionState.RESUME_REQUESTED)[FireSessionEvent.MissionResumeConfirmed] =
            FireSessionState.MISSION_RESUMED
        map.getValue(FireSessionState.MISSION_RESUMED)[FireSessionEvent.ScanContinued] =
            FireSessionState.SCANNING
        map.getValue(FireSessionState.MANUAL_HOLD)[FireSessionEvent.RecoveryVerified] =
            FireSessionState.SCANNING
        FireSessionState.entries.forEach { state ->
            map.getValue(state)[FireSessionEvent.DisarmRequested] = FireSessionState.DISARMED
        }
        return map
    }

    private fun confirmation() = VisibleConfirmation(
        kind = DetectionKind.FIRE,
        confidence = 0.9f,
        roi = NormalizedRoi(0.2f, 0.2f, 0.4f, 0.4f),
        firstFrameTimestampMillis = 1_000,
        secondFrameTimestampMillis = 1_100,
        policyVersion = "agent-visible-v1",
    )

    @Test
    fun terminalDurabilityRequiresMatchingPendingRequestAndRejectsConflicts() {
        val beforeRequest = reducer.reduce(
            FireSessionState.LASER_MEASURING,
            FireSessionEvent.TerminalResultDurable("precise-1"),
        )
        assertFalse(beforeRequest.accepted)

        val pending = reducer.reduce(FireSessionState.LASER_MEASURING, terminalReady())
        assertTrue(pending.accepted)
        assertEquals(terminalRequest(), pending.phase.pendingTerminal)
        assertFalse(reducer.reduce(pending.phase, terminalReady()).accepted)
        assertFalse(reducer.reduce(pending.phase, degradedReady()).accepted)
        assertFalse(
            reducer.reduce(pending.phase, FireSessionEvent.TerminalResultDurable("wrong-request")).accepted,
        )

        val durable = reducer.reduce(
            pending.phase,
            FireSessionEvent.TerminalResultDurable(terminalRequest().requestId),
        )
        assertEquals(FireSessionState.RESULT_DURABLE, durable.state)
        assertTrue(durable.phase.terminalPersistenceVerified)
        assertEquals(terminalRequest(), durable.phase.durableTerminal)
        assertFalse(
            reducer.reduce(durable.phase, FireSessionEvent.TerminalResultDurable("precise-1")).accepted,
        )
        assertFalse(reducer.reduce(FireSessionState.RESULT_DURABLE, FireSessionEvent.ResumeRequested).accepted)
        assertTrue(reducer.reduce(durable.phase, FireSessionEvent.ResumeRequested).accepted)
    }

    @Test
    fun confirmationTimestampsAreDefensivelyImmutableAcrossEffects() {
        val confirmation = confirmation()
        val mutableView = confirmation.frameTimestampsMillis as MutableList<Long>
        val reduction = reducer.reduce(
            FireSessionState.VISUAL_CONFIRMING,
            FireSessionEvent.VisualConfirmed(confirmation),
        )
        mutableView[0] = 999_999

        assertEquals(listOf(1_000L, 1_100L), confirmation.frameTimestampsMillis)
        val persisted = reduction.effects.single() as FireSessionEffect.PersistInitialAlert
        assertEquals(1_000L, persisted.confirmation.firstFrameTimestampMillis)
        assertEquals(1_100L, persisted.confirmation.secondFrameTimestampMillis)
    }

    private fun terminalRequest() = TerminalPersistenceRequest(
        requestId = "precise-1",
        locationStatus = LocationStatus.PRECISE,
        geoMethod = GeoMethod.LASER_RANGEFINDER,
    )

    private fun degradedRequest() = TerminalPersistenceRequest(
        requestId = "degraded-1",
        locationStatus = LocationStatus.DEGRADED_OSD,
        geoMethod = GeoMethod.AIRCRAFT_OBSERVATION,
    )

    private fun terminalReady() = FireSessionEvent.TerminalResultReady(terminalRequest())

    private fun degradedReady() = FireSessionEvent.TerminalResultReady(degradedRequest())

    private data class Case(
        val from: FireSessionState,
        val event: FireSessionEvent,
        val to: FireSessionState,
        val effects: List<FireSessionEffect> = emptyList(),
    )
}
