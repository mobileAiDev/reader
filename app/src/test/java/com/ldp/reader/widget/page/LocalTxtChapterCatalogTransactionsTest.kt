package com.ldp.reader.widget.page

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LocalTxtChapterCatalogTransactionsTest {

    @Test
    fun durableCatalogFenceNeverRunsOnCallerThread() = runBlocking {
        val callerThread = Thread.currentThread()

        val transactionThread = LocalTxtChapterCatalogTransactions.onIo {
            Thread.currentThread()
        }

        assertNotSame(callerThread, transactionThread)
    }

    @Test
    fun durableCatalogFencesAreSerializedWhileCallerRemainsSuspendable() = runBlocking {
        val firstStarted = CountDownLatch(1)
        val allowFirstToFinish = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val first = async(Dispatchers.Default) {
            LocalTxtChapterCatalogTransactions.onIo {
                firstStarted.countDown()
                assertTrue(allowFirstToFinish.await(5, TimeUnit.SECONDS))
            }
        }
        assertTrue(firstStarted.await(5, TimeUnit.SECONDS))
        val second = async(Dispatchers.Default) {
            LocalTxtChapterCatalogTransactions.onIo {
                secondStarted.countDown()
            }
        }

        assertFalse(secondStarted.await(100, TimeUnit.MILLISECONDS))
        allowFirstToFinish.countDown()
        first.await()
        second.await()
        assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
    }
}
