package com.yinxin.uavfir.firedetection

import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SerializedVisibleFireNcnnRuntimeTest {
    @Test
    fun concurrentCreateInferAndCloseNeverOverlapDelegateOperations() {
        val delegate = RecordingDelegate()
        val runtime = SerializedVisibleFireNcnnRuntime(delegate)
        val start = CountDownLatch(1)
        val handles = Collections.synchronizedList(mutableListOf<Long>())
        val creators = List(4) { index ->
            thread(start = true, name = "ncnn-create-$index") {
                check(start.await(2, TimeUnit.SECONDS))
                handles += runtime.create("model.param", "model.bin")
            }
        }
        start.countDown()
        creators.forEach { it.join(5_000) }
        assertTrue(creators.none(Thread::isAlive))

        val operations = handles.flatMap { handle ->
            listOf(
                thread(start = true) { runtime.infer(handle, ByteBuffer.allocateDirect(4)) },
                thread(start = true) { runtime.close(handle) },
            )
        }
        operations.forEach { it.join(5_000) }

        assertTrue(operations.none(Thread::isAlive))
        assertEquals(1, delegate.maxConcurrent.get())
        assertEquals(4, delegate.createCount.get())
        assertEquals(4, delegate.inferCount.get())
        assertEquals(4, delegate.closeCount.get())
    }

    @Test
    fun failedCreateReleasesSerializationLockForNextCreate() {
        val delegate = RecordingDelegate()
        val runtime = SerializedVisibleFireNcnnRuntime(delegate)
        delegate.failNextCreate.set(true)

        val failure = runCatching { runtime.create("bad.param", "bad.bin") }.exceptionOrNull()
        val handle = runtime.create("good.param", "good.bin")

        assertTrue(failure is IllegalStateException)
        assertTrue(handle > 0)
        assertEquals(2, delegate.createCount.get())
        assertEquals(1, delegate.maxConcurrent.get())
    }

    private class RecordingDelegate : VisibleFireNcnnRuntime {
        val maxConcurrent = AtomicInteger()
        val createCount = AtomicInteger()
        val inferCount = AtomicInteger()
        val closeCount = AtomicInteger()
        val failNextCreate = AtomicBoolean()
        private val active = AtomicInteger()
        private val nextHandle = AtomicInteger(1)

        override fun loadLibrary() = operation { Unit }

        override fun create(paramPath: String, binPath: String): Long = operation {
            createCount.incrementAndGet()
            if (failNextCreate.compareAndSet(true, false)) error("create failed")
            nextHandle.getAndIncrement().toLong()
        }

        override fun infer(handle: Long, input: ByteBuffer): FloatArray = operation {
            inferCount.incrementAndGet()
            FloatArray(0)
        }

        override fun close(handle: Long) = operation {
            closeCount.incrementAndGet()
            Unit
        }

        private fun <T> operation(block: () -> T): T {
            val now = active.incrementAndGet()
            maxConcurrent.accumulateAndGet(now, ::maxOf)
            try {
                Thread.sleep(20)
                return block()
            } finally {
                active.decrementAndGet()
            }
        }
    }
}
