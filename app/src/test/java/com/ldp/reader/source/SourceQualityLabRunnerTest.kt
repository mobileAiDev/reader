package com.ldp.reader.source

import com.ldp.reader.sourceengine.EngineFailure
import com.ldp.reader.sourceengine.EngineResult
import com.ldp.reader.sourceengine.legado.LegadoRuleSet
import com.ldp.reader.sourceengine.model.BookSource
import com.ldp.reader.sourceengine.model.CanonicalChapter
import com.ldp.reader.sourceengine.model.CanonicalChapterList
import com.ldp.reader.sourceengine.model.CleanContent
import com.ldp.reader.sourceengine.model.ContentQualityReport
import com.ldp.reader.sourceengine.model.SourceBook
import com.ldp.reader.sourceengine.model.SourceBookDetail
import com.ldp.reader.sourceengine.model.SourceChapter
import com.ldp.reader.sourceengine.model.SourceSearchAttempt
import com.ldp.reader.sourceengine.model.SourceSearchReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceQualityLabRunnerTest {
    @Test
    fun reportsDisabledRejectedIncompatibleAndAvailableSourcesWithoutUserStorage() {
        val runner = SourceQualityLabRunner(
            engine = FakeProbeEngine(),
            router = SourceQualityRouter(storage = InMemorySourceQualityStorage(), seed = SourceQualitySeed.empty())
        )

        val report = runner.run(sourceJson(), SourceQualityLabConfig(keyword = "斗破苍穹", maxSources = 10))

        assertEquals(3, report.importedCount)
        assertEquals(1, report.rejectedCount)
        assertEquals(1, report.disabledCount)
        assertEquals(1, report.incompatibleCount)
        assertEquals(1, report.availableCount)
        assertEquals(1, report.probedCount)

        val available = report.entries.first { it.sourceName == "Good Source" }
        assertEquals(SourceQualityLabStatus.AVAILABLE, available.status)
        assertTrue(available.usable)
        assertEquals(1, available.tier)
        assertTrue(available.score >= 8_000)
        assertEquals(3, available.chapterCount)
        assertEquals(95, available.contentQuality)

        val disabled = report.entries.first { it.sourceName == "Disabled Source" }
        assertEquals(SourceQualityLabStatus.DISABLED, disabled.status)
        assertFalse(disabled.usable)
        assertEquals(0, disabled.tier)

        val incompatible = report.entries.first { it.sourceName == "No Search Source" }
        assertEquals(SourceQualityLabStatus.INCOMPATIBLE, incompatible.status)
        assertFalse(incompatible.usable)

        val rejected = report.entries.first { it.status == SourceQualityLabStatus.REJECTED }
        assertTrue(rejected.message.contains("contract", ignoreCase = true))
        assertTrue(report.toTsv().contains("Good Source"))
        assertTrue(report.toSummaryText().contains("available=1"))
    }

    private class FakeProbeEngine : SourceQualityProbeEngine {
        override fun search(source: BookSource, keyword: String): EngineResult<SourceSearchReport> {
            val book = book(source)
            return EngineResult.Success(
                SourceSearchReport(
                    books = listOf(book),
                    attempts = listOf(SourceSearchAttempt(source.sourceName, true, 1, book.bookUrl))
                )
            )
        }

        override fun getBookDetail(book: SourceBook): EngineResult<SourceBookDetail> {
            return EngineResult.Success(
                SourceBookDetail(
                    book = book,
                    name = book.name,
                    author = book.author,
                    coverUrl = "",
                    intro = "",
                    kind = "",
                    lastChapter = "第三章 收束",
                    tocUrl = "${book.bookUrl}/catalog"
                )
            )
        }

        override fun getCanonicalChapterList(detail: SourceBookDetail): EngineResult<CanonicalChapterList> {
            val chapters = (1..3).map { index ->
                val chapter = SourceChapter(
                    source = detail.book.source,
                    book = detail.book,
                    index = index,
                    name = "第${index}章",
                    chapterUrl = "${detail.book.bookUrl}/$index"
                )
                CanonicalChapter(
                    key = "chapter-$index",
                    displayTitle = chapter.name,
                    ordinal = index,
                    sourceChapters = listOf(chapter)
                )
            }
            return EngineResult.Success(CanonicalChapterList(chapters, duplicateCount = 0, missingOrdinalRanges = emptyList()))
        }

        override fun getCleanContent(chapter: SourceChapter): EngineResult<CleanContent> {
            if (chapter.source.sourceName != "Good Source") {
                return EngineResult.Failure(EngineFailure.NetworkError("unexpected source"))
            }
            return EngineResult.Success(
                CleanContent(
                    rawContent = "raw",
                    cleanedContent = "cleaned".repeat(200),
                    report = ContentQualityReport(
                        qualityScore = 95,
                        rawLength = 1_200,
                        cleanedLength = 1_200,
                        paragraphCount = 10,
                        removedLineCount = 0,
                        duplicateLineCount = 0,
                        pollutionMarkers = emptyList(),
                        warnings = emptyList(),
                        coherenceScore = 95
                    )
                )
            )
        }

        private fun book(source: BookSource): SourceBook {
            return SourceBook(
                source = source,
                name = "斗破苍穹",
                author = "天蚕土豆",
                bookUrl = "${source.sourceUrl}/book",
                coverUrl = "",
                intro = "",
                kind = "",
                lastChapter = "第三章 收束"
            )
        }
    }

    private fun sourceJson(): String {
        return """
            [
              {
                "bookSourceName": "Good Source",
                "bookSourceUrl": "https://good.example",
                "enabled": true,
                "searchUrl": "https://good.example/search?q={{key}}",
                "ruleSearch": {
                  "bookList": ".book",
                  "name": ".title",
                  "author": ".author",
                  "bookUrl": "a@href"
                },
                "ruleBookInfo": {
                  "tocUrl": ".toc@href"
                },
                "ruleToc": {
                  "chapterList": ".chapter",
                  "chapterName": "a@text",
                  "chapterUrl": "a@href"
                },
                "ruleContent": {
                  "content": ".content@text"
                }
              },
              {
                "bookSourceName": "Disabled Source",
                "bookSourceUrl": "https://disabled.example",
                "enabled": false,
                "searchUrl": "https://disabled.example/search?q={{key}}",
                "ruleSearch": {
                  "bookList": ".book",
                  "name": ".title",
                  "bookUrl": "a@href"
                },
                "ruleToc": {
                  "chapterList": ".chapter",
                  "chapterName": "a@text",
                  "chapterUrl": "a@href"
                },
                "ruleContent": {
                  "content": ".content@text"
                }
              },
              {
                "bookSourceName": "No Search Source",
                "bookSourceUrl": "https://no-search.example",
                "enabled": true,
                "ruleSearch": {},
                "ruleToc": {
                  "chapterList": ".chapter",
                  "chapterName": "a@text",
                  "chapterUrl": "a@href"
                },
                "ruleContent": {
                  "content": ".content@text"
                }
              },
              {
                "bookSourceUrl": "https://rejected.example"
              }
            ]
        """.trimIndent()
    }
}
