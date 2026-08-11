package com.ldp.reader.media

import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class ComicRouteSupplementFailureBoundaryTest {

    @Test
    fun retryableSupplementDeadlineKeepsValidatedInitialRouteCandidates() {
        val primary = comicBook("validated-primary")

        val outcome = ComicRouteSupplementFailureBoundary.loadOrFallback(
            initialCandidates = listOf(primary)
        ) {
            throw MediaSearchRetryableException(
                completedSourceCount = 3,
                successfulSourceCount = 1,
                totalSourceCount = 24,
                timedOut = true
            )
        }

        assertEquals(listOf(primary), outcome.books)
        assertEquals(ComicRouteSupplementFallbackReason.DEADLINE, outcome.fallbackReason)
    }

    @Test
    fun explicitCancellationStillPropagatesAcrossSupplementFallbackBoundary() {
        val cancellation = CancellationException("detail closed")

        try {
            ComicRouteSupplementFailureBoundary.loadOrFallback(
                initialCandidates = listOf(comicBook("validated-primary"))
            ) {
                throw cancellation
            }
            fail("Expected explicit cancellation to propagate")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    private fun comicBook(name: String): MediaSourceBook {
        val emptyRules = MediaLegadoRuleSet("", emptyMap())
        val source = MediaSourceDefinition(
            sourceName = "fixture-source",
            sourceUrl = "https://example.com",
            sourceType = MediaSourceType.COMIC,
            sourceGroup = null,
            sourceComment = null,
            enabled = true,
            headers = emptyMap(),
            searchUrl = "https://example.com/search?q={{key}}",
            ruleSearch = emptyRules,
            ruleBookInfo = emptyRules,
            ruleToc = emptyRules,
            ruleContent = emptyRules,
            diagnostics = emptyList()
        )
        return MediaSourceBook(
            source = source,
            name = name,
            author = "fixture-author",
            bookUrl = "https://example.com/$name",
            coverUrl = "",
            intro = "",
            kind = "comic",
            lastChapter = ""
        )
    }
}
