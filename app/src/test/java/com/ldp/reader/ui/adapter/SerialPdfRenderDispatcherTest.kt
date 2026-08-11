package com.ldp.reader.ui.adapter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class SerialPdfRenderDispatcherTest {

    @Test
    fun renderWorkIsSerializedOnOneWorker() {
        val dispatcher = SerialPdfRenderDispatcher<String>(
            worker = Executors.newSingleThreadExecutor(),
            resultExecutor = Executor { it.run() }
        )
        val firstStarted = CountDownLatch(1)
        val allowFirstToFinish = CountDownLatch(1)
        val bothDelivered = CountDownLatch(2)
        val events = Collections.synchronizedList(mutableListOf<String>())

        dispatcher.submit(
            work = {
                events += "first-start"
                firstStarted.countDown()
                allowFirstToFinish.await(2, TimeUnit.SECONDS)
                events += "first-end"
                "first"
            },
            onResult = {
                events += "first-result"
                bothDelivered.countDown()
            },
            onDiscard = { error("current result must not be discarded") }
        )
        dispatcher.submit(
            work = {
                events += "second-start"
                "second"
            },
            onResult = {
                events += "second-result"
                bothDelivered.countDown()
            },
            onDiscard = { error("current result must not be discarded") }
        )

        assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
        assertFalse(events.contains("second-start"))
        allowFirstToFinish.countDown()
        assertTrue(bothDelivered.await(2, TimeUnit.SECONDS))
        assertEquals(
            listOf("first-start", "first-end", "first-result", "second-start", "second-result"),
            events.toList()
        )
        dispatcher.close {}
    }

    @Test
    fun cancelledQueuedGenerationNeverStarts() {
        val dispatcher = SerialPdfRenderDispatcher<String>(
            worker = Executors.newSingleThreadExecutor(),
            resultExecutor = Executor { it.run() }
        )
        val firstStarted = CountDownLatch(1)
        val allowFirstToFinish = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val cancelledWorkStarted = AtomicBoolean(false)

        dispatcher.submit(
            work = {
                firstStarted.countDown()
                allowFirstToFinish.await(2, TimeUnit.SECONDS)
                "first"
            },
            onResult = {},
            onDiscard = {}
        )
        val cancelled = dispatcher.submit(
            work = {
                cancelledWorkStarted.set(true)
                "cancelled"
            },
            onResult = { error("cancelled generation must not be delivered") },
            onDiscard = { error("queued generation must not allocate a result") }
        )

        assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
        dispatcher.cancel(cancelled)
        allowFirstToFinish.countDown()
        dispatcher.close { closed.countDown() }

        assertTrue(closed.await(2, TimeUnit.SECONDS))
        assertFalse(cancelledWorkStarted.get())
    }

    @Test
    fun cancelledRunningGenerationDiscardsItsCompletedResult() {
        val resultCallbacks = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        val resultCallbackQueued = CountDownLatch(1)
        val dispatcher = SerialPdfRenderDispatcher<String>(
            worker = Executors.newSingleThreadExecutor(),
            resultExecutor = Executor {
                resultCallbacks.add(it)
                resultCallbackQueued.countDown()
            }
        )
        val workFinished = CountDownLatch(1)
        val discarded = CountDownLatch(1)
        val delivered = AtomicBoolean(false)
        val request = dispatcher.submit(
            work = {
                workFinished.countDown()
                "old-page"
            },
            onResult = { delivered.set(true) },
            onDiscard = { discarded.countDown() }
        )

        assertTrue(workFinished.await(2, TimeUnit.SECONDS))
        assertTrue(resultCallbackQueued.await(2, TimeUnit.SECONDS))
        dispatcher.cancel(request)
        resultCallbacks.remove().run()

        assertTrue(discarded.await(2, TimeUnit.SECONDS))
        assertFalse(delivered.get())
        dispatcher.close {}
    }

    @Test
    fun rendererCloseWaitsForActiveRenderAndCancelsLaterWork() {
        val dispatcher = SerialPdfRenderDispatcher<String>(
            worker = Executors.newSingleThreadExecutor(),
            resultExecutor = Executor { it.run() }
        )
        val renderStarted = CountDownLatch(1)
        val allowRenderToFinish = CountDownLatch(1)
        val rendererClosed = CountDownLatch(1)
        val queuedRenderStarted = AtomicBoolean(false)

        dispatcher.submit(
            work = {
                renderStarted.countDown()
                allowRenderToFinish.await(2, TimeUnit.SECONDS)
                "active"
            },
            onResult = { error("closing generation must not be delivered") },
            onDiscard = {}
        )
        dispatcher.submit(
            work = {
                queuedRenderStarted.set(true)
                "queued"
            },
            onResult = { error("closing generation must not be delivered") },
            onDiscard = {}
        )

        assertTrue(renderStarted.await(2, TimeUnit.SECONDS))
        dispatcher.close { rendererClosed.countDown() }
        assertFalse(rendererClosed.await(100, TimeUnit.MILLISECONDS))
        allowRenderToFinish.countDown()

        assertTrue(rendererClosed.await(2, TimeUnit.SECONDS))
        assertFalse(queuedRenderStarted.get())
    }
}
