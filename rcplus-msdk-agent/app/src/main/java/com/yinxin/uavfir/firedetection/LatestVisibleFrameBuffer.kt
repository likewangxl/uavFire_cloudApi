package com.yinxin.uavfir.firedetection

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

enum class VisibleFrameSource {
    VISIBLE,
    THERMAL,
    UNKNOWN,
}

enum class VisibleFrameFormat {
    RGBA_8888,
    OTHER,
}

enum class VisibleFrameOfferResult {
    PUBLISHED,
    REJECTED_BEFORE_COPY,
    COPIED_DISCARDED,
    ;

    val published: Boolean
        get() = this == PUBLISHED
}

interface VisibleFrameIngress {
    val enabled: Boolean

    fun onSourceSwitchStarted(source: VisibleFrameSource): Long

    fun onSourceSwitchCompleted(generation: Long, success: Boolean)

    fun offerVisibleFrame(
        sourceGeneration: Long,
        format: VisibleFrameFormat,
        frameData: ByteArray,
        offset: Int,
        length: Int,
        width: Int,
        height: Int,
        capturedAtMillis: Long,
    ): VisibleFrameOfferResult

    companion object {
        val NO_OP = object : VisibleFrameIngress {
            override val enabled: Boolean = false
            override fun onSourceSwitchStarted(source: VisibleFrameSource): Long = 0L
            override fun onSourceSwitchCompleted(generation: Long, success: Boolean) = Unit
            override fun offerVisibleFrame(
                sourceGeneration: Long,
                format: VisibleFrameFormat,
                frameData: ByteArray,
                offset: Int,
                length: Int,
                width: Int,
                height: Int,
                capturedAtMillis: Long,
            ): VisibleFrameOfferResult = VisibleFrameOfferResult.REJECTED_BEFORE_COPY
        }
    }
}

internal data class LatestVisibleFrameBufferHooks(
    val afterAdmissionReserved: () -> Unit = {},
    val afterCopyPermissionAcquired: () -> Unit = {},
    val afterFinalAdmissibilityCheckBeforePublish: () -> Unit = {},
)

data class LatestVisibleFrameBufferMetrics(
    val acceptedFrames: Long,
    val rejectedFrames: Long,
    val sourceRejectedFrames: Long,
    val cadenceRejectedFrames: Long,
    val reservationRejectedFrames: Long,
    val copiedFrames: Long,
    val discardedCopiedFrames: Long,
    val replacedFrames: Long,
    val consumedFrames: Long,
    val releasedFrames: Long,
)

internal enum class LatestVisibleFrameBufferSlotPhase {
    EMPTY,
    CLOSED,
    FRAME,
    RESERVED,
    COPYING,
    COPYING_REVOKED,
}

internal enum class LatestVisibleFrameBufferSourcePhase {
    UNBOUND,
    CLOSED,
    TRANSITION,
    VISIBLE,
    THERMAL,
}

/**
 * Lock-free capacity-one handoff between a generation-bound MSDK decoder
 * listener and the detector loop.
 *
 * Source commands first invalidate the old generation and drain the slot.
 * A callback must then reserve both the cadence and slot before allocating its
 * copy. The Reservation -> Copying CAS is the copy-admission linearization
 * point: close/source-switch can revoke Reservation with zero copy, while a
 * producer that owns Copying completes its copy. Terminal operations can still
 * atomically replace Copying with CopyingRevoked, preventing any later
 * publication while the producer releases its authorized copy.
 */
