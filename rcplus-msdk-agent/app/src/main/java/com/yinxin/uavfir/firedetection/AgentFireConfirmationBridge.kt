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

data class CoordinatorOutcomeRecord(val eventId: String, val result: ClosedLoopResult)

fun interface CoordinatorOutcomeSink {
    fun record(eventId: String, result: ClosedLoopResult)
}

class BoundedCoordinatorOutcomeRecorder(private val capacity: Int = 64) : CoordinatorOutcomeSink {
    init { require(capacity > 0) }
    private val records = ArrayDeque<CoordinatorOutcomeRecord>()

    @Synchronized
    override fun record(eventId: String, result: ClosedLoopResult) {
        if (records.size == capacity) records.removeFirst()
        records.addLast(CoordinatorOutcomeRecord(eventId, result))
    }

    @Synchronized
    fun snapshot(): List<CoordinatorOutcomeRecord> = records.toList()
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
    private val evidenceCapture: AgentFireEvidenceCapture? = null,
    private val outcomeSink: CoordinatorOutcomeSink = CoordinatorOutcomeSink { _, _ -> },
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
        val pendingEvidence = evidenceCapture?.snapshot(frame, confirmation)
        val suffix = randomId()
        val eventId = "agent-${wallTimeMillis()}-$suffix"
        val envelope = AgentFireConfirmationEnvelope(
            sessionId = randomId(),
            eventId = eventId,
            taskId = context.taskId,
            sourceGeneration = context.sourceGeneration,
            confirmation = confirmation,
        )
        scope.launch {
            val evidence = if (pendingEvidence == null) {
                emptyList()
            } else {
                runCatching { checkNotNull(evidenceCapture).materialize(eventId, pendingEvidence) }
                    .getOrElse {
                        outcomeSink.record(eventId, ClosedLoopResult.Rejected("evidence-capture-failed"))
                        return@launch
                    }
                    .let(::listOf)
            }
            outcomeSink.record(eventId, runner.process(envelope.copy(evidence = evidence)))
        }
    }
}
