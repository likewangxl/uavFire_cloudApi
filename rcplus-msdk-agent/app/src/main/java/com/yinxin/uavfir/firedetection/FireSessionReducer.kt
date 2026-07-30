package com.yinxin.uavfir.firedetection

class FireSessionReducer {
    fun reduce(
        state: FireSessionState,
        event: FireSessionEvent,
    ): FireSessionReduction = reduce(FireSessionPhase.unproven(state), event)

    fun reduce(
        phase: FireSessionPhase,
        event: FireSessionEvent,
    ): FireSessionReduction {
        val state = phase.state
        if (event is FireSessionEvent.UnsafeFailure) {
            return accepted(FireSessionState.MANUAL_HOLD)
        }
        if (event == FireSessionEvent.DisarmRequested) {
            return accepted(FireSessionState.DISARMED)
        }

        return when {
            state == FireSessionState.DISARMED && event == FireSessionEvent.ArmRequested ->
                accepted(FireSessionState.ARMING)
            state == FireSessionState.ARMING && event == FireSessionEvent.Armed ->
                accepted(FireSessionState.SCANNING)
            state == FireSessionState.SCANNING && event == FireSessionEvent.CandidateObserved ->
                accepted(FireSessionState.VISUAL_CONFIRMING)
            state == FireSessionState.VISUAL_CONFIRMING && event == FireSessionEvent.CandidateCleared ->
                accepted(FireSessionState.SCANNING)
            state == FireSessionState.VISUAL_CONFIRMING && event is FireSessionEvent.VisualConfirmed ->
                accepted(
                    FireSessionState.VISUAL_CONFIRMED,
                    FireSessionEffect.PersistInitialAlert(event.confirmation),
                )
            state == FireSessionState.VISUAL_CONFIRMED && event == FireSessionEvent.InitialAlertDurable ->
                accepted(FireSessionState.HOLD_REQUESTED, FireSessionEffect.PauseMission)
            state == FireSessionState.HOLD_REQUESTED && event == FireSessionEvent.MissionPaused ->
                accepted(FireSessionState.HOVER_VERIFYING)
            state == FireSessionState.HOVER_VERIFYING && event == FireSessionEvent.HoverStable ->
                accepted(FireSessionState.TARGET_ALIGNING, FireSessionEffect.AlignTarget)
            state == FireSessionState.TARGET_ALIGNING && event == FireSessionEvent.TargetAligned ->
                accepted(FireSessionState.LASER_MEASURING, FireSessionEffect.MeasureLaser)
            state == FireSessionState.LASER_MEASURING &&
                event is FireSessionEvent.TerminalResultReady ->
                terminalResultReady(phase, event)
            state == FireSessionState.LASER_MEASURING &&
                event is FireSessionEvent.TerminalResultDurable ->
                terminalResultDurable(phase, event)
            state == FireSessionState.RESULT_DURABLE &&
                phase.terminalPersistenceVerified &&
                event == FireSessionEvent.ResumeRequested ->
                accepted(FireSessionState.RESUME_REQUESTED, FireSessionEffect.ResumeMission)
            state == FireSessionState.RESUME_REQUESTED && event == FireSessionEvent.MissionResumeConfirmed ->
                accepted(FireSessionState.MISSION_RESUMED)
            state == FireSessionState.MISSION_RESUMED && event == FireSessionEvent.ScanContinued ->
                accepted(FireSessionState.SCANNING)
            state == FireSessionState.MANUAL_HOLD && event == FireSessionEvent.RecoveryVerified ->
                accepted(FireSessionState.SCANNING)
            else -> rejected(state)
        }
    }

    private fun terminalResultReady(
        phase: FireSessionPhase,
        event: FireSessionEvent.TerminalResultReady,
    ): FireSessionReduction {
        if (phase.pendingTerminal != null || !event.request.isValidTerminalMapping) {
            return rejected(phase)
        }
        return accepted(
            phase.copy(pendingTerminal = event.request),
            FireSessionEffect.PersistTerminalResult(event.request),
        )
    }

    private fun terminalResultDurable(
        phase: FireSessionPhase,
        event: FireSessionEvent.TerminalResultDurable,
    ): FireSessionReduction {
        val pending = phase.pendingTerminal
        if (pending == null || pending.requestId != event.requestId) {
            return rejected(phase)
        }
        return accepted(
            FireSessionPhase(
                state = FireSessionState.RESULT_DURABLE,
                durableTerminal = pending,
            ),
        )
    }

    private fun accepted(
        state: FireSessionState,
        vararg effects: FireSessionEffect,
    ) = accepted(FireSessionPhase.unproven(state), *effects)

    private fun accepted(
        phase: FireSessionPhase,
        vararg effects: FireSessionEffect,
    ) = FireSessionReduction(phase = phase, effects = effects.toList(), accepted = true)

    private fun rejected(state: FireSessionState) =
        rejected(FireSessionPhase.unproven(state))

    private fun rejected(phase: FireSessionPhase) =
        FireSessionReduction(phase = phase, accepted = false)
}
