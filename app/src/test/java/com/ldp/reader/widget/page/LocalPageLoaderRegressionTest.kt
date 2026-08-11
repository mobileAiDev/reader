package com.ldp.reader.widget.page

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocalPageLoaderRegressionTest {

    @Test
    fun deterministicLineLimitFailureIsShownOnceInsteadOfEnteringTheCatalogRetryPath() {
        val source = localPageLoaderSource()

        assertTrue(source.contains("val decision = LocalTxtCatalogFailurePolicy.after(error)"))
        assertTrue(source.contains("if (!decision.retryPreparation)"))
        assertTrue(source.contains("showDocumentError(userMessage)"))
    }

    @Test
    fun localTxtParsingDoesNotDecodeEachFixedByteBlockIndependently() {
        val source = localPageLoaderSource()

        assertFalse(
            "Fixed byte blocks decoded independently corrupt multibyte characters at the block boundary",
            source.contains("String(buffer, 0, length, javaCharset())")
        )
        assertTrue(
            "Local TXT parsing must delegate byte offsets and decoding to the tested parser seam",
            source.contains("LocalTxtChapterSnapshotLoader.load(")
        )
    }

    @Test
    fun cachedLocalTxtCatalogDoesNotUseByteOffsetAsChapterOrdinal() {
        val source = localPageLoaderSource()

        assertFalse(
            "BookChapterBean.start is a byte offset and must never become catalogIndex",
            source.contains("chapter.catalogIndex = bean.start.toInt()")
        )
        assertTrue(
            "Cached local chapters must be mapped by their persisted list ordinal",
            source.contains("LocalTxtChapterMapper.fromCached(bookChapters)")
        )
        assertTrue(
            "Freshly parsed local chapters must use the same ordinal contract as cached chapters",
            localTxtParserSource().contains("catalogIndex = index")
        )
    }

    @Test
    fun localTxtCacheIsVersionedSoLegacyOffsetsAreReparsed() {
        val source = localPageLoaderSource()

        assertTrue(
            "Legacy chapter offsets must not survive a parser upgrade",
            source.contains("LocalTxtParserCacheVersionStore.isCurrent(")
        )
        assertTrue(
            "A successful parse must mark the cache with the current parser version",
            source.contains("LocalTxtParserCacheVersionStore.markCurrent(")
        )
        assertTrue(
            "Cache validity must include a millisecond file fingerprint, not only the formatted update time",
            source.contains("LocalTxtParserCacheVersionStore.fingerprint(bookFile)") &&
                source.contains("fingerprintContext.ensureActive()")
        )
        assertTrue(
            "Parsed offsets and chapter readers must share one stable file snapshot",
            source.contains("mBookSnapshot = prepared.snapshot") &&
                source.contains("snapshot.openRange(chapter.start, chapter.end)")
        )
    }

    @Test
    fun charsetDetectionAndChapterInspectionDoNotScanFilesOnTheMainDispatcher() {
        val source = localPageLoaderSource()
        val preparationStart = source.indexOf("private suspend fun prepareChapterRefresh(")
        val preparationEnd = source.indexOf("private data class PreparedChapterRefresh", preparationStart)

        assertTrue("Local TXT preparation seam must remain explicit", preparationStart >= 0)
        assertTrue("Local TXT preparation seam must have a bounded source region", preparationEnd > preparationStart)

        val preparation = source.substring(preparationStart, preparationEnd)
        assertTrue(
            "Charset detection and chapter inspection must run on Dispatchers.IO",
            preparation.contains("LocalTxtSnapshotOwnershipTransfer.prepare {") &&
                source.contains("withContext(Dispatchers.IO)")
        )
        assertTrue(
            "Strict charset detection must stay inside the IO preparation seam",
            preparation.contains("LocalTxtCharsetResolver.resolve(") &&
                preparation.contains("encodingPreference,")
        )
        assertTrue(
            "Chapter parsing must share the same IO preparation seam",
            preparation.contains("loadChapters(snapshot, charset, cancellationCheck)")
        )

        val refreshStart = source.indexOf("override fun refreshChapterList()")
        val refreshLaunch = source.indexOf("mChapterJob = chapterScope.launch", refreshStart)
        val synchronousRefreshPrefix = source.substring(refreshStart, refreshLaunch)
        assertFalse(
            "refreshChapterList must not synchronously trigger the full-file charset scan",
            synchronousRefreshPrefix.contains("LocalTxtSnapshotCharsetDetector.detect")
        )
    }

    @Test
    fun txtCopyCharsetAndChapterScansShareTheActivePreparationContext() {
        val source = localPageLoaderSource()
        val preparationStart = source.indexOf("private suspend fun prepareChapterRefresh(")
        val preparationEnd = source.indexOf("private data class PreparedChapterRefresh", preparationStart)
        val preparation = source.substring(preparationStart, preparationEnd)

        assertTrue(preparation.contains("val preparationContext = currentCoroutineContext()"))
        assertTrue(preparation.contains("val cancellationCheck = { preparationContext.ensureActive() }"))
        assertTrue(preparation.contains("snapshotDirectory,") && preparation.contains("cancellationCheck"))
        assertTrue(preparation.contains("LocalTxtCharsetResolver.resolve("))
        assertTrue(preparation.contains("encodingPreference,"))
        assertTrue(preparation.contains("loadChapters(snapshot, charset, cancellationCheck)"))
    }

    @Test
    fun chapterPersistenceAndCacheMarkShareTheLatestRequestGeneration() {
        val source = localPageLoaderSource()
        val begin = source.indexOf("beginChapterCatalogWriteRequest")
        val bind = source.indexOf("bindChapterCatalogWrite", begin)
        val save = source.indexOf("saveBookChaptersWithAsync(", bind)
        val latest = source.indexOf("runIfChapterCatalogWriteLatest(writeToken)", save)
        val mark = source.indexOf("LocalTxtParserCacheVersionStore.markCurrent(", latest)

        assertTrue(begin >= 0)
        assertTrue(bind > begin)
        assertTrue(save > bind)
        assertTrue(latest > save)
        assertTrue(mark > latest)
    }

    @Test
    fun durableCatalogRequestAndBindNeverWaitForObjectBoxWriteLockOnMain() {
        val source = localPageLoaderSource()
        val refreshStart = source.indexOf("override fun refreshChapterList()")
        val refreshLaunch = source.indexOf("mChapterJob = chapterScope.launch", refreshStart)
        val refreshEnd = source.indexOf("override fun getChapterReader", refreshLaunch)
        val synchronousPrefix = source.substring(refreshStart, refreshLaunch)
        val asynchronousRefresh = source.substring(refreshLaunch, refreshEnd)

        assertFalse(synchronousPrefix.contains("beginChapterCatalogWriteRequest"))
        assertTrue(asynchronousRefresh.contains("LocalTxtChapterCatalogTransactions.onIo"))
        assertTrue(asynchronousRefresh.contains("repository.beginChapterCatalogWriteRequest(bookId)"))
        assertTrue(asynchronousRefresh.contains("repository.bindChapterCatalogWrite("))
        assertTrue(asynchronousRefresh.contains("chapterRefreshRequests.isLatest(request)"))
    }

    @Test
    fun closeBookClosesTheSessionSnapshotAfterCancellingPageWork() {
        val source = localPageLoaderSource()
        val closeStart = source.indexOf("override fun closeBook()")
        val closeEnd = source.indexOf("override fun refreshChapterList()", closeStart)
        val close = source.substring(closeStart, closeEnd)

        assertTrue(close.indexOf("super.closeBook()") < close.indexOf("mBookSnapshot?.close()"))
        assertTrue(close.contains("mBookSnapshot = null"))
    }

    @Test
    fun readingSessionUsesPrivateImmutableCopyAndCancellationSafeOwnershipTransfer() {
        val source = localPageLoaderSource()

        assertTrue(source.contains("LocalTxtFileSnapshot.createImmutableCopy("))
        assertTrue(source.contains("val pendingOwnership = AtomicReference<T?>(null)"))
        assertTrue(source.contains("pendingOwnership.getAndSet(null)?.close()"))
    }

    @Test
    fun changingThePerBookEncodingInvalidatesCachedOffsetsAndReparsesTheSnapshot() {
        val source = localPageLoaderSource()

        assertTrue(source.contains("LocalTxtEncodingPreferences.load(bookId)"))
        assertTrue(source.contains("LocalTxtCharsetResolver.resolve("))
        assertTrue(source.contains("encodingPreference,"))
        assertTrue(source.contains("LocalTxtParserCacheVersionStore.isCurrent("))
        assertTrue(source.contains("fingerprintAfterCharset,"))
        assertTrue(source.contains("LocalTxtEncodingPreferences.save(bookId, preference)"))
        assertTrue(source.contains("showCatalogLoading()"))
        assertTrue(source.contains("refreshChapterList()"))
    }

    @Test
    fun encodingReloadUsesTheLiveReadingAnchorAndReopensAtRelativePageProgress() {
        val source = localPageLoaderSource()

        assertTrue(source.contains("LocalTxtReadingAnchorResolver.capture("))
        assertTrue(source.contains("chapterRefreshRequests.beginEncodingReload("))
        assertTrue(source.contains("readingAnchor = request.readingAnchor"))
        assertTrue(source.contains("LocalTxtReadingAnchorResolver.resolve("))
        assertTrue(source.contains("reopenChapterFromCatalog("))
        assertTrue(source.contains("resolvedAnchor.pageProgress.resolvePageIndex(pageCount)"))
    }

    @Test
    fun everyAsyncEncodingReloadPublicationIsFencedByTheLatestRequestObject() {
        val source = localPageLoaderSource()
        val refreshStart = source.indexOf("private fun refreshChapterList(")
        val refreshEnd = source.indexOf("override fun getChapterReader", refreshStart)
        val refresh = source.substring(refreshStart, refreshEnd)

        assertTrue(refresh.contains("chapterRefreshRequests.isLatest(request)"))
        assertFalse(refresh.contains("requestId != chapterRequestId"))
        assertFalse(refresh.contains("requestId == chapterRequestId"))
    }

    private fun localPageLoaderSource(): String {
        return File("src/main/java/com/ldp/reader/widget/page/LocalPageLoader.kt").readText()
    }

    private fun localTxtParserSource(): String {
        return File("src/main/java/com/ldp/reader/widget/page/LocalTxtChapterParser.kt").readText()
    }
}
