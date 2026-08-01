package com.yinxin.uavfir.firedetection

class FireSessionReducer {
    fun reduce(
        state: FireSessionState,
        event: FireSessionEvent,
    ): FireSessionReduction = reduce(ReducerOwnedPhase(state), event)

    fun reduce(
        phase: FireSessionPhase,
        event: FireSessionEvent,
    ): FireSessionReduction {
        val owned = phase as? ReducerOwnedPhase ?: ReducerOwnedPhase(phase.state)
        val state = owned.state
        if (event is FireSessionEvent.UnsafeFailure) {
            return accepted(ReducerOwnedPhase(FireSessionState.MANUAL_HOLD))
        }
        if (event == FireSessionEvent.DisarmRequested) {
            return accepted(ReducerOwnedPhase(FireSessionState.DISARMED))
        }

        return when {
            state == FireSessionState.DISARMED && event == FireSessionEvent.ArmRequested ->
                accepted(ReducerOwnedPhase(FireSessionState.ARMING))
            state == FireSessionState.ARMING && event == FireSessionEvent.Armed ->
                accepted(ReducerOwnedPhase(FireSessionState.SCANNING))
            state == FireSessionState.SCANNING && event == FireSessionEvent.CandidateObserved ->
                accepted(ReducerOwnedPhase(FireSessionState.VISUAL_CONFIRMING))
            state == FireSessionState.VISUAL_CONFIRMING && event == FireSessionEvent.CandidateCleared ->
                accepted(ReducerOwnedPhase(FireSessionState.SCANNING))
            state == FireSessionState.VISUAL_CONFIRMING && event is FireSessionEvent.VisualConfirmed ->
                visualConfirmed(event)
            state == FireSessionState.VISUAL_CONFIRMED && event is FireSessionEvent.InitialAlertDurable ->
                initialAlertDurable(owned, event)
            state == FireSessionState.HOLD_REQUESTED && event == FireSessionEvent.MissionPaused ->
                accepted(owned.moveTo(FireSessionState.HOVER_VERIFYING))
            state == FireSessionState.HOVER_VERIFYING && event == FireSessionEvent.HoverStable ->
                accepted(
                    owned.moveTo(FireSessionState.TARGET_ALIGNING),
                    FireSessionEffect.AlignTarget,
                )
            state == FireSessionState.TARGET_ALIGNING && event == FireSessionEvent.TargetAligned ->
                accepted(
                    owned.moveTo(FireSessionState.LASER_MEASURING),
                    FireSessionEffect.MeasureLaser,
                )
            state == FireSessionState.LASER_MEASURING &&
                event is FireSessionEvent.TerminalResultReady ->
                terminalResultReady(owned, event)
            state == FireSessionState.TARGET_ALIGNING &&
                event is FireSessionEvent.TerminalResultReady &&
                event.request.locationStatus == LocationStatus.DEGRADED_OSD ->
                terminalResultReady(owned, event)
            state in setOf(FireSessionState.LASER_MEASURING, FireSessionState.TARGET_ALIGNING) &&
                event is FireSessionEvent.TerminalResultDurable ->
                terminalResultDurable(owned, event)
            state == FireSessionState.RESULT_DURABLE &&
                owned.durableTerminal != null &&
                event == FireSessionEvent.ResumeRequested ->
                accepted(
                    owned.moveTo(
                        state = FireSessionState.RESUME_REQUESTED,
                        durableTerminal = null,
                    ),
                    FireSessionEffect.ResumeMission,
                )
            state == FireSessionState.RESUME_REQUESTED && event == FireSessionEvent.MissionResumeConfirmed ->
                accepted(owned.moveTo(FireSessionState.MISSION_RESUMED))
            state == FireSessionState.MISSION_RESUMED && event == FireSessionEvent.ScanContinued ->
                accepted(ReducerOwnedPhase(FireSessionState.SCANNING))
            state == FireSessionState.MANUAL_HOLD && event == FireSessionEvent.RecoveryVerified ->
                accepted(ReducerOwnedPhase(FireSessionState.SCANNING))
            else -> rejected(owned)
        }
    }

