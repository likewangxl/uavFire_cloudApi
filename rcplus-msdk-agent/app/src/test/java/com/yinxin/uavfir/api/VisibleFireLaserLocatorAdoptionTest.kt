package com.yinxin.uavfir.api

import com.yinxin.uavfir.firedetection.FireControlSessionKey
import com.yinxin.uavfir.firedetection.HoverControlBinding
import com.yinxin.uavfir.firedetection.MissionBreakpoint
import com.yinxin.uavfir.firedetection.MissionExecutionKey
import com.yinxin.uavfir.firedetection.MissionHoldToken
import com.yinxin.uavfir.firedetection.MissionIdentity
import com.yinxin.uavfir.firedetection.StableHoverEvidence
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisibleFireLaserLocatorAdoptionTest {
    private val control = FireControlSessionKey("session-1", 41)
    private val mission = MissionExecutionKey(MissionIdentity("mission-1", "route.kmz"), 7)
    private val breakpoint = MissionBreakpoint(0, 3, 0.25)
    private val token = MissionHoldToken(mission, breakpoint, pausedCommandGeneration = 9, holdGeneration = 2)
    private val evidence = StableHoverEvidence(
        binding = HoverControlBinding(control, mission, pausedCommandGeneration = 9),
        hoverEpoch = 3,
        issuedAtMonotonicMs = 1_000,
        nonce = 5,
    )

    @Test
    fun adoptVerifiedHoldRegistersExactLocalOwnerWithoutIssuingFlightCommands() = runTest {
        val missionHold = RecordingMissionHold()
        val flight = RecordingFlightControl()
        val laser = RecordingLaser()
        val locator = VisibleFireLaserLocator(missionHold, flight, laserRangefinder = laser)

        val result = locator.adoptVerifiedHold(
            missionToken = token,
            stableHoverEvidence = evidence,
            controlSession = control,
            sessionId = "session-1",
            eventId = "event-1",
            generation = 41,
        )

        assertEquals(VerifiedHoldAdoptionResult.Adopted, result)
        assertEquals(0, missionHold.calls)
        assertEquals(0, flight.hoverCalls)
        assertTrue(
            locator.forceSafeCleanup(
                controlSession = control,
                sessionId = "session-1",
                eventId = "event-1",
                generation = 41,
            ),
        )
        assertEquals(1, laser.disableCalls)
    }

    @Test
    fun adoptVerifiedHoldRejectsEveryCrossSessionOrMissionBinding() = runTest {
        suspend fun rejected(
            missionToken: MissionHoldToken = token,
            stable: StableHoverEvidence = evidence,
            controlSession: FireControlSessionKey = control,
            sessionId: String = "session-1",
            eventId: String = "event-1",
            generation: Long = 41,
        ): VerifiedHoldAdoptionResult {
            val locator = VisibleFireLaserLocator(
                RecordingMissionHold(),
                RecordingFlightControl(),
            )
            return locator.adoptVerifiedHold(
                missionToken,
                stable,
                controlSession,
                sessionId,
                eventId,
                generation,
            )
        }

        assertTrue(rejected(sessionId = "other") is VerifiedHoldAdoptionResult.Rejected)
        assertTrue(rejected(eventId = " ") is VerifiedHoldAdoptionResult.Rejected)
        assertTrue(rejected(generation = 42) is VerifiedHoldAdoptionResult.Rejected)
        assertTrue(
            rejected(
                missionToken = token.copy(breakpoint = breakpoint.copy(segmentProgress = 2.0)),
            ) is VerifiedHoldAdoptionResult.Rejected,
        )
        assertTrue(
            rejected(controlSession = FireControlSessionKey("session-1", 42)) is
                VerifiedHoldAdoptionResult.Rejected,
        )
        val otherMission = MissionExecutionKey(MissionIdentity("mission-2", "other.kmz"), 8)
        assertTrue(
            rejected(
                missionToken = token.copy(mission = otherMission),
            ) is VerifiedHoldAdoptionResult.Rejected,
        )
        assertTrue(
            rejected(
                missionToken = token.copy(pausedCommandGeneration = 10),
            ) is VerifiedHoldAdoptionResult.Rejected,
        )
        val otherEvidence = StableHoverEvidence(
            HoverControlBinding(FireControlSessionKey("other", 41), mission, 9),
            hoverEpoch = 3,
            issuedAtMonotonicMs = 1_000,
            nonce = 6,
        )
        assertTrue(rejected(stable = otherEvidence) is VerifiedHoldAdoptionResult.Rejected)
    }

    @Test
    fun staleCleanupCannotClearOrDisableAnotherOwner() = runTest {
        val laser = RecordingLaser()
        val locator = VisibleFireLaserLocator(
            RecordingMissionHold(),
            RecordingFlightControl(),
            laserRangefinder = laser,
        )
        assertEquals(
            VerifiedHoldAdoptionResult.Adopted,
            locator.adoptVerifiedHold(token, evidence, control, "session-1", "event-1", 41),
        )

        assertFalse(
            locator.forceSafeCleanup(control, "session-1", "stale-event", 41),
        )
        assertEquals(0, laser.disableCalls)
        assertFalse(
            locator.forceSafeCleanup(
                FireControlSessionKey("session-1", 42),
                "session-1",
                "event-1",
                42,
            ),
        )
        assertEquals(0, laser.disableCalls)
        assertTrue(locator.forceSafeCleanup(control, "session-1", "event-1", 41))
        assertEquals(1, laser.disableCalls)
    }

    @Test
    fun forceSafeCleanupClearsExactOwnerEvenWhenDisableFailsAndSurfacesFailure() = runTest {
        val failing = RecordingLaser(disableFailure = IllegalStateException("disable-failed"))
        val locator = VisibleFireLaserLocator(
            RecordingMissionHold(),
            RecordingFlightControl(),
            laserRangefinder = failing,
        )
        assertEquals(
            VerifiedHoldAdoptionResult.Adopted,
            locator.adoptVerifiedHold(token, evidence, control, "session-1", "event-1", 41),
        )

        val failure = runCatching {
            locator.forceSafeCleanup(control, "session-1", "event-1", 41)
        }.exceptionOrNull()

        assertEquals("laser-force-safe-cleanup-failed", failure?.message)
        assertEquals(1, failing.disableCalls)
        // Exact ownership was cleared in finally, so the same proof can be adopted again.
        assertEquals(
            VerifiedHoldAdoptionResult.Adopted,
            locator.adoptVerifiedHold(token, evidence, control, "session-1", "event-1", 41),
        )
    }

    @Test
    fun startupCleanupNeedsNoSessionTokenAndClearsLocalOwnerBeforeRecovery() = runTest {
        val laser = RecordingLaser()
        val locator = VisibleFireLaserLocator(
            RecordingMissionHold(),
            RecordingFlightControl(),
            laserRangefinder = laser,
        )
        assertEquals(
            VerifiedHoldAdoptionResult.Adopted,
            locator.adoptVerifiedHold(token, evidence, control, "session-1", "event-1", 41),
        )

        locator.forceSafeStartupCleanup()

        assertEquals(1, laser.disableCalls)
        assertEquals(
            VerifiedHoldAdoptionResult.Adopted,
            locator.adoptVerifiedHold(token, evidence, control, "session-1", "event-1", 41),
        )
    }

    @Test
    fun startupCleanupSurfacesDisableFailureAfterClearingLocalOwner() = runTest {
        val laser = RecordingLaser(disableFailure = IllegalStateException("disable-failed"))
        val locator = VisibleFireLaserLocator(
            RecordingMissionHold(),
            RecordingFlightControl(),
            laserRangefinder = laser,
        )
        locator.adoptVerifiedHold(token, evidence, control, "session-1", "event-1", 41)

        val failure = runCatching { locator.forceSafeStartupCleanup() }.exceptionOrNull()

        assertEquals("laser-startup-cleanup-failed", failure?.message)
        assertEquals(
            VerifiedHoldAdoptionResult.Adopted,
            locator.adoptVerifiedHold(token, evidence, control, "session-1", "event-1", 41),
        )
    }

    private class RecordingMissionHold : MissionHoldControl {
        var calls = 0
        override suspend fun holdForConfirmation(): Boolean = true.also { calls++ }
        override suspend fun resumeAfterConfirmation() = Unit
    }

    private class RecordingFlightControl : FlightControlActionClient {
        var hoverCalls = 0
        override suspend fun hover() { hoverCalls++ }
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

    private class RecordingLaser(
        private val disableFailure: Throwable? = null,
    ) : LaserRangefinderClient {
        var disableCalls = 0
        override suspend fun enable() = Unit
        override suspend fun measure(): LaserRangefinderResult? = null
        override suspend fun disable() {
            disableCalls++
            disableFailure?.let { throw it }
        }
    }
}
