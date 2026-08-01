package com.yinxin.uavfir.firedetection

import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

data class AgentFireMonitoringContext(
    val taskId: String,
    val sourceGeneration: Long,
) {
    init { require(taskId.isNotBlank() && sourceGeneration > 0) }
}

/**
 * Runs Task 5 on Task 4's sole inference worker while RGBA pixels are valid,
 * then hands only an immutable, source-generation-bound confirmation to Task 9.
 */
class AgentFireConfirmationBridge(
    private val tracker: VisibleConfirmationTracker,
    private val monitoringContext: () -> AgentFireMonitoringContext?,
    private val health: () -> ConfirmationHealth,
    private val coordinator: () -> AgentFireClosedLoopCoordinator?,
    private val scope: CoroutineScope,
    private val wallTimeMillis: () -> Long = System::currentTimeMillis,
    private val randomId: () -> String = { UUID.randomUUID().toString() },
) : VisibleInferenceConfirmationObserver {
    override fun onInference(
        frame: VisibleRgbaFrame,
        result: VisibleDetectionResult,
        completedAtMillis: Long,
    ) {
        val context = monitoringContext() ?: run {
            tracker.reset()
            return
        }
        if (context.sourceGeneration != frame.sourceGeneration) {
            tracker.reset()
            return
        }
        val outcome = tracker.observe(
            VisibleConfirmationInput(
                frame = frame,
                result = result,
                observedAtMillis = completedAtMillis,
                health = health(),
            ),
        )
        val confirmation = outcome.confirmation ?: return
        val runner = coordinator() ?: return
        val suffix = randomId()
        val eventId = "agent-${wallTimeMillis()}-$suffix"
        val envelope = AgentFireConfirmationEnvelope(
            sessionId = randomId(),
            eventId = eventId,
            taskId = context.taskId,
            sourceGeneration = context.sourceGeneration,
            confirmation = confirmation,
        )
        scope.launch { runner.process(envelope) }
    }
}
