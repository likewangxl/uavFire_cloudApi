package com.yinxin.uavfir.firedetection

import com.yinxin.uavfir.api.VerifiedHoldAdoptionResult
import com.yinxin.uavfir.api.VisibleFireLaserLocator

/** Uses Task 8 only after adopting Task 7's exact verified hold; no second flight command. */
class Task8CoordinatorLocalizationPort(
    private val locator: VisibleFireLaserLocator,
) : CoordinatorLocalizationPort {
    override suspend fun localize(
        session: CoordinatorSession,
        request: FireLocalizationRequest,
        holdProof: CoordinatorHoldProof,
        hoverProof: CoordinatorHoverProof,
        onLaserMeasurementBoundary: suspend () -> Boolean,
    ): BoundLocalizationResult {
        val token = holdProof.missionToken
        val stable = hoverProof.stableEvidence
        val exact = token != null && stable != null &&
            holdProof.sessionId == session.sessionId && holdProof.eventId == session.eventId &&
            holdProof.generation == session.generation && hoverProof.sessionId == session.sessionId &&
            hoverProof.eventId == session.eventId && hoverProof.generation == session.generation
        if (!exact) {
            return BoundLocalizationResult(
                session.sessionId,
                session.eventId,
                session.generation,
                FireLocalizationResult.ManualHold(
                    session.kind,
                    FireLocalizationFailure.EVENT_SESSION_MISMATCH,
                ),
            )
        }
        val control = FireControlSessionKey(session.sessionId, session.generation)
        val adopted = locator.adoptVerifiedHold(
            missionToken = checkNotNull(token),
            stableHoverEvidence = checkNotNull(stable),
            controlSession = control,
            sessionId = session.sessionId,
            eventId = session.eventId,
            generation = session.generation,
        )
        if (adopted !is VerifiedHoldAdoptionResult.Adopted) {
            return BoundLocalizationResult(
                session.sessionId,
                session.eventId,
                session.generation,
                FireLocalizationResult.ManualHold(
                    session.kind,
                    FireLocalizationFailure.EVENT_SESSION_MISMATCH,
                ),
            )
        }
        return BoundLocalizationResult(
            session.sessionId,
            session.eventId,
            session.generation,
            locator.localize(request, onLaserMeasurementBoundary),
        )
    }

    override suspend fun ensureLaserDisabledAndAlignmentClosed(session: CoordinatorSession?) {
        if (session == null) {
            locator.forceSafeStartupCleanup()
            return
        }
        check(
            locator.forceSafeCleanup(
                FireControlSessionKey(session.sessionId, session.generation),
                session.sessionId,
                session.eventId,
                session.generation,
            ),
        ) { "localization-cleanup-owner-mismatch" }
    }
}
