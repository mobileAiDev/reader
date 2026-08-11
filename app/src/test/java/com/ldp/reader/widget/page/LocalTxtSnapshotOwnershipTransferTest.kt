package com.ldp.reader.widget.page

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class LocalTxtSnapshotOwnershipTransferTest {

    @Test
    fun cancellationBeforeIoResultDeliveryClosesPreparedSnapshot() = runBlocking {
        val callerDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + callerDispatcher)
        val preparationStarted = CountDownLatch(1)
        val allowPreparationToReturn = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val delivered = AtomicBoolean(false)
        try {
            val job = scope.launch {
                LocalTxtSnapshotOwnershipTransfer.prepare {
                    preparationStarted.countDown()
                    assertTrue(allowPreparationToReturn.await(5, TimeUnit.SECONDS))
                    Closeable { closed.countDown() }
                }
                delivered.set(true)
            }

            assertTrue(preparationStarted.await(5, TimeUnit.SECONDS))
            job.cancel()
            allowPreparationToReturn.countDown()
            job.join()

            assertTrue(closed.await(5, TimeUnit.SECONDS))
            assertFalse(delivered.get())
        } finally {
            scope.cancel()
            callerDispatcher.close()
        }
    }

    @Test
    fun successfulDeliveryTransfersCloseResponsibilityToCaller() = runBlocking {
        val closed = AtomicBoolean(false)

        val prepared = LocalTxtSnapshotOwnershipTransfer.prepare {
            Closeable { closed.set(true) }
        }

        assertFalse(closed.get())
        prepared.close()
        assertTrue(closed.get())
    }
}
