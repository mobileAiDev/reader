package com.ldp.reader.model.local

import com.ldp.reader.model.bean.BookChapterBean
import com.ldp.reader.model.bean.BookRecordBean
import com.ldp.reader.model.bean.CollBookBean
import com.ldp.reader.model.objectbox.MyObjectBox
import com.ldp.reader.model.objectbox.ObjectBoxBookStore
import com.ldp.reader.source.SourceEngineCachedCatalogMigration
import com.ldp.reader.ui.activity.BookShelfAddGeneration
import com.ldp.reader.utils.BookIdentity
import io.objectbox.BoxStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BookRepositoryRemovalTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private var boxStore: BoxStore? = null

    @After
    fun tearDown() {
        boxStore?.close()
    }

    @Test
    fun givingUpAndReaddingSourceEngineBookStartsWithoutOldReadingProgress() {
        val store = MyObjectBox.builder()
            .directory(temporaryFolder.newFolder("objectbox"))
            .build()
        boxStore = store
        val deletedCacheIds = mutableListOf<String?>()
        val clearedProgressIds = mutableListOf<String?>()
        val repository = BookRepository(
            store,
            cachedBookDeleter = { deletedCacheIds.add(it) },
            readingProgressClearer = { clearedProgressIds.add(it) }
        )
        val title = "诡秘之主"
        val author = "爱潜水的乌贼"
        val shelfId = BookIdentity.sourceEngineShelfId(title, author)
        val routeId = "source_engine_book_non_numeric_route_payload"
        val collected = sourceEngineBook(shelfId, routeId, title, author)

        repository.saveCollBook(collected)
        val chaptersSaved = CountDownLatch(1)
        repository.saveBookChaptersWithAsync(
            listOf(chapter(shelfId, 0), chapter(shelfId, 1)),
            onSuccess = chaptersSaved::countDown
        )
        assertTrue("chapter fixture should be committed", chaptersSaved.await(5, TimeUnit.SECONDS))
        repository.saveBookRecord(BookRecordBean(shelfId, 1, 37))

        repository.deleteCollBookWithFiles(collected)

        assertNull(repository.getCollBook(shelfId))
        assertTrue(repository.getBookChapters(shelfId).isEmpty())
        assertNull("giving up must remove the record that PageLoader restores", repository.getBookRecord(shelfId))
        assertEquals(listOf(shelfId), deletedCacheIds)
        assertEquals(listOf(shelfId), clearedProgressIds)

        repository.saveCollBook(sourceEngineBook(shelfId, routeId, title, author))

        assertNull("re-added book must start from the default first chapter", repository.getBookRecord(shelfId))
    }

    @Test
    fun cancelledCatalogRefreshCannotReinsertBookAfterGivingUp() {
        val store = MyObjectBox.builder()
            .directory(temporaryFolder.newFolder("objectbox-stale-add"))
            .build()
        boxStore = store
        val repository = BookRepository(
            store,
            cachedBookDeleter = {},
            readingProgressClearer = {}
        )
        val title = "诡秘之主"
        val author = "爱潜水的乌贼"
        val shelfId = BookIdentity.sourceEngineShelfId(title, author)
        val routeId = "source_engine_book_non_numeric_route_payload"
        val staleCatalogResult = sourceEngineBook(shelfId, routeId, title, author)
        val addGeneration = BookShelfAddGeneration()

        repository.saveCollBook(staleCatalogResult)
        val pendingAdd = addGeneration.beginAdd()
        addGeneration.giveUp()
        repository.deleteCollBookWithFiles(staleCatalogResult)

        val writeCompleted = CountDownLatch(1)
        val staleWriteApplied = AtomicBoolean(true)
        repository.saveCollBookWithAsync(
            staleCatalogResult,
            shouldApply = { addGeneration.isCurrent(pendingAdd) },
            onComplete = { applied ->
                staleWriteApplied.set(applied)
                writeCompleted.countDown()
            }
        )

        assertTrue("stale async write should finish deterministically", writeCompleted.await(5, TimeUnit.SECONDS))
        assertFalse("cancelled add must not publish its catalog result", staleWriteApplied.get())
        assertNull("cancelled add must not put the book back on the shelf", repository.getCollBook(shelfId))
    }

    @Test
    fun readerCatalogRemainsAuthoritativeWhenQueuedDetailWriteCompletesLate() {
        val store = MyObjectBox.builder()
            .directory(temporaryFolder.newFolder("objectbox-reader-catalog-owner"))
            .build()
        boxStore = store
        val repository = BookRepository(
            store,
            cachedBookDeleter = {},
            readingProgressClearer = {}
        )
        val title = "诡秘之主"
        val author = "爱潜水的乌贼"
        val shelfId = BookIdentity.sourceEngineShelfId(title, author)
        val routeId = "source_engine_book_non_numeric_route_payload"
        val generation = BookShelfAddGeneration()
        val pendingDetailWrite = generation.beginAdd()
        repository.saveCollBook(sourceEngineBook(shelfId, routeId, title, author))

        val queuedDetail = sourceEngineBook(shelfId, routeId, title, author).apply {
            setBookChapters(listOf(chapter(shelfId, 0)))
            chaptersCount = 1
            lastChapter = "详情页旧目录"
        }
        val detailGuardReached = CountDownLatch(1)
        val releaseDetailGuard = CountDownLatch(1)
        val detailWriteFinished = CountDownLatch(1)
        val detailWriteApplied = AtomicBoolean(true)
        repository.saveCollBookWithAsync(
            queuedDetail,
            shouldApply = {
                detailGuardReached.countDown()
                releaseDetailGuard.await(5, TimeUnit.SECONDS) && generation.isCurrent(pendingDetailWrite)
            },
            onComplete = { applied ->
                detailWriteApplied.set(applied)
                detailWriteFinished.countDown()
            }
        )

        assertTrue("detail write should be queued before reading starts", detailGuardReached.await(5, TimeUnit.SECONDS))
        generation.handOffCatalogPersistenceToReader()
        val readerCatalog = listOf(chapter(shelfId, 0), chapter(shelfId, 1))
        val readerBook = sourceEngineBook(shelfId, routeId, title, author).apply {
            setBookChapters(readerCatalog)
            chaptersCount = readerCatalog.size
            lastChapter = readerCatalog.last().title
        }
        val readerWriteFinished = CountDownLatch(1)
        repository.saveCollBookWithAsync(readerBook, onComplete = { readerWriteFinished.countDown() })
        releaseDetailGuard.countDown()

        assertTrue("queued detail write should finish", detailWriteFinished.await(5, TimeUnit.SECONDS))
        assertTrue("reader catalog should finish", readerWriteFinished.await(5, TimeUnit.SECONDS))
        assertFalse("detail must lose catalog ownership after reading starts", detailWriteApplied.get())
        assertEquals(readerCatalog.map { it.title }, repository.getBookChapters(shelfId).map { it.title })
        assertEquals(readerCatalog.last().title, repository.getCollBook(shelfId)!!.lastChapter)
    }

    @Test
    fun readerCatalogCommitsLastWhenDetailTransactionAlreadyPassedItsGate() {
        val store = MyObjectBox.builder()
            .directory(temporaryFolder.newFolder("objectbox-detail-transaction-first"))
            .build()
        boxStore = store
        val repository = BookRepository(
            store,
            cachedBookDeleter = {},
            readingProgressClearer = {}
        )
        val title = "诡秘之主"
        val author = "爱潜水的乌贼"
        val shelfId = BookIdentity.sourceEngineShelfId(title, author)
        val routeId = "source_engine_book_non_numeric_route_payload"
        val generation = BookShelfAddGeneration()
        val pendingDetailWrite = generation.beginAdd()
        repository.saveCollBook(sourceEngineBook(shelfId, routeId, title, author))

        val detailCatalog = listOf(chapter(shelfId, 0))
        val detailBook = sourceEngineBook(shelfId, routeId, title, author).apply {
            setBookChapters(detailCatalog)
            chaptersCount = detailCatalog.size
            lastChapter = detailCatalog.last().title
        }
        val detailGatePassed = CountDownLatch(1)
        val releaseDetailTransaction = CountDownLatch(1)
        val detailWriteFinished = CountDownLatch(1)
        val detailWriteApplied = AtomicBoolean(false)
        repository.saveCollBookWithAsync(
            detailBook,
            shouldApply = {
                val acceptedInsideTransaction = generation.isCurrent(pendingDetailWrite)
                detailGatePassed.countDown()
                releaseDetailTransaction.await(5, TimeUnit.SECONDS)
                acceptedInsideTransaction
            },
            onComplete = { applied ->
                detailWriteApplied.set(applied)
                detailWriteFinished.countDown()
            }
        )
        assertTrue(
            "detail should pass its gate from inside the first ObjectBox transaction",
            detailGatePassed.await(5, TimeUnit.SECONDS)
        )

        generation.handOffCatalogPersistenceToReader()
        val readerCatalog = listOf(chapter(shelfId, 0), chapter(shelfId, 1), chapter(shelfId, 2))
        val readerBook = sourceEngineBook(shelfId, routeId, title, author).apply {
            setBookChapters(readerCatalog)
            chaptersCount = readerCatalog.size
            lastChapter = readerCatalog.last().title
        }
        val readerWriteFinished = CountDownLatch(1)
        repository.saveCollBookWithAsync(readerBook, onComplete = { readerWriteFinished.countDown() })
        releaseDetailTransaction.countDown()

        assertTrue("detail transaction should commit", detailWriteFinished.await(5, TimeUnit.SECONDS))
        assertTrue("reader transaction should commit after detail", readerWriteFinished.await(5, TimeUnit.SECONDS))
        assertTrue("the transaction that already passed its gate is allowed to finish", detailWriteApplied.get())
        assertEquals(
            "reader and detail use the same repository/store, so the queued reader transaction wins",
            readerCatalog.map { it.title },
            repository.getBookChapters(shelfId).map { it.title }
        )
        assertEquals(readerCatalog.last().title, repository.getCollBook(shelfId)!!.lastChapter)
    }

    @Test
    fun persistedPollutedCatalogCannotWinLaterSaveByItsLargerRawSize() {
        val store = MyObjectBox.builder()
            .directory(temporaryFolder.newFolder("objectbox-catalog-migration"))
            .build()
        boxStore = store
        val repository = BookRepository(
            store,
            cachedBookDeleter = {},
            readingProgressClearer = {}
        )
        val title = "诡秘之主"
        val author = "爱潜水的乌贼"
        val shelfId = BookIdentity.sourceEngineShelfId(title, author)
        val routeId = "source_engine_book_non_numeric_route_payload"
        val stored = sourceEngineBook(shelfId, routeId, title, author)
        repository.saveCollBook(stored)
        val polluted = pollutedChapters(shelfId)
        ObjectBoxBookStore(store).replaceBookChapters(shelfId, polluted)

        val incoming = sourceEngineBook(shelfId, routeId, title, author).apply {
            setBookChapters(polluted.filterNot { chapter ->
                chapter.title == "378. 第381章" || chapter.title == "VIP. 第381章(最新)"
            })
            chaptersCount = getBookChapters().orEmpty().size
            lastChapter = getBookChapters().orEmpty().lastOrNull()?.title
        }
        val cleanSaved = CountDownLatch(1)
        repository.saveCollBookWithAsync(incoming, onComplete = { cleanSaved.countDown() })
        assertTrue("clean catalog should be committed", cleanSaved.await(5, TimeUnit.SECONDS))

        val persisted = repository.getBookChapters(shelfId)
        assertEquals(392, persisted.size)
        assertEquals(0, persisted.count { chapter -> chapter.title == "378. 第381章" })
        assertEquals(0, persisted.count { chapter -> chapter.title == "VIP. 第381章(最新)" })
        assertEquals("不是诈尸，不是遭了阿蒙", persisted.last().title)
    }

    @Test
    fun unsafeSameSizeCatalogRewriteIsNotPersistedAcrossRepositoryReopen() {
        val databaseDirectory = temporaryFolder.newFolder("objectbox-unsafe-catalog-rewrite")
        val store = MyObjectBox.builder()
            .directory(databaseDirectory)
            .build()
        boxStore = store
        val repository = BookRepository(
            store,
            cachedBookDeleter = {},
            readingProgressClearer = {}
        )
        val title = "斗罗大陆"
        val author = "唐家三少"
        val shelfId = BookIdentity.sourceEngineShelfId(title, author)
        val routeId = "source_engine_book_non_numeric_route_payload"
        val storedCatalog = namedCatalog(
            shelfId,
            "第五百九十章 前一章",
            "第五百九十一章 融合...",
            "第五百九十二章 后一章"
        )
        val stored = sourceEngineBook(shelfId, routeId, title, author).apply {
            setBookChapters(storedCatalog)
            chaptersCount = storedCatalog.size
            lastChapter = storedCatalog.last().title
        }
        val initialWriteFinished = CountDownLatch(1)
        repository.saveCollBookWithAsync(stored, onComplete = { initialWriteFinished.countDown() })
        assertTrue("initial catalog should be committed", initialWriteFinished.await(5, TimeUnit.SECONDS))

        val unsafeCatalog = namedCatalog(
            shelfId,
            "第五百九十章 前一章",
            "第五百九十一章 融合...",
            "第五百九十二章 完全无关的改名"
        )
        val unsafeRefresh = sourceEngineBook(shelfId, routeId, title, author).apply {
            setBookChapters(unsafeCatalog)
            chaptersCount = unsafeCatalog.size
            lastChapter = unsafeCatalog.last().title
        }
        val refreshWriteFinished = CountDownLatch(1)
        repository.saveCollBookWithAsync(unsafeRefresh, onComplete = { refreshWriteFinished.countDown() })
        assertTrue("unsafe refresh transaction should finish", refreshWriteFinished.await(5, TimeUnit.SECONDS))

        store.close()
        boxStore = null
        val reopenedStore = MyObjectBox.builder()
            .directory(databaseDirectory)
            .build()
        boxStore = reopenedStore
        val reopenedRepository = BookRepository(
            reopenedStore,
            cachedBookDeleter = {},
            readingProgressClearer = {}
        )

        assertEquals(storedCatalog.map { it.title }, reopenedRepository.getBookChapters(shelfId).map { it.title })
        assertEquals(storedCatalog.last().title, reopenedRepository.getCollBook(shelfId)!!.lastChapter)
    }

    @Test
    fun safeSameRouteTitleCompletionIsPersistedAcrossRepositoryReopen() {
        val databaseDirectory = temporaryFolder.newFolder("objectbox-safe-catalog-title-completion")
        val store = MyObjectBox.builder()
            .directory(databaseDirectory)
            .build()
        boxStore = store
        val repository = BookRepository(
            store,
            cachedBookDeleter = {},
            readingProgressClearer = {}
        )
        val title = "斗罗大陆"
        val author = "唐家三少"
        val shelfId = BookIdentity.sourceEngineShelfId(title, author)
        val routeId = "source_engine_book_non_numeric_route_payload"
        val storedCatalog = namedCatalog(
            shelfId,
            "第五百九十章 前一章",
            "第五百九十一章 融合...",
            "第五百九十二章 后一章"
        )
        val stored = sourceEngineBook(shelfId, routeId, title, author).apply {
            setBookChapters(storedCatalog)
            chaptersCount = storedCatalog.size
            lastChapter = storedCatalog.last().title
        }
        val initialWriteFinished = CountDownLatch(1)
        repository.saveCollBookWithAsync(stored, onComplete = { initialWriteFinished.countDown() })
        assertTrue("initial catalog should be committed", initialWriteFinished.await(5, TimeUnit.SECONDS))

        val completedCatalog = namedCatalog(
            shelfId,
            "第五百九十章 前一章",
            "第五百九十一章 融合成功！",
            "第五百九十二章 后一章"
        )
        val completedRefresh = sourceEngineBook(shelfId, routeId, title, author).apply {
            setBookChapters(completedCatalog)
            chaptersCount = completedCatalog.size
            lastChapter = completedCatalog.last().title
        }
        val refreshWriteFinished = CountDownLatch(1)
        repository.saveCollBookWithAsync(completedRefresh, onComplete = { refreshWriteFinished.countDown() })
        assertTrue("safe title completion should finish", refreshWriteFinished.await(5, TimeUnit.SECONDS))

        store.close()
        boxStore = null
        val reopenedStore = MyObjectBox.builder()
            .directory(databaseDirectory)
            .build()
        boxStore = reopenedStore
        val reopenedRepository = BookRepository(
            reopenedStore,
            cachedBookDeleter = {},
            readingProgressClearer = {}
        )

        assertEquals(completedCatalog.map { it.title }, reopenedRepository.getBookChapters(shelfId).map { it.title })
    }

    @Test
    fun safeTailAppendIsPersistedAcrossRepositoryReopen() {
        val databaseDirectory = temporaryFolder.newFolder("objectbox-safe-catalog-tail-append")
        val store = MyObjectBox.builder()
            .directory(databaseDirectory)
            .build()
        boxStore = store
        val repository = BookRepository(
            store,
            cachedBookDeleter = {},
            readingProgressClearer = {}
        )
        val title = "斗罗大陆"
        val author = "唐家三少"
        val shelfId = BookIdentity.sourceEngineShelfId(title, author)
        val routeId = "source_engine_book_non_numeric_route_payload"
        val storedCatalog = namedCatalog(shelfId, "第一章", "第二章")
        val stored = sourceEngineBook(shelfId, routeId, title, author).apply {
            setBookChapters(storedCatalog)
            chaptersCount = storedCatalog.size
            lastChapter = storedCatalog.last().title
        }
        val initialWriteFinished = CountDownLatch(1)
        repository.saveCollBookWithAsync(stored, onComplete = { initialWriteFinished.countDown() })
        assertTrue("initial catalog should be committed", initialWriteFinished.await(5, TimeUnit.SECONDS))
        val appendedCatalog = namedCatalog(shelfId, "第一章", "第二章", "第三章 新章")
        val appended = sourceEngineBook(shelfId, routeId, title, author).apply {
            setBookChapters(appendedCatalog)
            chaptersCount = appendedCatalog.size
            lastChapter = appendedCatalog.last().title
        }
        val appendFinished = CountDownLatch(1)

        repository.saveCollBookWithAsync(appended, onComplete = { appendFinished.countDown() })
        assertTrue("tail append should finish", appendFinished.await(5, TimeUnit.SECONDS))

        store.close()
        boxStore = null
        val reopenedStore = MyObjectBox.builder()
            .directory(databaseDirectory)
            .build()
        boxStore = reopenedStore
        val reopenedRepository = BookRepository(
            reopenedStore,
            cachedBookDeleter = {},
            readingProgressClearer = {}
        )

        assertEquals(appendedCatalog.map { it.link }, reopenedRepository.getBookChapters(shelfId).map { it.link })
        assertEquals(appendedCatalog.map { it.title }, reopenedRepository.getBookChapters(shelfId).map { it.title })
        assertEquals(appendedCatalog.last().title, reopenedRepository.getCollBook(shelfId)!!.lastChapter)
    }

    @Test
    fun sameIdentityRefreshCannotReorderCatalogThroughPersistedStartOffsets() {
        val databaseDirectory = temporaryFolder.newFolder("objectbox-catalog-start-order")
        val store = MyObjectBox.builder()
            .directory(databaseDirectory)
            .build()
        boxStore = store
        val repository = BookRepository(
            store,
            cachedBookDeleter = {},
            readingProgressClearer = {}
        )
        val title = "斗罗大陆"
        val author = "唐家三少"
        val shelfId = BookIdentity.sourceEngineShelfId(title, author)
        val routeId = "source_engine_book_non_numeric_route_payload"
        val storedCatalog = namedCatalog(shelfId, "第一章", "第二章", "第三章")
        val stored = sourceEngineBook(shelfId, routeId, title, author).apply {
            setBookChapters(storedCatalog)
            chaptersCount = storedCatalog.size
            lastChapter = storedCatalog.last().title
        }
        val initialWriteFinished = CountDownLatch(1)
        repository.saveCollBookWithAsync(stored, onComplete = { initialWriteFinished.countDown() })
        assertTrue("initial catalog should be committed", initialWriteFinished.await(5, TimeUnit.SECONDS))
        val reorderedOffsets = namedCatalog(shelfId, "第一章", "第二章", "第三章").also { chapters ->
            chapters[0].start = 2
            chapters[1].start = 1
            chapters[2].start = 0
        }
        val refresh = sourceEngineBook(shelfId, routeId, title, author).apply {
            setBookChapters(reorderedOffsets)
            chaptersCount = reorderedOffsets.size
            lastChapter = reorderedOffsets.last().title
        }
        val refreshFinished = CountDownLatch(1)

        repository.saveCollBookWithAsync(refresh, onComplete = { refreshFinished.countDown() })
        assertTrue("same-identity refresh should finish", refreshFinished.await(5, TimeUnit.SECONDS))

        store.close()
        boxStore = null
        val reopenedStore = MyObjectBox.builder()
            .directory(databaseDirectory)
            .build()
        boxStore = reopenedStore
        val reopenedRepository = BookRepository(
            reopenedStore,
            cachedBookDeleter = {},
            readingProgressClearer = {}
        )
        val reopened = reopenedRepository.getBookChapters(shelfId)

        assertEquals(storedCatalog.map { it.link }, reopened.map { it.link })
        assertEquals(storedCatalog.map { it.title }, reopened.map { it.title })
        assertEquals(listOf(0L, 1L, 2L), reopened.map { it.start })
    }

    @Test
    fun rejectedDifferentRouteCatalogCannotPartiallyRebindShelfMetadata() {
        val databaseDirectory = temporaryFolder.newFolder("objectbox-rejected-route-rebind")
        val store = MyObjectBox.builder()
            .directory(databaseDirectory)
            .build()
        boxStore = store
        val repository = BookRepository(
            store,
            cachedBookDeleter = {},
            readingProgressClearer = {}
        )
        val title = "斗罗大陆"
        val author = "唐家三少"
        val shelfId = BookIdentity.sourceEngineShelfId(title, author)
        val storedRouteId = "source_engine_book_original_route_payload"
        val incomingRouteId = "source_engine_book_different_route_payload"
        val storedCatalog = namedCatalog(shelfId, "第一章", "第二章")
        val stored = sourceEngineBook(shelfId, storedRouteId, title, author).apply {
            setBookChapters(storedCatalog)
            chaptersCount = storedCatalog.size
            lastChapter = storedCatalog.last().title
            shortIntro = "旧简介"
        }
        val initialWriteFinished = CountDownLatch(1)
        repository.saveCollBookWithAsync(stored, onComplete = { initialWriteFinished.countDown() })
        assertTrue("initial catalog should be committed", initialWriteFinished.await(5, TimeUnit.SECONDS))
        val incompatibleCatalog = namedCatalog(shelfId, "第一章", "第二章").onEachIndexed { index, chapter ->
            chapter.link = "source_engine_chapter_different_route_$index"
        }
        val incompatible = sourceEngineBook(shelfId, incomingRouteId, title, author).apply {
            setBookChapters(incompatibleCatalog)
            chaptersCount = incompatibleCatalog.size
            lastChapter = "第二章 错误尾章"
            shortIntro = "新简介"
        }
        val refreshFinished = CountDownLatch(1)

        repository.saveCollBookWithAsync(incompatible, onComplete = { refreshFinished.countDown() })
        assertTrue("incompatible refresh should finish metadata storage", refreshFinished.await(5, TimeUnit.SECONDS))

        store.close()
        boxStore = null
        val reopenedStore = MyObjectBox.builder()
            .directory(databaseDirectory)
            .build()
        boxStore = reopenedStore
        val reopenedRepository = BookRepository(
            reopenedStore,
            cachedBookDeleter = {},
            readingProgressClearer = {}
        )
        val reopenedBook = reopenedRepository.getCollBook(shelfId)!!

        assertEquals(storedCatalog.map { it.link }, reopenedRepository.getBookChapters(shelfId).map { it.link })
        assertEquals(storedRouteId, reopenedBook.bookIdInBiquge)
        assertEquals(storedCatalog.size, reopenedBook.chaptersCount)
        assertEquals(storedCatalog.last().title, reopenedBook.lastChapter)
        assertEquals("新简介", reopenedBook.shortIntro)
    }

    @Test
    fun firstCatalogWithABlankRouteCannotPolluteCatalogMetadataAcrossRepositoryReopen() {
        val databaseDirectory = temporaryFolder.newFolder("objectbox-invalid-first-catalog")
        val store = MyObjectBox.builder()
            .directory(databaseDirectory)
            .build()
        boxStore = store
        val repository = BookRepository(
            store,
            cachedBookDeleter = {},
            readingProgressClearer = {}
        )
        val title = "斗罗大陆"
        val author = "唐家三少"
        val shelfId = BookIdentity.sourceEngineShelfId(title, author)
        val routeId = "source_engine_book_non_numeric_route_payload"
        repository.saveCollBook(sourceEngineBook(shelfId, routeId, title, author).apply {
            chaptersCount = 0
            lastChapter = "目录待加载"
            shortIntro = "旧简介"
        })
        val invalidCatalog = namedCatalog(shelfId, "第一章", "第二章").also { chapters ->
            chapters[1].link = ""
        }
        val incoming = sourceEngineBook(shelfId, routeId, title, author).apply {
            setBookChapters(invalidCatalog)
            chaptersCount = invalidCatalog.size
            lastChapter = "第二章 错误尾章"
            shortIntro = "新简介"
        }
        val writeFinished = CountDownLatch(1)

        repository.saveCollBookWithAsync(incoming, onComplete = { writeFinished.countDown() })
        assertTrue("invalid first catalog transaction should finish", writeFinished.await(5, TimeUnit.SECONDS))

        store.close()
        boxStore = null
        val reopenedStore = MyObjectBox.builder()
            .directory(databaseDirectory)
            .build()
        boxStore = reopenedStore
        val reopenedRepository = BookRepository(
            reopenedStore,
            cachedBookDeleter = {},
            readingProgressClearer = {}
        )
        val reopenedBook = reopenedRepository.getCollBook(shelfId)!!

        assertTrue(reopenedRepository.getBookChapters(shelfId).isEmpty())
        assertEquals(0, reopenedBook.chaptersCount)
        assertEquals("目录待加载", reopenedBook.lastChapter)
        assertEquals("新简介", reopenedBook.shortIntro)
    }

    @Test
    fun firstSynchronousShelfSaveDoesNotTrustInvalidAttachedCatalogMetadata() {
        val store = MyObjectBox.builder()
            .directory(temporaryFolder.newFolder("objectbox-invalid-first-sync-metadata"))
            .build()
        boxStore = store
        val repository = BookRepository(
            store,
            cachedBookDeleter = {},
            readingProgressClearer = {}
        )
        val title = "斗罗大陆"
        val author = "唐家三少"
        val shelfId = BookIdentity.sourceEngineShelfId(title, author)
        val routeId = "source_engine_book_non_numeric_route_payload"
        val invalidCatalog = namedCatalog(shelfId, "第一章", "第二章").also { chapters ->
            chapters[1].link = ""
        }
        val incoming = sourceEngineBook(shelfId, routeId, title, author).apply {
            setBookChapters(invalidCatalog)
            chaptersCount = invalidCatalog.size
            lastChapter = "第二章 错误尾章"
            shortIntro = "可保留的新简介"
        }

        repository.saveCollBook(incoming)

        val saved = repository.getCollBook(shelfId)!!
        assertTrue(repository.getBookChapters(shelfId).isEmpty())
        assertEquals(0, saved.chaptersCount)
        assertNull(saved.lastChapter)
        assertEquals("可保留的新简介", saved.shortIntro)
        assertEquals(routeId, saved.bookIdInBiquge)
    }

    @Test
    fun metadataOnlyRefreshStillUpdatesAnEmptyCatalogShelfEntry() {
        val store = MyObjectBox.builder()
            .directory(temporaryFolder.newFolder("objectbox-metadata-only-empty-catalog"))
            .build()
        boxStore = store
        val repository = BookRepository(
            store,
            cachedBookDeleter = {},
            readingProgressClearer = {}
        )
        val title = "斗罗大陆"
        val author = "唐家三少"
        val shelfId = BookIdentity.sourceEngineShelfId(title, author)
        val routeId = "source_engine_book_non_numeric_route_payload"
        repository.saveCollBook(sourceEngineBook(shelfId, routeId, title, author).apply {
            chaptersCount = 0
            lastChapter = "目录待加载"
            shortIntro = "旧简介"
        })
        val metadataOnly = sourceEngineBook(shelfId, routeId, title, author).apply {
            chaptersCount = 620
            lastChapter = "第六百二十章 新章"
            shortIntro = "新简介"
        }
        val writeFinished = CountDownLatch(1)

        repository.saveCollBookWithAsync(metadataOnly, onComplete = { writeFinished.countDown() })
        assertTrue("metadata-only transaction should finish", writeFinished.await(5, TimeUnit.SECONDS))

        val saved = repository.getCollBook(shelfId)!!
        assertTrue(repository.getBookChapters(shelfId).isEmpty())
        assertEquals(620, saved.chaptersCount)
        assertEquals("第六百二十章 新章", saved.lastChapter)
        assertEquals("新简介", saved.shortIntro)
    }

    @Test
    fun queuedInvalidFirstCatalogCannotBecomeTheBaselineForANewerValidCatalog() {
        val databaseDirectory = temporaryFolder.newFolder("objectbox-invalid-first-catalog-race")
        val store = MyObjectBox.builder()
            .directory(databaseDirectory)
            .build()
        boxStore = store
        val repository = BookRepository(
            store,
            cachedBookDeleter = {},
            readingProgressClearer = {}
        )
        val title = "斗罗大陆"
        val author = "唐家三少"
        val shelfId = BookIdentity.sourceEngineShelfId(title, author)
        val routeId = "source_engine_book_non_numeric_route_payload"
        repository.saveCollBook(sourceEngineBook(shelfId, routeId, title, author).apply {
            chaptersCount = 0
            lastChapter = "目录待加载"
        })
        val invalidCatalog = namedCatalog(shelfId, "第一章 错误", "第二章 错误").also { chapters ->
            chapters[1].link = chapters[0].link
        }
        val invalidBook = sourceEngineBook(shelfId, routeId, title, author).apply {
            setBookChapters(invalidCatalog)
            chaptersCount = invalidCatalog.size
            lastChapter = invalidCatalog.last().title
        }
        val validCatalog = namedCatalog(shelfId, "第一章 正文", "第二章 正文")
        val validBook = sourceEngineBook(shelfId, routeId, title, author).apply {
            setBookChapters(validCatalog)
            chaptersCount = validCatalog.size
            lastChapter = validCatalog.last().title
        }
        val invalidWriteStarted = CountDownLatch(1)
        val releaseInvalidWrite = CountDownLatch(1)
        val invalidWriteFinished = CountDownLatch(1)
        val validWriteFinished = CountDownLatch(1)
        repository.saveCollBookWithAsync(
            invalidBook,
            shouldApply = {
                invalidWriteStarted.countDown()
                releaseInvalidWrite.await(5, TimeUnit.SECONDS)
            },
            onComplete = { invalidWriteFinished.countDown() }
        )
        assertTrue("invalid transaction should own the first write slot", invalidWriteStarted.await(5, TimeUnit.SECONDS))
        repository.saveCollBookWithAsync(validBook, onComplete = { validWriteFinished.countDown() })

        releaseInvalidWrite.countDown()
        assertTrue("invalid transaction should finish", invalidWriteFinished.await(5, TimeUnit.SECONDS))
        assertTrue("valid transaction should finish", validWriteFinished.await(5, TimeUnit.SECONDS))

        store.close()
        boxStore = null
        val reopenedStore = MyObjectBox.builder()
            .directory(databaseDirectory)
            .build()
        boxStore = reopenedStore
        val reopenedRepository = BookRepository(
            reopenedStore,
            cachedBookDeleter = {},
            readingProgressClearer = {}
        )

        assertEquals(validCatalog.map { it.link }, reopenedRepository.getBookChapters(shelfId).map { it.link })
        assertEquals(validCatalog.map { it.title }, reopenedRepository.getBookChapters(shelfId).map { it.title })
        assertEquals(validCatalog.last().title, reopenedRepository.getCollBook(shelfId)!!.lastChapter)
    }

    @Test
    fun validCatalogRepairsALegacyDuplicateRouteBaselineAcrossRepositoryReopen() {
        val databaseDirectory = temporaryFolder.newFolder("objectbox-legacy-duplicate-route-repair")
        val store = MyObjectBox.builder()
            .directory(databaseDirectory)
            .build()
        boxStore = store
        val repository = BookRepository(
            store,
            cachedBookDeleter = {},
            readingProgressClearer = {}
        )
        val title = "斗罗大陆"
        val author = "唐家三少"
        val shelfId = BookIdentity.sourceEngineShelfId(title, author)
        val routeId = "source_engine_book_non_numeric_route_payload"
        repository.saveCollBook(sourceEngineBook(shelfId, routeId, title, author))
        val legacy = namedCatalog(shelfId, "第一章 旧", "第二章 旧").also { chapters ->
            chapters[1].link = chapters[0].link
        }
        ObjectBoxBookStore(store).replaceBookChapters(shelfId, legacy)
        val repaired = namedCatalog(shelfId, "第一章 正文", "第二章 正文")
        val repairedBook = sourceEngineBook(shelfId, routeId, title, author).apply {
            setBookChapters(repaired)
            chaptersCount = repaired.size
            lastChapter = repaired.last().title
        }
        val repairFinished = CountDownLatch(1)

        repository.saveCollBookWithAsync(repairedBook, onComplete = { repairFinished.countDown() })
        assertTrue("legacy repair should finish", repairFinished.await(5, TimeUnit.SECONDS))

        store.close()
        boxStore = null
        val reopenedStore = MyObjectBox.builder()
            .directory(databaseDirectory)
            .build()
        boxStore = reopenedStore
        val reopenedRepository = BookRepository(
            reopenedStore,
            cachedBookDeleter = {},
            readingProgressClearer = {}
        )

        assertEquals(repaired.map { it.link }, reopenedRepository.getBookChapters(shelfId).map { it.link })
        assertEquals(repaired.map { it.title }, reopenedRepository.getBookChapters(shelfId).map { it.title })
        assertEquals(repaired.last().title, reopenedRepository.getCollBook(shelfId)!!.lastChapter)
    }

    @Test
    fun catalogMigrationPersistsCatalogAndRecordInOneSynchronousTransaction() {
        val store = MyObjectBox.builder()
            .directory(temporaryFolder.newFolder("objectbox-catalog-record-migration"))
            .build()
        boxStore = store
        val repository = BookRepository(
            store,
            cachedBookDeleter = {},
            readingProgressClearer = {}
        )
        val title = "诡秘之主"
        val author = "爱潜水的乌贼"
        val shelfId = BookIdentity.sourceEngineShelfId(title, author)
        val routeId = "source_engine_book_non_numeric_route_payload"
        repository.saveCollBook(sourceEngineBook(shelfId, routeId, title, author))
        val polluted = pollutedChapters(shelfId)
        ObjectBoxBookStore(store).replaceBookChapters(shelfId, polluted)
        repository.saveBookRecord(BookRecordBean(shelfId, 377, 7))

        val migration = SourceEngineCachedCatalogMigration.sanitize(repository.getBookChapters(shelfId))
        val record = repository.getBookRecord(shelfId)!!
        assertTrue(migration.remapRecord(record))
        repository.persistSourceEngineCatalogMigration(migration.chapters, record)

        val persistedChapters = repository.getBookChapters(shelfId)
        val persistedRecord = repository.getBookRecord(shelfId)!!
        val persistedBook = repository.getCollBook(shelfId)!!
        assertEquals(392, persistedChapters.size)
        assertEquals(375, persistedRecord.chapter)
        assertEquals(7, persistedRecord.pagePos)
        assertEquals(persistedChapters[375].link, persistedRecord.chapterLink)
        assertEquals(persistedChapters[375].title, persistedRecord.chapterTitle)
        assertEquals(392, persistedBook.chaptersCount)
        assertEquals("不是诈尸，不是遭了阿蒙", persistedBook.lastChapter)
        assertEquals(0, persistedChapters.count { chapter -> chapter.title == "378. 第381章" })
        assertEquals(0, persistedChapters.count { chapter -> chapter.title == "VIP. 第381章(最新)" })

        val queuedOldWriteFinished = CountDownLatch(1)
        repository.saveBookChaptersWithAsync(polluted, onSuccess = queuedOldWriteFinished::countDown)
        assertTrue("queued legacy write should finish", queuedOldWriteFinished.await(5, TimeUnit.SECONDS))
        val afterQueuedWrite = repository.getBookChapters(shelfId)
        val afterQueuedBook = repository.getCollBook(shelfId)!!
        assertEquals(392, afterQueuedWrite.size)
        assertEquals(0, afterQueuedWrite.count { chapter -> chapter.title == "378. 第381章" })
        assertEquals(0, afterQueuedWrite.count { chapter -> chapter.title == "VIP. 第381章(最新)" })
        assertEquals(392, afterQueuedBook.chaptersCount)
        assertEquals("不是诈尸，不是遭了阿蒙", afterQueuedBook.lastChapter)
        assertEquals(375, repository.getBookRecord(shelfId)!!.chapter)
        assertEquals(7, repository.getBookRecord(shelfId)!!.pagePos)
    }

    private fun sourceEngineBook(
        shelfId: String,
        routeId: String,
        title: String,
        author: String
    ): CollBookBean {
        return CollBookBean().apply {
            set_id(shelfId)
            this.title = title
            this.author = author
            bookIdInBiquge = routeId
            chaptersCount = 2
            lastChapter = "第二章"
        }
    }

    private fun chapter(bookId: String, index: Int): BookChapterBean {
        return BookChapterBean().apply {
            id = "chapter-$index"
            this.bookId = bookId
            title = "第${index + 1}章"
            link = "source_engine_chapter_non_numeric_$index"
            start = index.toLong()
        }
    }

    private fun namedCatalog(bookId: String, vararg titles: String): List<BookChapterBean> {
        return titles.mapIndexed { index, title ->
            chapter(bookId, index).apply { this.title = title }
        }
    }

    private fun pollutedChapters(bookId: String): List<BookChapterBean> {
        fun namedChapter(identity: Int, title: String): BookChapterBean {
            return BookChapterBean().apply {
                id = "catalog-$identity"
                this.bookId = bookId
                this.title = title
                link = "source_engine_chapter_non_numeric_$identity"
                start = (identity - 1).toLong()
            }
        }
        return ((1..160).map { ordinal -> namedChapter(ordinal, "第${ordinal}章 正文$ordinal") } +
            listOf(
                namedChapter(10_001, "378. 第381章"),
                namedChapter(10_002, "VIP. 第381章(最新)")
            ) +
            (161..390).map { ordinal -> namedChapter(ordinal, "第${ordinal}章 正文$ordinal") } +
            listOf(
                namedChapter(20_001, "一个普通人的日常（一）"),
                namedChapter(20_002, "不是诈尸，不是遭了阿蒙")
            )
            ).onEachIndexed { index, chapter -> chapter.start = index.toLong() }
    }
}