    private fun visualConfirmed(
        event: FireSessionEvent.VisualConfirmed,
    ): FireSessionReduction {
        val request = event.request
        return accepted(
            ReducerOwnedPhase(
                state = FireSessionState.VISUAL_CONFIRMED,
                sessionId = request.sessionId,
                eventId = request.eventId,
                pendingInitial = request,
            ),
            FireSessionEffect.PersistInitialAlert(request),
        )
    }

    private fun initialAlertDurable(
        phase: ReducerOwnedPhase,
        event: FireSessionEvent.InitialAlertDurable,
    ): FireSessionReduction {
        if (phase.pendingInitial != event.request) {
            return rejected(phase)
        }
        return accepted(
            phase.moveTo(
                state = FireSessionState.HOLD_REQUESTED,
                pendingInitial = null,
            ),
            FireSessionEffect.PauseMission,
        )
    }

    private fun terminalResultReady(
        phase: ReducerOwnedPhase,
        event: FireSessionEvent.TerminalResultReady,
    ): FireSessionReduction {
        val request = event.request
        if (phase.pendingTerminal != null ||
            !request.isValidTerminalMapping ||
            request.sessionId != phase.sessionId ||
            request.eventId != phase.eventId
        ) {
            return rejected(phase)
        }
        return accepted(
            phase.moveTo(
                state = phase.state,
                pendingTerminal = request,
            ),
            FireSessionEffect.PersistTerminalResult(request),
        )
    }

    private fun terminalResultDurable(
        phase: ReducerOwnedPhase,
        event: FireSessionEvent.TerminalResultDurable,
    ): FireSessionReduction {
        val pending = phase.pendingTerminal
        if (pending == null || pending != event.request) {
            return rejected(phase)
        }
        return accepted(
            phase.moveTo(
                state = FireSessionState.RESULT_DURABLE,
                pendingTerminal = null,
                durableTerminal = pending,
            ),
        )
    }

    private fun accepted(
        phase: ReducerOwnedPhase,
        vararg effects: FireSessionEffect,
    ) = FireSessionReduction(phase = phase, effects = effects.toList(), accepted = true)

    private fun rejected(phase: ReducerOwnedPhase) =
        FireSessionReduction(phase = phase, accepted = false)
}

private data class ReducerOwnedPhase(
    override val state: FireSessionState,
    val sessionId: String? = null,
    val eventId: String? = null,
    val pendingInitial: InitialPersistenceRequest? = null,
    val pendingTerminal: TerminalPersistenceRequest? = null,
    val durableTerminal: TerminalPersistenceRequest? = null,
) : FireSessionPhase {
    init {
        require((sessionId == null) == (eventId == null))
        require(pendingInitial == null || state == FireSessionState.VISUAL_CONFIRMED)
        require(
            pendingTerminal == null ||
                state == FireSessionState.LASER_MEASURING ||
                state == FireSessionState.TARGET_ALIGNING,
        )
        require(durableTerminal == null || state == FireSessionState.RESULT_DURABLE)
        require(listOfNotNull(pendingInitial, pendingTerminal, durableTerminal).size <= 1)
    }

    fun moveTo(
        state: FireSessionState,
        pendingInitial: InitialPersistenceRequest? = this.pendingInitial,
        pendingTerminal: TerminalPersistenceRequest? = this.pendingTerminal,
        durableTerminal: TerminalPersistenceRequest? = this.durableTerminal,
    ) = ReducerOwnedPhase(
        state = state,
        sessionId = sessionId,
        eventId = eventId,
        pendingInitial = pendingInitial,
        pendingTerminal = pendingTerminal,
        durableTerminal = durableTerminal,
    )
}