class LatestVisibleFrameBuffer internal constructor(
    private val admissionNowMillis: () -> Long = SystemClock::elapsedRealtime,
    private val sourceQuarantineMillis: Long = SOURCE_QUARANTINE_MILLIS,
    private val hooks: LatestVisibleFrameBufferHooks = LatestVisibleFrameBufferHooks(),
) : VisibleFrameIngress, AutoCloseable {
    override val enabled: Boolean = true

    fun currentVisibleSourceGeneration(): Long? =
        (sourceState.get() as? SourceState.Visible)?.generation

    private sealed interface Slot {
        data object Empty : Slot
        data object Closed : Slot
        data class Frame(val value: VisibleRgbaFrame) : Slot
        data class Reservation(
            val token: Any,
            val generation: Long,
            val replaced: Frame?,
        ) : Slot
        data class Copying(
            val token: Any,
            val generation: Long,
            val replaced: Frame?,
        ) : Slot
        data class CopyingRevoked(
            val copying: Copying,
            val terminal: RevokedTerminal,
        ) : Slot
    }

    private enum class RevokedTerminal {
        EMPTY,
        CLOSED,
    }

    private sealed interface SourceState {
        data object Unbound : SourceState
        data object Closed : SourceState
        data class Transition(val generation: Long, val target: VisibleFrameSource) : SourceState
        data class Visible(val generation: Long, val notBeforeMillis: Long) : SourceState
        data class Thermal(val generation: Long) : SourceState
    }

    private val slot = AtomicReference<Slot>(Slot.Empty)
    private val sourceState = AtomicReference<SourceState>(SourceState.Unbound)
    private val sourceGeneration = AtomicLong()
    private val nextAdmissionAtMillis = AtomicLong(Long.MIN_VALUE)
    private val acceptedFrames = AtomicLong()
    private val rejectedFrames = AtomicLong()
    private val sourceRejectedFrames = AtomicLong()
    private val cadenceRejectedFrames = AtomicLong()
    private val reservationRejectedFrames = AtomicLong()
    private val copiedFrames = AtomicLong()
    private val discardedCopiedFrames = AtomicLong()
    private val replacedFrames = AtomicLong()
    private val consumedFrames = AtomicLong()
    private val releasedFrames = AtomicLong()

    override fun onSourceSwitchStarted(source: VisibleFrameSource): Long {
        val generation = sourceGeneration.incrementAndGet()
        while (true) {
            val current = sourceState.get()
            if (current === SourceState.Closed) return generation
            if (sourceState.compareAndSet(current, SourceState.Transition(generation, source))) break
        }
        nextAdmissionAtMillis.set(Long.MIN_VALUE)
        invalidateQueuedOrReservedFrame()
        return generation
    }

    override fun onSourceSwitchCompleted(generation: Long, success: Boolean) {
        while (true) {
            val current = sourceState.get()
            if (current !is SourceState.Transition || current.generation != generation) return
            val completed = when {
                !success -> SourceState.Unbound
                current.target == VisibleFrameSource.VISIBLE -> SourceState.Visible(
                    generation = generation,
                    notBeforeMillis = admissionNowMillis() + sourceQuarantineMillis,
                )
                current.target == VisibleFrameSource.THERMAL -> SourceState.Thermal(generation)
                else -> SourceState.Unbound
            }
            if (sourceState.compareAndSet(current, completed)) return
        }
    }

    override fun offerVisibleFrame(
        sourceGeneration: Long,
        format: VisibleFrameFormat,
        frameData: ByteArray,
        offset: Int,
        length: Int,
        width: Int,
        height: Int,
        capturedAtMillis: Long,
    ): VisibleFrameOfferResult {
        val expectedLength = width.toLong() * height.toLong() * RGBA_BYTES
        if (format != VisibleFrameFormat.RGBA_8888 ||
            width <= 0 ||
            height <= 0 ||
            capturedAtMillis < 0L ||
            expectedLength <= 0L ||
            expectedLength > Int.MAX_VALUE ||
            offset < 0 ||
            length < expectedLength ||
            offset.toLong() + expectedLength > frameData.size
        ) {
            reject()
            return VisibleFrameOfferResult.REJECTED_BEFORE_COPY
        }
        if (!isVisibleGenerationBound(sourceGeneration)) {
            sourceRejectedFrames.incrementAndGet()
            reject()
            return VisibleFrameOfferResult.REJECTED_BEFORE_COPY
        }
        val now = admissionNowMillis()
        if (!isVisibleGenerationAdmissible(sourceGeneration, now)) {
            sourceRejectedFrames.incrementAndGet()
            reject()
            return VisibleFrameOfferResult.REJECTED_BEFORE_COPY
        }
        if (!reserveCadence(now)) {
            cadenceRejectedFrames.incrementAndGet()
            reject()
            return VisibleFrameOfferResult.REJECTED_BEFORE_COPY
        }

        val reservation = reserveSlot(sourceGeneration) ?: run {
            reservationRejectedFrames.incrementAndGet()
            reject()
            return VisibleFrameOfferResult.REJECTED_BEFORE_COPY
        }
        hooks.afterAdmissionReserved()
        if (slot.get() !== reservation ||
            !isVisibleGenerationAdmissible(sourceGeneration, admissionNowMillis())
        ) {
            cancelReservation(reservation)
            reject()
            return VisibleFrameOfferResult.REJECTED_BEFORE_COPY
        }
        val copying = Slot.Copying(
            token = reservation.token,
            generation = reservation.generation,
            replaced = reservation.replaced,
        )
        if (!slot.compareAndSet(reservation, copying)) {
            reject()
            return VisibleFrameOfferResult.REJECTED_BEFORE_COPY
        }
        hooks.afterCopyPermissionAcquired()

        val pixels = frameData.copyOfRange(offset, offset + expectedLength.toInt())
        copiedFrames.incrementAndGet()
        val frame = VisibleRgbaFrame(
            pixels = pixels,
            width = width,
            height = height,
            capturedAtMillis = capturedAtMillis,
            sourceGeneration = sourceGeneration,
            onRelease = { releasedFrames.incrementAndGet() },
        )
        val admissibleForPublication =
            isVisibleGenerationAdmissible(sourceGeneration, admissionNowMillis())
        hooks.afterFinalAdmissibilityCheckBeforePublish()
        if (!admissibleForPublication || !slot.compareAndSet(copying, Slot.Frame(frame))) {
            frame.release()
            finishDiscardedCopy(copying)
            discardedCopiedFrames.incrementAndGet()
            reject()
            return VisibleFrameOfferResult.COPIED_DISCARDED
        }
        acceptedFrames.incrementAndGet()
        copying.replaced?.let {
            replacedFrames.incrementAndGet()
            it.value.release()
        }
        return VisibleFrameOfferResult.PUBLISHED
    }

    fun offer(frame: VisibleRgbaFrame): Boolean {
        while (true) {
            val current = slot.get()
            if (current === Slot.Closed ||
                current is Slot.Reservation ||
                current is Slot.Copying ||
                current is Slot.CopyingRevoked
            ) {
                rejectedFrames.incrementAndGet()
                frame.release()
                return false
            }
            if (!slot.compareAndSet(current, Slot.Frame(frame))) continue
            acceptedFrames.incrementAndGet()
            if (current is Slot.Frame) {
                replacedFrames.incrementAndGet()
                current.value.release()
            }
            return true
        }
    }

    fun takeLatest(): VisibleRgbaFrame? {
        while (true) {
            val current = slot.get()
            if (current !is Slot.Frame) return null
            if (!slot.compareAndSet(current, Slot.Empty)) continue
            consumedFrames.incrementAndGet()
            return current.value
        }
    }

    fun snapshot() = LatestVisibleFrameBufferMetrics(
        acceptedFrames = acceptedFrames.get(),
        rejectedFrames = rejectedFrames.get(),
        sourceRejectedFrames = sourceRejectedFrames.get(),
        cadenceRejectedFrames = cadenceRejectedFrames.get(),
        reservationRejectedFrames = reservationRejectedFrames.get(),
        copiedFrames = copiedFrames.get(),
        discardedCopiedFrames = discardedCopiedFrames.get(),
        replacedFrames = replacedFrames.get(),
        consumedFrames = consumedFrames.get(),
        releasedFrames = releasedFrames.get(),
    )

    override fun close() {
        sourceState.set(SourceState.Closed)
        while (true) {
            val current = slot.get()
            if (current === Slot.Closed) return
            if (current is Slot.Copying) {
                if (slot.compareAndSet(
                        current,
                        Slot.CopyingRevoked(current, RevokedTerminal.CLOSED),
                    )
                ) {
                    current.replaced?.value?.release()
                    return
                }
                continue
            }
            if (current is Slot.CopyingRevoked) {
                if (current.terminal == RevokedTerminal.CLOSED) {
                    current.copying.replaced?.value?.release()
                    return
                }
                if (slot.compareAndSet(
                        current,
                        current.copy(terminal = RevokedTerminal.CLOSED),
                    )
                ) {
                    current.copying.replaced?.value?.release()
                    return
                }
                continue
            }
            if (!slot.compareAndSet(current, Slot.Closed)) continue
            releaseOwnedBy(current)
            return
        }
    }

    internal fun slotPhaseForTesting(): LatestVisibleFrameBufferSlotPhase =
        when (slot.get()) {
            Slot.Empty -> LatestVisibleFrameBufferSlotPhase.EMPTY
            Slot.Closed -> LatestVisibleFrameBufferSlotPhase.CLOSED
            is Slot.Frame -> LatestVisibleFrameBufferSlotPhase.FRAME
            is Slot.Reservation -> LatestVisibleFrameBufferSlotPhase.RESERVED
            is Slot.Copying -> LatestVisibleFrameBufferSlotPhase.COPYING
            is Slot.CopyingRevoked -> LatestVisibleFrameBufferSlotPhase.COPYING_REVOKED
        }

    internal fun sourcePhaseForTesting(): LatestVisibleFrameBufferSourcePhase =
        when (sourceState.get()) {
            SourceState.Unbound -> LatestVisibleFrameBufferSourcePhase.UNBOUND
            SourceState.Closed -> LatestVisibleFrameBufferSourcePhase.CLOSED
            is SourceState.Transition -> LatestVisibleFrameBufferSourcePhase.TRANSITION
            is SourceState.Visible -> LatestVisibleFrameBufferSourcePhase.VISIBLE
            is SourceState.Thermal -> LatestVisibleFrameBufferSourcePhase.THERMAL
        }

    private fun isVisibleGenerationAdmissible(generation: Long, now: Long): Boolean {
        val state = sourceState.get()
        return state is SourceState.Visible &&
            state.generation == generation &&
            now >= state.notBeforeMillis
    }

    private fun isVisibleGenerationBound(generation: Long): Boolean {
        val state = sourceState.get()
        return state is SourceState.Visible && state.generation == generation
    }

    private fun reserveCadence(now: Long): Boolean {
        while (true) {
            val deadline = nextAdmissionAtMillis.get()
            if (now < deadline) return false
            val next = now + ADMISSION_INTERVAL_MILLIS
            if (nextAdmissionAtMillis.compareAndSet(deadline, next)) return true
        }
    }

    private fun reserveSlot(generation: Long): Slot.Reservation? {
        while (true) {
            val current = slot.get()
            if (current === Slot.Closed ||
                current is Slot.Reservation ||
                current is Slot.Copying ||
                current is Slot.CopyingRevoked
            ) {
                return null
            }
            val reservation = Slot.Reservation(Any(), generation, current as? Slot.Frame)
            if (slot.compareAndSet(current, reservation)) return reservation
        }
    }

    private fun cancelReservation(reservation: Slot.Reservation) {
        if (slot.compareAndSet(reservation, reservation.replaced ?: Slot.Empty)) {
            return
        }
    }

    private fun finishDiscardedCopy(copying: Slot.Copying) {
        while (true) {
            val current = slot.get()
            val terminal = when {
                current === copying && sourceState.get() === SourceState.Closed -> Slot.Closed
                current === copying -> Slot.Empty
                current is Slot.CopyingRevoked &&
                    current.copying === copying &&
                    (
                        current.terminal == RevokedTerminal.CLOSED ||
                            sourceState.get() === SourceState.Closed
                        ) -> Slot.Closed
                current is Slot.CopyingRevoked && current.copying === copying -> Slot.Empty
                else -> error("Copy admission ownership was unexpectedly transferred")
            }
            if (!slot.compareAndSet(current, terminal)) continue
            copying.replaced?.value?.release()
            return
        }
    }

    private fun invalidateQueuedOrReservedFrame() {
        while (true) {
            val current = slot.get()
            if (current === Slot.Empty || current === Slot.Closed) return
            if (current is Slot.Copying) {
                if (slot.compareAndSet(
                        current,
                        Slot.CopyingRevoked(current, RevokedTerminal.EMPTY),
                    )
                ) {
                    current.replaced?.value?.release()
                    return
                }
                continue
            }
            if (current is Slot.CopyingRevoked) {
                current.copying.replaced?.value?.release()
                return
            }
            if (!slot.compareAndSet(current, Slot.Empty)) continue
            releaseOwnedBy(current)
            return
        }
    }

    private fun releaseOwnedBy(slot: Slot) {
        when (slot) {
            is Slot.Frame -> slot.value.release()
            is Slot.Reservation -> slot.replaced?.value?.release()
            is Slot.Copying -> Unit
            is Slot.CopyingRevoked -> Unit
            Slot.Empty, Slot.Closed -> Unit
        }
    }

    private fun reject() {
        rejectedFrames.incrementAndGet()
    }

    companion object {
        const val ADMISSION_INTERVAL_MILLIS = 200L
        const val SOURCE_QUARANTINE_MILLIS = 500L
        private const val RGBA_BYTES = 4L
    }
}
