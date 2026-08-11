package com.ldp.reader.widget.page

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocalTxtAsyncPaginationContractTest {

    @Test
    fun localTxtEntryLoadsCurrentChapterOnIoAndPublishesOnlyLatestGeneration() {
        val pageLoader = File("src/main/java/com/ldp/reader/widget/page/PageLoader.kt").readText()
        val localLoader = File("src/main/java/com/ldp/reader/widget/page/LocalPageLoader.kt").readText()

        assertTrue(
            localLoader.contains(
                "override fun shouldLoadCurrentChapterInBackground(): Boolean = true"
            )
        )
        val requestStart = pageLoader.indexOf("private fun requestCurrentChapterInBackground(")
        val publishStart = pageLoader.indexOf("private fun publishPageLoad(", requestStart)
        assertTrue(requestStart >= 0)
        assertTrue(publishStart > requestStart)
        val request = pageLoader.substring(requestStart, publishStart)
        assertTrue(request.contains("withContext(Dispatchers.IO)"))
        assertTrue(request.contains("checkCancelled = { loadContext.ensureActive() }"))
        assertTrue(request.contains("currentPageLoadGeneration.isLatest(generation)"))
        assertTrue(
            request.indexOf("currentPageLoadGeneration.isLatest(generation)") <
                request.indexOf("publishPageLoad(")
        )
    }

    @Test
    fun closingLoaderCancelsCurrentPaginationAndInvalidatesItsGeneration() {
        val pageLoader = File("src/main/java/com/ldp/reader/widget/page/PageLoader.kt").readText()
        val closeStart = pageLoader.indexOf("open fun closeBook()")
        val closeEnd = pageLoader.indexOf("private fun clearList", closeStart)
        val close = pageLoader.substring(closeStart, closeEnd)

        assertTrue(close.contains("mCurrentPageLoadJob?.cancel()"))
        assertTrue(close.contains("currentPageLoadGeneration.invalidate()"))
    }

    @Test
    fun cancelledPrevOrNextAnimationInvalidatesPendingLoadAndRestoresChapter() {
        val pageLoader = File("src/main/java/com/ldp/reader/widget/page/PageLoader.kt").readText()
        val cancelStart = pageLoader.indexOf("private fun cancelPendingAsyncChapterTransition(")
        val cancelEnd = pageLoader.indexOf("private fun cancelNextChapter()", cancelStart)
        val cancel = pageLoader.substring(cancelStart, cancelEnd)

        assertTrue(cancel.contains("mCurrentPageLoadJob?.cancel()"))
        assertTrue(cancel.contains("currentPageLoadGeneration.invalidate()"))
        assertTrue(cancel.contains("mCurChapterPos = mLastChapterPos"))
        assertTrue(cancel.contains("pendingTurn == PendingPageTurn.TO_NEXT_CHAPTER"))
        assertTrue(cancel.contains("mCurPageList = mPrePageList ?: mCurPageList"))
        assertTrue(cancel.contains("mCurPageList = mNextPageList ?: mCurPageList"))
    }

    @Test
    fun stalePreloadCannotPublishAfterLayoutOrChapterGenerationChanges() {
        val pageLoader = File("src/main/java/com/ldp/reader/widget/page/PageLoader.kt").readText()
        val preloadStart = pageLoader.indexOf("private fun preLoadNextChapter()")
        val preloadEnd = pageLoader.indexOf("private fun cancelPreload()", preloadStart)
        val preload = pageLoader.substring(preloadStart, preloadEnd)

        assertTrue(preload.contains("val generation = preLoadGeneration.next()"))
        assertTrue(preload.contains("preLoadGeneration.isLatest(generation)"))
        assertTrue(
            preload.indexOf("preLoadGeneration.isLatest(generation)") <
                preload.indexOf("mNextPageList = loaded.pages")
        )
        assertTrue(pageLoader.contains("private fun cancelPreload()"))
        assertTrue(pageLoader.contains("preLoadGeneration.invalidate()"))
    }

    @Test
    fun localFontResizeAndUncachedChapterTransitionsNeverUseSynchronousPagination() {
        val pageLoader = File("src/main/java/com/ldp/reader/widget/page/PageLoader.kt").readText()

        val setText = functionBody(pageLoader, "fun setTextSize(", "fun setNightMode(")
        assertTrue(setText.contains("shouldLoadCurrentChapterInBackground()"))
        assertTrue(setText.contains("requestCurrentChapterInBackground("))

        val prepareDisplay = functionBody(pageLoader, "fun prepareDisplay(", "fun prev(")
        assertTrue(prepareDisplay.contains("shouldLoadCurrentChapterInBackground()"))
        assertTrue(prepareDisplay.contains("requestCurrentChapterInBackground("))

        val previous = functionBody(pageLoader, "open fun parsePrevChapter(", "private fun hasPrevChapter(")
        assertTrue(previous.contains("Int.MAX_VALUE"))
        assertTrue(previous.contains("preserveTransitionCaches = true"))
        val next = functionBody(pageLoader, "open fun parseNextChapter(", "private fun dealLoadPageList(")
        assertTrue(next.contains("preserveTransitionCaches = true"))
    }

    @Test
    fun emptyPreloadedChapterIsNormalizedToSafePlaceholderWithEmptyStatus() {
        val loaded = normalizeLoadedChapterPages(ArrayList())

        assertEquals(PageLoader.STATUS_EMPTY, loaded.status)
        assertEquals(1, loaded.pages.size)
        assertTrue(loaded.pages.single().lines!!.isEmpty())
    }

    @Test
    fun adjacentChapterCachesPreserveStatusAcrossTransitionAndCancellation() {
        val pageLoader = File("src/main/java/com/ldp/reader/widget/page/PageLoader.kt").readText()
        val preload = functionBody(
            pageLoader,
            "private fun preLoadNextChapter()",
            "private fun cancelPreload()"
        )
        val cancel = functionBody(
            pageLoader,
            "private fun cancelPendingAsyncChapterTransition(",
            "private fun cancelNextChapter()"
        )

        assertTrue(preload.contains("normalizeLoadedChapterPages(pages)"))
        assertTrue(preload.contains("mNextPageStatus = loaded.status"))
        assertTrue(cancel.contains("restoredStatus = mPrePageStatus"))
        assertTrue(cancel.contains("restoredStatus = mNextPageStatus"))
        assertTrue(cancel.contains("mStatus = if (mCurPageList == null) STATUS_LOADING else restoredStatus"))
    }

    @Test
    fun withinChapterCancelCannotBeMisclassifiedByStalePreviousChapterPosition() {
        val pageLoader = File("src/main/java/com/ldp/reader/widget/page/PageLoader.kt").readText()
        val previousPage = functionBody(pageLoader, "fun prev(", "open fun parsePrevChapter(")
        val cancel = functionBody(
            pageLoader,
            "fun pageCancel()",
            "private fun cancelPendingAsyncChapterTransition("
        )

        assertTrue(previousPage.contains("mPendingPageTurn = PendingPageTurn.WITHIN_CHAPTER"))
        assertTrue(cancel.contains("when (pendingTurn)"))
        assertTrue(cancel.contains("PendingPageTurn.WITHIN_CHAPTER"))
        assertTrue(!cancel.contains("mCurPage!!.position == 0"))
        assertTrue(!cancel.contains("mCurChapterPos > mLastChapterPos"))
    }

    private fun functionBody(source: String, start: String, end: String): String {
        val startIndex = source.indexOf(start)
        val endIndex = source.indexOf(end, startIndex)
        assertTrue(startIndex >= 0)
        assertTrue(endIndex > startIndex)
        return source.substring(startIndex, endIndex)
    }
}
