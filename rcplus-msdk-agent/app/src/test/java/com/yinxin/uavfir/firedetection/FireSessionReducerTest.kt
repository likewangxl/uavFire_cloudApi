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
        FireSessionEvent.VisualConfirmed(initialRequest()),
        FireSessionEvent.InitialAlertDurable(initialRequest()),
        FireSessionEvent.MissionPaused,
        FireSessionEvent.HoverStable,
        FireSessionEvent.TargetAligned,
        terminalReady(),
        degradedReady(),
        FireSessionEvent.TerminalResultReady(
            terminalRequest(locationStatus = LocationStatus.LASER_LOCATING),
        ),
        FireSessionEvent.TerminalResultReady(
            terminalRequest(geoMethod = GeoMethod.AIRCRAFT_OBSERVATION),
        ),
        FireSessionEvent.TerminalResultDurable(terminalRequest()),
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
                FireSessionEvent.VisualConfirmed(initialRequest()),
                FireSessionState.VISUAL_CONFIRMED,
                listOf(FireSessionEffect.PersistInitialAlert(initialRequest())),
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

        val visual = reducer.reduce(
            FireSessionState.VISUAL_CONFIRMING,
            FireSessionEvent.VisualConfirmed(initialRequest()),
        )
        val held = reducer.reduce(visual.phase, FireSessionEvent.InitialAlertDurable(initialRequest()))
        assertEquals(listOf(FireSessionEffect.PauseMission), held.effects)
        var phase = reducer.reduce(held.phase, FireSessionEvent.MissionPaused).phase
        phase = reducer.reduce(phase, FireSessionEvent.HoverStable).phase
        phase = reducer.reduce(phase, FireSessionEvent.TargetAligned).phase
        val requested = reducer.reduce(phase, terminalReady())
        assertEquals(listOf(FireSessionEffect.PersistTerminalResult(terminalRequest())), requested.effects)
        val durable = reducer.reduce(
            requested.phase,
            FireSessionEvent.TerminalResultDurable(terminalRequest()),
        )
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

        val degraded = reducer.reduce(laserPhase(), degradedReady())
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
        map.getValue(FireSessionState.HOLD_REQUESTED)[FireSessionEvent.MissionPaused] =
            FireSessionState.HOVER_VERIFYING
        map.getValue(FireSessionState.HOVER_VERIFYING)[FireSessionEvent.HoverStable] =
            FireSessionState.TARGET_ALIGNING
        map.getValue(FireSessionState.TARGET_ALIGNING)[FireSessionEvent.TargetAligned] =
            FireSessionState.LASER_MEASURING
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
            FireSessionEvent.TerminalResultDurable(terminalRequest()),
        )
        assertFalse(beforeRequest.accepted)

        val pending = reducer.reduce(laserPhase(), terminalReady())
        assertTrue(pending.accepted)
        assertFalse(reducer.reduce(pending.phase, terminalReady()).accepted)
        assertFalse(reducer.reduce(pending.phase, degradedReady()).accepted)
        assertFalse(
            reducer.reduce(
                pending.phase,
                FireSessionEvent.TerminalResultDurable(terminalRequest(requestId = REQUEST_ID_3)),
            ).accepted,
        )

        val durable = reducer.reduce(
            pending.phase,
            FireSessionEvent.TerminalResultDurable(terminalRequest()),
        )
        assertEquals(FireSessionState.RESULT_DURABLE, durable.state)
        assertFalse(
            reducer.reduce(
                durable.phase,
                FireSessionEvent.TerminalResultDurable(terminalRequest()),
            ).accepted,
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
            FireSessionEvent.VisualConfirmed(initialRequest(confirmation = confirmation)),
        )
        mutableView[0] = 999_999

        assertEquals(listOf(1_000L, 1_100L), confirmation.frameTimestampsMillis)
        val persisted = reduction.effects.single() as FireSessionEffect.PersistInitialAlert
        assertEquals(1_000L, persisted.request.confirmation.firstFrameTimestampMillis)
        assertEquals(1_100L, persisted.request.confirmation.secondFrameTimestampMillis)
    }

    @Test
    fun phaseProofIsOpaqueAndCannotBeForgedThroughPublicApi() {
        assertTrue(FireSessionPhase::class.java.isInterface)
        assertTrue(FireSessionPhase::class.java.declaredConstructors.isEmpty())
        assertFalse(FireSessionPhase::class.java.methods.any { it.name == "copy" })
        val forged = object : FireSessionPhase {
            override val state = FireSessionState.RESULT_DURABLE
        }
        assertFalse(reducer.reduce(forged, FireSessionEvent.ResumeRequested).accepted)
    }

    @Test
    fun initialDurabilityRequiresExactSessionEventAndRequestIdentity() {
        assertFalse(
            reducer.reduce(
                FireSessionState.VISUAL_CONFIRMED,
                FireSessionEvent.InitialAlertDurable(initialRequest()),
            ).accepted,
        )
        val pending = reducer.reduce(
            FireSessionState.VISUAL_CONFIRMING,
            FireSessionEvent.VisualConfirmed(initialRequest()),
        )
        listOf(
            initialRequest(sessionId = "other-session"),
            initialRequest(eventId = "other-event"),
            initialRequest(requestId = REQUEST_ID_2),
        ).forEach { stale ->
            assertFalse(reducer.reduce(pending.phase, FireSessionEvent.InitialAlertDurable(stale)).accepted)
        }
        assertTrue(
            reducer.reduce(
                pending.phase,
                FireSessionEvent.InitialAlertDurable(initialRequest()),
            ).accepted,
        )
    }

    @Test
    fun terminalDurabilityComparesExactRequestNotOnlyRequestId() {
        val pending = reducer.reduce(laserPhase(), terminalReady())
        listOf(
            terminalRequest(sessionId = "other-session"),
            terminalRequest(eventId = "other-event"),
            terminalRequest(locationStatus = LocationStatus.DEGRADED_OSD),
            terminalRequest(geoMethod = GeoMethod.AIRCRAFT_OBSERVATION),
        ).forEach { collision ->
            assertFalse(
                reducer.reduce(
                    pending.phase,
                    FireSessionEvent.TerminalResultDurable(collision),
                ).accepted,
            )
        }
        assertTrue(
            reducer.reduce(
                pending.phase,
                FireSessionEvent.TerminalResultDurable(terminalRequest()),
            ).accepted,
        )
    }

    @Test
    fun persistenceRequestIdsMustBeCollisionResistantUuids() {
        listOf("", "precise-1", "11111111-1111-1111-1111-111111111111").forEach { invalid ->
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                initialRequest(requestId = invalid)
            }
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                terminalRequest(requestId = invalid)
            }
        }
    }

    private fun initialRequest(
        sessionId: String = SESSION_ID,
        eventId: String = EVENT_ID,
        requestId: String = REQUEST_ID_1,
        confirmation: VisibleConfirmation = confirmation(),
    ) = InitialPersistenceRequest(
        sessionId = sessionId,
        eventId = eventId,
        requestId = requestId,
        confirmation = confirmation,
    )

    private fun terminalRequest(
        sessionId: String = SESSION_ID,
        eventId: String = EVENT_ID,
        requestId: String = REQUEST_ID_2,
        locationStatus: LocationStatus = LocationStatus.PRECISE,
        geoMethod: GeoMethod = GeoMethod.LASER_RANGEFINDER,
    ) = TerminalPersistenceRequest(
        sessionId = sessionId,
        eventId = eventId,
        requestId = requestId,
        locationStatus = locationStatus,
        geoMethod = geoMethod,
    )

    private fun degradedRequest() = TerminalPersistenceRequest(
        sessionId = SESSION_ID,
        eventId = EVENT_ID,
        requestId = REQUEST_ID_3,
        locationStatus = LocationStatus.DEGRADED_OSD,
        geoMethod = GeoMethod.AIRCRAFT_OBSERVATION,
    )

    private fun terminalReady() = FireSessionEvent.TerminalResultReady(terminalRequest())

    private fun degradedReady() = FireSessionEvent.TerminalResultReady(degradedRequest())

    private fun laserPhase(): FireSessionPhase {
        val visual = reducer.reduce(
            FireSessionState.VISUAL_CONFIRMING,
            FireSessionEvent.VisualConfirmed(initialRequest()),
        )
        var phase = reducer.reduce(
            visual.phase,
            FireSessionEvent.InitialAlertDurable(initialRequest()),
        ).phase
        phase = reducer.reduce(phase, FireSessionEvent.MissionPaused).phase
        phase = reducer.reduce(phase, FireSessionEvent.HoverStable).phase
        return reducer.reduce(phase, FireSessionEvent.TargetAligned).phase
    }

    private data class Case(
        val from: FireSessionState,
        val event: FireSessionEvent,
        val to: FireSessionState,
        val effects: List<FireSessionEffect> = emptyList(),
    )

    private companion object {
        const val SESSION_ID = "session-1"
        const val EVENT_ID = "event-1"
        const val REQUEST_ID_1 = "11111111-1111-4111-8111-111111111111"
        const val REQUEST_ID_2 = "22222222-2222-4222-8222-222222222222"
        const val REQUEST_ID_3 = "33333333-3333-4333-8333-333333333333"
    }
}
