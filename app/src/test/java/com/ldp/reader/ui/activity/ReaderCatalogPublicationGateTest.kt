package com.ldp.reader.ui.activity

import com.ldp.reader.model.bean.BookChapterBean
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderCatalogPublicationGateTest {
    @Test
    fun laterCatalogFromTheSameBaselineCannotOverwriteTheFirstChangedPublication() {
        val gate = ReaderCatalogPublicationGate()
        val session = gate.beginSession("source-engine-shelf-book")
        val baseline = catalog("chapter-1", "chapter-2")
        val mainAttempt = gate.capture(session, baseline)
        val tierAttempt = gate.capture(session, baseline)
        var visible = baseline

        val mainOutcome = gate.publishMainResult(
            attempt = mainAttempt,
            current = visible,
            candidate = catalog("chapter-1", "chapter-2", "chapter-3-main")
        ) { accepted ->
            visible = accepted
        }
        val tierOutcome = gate.promoteTierResult(
            attempt = tierAttempt,
            current = visible,
            candidate = catalog("chapter-1", "chapter-2", "chapter-3-tier")
        ) { accepted ->
            visible = accepted
        }

        assertEquals(ReaderCatalogPublicationOutcome.APPLIED_CHANGED, mainOutcome)
        assertEquals(ReaderCatalogPublicationOutcome.STALE, tierOutcome)
        assertEquals(
            listOf("chapter-1", "chapter-2", "chapter-3-main"),
            visible.map { chapter -> chapter.link }
        )
    }

    @Test
    fun rejectedMainResultDoesNotBecomeTheBaselineForAConcurrentValidAppend() {
        val gate = ReaderCatalogPublicationGate()
        val session = gate.beginSession("source-engine-shelf-book")
        val baseline = catalog("chapter-1", "chapter-2")
        val mainAttempt = gate.capture(session, baseline)
        val tierAttempt = gate.capture(session, baseline)
        val duplicateRoute = catalog("chapter-1", "chapter-1")
        var visible = baseline

        val mainOutcome = gate.publishMainResult(
            attempt = mainAttempt,
            current = visible,
            candidate = duplicateRoute
        ) { accepted ->
            visible = accepted
        }
        val tierOutcome = gate.promoteTierResult(
            attempt = tierAttempt,
            current = visible,
            candidate = catalog("chapter-1", "chapter-2", "chapter-3")
        ) { accepted ->
            visible = accepted
        }

        assertEquals(ReaderCatalogPublicationOutcome.RETAINED_CURRENT, mainOutcome)
        assertEquals(ReaderCatalogPublicationOutcome.APPLIED_CHANGED, tierOutcome)
        assertEquals(listOf("chapter-1", "chapter-2", "chapter-3"), visible.map { it.link })
    }

    @Test
    fun invalidResultArrivingFirstCannotBecomeAnEmptySessionBaseline() {
        val gate = ReaderCatalogPublicationGate()
        val session = gate.beginSession("source-engine-shelf-book")
        val baseline = emptyList<BookChapterBean>()
        val badAttempt = gate.capture(session, baseline)
        val validAttempt = gate.capture(session, baseline)
        var visible = baseline

        val badOutcome = gate.publishMainResult(
            attempt = badAttempt,
            current = visible,
            candidate = catalog("chapter-1", "chapter-1")
        ) { accepted ->
            visible = accepted
        }
        val validOutcome = gate.publishMainResult(
            attempt = validAttempt,
            current = visible,
            candidate = catalog("chapter-1", "chapter-2")
        ) { accepted ->
            visible = accepted
        }

        assertEquals(ReaderCatalogPublicationOutcome.RETAINED_CURRENT, badOutcome)
        assertEquals(ReaderCatalogPublicationOutcome.APPLIED_CHANGED, validOutcome)
        assertEquals(listOf("chapter-1", "chapter-2"), visible.map { it.link })
    }

    @Test
    fun unchangedMainResultDoesNotDiscardAConcurrentSafeTitleEnrichment() {
        val gate = ReaderCatalogPublicationGate()
        val session = gate.beginSession("source-engine-shelf-book")
        val baseline = titledCatalog(
            "chapter-590" to "第五百九十章 前一章",
            "chapter-591" to "第五百九十一章 融合..."
        )
        val mainAttempt = gate.capture(session, baseline)
        val tierAttempt = gate.capture(session, baseline)
        var visible = baseline

        val mainOutcome = gate.publishMainResult(
            attempt = mainAttempt,
            current = visible,
            candidate = titledCatalog(
                "chapter-590" to "第五百九十章 前一章",
                "chapter-591" to "第五百九十一章 融合..."
            )
        ) { accepted ->
            visible = accepted
        }
        val tierOutcome = gate.promoteTierResult(
            attempt = tierAttempt,
            current = visible,
            candidate = titledCatalog(
                "chapter-590" to "第五百九十章 前一章",
                "chapter-591" to "第五百九十一章 融合成功！"
            )
        ) { accepted ->
            visible = accepted
        }

        assertEquals(ReaderCatalogPublicationOutcome.APPLIED_UNCHANGED, mainOutcome)
        assertEquals(ReaderCatalogPublicationOutcome.APPLIED_CHANGED, tierOutcome)
        assertEquals("第五百九十一章 融合成功！", visible.last().title)
    }

    @Test
    fun tierProjectsSafeCrossSourceTitleCompletionOntoCurrentReadingRoutes() {
        val gate = ReaderCatalogPublicationGate()
        val session = gate.beginSession("source-engine-shelf-book")
        val current = titledCatalog(
            "current-590" to "第五百九十章 前一章",
            "current-591" to "第五百九十一章 融合...",
            "current-592" to "第五百九十二章 后一章"
        )
        val attempt = gate.capture(session, current)
        var visible = current

        val outcome = gate.promoteTierResult(
            attempt = attempt,
            current = visible,
            candidate = titledCatalog(
                "fallback-590" to "第五百九十章 前一章",
                "fallback-591" to "第五百九十一章 融合成功！",
                "fallback-592" to "第五百九十二章 后一章"
            )
        ) { accepted ->
            visible = accepted
        }

        assertEquals(ReaderCatalogPublicationOutcome.APPLIED_CHANGED, outcome)
        assertEquals(listOf("current-590", "current-591", "current-592"), visible.map { it.link })
        assertEquals("第五百九十一章 融合成功！", visible[1].title)
    }

    @Test
    fun tierRejectsCrossSourceRewriteThatIsNotASafeTitleCompletion() {
        val gate = ReaderCatalogPublicationGate()
        val session = gate.beginSession("source-engine-shelf-book")
        val current = titledCatalog(
            "current-590" to "第五百九十章 前一章",
            "current-591" to "第五百九十一章 融合...",
            "current-592" to "第五百九十二章 后一章"
        )
        val attempt = gate.capture(session, current)
        var visible = current

        val outcome = gate.promoteTierResult(
            attempt = attempt,
            current = visible,
            candidate = titledCatalog(
                "fallback-590" to "第五百九十章 前一章",
                "fallback-591" to "第五百九十一章 灵魂融合成功！",
                "fallback-592" to "第五百九十二章 后一章"
            )
        ) { accepted ->
            visible = accepted
        }

        assertEquals(ReaderCatalogPublicationOutcome.REJECTED, outcome)
        assertEquals(listOf("current-590", "current-591", "current-592"), visible.map { it.link })
        assertEquals("第五百九十一章 融合...", visible[1].title)
    }

    @Test
    fun staleMainRetryMergesTierTitleEnrichmentWithMainAppend() {
        val gate = ReaderCatalogPublicationGate()
        val session = gate.beginSession("source-engine-shelf-book")
        val baseline = titledCatalog(
            "chapter-590" to "第五百九十章 前一章",
            "chapter-591" to "第五百九十一章 融合..."
        )
        val oldMainAttempt = gate.capture(session, baseline)
        val tierAttempt = gate.capture(session, baseline)
        var visible = baseline

        val tierOutcome = gate.promoteTierResult(
            attempt = tierAttempt,
            current = visible,
            candidate = titledCatalog(
                "chapter-590" to "第五百九十章 前一章",
                "chapter-591" to "第五百九十一章 融合成功！"
            )
        ) { accepted ->
            visible = accepted
        }
        val oldMainOutcome = gate.publishMainResult(
            attempt = oldMainAttempt,
            current = visible,
            candidate = titledCatalog(
                "chapter-590" to "第五百九十章 前一章",
                "chapter-591" to "第五百九十一章 融合...",
                "chapter-592" to "第五百九十二章 新章"
            )
        ) { accepted ->
            visible = accepted
        }
        val retryOutcome = gate.retryStaleMainResult(
            attempt = oldMainAttempt,
            current = visible,
            candidate = titledCatalog(
                "chapter-590" to "第五百九十章 前一章",
                "chapter-591" to "第五百九十一章 融合...",
                "chapter-592" to "第五百九十二章 新章"
            )
        ) { accepted ->
            visible = accepted
        }

        assertEquals(ReaderCatalogPublicationOutcome.APPLIED_CHANGED, tierOutcome)
        assertEquals(ReaderCatalogPublicationOutcome.STALE, oldMainOutcome)
        assertEquals(ReaderCatalogPublicationOutcome.APPLIED_CHANGED, retryOutcome)
        assertEquals(3, visible.size)
        assertEquals("第五百九十一章 融合成功！", visible[1].title)
        assertEquals("chapter-592", visible[2].link)
    }

    @Test
    fun startingANewSessionInvalidatesOutstandingResultsFromCancellationOrReopen() {
        val gate = ReaderCatalogPublicationGate()
        val baseline = catalog("chapter-1", "chapter-2")
        val oldSession = gate.beginSession("source-engine-shelf-book")
        val oldAttempt = gate.capture(oldSession, baseline)
        val reopenedSession = gate.beginSession("source-engine-shelf-book")
        val reopenedAttempt = gate.capture(reopenedSession, baseline)
        var visible = baseline

        val staleOutcome = gate.publishMainResult(
            attempt = oldAttempt,
            current = visible,
            candidate = catalog("chapter-1", "chapter-2", "chapter-old")
        ) { accepted ->
            visible = accepted
        }
        val reopenedOutcome = gate.publishMainResult(
            attempt = reopenedAttempt,
            current = visible,
            candidate = catalog("chapter-1", "chapter-2", "chapter-new")
        ) { accepted ->
            visible = accepted
        }

        assertEquals(ReaderCatalogPublicationOutcome.STALE, staleOutcome)
        assertEquals(ReaderCatalogPublicationOutcome.APPLIED_CHANGED, reopenedOutcome)
        assertEquals("chapter-new", visible.last().link)
    }

    @Test
    fun retryCanRecaptureThePublishedBaselineAfterItsPreviousAttemptBecameStale() {
        val gate = ReaderCatalogPublicationGate()
        val session = gate.beginSession("source-engine-shelf-book")
        val baseline = catalog("chapter-1", "chapter-2")
        val mainAttempt = gate.capture(session, baseline)
        val tierAttempt = gate.capture(session, baseline)
        var visible = baseline

        gate.publishMainResult(
            attempt = mainAttempt,
            current = visible,
            candidate = catalog("chapter-1", "chapter-2", "chapter-3")
        ) { accepted ->
            visible = accepted
        }
        val staleOutcome = gate.promoteTierResult(
            attempt = tierAttempt,
            current = visible,
            candidate = catalog("chapter-1", "chapter-2", "chapter-tier")
        ) { accepted ->
            visible = accepted
        }
        val retryAttempt = gate.capture(session, visible)
        val retryOutcome = gate.promoteTierResult(
            attempt = retryAttempt,
            current = visible,
            candidate = catalog("chapter-1", "chapter-2", "chapter-3", "chapter-4")
        ) { accepted ->
            visible = accepted
        }

        assertEquals(ReaderCatalogPublicationOutcome.STALE, staleOutcome)
        assertEquals(ReaderCatalogPublicationOutcome.APPLIED_CHANGED, retryOutcome)
        assertEquals("chapter-4", visible.last().link)
    }

    @Test
    fun staleTierRetryMergesMainAppendWith591TitleEnrichment() {
        val gate = ReaderCatalogPublicationGate()
        val session = gate.beginSession("source-engine-shelf-book")
        val baseline = titledCatalog(
            "chapter-590" to "第五百九十章 前一章",
            "chapter-591" to "第五百九十一章 融合..."
        )
        val mainAttempt = gate.capture(session, baseline)
        val tierAttempt = gate.capture(session, baseline)
        var visible = baseline

        gate.publishMainResult(
            attempt = mainAttempt,
            current = visible,
            candidate = titledCatalog(
                "chapter-590" to "第五百九十章 前一章",
                "chapter-591" to "第五百九十一章 融合...",
                "chapter-592" to "第五百九十二章 新章"
            )
        ) { accepted ->
            visible = accepted
        }
        val staleOutcome = gate.promoteTierResult(
            attempt = tierAttempt,
            current = visible,
            candidate = titledCatalog(
                "chapter-590" to "第五百九十章 前一章",
                "chapter-591" to "第五百九十一章 融合成功！"
            )
        ) { accepted ->
            visible = accepted
        }
        val retryOutcome = gate.retryStaleTierResult(
            attempt = tierAttempt,
            current = visible,
            candidate = titledCatalog(
                "chapter-590" to "第五百九十章 前一章",
                "chapter-591" to "第五百九十一章 融合成功！"
            )
        ) { accepted ->
            visible = accepted
        }

        assertEquals(ReaderCatalogPublicationOutcome.STALE, staleOutcome)
        assertEquals(ReaderCatalogPublicationOutcome.APPLIED_CHANGED, retryOutcome)
        assertEquals(
            listOf("chapter-590", "chapter-591", "chapter-592"),
            visible.map { it.link }
        )
        assertEquals("第五百九十一章 融合成功！", visible[1].title)
        assertEquals("第五百九十二章 新章", visible[2].title)
    }

    @Test
    fun staleTierRetryFromAnOldSessionDoesNotRecaptureOrPublish() {
        val gate = ReaderCatalogPublicationGate()
        val baseline = catalog("chapter-1", "chapter-2")
        val oldSession = gate.beginSession("source-engine-old-book")
        val oldAttempt = gate.capture(oldSession, baseline)
        gate.beginSession("source-engine-new-book")
        var visible = baseline
        var publishCount = 0

        val retryOutcome = gate.retryStaleTierResult(
            attempt = oldAttempt,
            current = visible,
            candidate = catalog("chapter-1", "chapter-2", "chapter-old")
        ) { accepted ->
            publishCount += 1
            visible = accepted
        }

        assertEquals(ReaderCatalogPublicationOutcome.STALE, retryOutcome)
        assertEquals(0, publishCount)
        assertEquals(listOf("chapter-1", "chapter-2"), visible.map { it.link })
    }

    @Test
    fun staleMainRetryFromAnOldSessionDoesNotRecaptureOrPublish() {
        val gate = ReaderCatalogPublicationGate()
        val baseline = catalog("chapter-1", "chapter-2")
        val oldSession = gate.beginSession("source-engine-old-book")
        val oldAttempt = gate.capture(oldSession, baseline)
        gate.beginSession("source-engine-new-book")
        var visible = baseline
        var publishCount = 0

        val retryOutcome = gate.retryStaleMainResult(
            attempt = oldAttempt,
            current = visible,
            candidate = catalog("chapter-1", "chapter-2", "chapter-old")
        ) { accepted ->
            publishCount += 1
            visible = accepted
        }

        assertEquals(ReaderCatalogPublicationOutcome.STALE, retryOutcome)
        assertEquals(0, publishCount)
        assertEquals(listOf("chapter-1", "chapter-2"), visible.map { it.link })
    }

    @Test
    fun staleTierRetryRejectsSameSizeDifferentRouteConflictWithoutOverwritingCurrent() {
        val gate = ReaderCatalogPublicationGate()
        val session = gate.beginSession("source-engine-shelf-book")
        val baseline = catalog("chapter-1", "chapter-2")
        val mainAttempt = gate.capture(session, baseline)
        val tierAttempt = gate.capture(session, baseline)
        var visible = baseline

        gate.publishMainResult(
            attempt = mainAttempt,
            current = visible,
            candidate = catalog("chapter-1", "chapter-2", "chapter-3-main")
        ) { accepted ->
            visible = accepted
        }
        val retryOutcome = gate.retryStaleTierResult(
            attempt = tierAttempt,
            current = visible,
            candidate = catalog("chapter-1", "chapter-conflict")
        ) { accepted ->
            visible = accepted
        }

        assertEquals(ReaderCatalogPublicationOutcome.REJECTED, retryOutcome)
        assertEquals(
            listOf("chapter-1", "chapter-2", "chapter-3-main"),
            visible.map { it.link }
        )
    }

    @Test
    fun staleMainRetryRejectsSameSizeDifferentRouteConflictWithoutOverwritingCurrent() {
        val gate = ReaderCatalogPublicationGate()
        val session = gate.beginSession("source-engine-shelf-book")
        val baseline = titledCatalog(
            "chapter-590" to "第五百九十章 前一章",
            "chapter-591" to "第五百九十一章 融合..."
        )
        val mainAttempt = gate.capture(session, baseline)
        val tierAttempt = gate.capture(session, baseline)
        var visible = baseline

        gate.promoteTierResult(
            attempt = tierAttempt,
            current = visible,
            candidate = titledCatalog(
                "chapter-590" to "第五百九十章 前一章",
                "chapter-591" to "第五百九十一章 融合成功！"
            )
        ) { accepted ->
            visible = accepted
        }
        val retryOutcome = gate.retryStaleMainResult(
            attempt = mainAttempt,
            current = visible,
            candidate = catalog("chapter-590", "chapter-conflict")
        ) { accepted ->
            visible = accepted
        }

        assertEquals(ReaderCatalogPublicationOutcome.REJECTED, retryOutcome)
        assertEquals(listOf("chapter-590", "chapter-591"), visible.map { it.link })
        assertEquals("第五百九十一章 融合成功！", visible[1].title)
    }

    @Test
    fun mainResultWhoseLinksOnlyDriftedInVolatileBookMetadataPublishes() {
        // Regression: the on-device catalog froze because every encoded chapter link embedded
        // the book's lastChapter, so each source update rewrote every link and the fresh
        // catalog was retained away instead of published.
        val gate = ReaderCatalogPublicationGate()
        val session = gate.beginSession("source-engine-shelf-book")
        val baseline = listOf(
            encodedChapter(1, lastChapter = "第2章 旧末章"),
            encodedChapter(2, lastChapter = "第2章 旧末章")
        )
        val candidate = listOf(
            encodedChapter(1, lastChapter = "第4章 新末章"),
            encodedChapter(2, lastChapter = "第4章 新末章"),
            encodedChapter(3, lastChapter = "第4章 新末章"),
            encodedChapter(4, lastChapter = "第4章 新末章")
        )
        val attempt = gate.capture(session, baseline)
        var visible = baseline

        val outcome = gate.publishMainResult(
            attempt = attempt,
            current = baseline,
            candidate = candidate
        ) { accepted ->
            visible = accepted
        }

        assertEquals(ReaderCatalogPublicationOutcome.APPLIED_CHANGED, outcome)
        assertEquals(candidate.map { it.link }, visible.map { it.link })
        assertEquals("第4章 正文", visible.last().title)
    }

    private fun encodedChapter(ordinal: Int, lastChapter: String): BookChapterBean {
        val json = """
            {"author":"作者","bookName":"测试书","bookUrl":"http://site.example/book/1/",
            "chapterName":"第${ordinal}章 正文","chapterUrl":"http://site.example/book/1/$ordinal.html",
            "index":${ordinal - 1},"lastChapter":"$lastChapter","sourceUrl":"http://source.example/"}
        """.trimIndent().replace("\n", "")
        return BookChapterBean().apply {
            link = "source_engine_chapter_" +
                com.ldp.reader.utils.Base64Compat.encodeUrlWithoutPadding(json.toByteArray(Charsets.UTF_8))
            title = "第${ordinal}章 正文"
        }
    }

    private fun catalog(vararg routes: String): List<BookChapterBean> {
        return routes.mapIndexed { index, route ->
            BookChapterBean().apply {
                link = route
                title = "第${index + 1}章"
            }
        }
    }

    private fun titledCatalog(vararg chapters: Pair<String, String>): List<BookChapterBean> {
        return chapters.map { (route, chapterTitle) ->
            BookChapterBean().apply {
                link = route
                title = chapterTitle
            }
        }
    }
}
