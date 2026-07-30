package com.yinxin.uavfir.firedetection

class FireSessionReducer {
    fun reduce(
        state: FireSessionState,
        event: FireSessionEvent,
    ): FireSessionReduction {
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
            state == FireSessionState.LASER_MEASURING && event is FireSessionEvent.TerminalResultReady ->
                terminalResultReady(state, event)
            state == FireSessionState.LASER_MEASURING && event == FireSessionEvent.TerminalResultDurable ->
                accepted(FireSessionState.RESULT_DURABLE)
            state == FireSessionState.RESULT_DURABLE && event == FireSessionEvent.ResumeRequested ->
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
        state: FireSessionState,
        event: FireSessionEvent.TerminalResultReady,
    ): FireSessionReduction = runCatching {
        FireSessionEffect.PersistTerminalResult(event.locationStatus, event.geoMethod)
    }.fold(
        onSuccess = { effect -> accepted(state, effect) },
        onFailure = { rejected(state) },
    )

    private fun accepted(
        state: FireSessionState,
        vararg effects: FireSessionEffect,
    ) = FireSessionReduction(state = state, effects = effects.toList(), accepted = true)

    private fun rejected(state: FireSessionState) =
        FireSessionReduction(state = state, accepted = false)
}
