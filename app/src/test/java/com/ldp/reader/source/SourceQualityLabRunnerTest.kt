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

        val report = runner.run(
            sourceJson(),
            SourceQualityLabConfig(
                keyword = "斗破苍穹",
                sampleKeywords = listOf("斗破苍穹", "诡秘之主"),
                maxSources = 10
            )
        )

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
        assertEquals(2, available.sampleCount)
        assertEquals(2, available.availableSampleCount)
        assertTrue(available.rareReadable)
        assertEquals(1, available.readableSourceCountForSample)
        assertTrue(report.toSummaryText().contains("Rare readable"))

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

    @Test
    fun rejectsNonExactSearchResultsAsMismatch() {
        val runner = SourceQualityLabRunner(
            engine = MismatchProbeEngine(),
            router = SourceQualityRouter(storage = InMemorySourceQualityStorage(), seed = SourceQualitySeed.empty())
        )

        val report = runner.run(
            singleValidSourceJson("Mismatch Source", "https://mismatch.example"),
            SourceQualityLabConfig(
                keyword = "诡秘之主",
                sampleKeywords = listOf("诡秘之主"),
                maxSources = 1,
                requireExactSearchMatch = true
            )
        )

        assertEquals(1, report.probedCount)
        assertEquals(0, report.availableCount)
        val mismatch = report.entries.single()
        assertEquals(SourceQualityLabStatus.SEARCH_MISMATCH, mismatch.status)
        assertFalse(mismatch.usable)
        assertTrue(mismatch.message.contains("SEARCH_MISMATCH"))
        assertEquals(0, report.entries.count { it.status == SourceQualityLabStatus.AVAILABLE })
    }

    @Test
    fun rejectsSuffixSearchResultWhenNoExactTitleExists() {
        val runner = SourceQualityLabRunner(
            engine = SuffixMismatchProbeEngine(),
            router = SourceQualityRouter(storage = InMemorySourceQualityStorage(), seed = SourceQualitySeed.empty())
        )

        val report = runner.run(
            singleValidSourceJson("Suffix Mismatch Source", "https://suffix.example"),
            SourceQualityLabConfig(
                keyword = "我不是戏神",
                sampleKeywords = listOf("我不是戏神"),
                maxSources = 1,
                requireExactSearchMatch = true
            )
        )

        assertEquals(0, report.availableCount)
        assertEquals(SourceQualityLabStatus.SEARCH_MISMATCH, report.entries.single().status)
    }

    @Test
    fun acceptsExactSearchResultEvenWhenOtherResultsAreNonExact() {
        val engine = MixedSearchProbeEngine()
        val runner = SourceQualityLabRunner(
            engine = engine,
            router = SourceQualityRouter(storage = InMemorySourceQualityStorage(), seed = SourceQualitySeed.empty())
        )

        val report = runner.run(
            singleValidSourceJson("Mixed Source", "https://mixed.example"),
            SourceQualityLabConfig(
                keyword = "诡秘之主",
                sampleKeywords = listOf("诡秘之主"),
                maxSources = 1,
                requireExactSearchMatch = true
            )
        )

        assertEquals(1, report.availableCount)
        val available = report.entries.single()
        assertEquals(SourceQualityLabStatus.AVAILABLE, available.status)
        assertEquals("诡秘之主", available.bookName)
        assertEquals("https://mixed.example/book/exact", engine.detailBookUrl)
    }

    @Test
    fun appliesSourceOffsetBeforeNetworkProbeBatch() {
        val engine = CountingProbeEngine()
        val runner = SourceQualityLabRunner(
            engine = engine,
            router = SourceQualityRouter(storage = InMemorySourceQualityStorage(), seed = SourceQualitySeed.empty())
        )

        val report = runner.run(
            sourceJson(),
            SourceQualityLabConfig(
                sampleKeywords = listOf("斗破苍穹"),
                sourceOffset = 1,
                maxSources = 10
            )
        )

        assertEquals(0, engine.searchCount)
        assertEquals(0, report.probedCount)
        val skipped = report.entries.first { it.sourceName == "Good Source" }
        assertEquals(SourceQualityLabStatus.SKIPPED_BY_LIMIT, skipped.status)
        assertTrue(skipped.message.contains("sourceOffset=1"))
    }

    private open class FakeProbeEngine : SourceQualityProbeEngine {
        override open fun search(source: BookSource, keyword: String): EngineResult<SourceSearchReport> {
            val book = book(source, keyword)
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

        private fun book(source: BookSource, keyword: String): SourceBook {
            return SourceBook(
                source = source,
                name = keyword,
                author = "天蚕土豆",
                bookUrl = "${source.sourceUrl}/book/$keyword",
                coverUrl = "",
                intro = "",
                kind = "",
                lastChapter = "第三章 收束"
            )
        }
    }

    private class MismatchProbeEngine : SourceQualityProbeEngine {
        override fun search(source: BookSource, keyword: String): EngineResult<SourceSearchReport> {
            val book = SourceBook(
                source = source,
                name = "诡秘之主番外合集",
                author = "无关作者",
                bookUrl = "${source.sourceUrl}/wrong",
                coverUrl = "",
                intro = "",
                kind = "",
                lastChapter = "第一章"
            )
            return EngineResult.Success(
                SourceSearchReport(
                    books = listOf(book),
                    attempts = listOf(SourceSearchAttempt(source.sourceName, true, 1, book.bookUrl))
                )
            )
        }

        override fun getBookDetail(book: SourceBook): EngineResult<SourceBookDetail> {
            throw AssertionError("detail should not be called for mismatched search results")
        }

        override fun getCanonicalChapterList(detail: SourceBookDetail): EngineResult<CanonicalChapterList> {
            throw AssertionError("catalog should not be called for mismatched search results")
        }

        override fun getCleanContent(chapter: SourceChapter): EngineResult<CleanContent> {
            throw AssertionError("content should not be called for mismatched search results")
        }
    }

    private class SuffixMismatchProbeEngine : SourceQualityProbeEngine {
        override fun search(source: BookSource, keyword: String): EngineResult<SourceSearchReport> {
            val book = SourceBook(
                source = source,
                name = "$keyword(1-1291)",
                author = "无关作者",
                bookUrl = "${source.sourceUrl}/wrong",
                coverUrl = "",
                intro = "",
                kind = "",
                lastChapter = "第一章"
            )
            return EngineResult.Success(
                SourceSearchReport(
                    books = listOf(book),
                    attempts = listOf(SourceSearchAttempt(source.sourceName, true, 1, book.bookUrl))
                )
            )
        }

        override fun getBookDetail(book: SourceBook): EngineResult<SourceBookDetail> {
            throw AssertionError("detail should not be called for suffixed search results")
        }

        override fun getCanonicalChapterList(detail: SourceBookDetail): EngineResult<CanonicalChapterList> {
            throw AssertionError("catalog should not be called for suffixed search results")
        }

        override fun getCleanContent(chapter: SourceChapter): EngineResult<CleanContent> {
            throw AssertionError("content should not be called for suffixed search results")
        }
    }

    private class MixedSearchProbeEngine : SourceQualityProbeEngine {
        var detailBookUrl: String = ""

        override fun search(source: BookSource, keyword: String): EngineResult<SourceSearchReport> {
            val extra = SourceBook(
                source = source,
                name = "诡秘之主同人短篇",
                author = "同人作者",
                bookUrl = "${source.sourceUrl}/book/extra",
                coverUrl = "",
                intro = "",
                kind = "",
                lastChapter = "第一章"
            )
            val exact = SourceBook(
                source = source,
                name = "诡秘之主",
                author = "爱潜水的乌贼",
                bookUrl = "${source.sourceUrl}/book/exact",
                coverUrl = "",
                intro = "",
                kind = "",
                lastChapter = "第三章 收束"
            )
            return EngineResult.Success(
                SourceSearchReport(
                    books = listOf(extra, exact),
                    attempts = listOf(SourceSearchAttempt(source.sourceName, true, 2, exact.bookUrl))
                )
            )
        }

        override fun getBookDetail(book: SourceBook): EngineResult<SourceBookDetail> {
            detailBookUrl = book.bookUrl
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
    }

    private class CountingProbeEngine : FakeProbeEngine() {
        var searchCount: Int = 0

        override fun search(source: BookSource, keyword: String): EngineResult<SourceSearchReport> {
            searchCount += 1
            return super.search(source, keyword)
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

    private fun singleValidSourceJson(name: String, url: String): String {
        return """
            [
              {
                "bookSourceName": "$name",
                "bookSourceUrl": "$url",
                "enabled": true,
                "searchUrl": "$url/search?q={{key}}",
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
              }
            ]
        """.trimIndent()
    }
}
