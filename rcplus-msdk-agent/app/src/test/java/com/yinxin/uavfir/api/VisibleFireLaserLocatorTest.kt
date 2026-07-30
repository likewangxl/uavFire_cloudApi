package com.yinxin.uavfir.api

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
    ) : LaserRangefinderClient {
        var disableCalls = 0

        override suspend fun measure(): LaserRangefinderResult? =
            if (results.isEmpty()) null else results.removeAt(0)

        override suspend fun disable() {
            disableCalls += 1
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

    companion object {
        private val ROI = mapOf(
            "x" to 0.4,
            "y" to 0.3,
            "width" to 0.2,
            "height" to 0.2,
        )
    }
}
