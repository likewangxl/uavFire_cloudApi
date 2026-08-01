package com.yinxin.uavfir.firedetection

sealed interface RecoveryResult {
    data class Resumed(val eventId: String) : RecoveryResult
    data class ManualHold(val eventId: String, val durable: Boolean) : RecoveryResult
}

class AgentFireRecoveryCoordinator(
    private val store: CoordinatorStorePort,
    private val delivery: CoordinatorOutboxPort,
    private val localization: CoordinatorLocalizationPort,
    private val mission: CoordinatorMissionPort,
) {
    suspend fun recover(): List<RecoveryResult> {
        localization.ensureLaserDisabledAndAlignmentClosed(null)
        val sessions = store.loadRecoverySessions()
        delivery.restartPendingDelivery()
        return sessions.map { recovery ->
            val session = recovery.session
            val stateMayOwnFlight = recovery.persistedState in UNSAFE_RESTART_STATES
            if (!stateMayOwnFlight) {
                return@map manualHold(session, CoordinatorManualHoldReason.STARTUP_RECOVERY_UNCERTAIN)
            }
            if (!recovery.terminalResultDurable) {
                return@map manualHold(session, CoordinatorManualHoldReason.STARTUP_RECOVERY_UNCERTAIN)
            }
            when (mission.reconcileForRecovery(recovery)) {
                RecoveryMissionOutcome.Resumed -> RecoveryResult.Resumed(session.eventId)
                is RecoveryMissionOutcome.ManualHold ->
                    manualHold(session, CoordinatorManualHoldReason.STARTUP_RECOVERY_UNCERTAIN)
            }
        }
    }

    private suspend fun manualHold(
        session: CoordinatorSession,
        reason: CoordinatorManualHoldReason,
    ): RecoveryResult.ManualHold = RecoveryResult.ManualHold(
        session.eventId,
        store.persistManualHold(session, reason).durable,
    )

    companion object {
        private val UNSAFE_RESTART_STATES = setOf(
            FireSessionState.HOLD_REQUESTED,
            FireSessionState.HOVER_VERIFYING,
            FireSessionState.TARGET_ALIGNING,
            FireSessionState.LASER_MEASURING,
            FireSessionState.RESULT_DURABLE,
            FireSessionState.RESUME_REQUESTED,
        )
    }
}
