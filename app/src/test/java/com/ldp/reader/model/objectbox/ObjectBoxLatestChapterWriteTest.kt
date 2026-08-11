package com.ldp.reader.model.objectbox

import com.ldp.reader.model.bean.BookChapterBean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import io.objectbox.BoxStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ObjectBoxLatestChapterWriteTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private var boxStore: BoxStore? = null

    @After
    fun tearDown() {
        boxStore?.close()
    }

    @Test
    fun olderTransactionCannotOverwriteOrMarkAfterNewerGenerationWasRequested() {
        val store = newStore()
        val older = beginWrite(store, "book", "fingerprint-a")
        val newerRequest = store.beginChapterCatalogWriteRequest("book")

        assertFalse(store.replaceBookChaptersIfLatest(older, listOf(chapter("chapter-a"))))
        val newer = store.bindChapterCatalogWrite(newerRequest, "fingerprint-b")!!
        assertTrue(store.replaceBookChaptersIfLatest(newer, listOf(chapter("chapter-b"))))
        assertEquals("chapter-b", store.getBookChapters("book").single().id)

        var markedFingerprint: String? = null
        assertFalse(
            store.runIfChapterCatalogWriteLatest(older) {
                markedFingerprint = older.fingerprint
            }
        )
        assertTrue(
            store.runIfChapterCatalogWriteLatest(newer) {
                markedFingerprint = newer.fingerprint
            }
        )
        assertEquals("fingerprint-b", markedFingerprint)
    }

    @Test
    fun generationsAreIndependentPerBook() {
        val store = newStore()
        val firstBook = beginWrite(store, "book-a", "fingerprint-a")
        val secondBook = beginWrite(store, "book-b", "fingerprint-b")

        assertTrue(store.replaceBookChaptersIfLatest(firstBook, listOf(chapter("chapter-a"))))
        assertTrue(store.replaceBookChaptersIfLatest(secondBook, listOf(chapter("chapter-b"))))

        assertEquals("chapter-a", store.getBookChapters("book-a").single().id)
        assertEquals("chapter-b", store.getBookChapters("book-b").single().id)
    }

    @Test
    fun committedCatalogActivationSurvivesStoreRecreation() {
        val store = newStore()
        val token = beginWrite(store, "book", "fingerprint-a")

        boxStore!!.runInTx {
            assertTrue(store.replaceBookChaptersIfLatest(token, listOf(chapter("chapter-a"))))
        }

        val recreatedStore = ObjectBoxBookStore(boxStore!!)
        assertEquals("chapter-a", recreatedStore.getBookChapters("book").single().id)
    }

    @Test
    fun requestArrivingBeforeAsyncCommitCallbackRemovesOnlyCommittedOlderRows() {
        val store = newStore()
        val older = beginWrite(store, "book", "fingerprint-a")

        boxStore!!.runInTx {
            assertTrue(store.replaceBookChaptersIfLatest(older, listOf(chapter("chapter-a"))))
        }
        val newerRequest = store.beginChapterCatalogWriteRequest("book")

        val recreatedBeforeCallback = ObjectBoxBookStore(boxStore!!)
        assertTrue(recreatedBeforeCallback.getBookChapters("book").isEmpty())
        assertFalse(store.finalizeChapterCatalogWriteAfterCommit(older))
        assertTrue(store.getBookChapters("book").isEmpty())

        val newer = store.bindChapterCatalogWrite(newerRequest, "fingerprint-b")!!
        boxStore!!.runInTx {
            assertTrue(store.replaceBookChaptersIfLatest(newer, listOf(chapter("chapter-b"))))
        }
        assertTrue(store.finalizeChapterCatalogWriteAfterCommit(newer))
        assertEquals("chapter-b", store.getBookChapters("book").single().id)
    }

    @Test
    fun staleCommitCleanupNeverDeletesRowsAlreadyOwnedByNewerToken() {
        val store = newStore()
        val older = beginWrite(store, "book", "fingerprint-a")
        boxStore!!.runInTx {
            assertTrue(store.replaceBookChaptersIfLatest(older, listOf(chapter("chapter-a"))))
        }
        val newer = beginWrite(store, "book", "fingerprint-b")
        boxStore!!.runInTx {
            assertTrue(store.replaceBookChaptersIfLatest(newer, listOf(chapter("chapter-b"))))
        }

        assertFalse(store.finalizeChapterCatalogWriteAfterCommit(older))
        assertEquals("chapter-b", store.getBookChapters("book").single().id)
    }

    @Test
    fun pendingGenerationRejectsOldCommittedRowsEvenIfCommitCallbackNeverRuns() {
        val store = newStore()
        val older = beginWrite(store, "book", "fingerprint-a")
        boxStore!!.runInTx {
            assertTrue(store.replaceBookChaptersIfLatest(older, listOf(chapter("chapter-a"))))
        }
        store.beginChapterCatalogWriteRequest("book")

        val recreatedAfterProcessDeath = ObjectBoxBookStore(boxStore!!)

        assertTrue(recreatedAfterProcessDeath.getBookChapters("book").isEmpty())
    }

    @Test
    fun pendingGenerationAlsoRejectsLegacyRowsWithoutTokenMetadata() {
        val store = newStore()
        store.replaceBookChapters("book", listOf(chapter("legacy-a")))

        store.beginChapterCatalogWriteRequest("book")
        val recreatedAfterProcessDeath = ObjectBoxBookStore(boxStore!!)

        assertTrue(recreatedAfterProcessDeath.getBookChapters("book").isEmpty())
    }

    @Test
    fun durableRequestCanWaitForCatalogTransactionWithoutCommitCallbackDeadlock() {
        val store = newStore()
        val older = beginWrite(store, "book", "fingerprint-a")
        val transactionReady = CountDownLatch(1)
        val allowCommit = CountDownLatch(1)
        val callbackDone = CountDownLatch(1)
        val newerRequestDone = CountDownLatch(1)
        val callbackError = AtomicReference<Throwable?>(null)

        store.runInTxAsync(
            Runnable {
                assertTrue(store.replaceBookChaptersIfLatest(older, listOf(chapter("chapter-a"))))
                transactionReady.countDown()
                assertTrue(allowCommit.await(5, TimeUnit.SECONDS))
            },
            onComplete = { error ->
                try {
                    if (error != null) throw error
                    store.finalizeChapterCatalogWriteAfterCommit(older)
                } catch (failure: Throwable) {
                    callbackError.set(failure)
                } finally {
                    callbackDone.countDown()
                }
            }
        )
        assertTrue(transactionReady.await(5, TimeUnit.SECONDS))
        Thread {
            store.beginChapterCatalogWriteRequest("book")
            newerRequestDone.countDown()
        }.start()

        allowCommit.countDown()

        assertTrue(newerRequestDone.await(5, TimeUnit.SECONDS))
        assertTrue(callbackDone.await(5, TimeUnit.SECONDS))
        callbackError.get()?.let { throw AssertionError(it) }
        assertTrue(store.getBookChapters("book").isEmpty())
    }

    private fun newStore(): ObjectBoxBookStore {
        boxStore = MyObjectBox.builder()
            .directory(temporaryFolder.newFolder("objectbox-latest-write"))
            .build()
        return ObjectBoxBookStore(boxStore!!)
    }

    private fun beginWrite(
        store: ObjectBoxBookStore,
        bookId: String,
        fingerprint: String
    ): ChapterCatalogWriteToken {
        val request = store.beginChapterCatalogWriteRequest(bookId)
        return store.bindChapterCatalogWrite(request, fingerprint)!!
    }

    private fun chapter(id: String): BookChapterBean {
        return BookChapterBean(id, "link-$id", "title-$id", null, false, null, 0L, 10L)
    }
}
