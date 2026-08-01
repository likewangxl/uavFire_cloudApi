package com.yinxin.uavfir.firedetection

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AwaitableMissionControlTest {
    private val mission = MissionExecutionKey(MissionIdentity("m", "m.kmz"), 1)
    private val breakpoint = MissionBreakpoint(1, 2, .4)

    @Test
    fun pauseNeedsMatchingCallbackAndStateInEitherOrderAndCoalesces() = runTest {
        listOf(true, false).forEach { callbackFirst ->
            val port = Port(MissionSnapshot(mission, ObservedMissionState.EXECUTING, 0), breakpoint)
            val control = control(port)
            val first = async { control.pause() }
            val second = async { control.pause() }
            advanceUntilIdle()
            assertEquals(1, port.pauseCalls)
            if (callbackFirst) {
                port.callbackSuccess()
                advanceUntilIdle()
                assertFalse(first.isCompleted)
                port.emit(ObservedMissionState.INTERRUPTED)
            } else {
                port.emit(ObservedMissionState.INTERRUPTED)
                advanceUntilIdle()
                assertFalse(first.isCompleted)
                port.callbackSuccess()
            }
            assertEquals(first.await(), second.await())
            control.close()
        }
    }

    @Test
    fun noWaylineAloneUsesHoverAndUnknownFailsClosed() = runTest {
        val idle = Port(MissionSnapshot(null, ObservedMissionState.IDLE, 0), null)
        var hovered = 0
        val idleControl = control(idle) { hovered++ }
        assertEquals(MissionHoldResult.HoveringNoWayline, idleControl.pause())
        assertEquals(1, hovered)
        idleControl.close()

        val unknown = Port(MissionSnapshot(mission, ObservedMissionState.UNKNOWN, 0), breakpoint)
        val unknownControl = control(unknown)
        assertEquals(
            MissionHoldResult.ManualHold(FlightSafetyReason.UNKNOWN_MISSION_STATE),
            unknownControl.pause(),
        )
        unknownControl.close()
    }

    @Test
    fun capturedMissionMustMatchConfirmationTaskBindingBeforePauseSubmission() = runTest {
        val port = Port(MissionSnapshot(mission, ObservedMissionState.EXECUTING, 0), breakpoint)
        val control = control(port)

        assertEquals(
            MissionHoldResult.ManualHold(FlightSafetyReason.MISSION_IDENTITY_MISMATCH),
            control.pause(expectedMissionId = "different-active-task"),
        )
        assertEquals(0, port.pauseCalls)
        control.close()
    }

    private fun kotlinx.coroutines.test.TestScope.control(
        port: Port,
        hover: suspend () -> Unit = {},
    ) = AwaitableMissionControl(
        port,
        hover,
        this,
        FlightSafetyGate(),
        FailClosedResumeSafetyEvidenceProvider,
    ) { 1_000 }

    private class Port(
        private var current: MissionSnapshot,
        private val breakpoint: MissionBreakpoint?,
    ) : MissionControlPort {
        var pauseCalls = 0
        private var callback: ((MissionCommandCallback) -> Unit)? = null
        private val listeners = linkedSetOf<(MissionSnapshot) -> Unit>()

        override fun snapshot() = current
        override fun queryBreakpoint(
            mission: MissionExecutionKey,
            callback: (MissionBreakpoint?, MissionCommandError?) -> Unit,
        ): MissionCancellation {
            callback(breakpoint, null)
            return MissionCancellation {}
        }
        override fun pause(
            mission: MissionExecutionKey,
            onSubmissionBoundary: (MissionCommandSubmission) -> Unit,
            callback: (MissionCommandCallback) -> Unit,
        ): MissionCommandSubmission {
            pauseCalls++
            this.callback = callback
            current = current.copy(commandGeneration = current.commandGeneration + 1)
            emit(current.state)
            return MissionCommandSubmission(mission, current.commandGeneration, MissionCancellation {})
                .also(onSubmissionBoundary)
        }
        override fun resume(
            mission: MissionExecutionKey,
            breakpoint: MissionBreakpoint,
            onSubmissionBoundary: (MissionCommandSubmission) -> Unit,
            callback: (MissionCommandCallback) -> Unit,
        ) = error("unused")
        override fun observeSnapshots(listener: (MissionSnapshot) -> Unit): MissionCancellation {
            listeners += listener
            listener(current)
            return MissionCancellation { listeners -= listener }
        }
        fun emit(state: ObservedMissionState) {
            current = current.copy(state = state)
            listeners.toList().forEach { it(current) }
        }
        fun callbackSuccess() {
            callback?.invoke(MissionCommandCallback(current.mission!!, current.commandGeneration, null))
        }
    }
}
