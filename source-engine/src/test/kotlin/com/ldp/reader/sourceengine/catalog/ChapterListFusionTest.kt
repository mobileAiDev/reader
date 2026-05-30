package com.ldp.reader.sourceengine.catalog

import com.ldp.reader.sourceengine.legado.LegadoRuleSet
import com.ldp.reader.sourceengine.model.BookSource
import com.ldp.reader.sourceengine.model.SourceBook
import com.ldp.reader.sourceengine.model.SourceChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterListFusionTest {
    @Test
    fun normalizesChapterNumbersAndReportsDuplicatesAndMissingRanges() {
        val source = fixtureSource("A")
        val book = fixtureBook(source)
        val firstList = listOf(
            chapter(source, book, 0, "第一章 陨落的天才"),
            chapter(source, book, 1, "第2章 斗之气三段"),
            chapter(source, book, 2, "第四章 云岚宗")
        )
        val secondSource = fixtureSource("B")
        val secondBook = fixtureBook(secondSource)
        val secondList = listOf(
            chapter(secondSource, secondBook, 0, "第1章 陨落的天才"),
            chapter(secondSource, secondBook, 1, "第4章 云岚宗")
        )

        val result = ChapterListFusion().fuse(listOf(firstList, secondList))

        assertEquals(3, result.chapters.size)
        assertEquals(2, result.duplicateCount)
        assertEquals(1, result.missingOrdinalRanges.size)
        assertEquals(3, result.missingOrdinalRanges[0].start)
        assertEquals(3, result.missingOrdinalRanges[0].end)
        assertEquals(1, result.chapters[0].ordinal)
        assertEquals(2, result.chapters[0].sourceChapters.size)
    }

    @Test
    fun parsesChineseNumberTitles() {
        val title = ChapterNormalizer().normalize("第一百零二章 山谷")

        assertEquals(102, title.ordinal)
        assertEquals("n:102", title.key)
        assertTrue(title.displayTitle.contains("山谷"))
    }

    @Test
    fun parsesChapterOrdinalAfterVolumePrefix() {
        val title = ChapterNormalizer().normalize("第十一卷 真仙降临 第两千四百三十七章 龙岛")

        assertEquals(2437, title.ordinal)
        assertEquals("n:2437", title.key)
        assertEquals("第两千四百三十七章 龙岛", title.displayTitle)
    }

    @Test
    fun repairsMalformedVolumePrefixedTailTitles() {
        val source = fixtureSource("A")
        val book = fixtureBook(source)
        val rawCatalog = listOf(
            chapter(source, book, 0, "第十一卷 真仙降临 第第两千四百三十七章 龙岛"),
            chapter(source, book, 1, "第十一卷 真仙降临 第两千四百三十八章 三收获"),
            chapter(source, book, 2, "第十一卷 真仙降临 第两千四百三十九章第 交易、大会"),
            chapter(source, book, 3, "第十一卷 降真仙降临 第两千四百四十章 得果"),
            chapter(source, book, 4, "第十一卷 真仙降临 第两千四百四十一一章 人族之变")
        )

        val result = ChapterListFusion().fuse(listOf(rawCatalog))

        assertEquals("第两千四百三十七章 龙岛", result.chapters[0].displayTitle)
        assertEquals("第两千四百三十八章 收获", result.chapters[1].displayTitle)
        assertEquals("第两千四百三十九章 交易、大会", result.chapters[2].displayTitle)
        assertEquals("第两千四百四十章 得果", result.chapters[3].displayTitle)
        assertEquals("第两千四百四十一章 人族之变", result.chapters[4].displayTitle)
    }

    @Test
    fun dropsRecentUpdatePrefixWhenCatalogRestartsFromBeginning() {
        val source = fixtureSource("A")
        val book = fixtureBook(source)
        val rawCatalog = listOf(
            chapter(source, book, 0, "第一千五百九十七章 最新更新"),
            chapter(source, book, 1, "第一千五百九十八章 最新更新二"),
            chapter(source, book, 2, "第一章 陨落的天才"),
            chapter(source, book, 3, "第二章 斗之气三段"),
            chapter(source, book, 4, "第三章 客人"),
            chapter(source, book, 5, "第四章 云岚宗"),
            chapter(source, book, 6, "第五章 退婚"),
            chapter(source, book, 7, "第六章 炼药师")
        )

        val result = ChapterListFusion().fuse(listOf(rawCatalog))

        assertEquals(6, result.chapters.size)
        assertEquals("第一章 陨落的天才", result.chapters.first().displayTitle)
        assertEquals(1, result.chapters.first().ordinal)
        assertEquals("第六章 炼药师", result.chapters.last().displayTitle)
    }

    @Test
    fun dropsShortDuplicatedLatestPrefixBeforeAscendingCatalog() {
        val source = fixtureSource("A")
        val book = fixtureBook(source)
        val rawCatalog = listOf(
            chapter(source, book, 0, "第三章 丁家（感谢极光会O先生）"),
            chapter(source, book, 1, "第二章 木鸢"),
            chapter(source, book, 2, "第一章 晓梦"),
            chapter(source, book, 3, "欢迎收藏"),
            chapter(source, book, 4, "第一章 晓梦"),
            chapter(source, book, 5, "第二章 木鸢"),
            chapter(source, book, 6, "第三章 丁家（感谢极光会O先生）")
        )

        val result = ChapterListFusion().fuse(listOf(rawCatalog))

        assertEquals(3, result.chapters.size)
        assertEquals("第一章 晓梦", result.chapters.first().displayTitle)
        assertEquals("第三章 丁家（感谢极光会O先生）", result.chapters.last().displayTitle)
    }

    @Test
    fun dropsNumericLatestUpdatePrefixBeforeFullAscendingCatalog() {
        val source = fixtureSource("A")
        val book = fixtureBook(source)
        val recentPrefix = (687 downTo 681).mapIndexed { index, ordinal ->
            chapter(source, book, index, "$ordinal、最新$ordinal")
        }
        val mainCatalog = (1..687).map { ordinal ->
            chapter(source, book, 1_000 + ordinal, "$ordinal、正文$ordinal")
        }

        val result = ChapterListFusion().fuse(listOf(recentPrefix + mainCatalog))

        assertEquals(687, result.chapters.size)
        assertEquals("1、正文1", result.chapters.first().displayTitle)
        assertEquals("687、正文687", result.chapters.last().displayTitle)
    }

    @Test
    fun reversesLatestFirstCatalogBeforeFusion() {
        val source = fixtureSource("A")
        val book = fixtureBook(source)
        val rawCatalog = (89 downTo 1).mapIndexed { index, ordinal ->
            val title = if (ordinal == 89) "89 番外" else "$ordinal 难哄"
            chapter(source, book, index, title)
        }

        val result = ChapterListFusion().fuse(listOf(rawCatalog))

        assertEquals("1 难哄", result.chapters.first().displayTitle)
        assertEquals("89 番外", result.chapters.last().displayTitle)
    }

    @Test
    fun keepsVolumeCatalogWhenChapterNumbersRestartInsideMainList() {
        val source = fixtureSource("A")
        val book = fixtureBook(source)
        val firstVolume = (1..120).map { index ->
            chapter(source, book, index - 1, "第${index}章 第一卷正文$index")
        }
        val secondVolume = (1..90).map { index ->
            chapter(source, book, 120 + index - 1, "第${index}章 第二卷正文$index")
        }

        val result = ChapterListFusion().fuse(listOf(firstVolume + secondVolume))

        assertEquals(210, result.chapters.size)
        assertEquals("第1章 第一卷正文1", result.chapters.first().displayTitle)
        assertEquals("第1章 第二卷正文1", result.chapters[120].displayTitle)
        assertEquals("第90章 第二卷正文90", result.chapters.last().displayTitle)
        assertEquals(0, result.duplicateCount)
    }

    @Test
    fun doesNotCollapseSameOrdinalWithDifferentChapterTitles() {
        val source = fixtureSource("A")
        val book = fixtureBook(source)
        val rawCatalog = listOf(
            chapter(source, book, 0, "第一章 绯红"),
            chapter(source, book, 1, "第二章 情况"),
            chapter(source, book, 2, "第一章 小丑"),
            chapter(source, book, 3, "第二章 俱乐部")
        )

        val result = ChapterListFusion().fuse(listOf(rawCatalog))

        assertEquals(4, result.chapters.size)
        assertEquals("第一章 绯红", result.chapters[0].displayTitle)
        assertEquals("第二章 情况", result.chapters[1].displayTitle)
        assertEquals("第一章 小丑", result.chapters[2].displayTitle)
        assertEquals("第二章 俱乐部", result.chapters[3].displayTitle)
    }

    @Test
    fun dropsSmallRestartedSideStoryAfterTerminalMainChapter() {
        val source = fixtureSource("A")
        val book = fixtureBook(source)
        val mainCatalog = (1..120).map { index ->
            chapter(source, book, index - 1, "第${index}章 主线$index")
        } + chapter(source, book, 120, "第一百二十一章 结束，也是开始")
        val sideStory = (1..15).map { index ->
            chapter(source, book, 120 + index, "第${index}章 外传$index")
        }

        val result = ChapterListFusion().fuse(listOf(mainCatalog + sideStory))

        assertEquals(121, result.chapters.size)
        assertEquals("第一百二十一章 结束，也是开始", result.chapters.last().displayTitle)
    }

    @Test
    fun dropsTrailingLowOrdinalRestartBlockAfterLongMainCatalog() {
        val source = fixtureSource("A")
        val book = fixtureBook(source)
        val mainCatalog = (1..946).map { index ->
            chapter(source, book, index - 1, "第${index}章 主线$index")
        }
        val repeatedTail = (1..122).map { index ->
            chapter(source, book, 946 + index - 1, "第${index}章 主线$index")
        }

        val result = ChapterListFusion().fuse(listOf(mainCatalog + repeatedTail))

        assertEquals(946, result.chapters.size)
        assertEquals("第946章 主线946", result.chapters.last().displayTitle)
    }

    @Test
    fun dropsTrailingLowOrdinalRestartBlockWithoutTerminalMarker() {
        val source = fixtureSource("A")
        val book = fixtureBook(source)
        val mainCatalog = (1..1293).map { index ->
            chapter(source, book, index - 1, "第${index}章 正文章节$index")
        }
        val repeatedTail = (1..72).map { index ->
            chapter(source, book, 1293 + index - 1, "第${index}章 正文章节$index")
        }

        val result = ChapterListFusion().fuse(listOf(mainCatalog + repeatedTail))

        assertEquals(1293, result.chapters.size)
        assertEquals("第1293章 正文章节1293", result.chapters.last().displayTitle)
    }

    @Test
    fun dropsTrailingLowOrdinalRestartBlockAfterNonOrdinalSeparator() {
        val source = fixtureSource("A")
        val book = fixtureBook(source)
        val mainCatalog = (1..1389).map { index ->
            chapter(source, book, index - 1, "第${index}章 正文章节$index")
        } + chapter(source, book, 1389, "卷末总结")
        val repeatedTail = (1..42).map { index ->
            chapter(source, book, 1390 + index - 1, "第${index}章 正文章节$index")
        }

        val result = ChapterListFusion().fuse(listOf(mainCatalog + repeatedTail))

        assertEquals(1389, result.chapters.size)
        assertEquals("第1389章 正文章节1389", result.chapters.last().displayTitle)
    }

    @Test
    fun keepsLongVolumeRestartWhenTitlesAreDifferent() {
        val source = fixtureSource("A")
        val book = fixtureBook(source)
        val firstVolume = (1..600).map { index ->
            chapter(source, book, index - 1, "第${index}章 第一卷正文$index")
        }
        val secondVolume = (1..80).map { index ->
            chapter(source, book, 600 + index - 1, "第${index}章 第二卷正文$index")
        }

        val result = ChapterListFusion().fuse(listOf(firstVolume + secondVolume))

        assertEquals(680, result.chapters.size)
        assertEquals("第80章 第二卷正文80", result.chapters.last().displayTitle)
    }

    @Test
    fun dropsAnnouncementEntriesFromLongCatalog() {
        val source = fixtureSource("A")
        val book = fixtureBook(source)
        val rawCatalog = (1..25).map { index ->
            chapter(source, book, index - 1, "第${index}章 正文章节$index")
        } + listOf(
            chapter(source, book, 25, "新书元尊已在起点上传，欢迎大家阅读。"),
            chapter(source, book, 26, "今天和明天都只有一更（中午已更）"),
            chapter(source, book, 27, "今晚别等了"),
            chapter(source, book, 28, "请一天假"),
            chapter(source, book, 29, "兄弟们，请一天"),
            chapter(source, book, 30, "请假条"),
            chapter(source, book, 31, "新书已发，《苟在两界修仙》，还请多多支持"),
            chapter(source, book, 32, "新书，大道之上，在起点发布啦！"),
            chapter(source, book, 33, "完本感言"),
            chapter(source, book, 34, "更新计划"),
            chapter(source, book, 35, "五月中奖名单"),
            chapter(source, book, 36, "感谢Raincheck 打赏黄金。"),
            chapter(source, book, 37, "第二十六章 正文继续")
        )

        val result = ChapterListFusion().fuse(listOf(rawCatalog))

        assertEquals(26, result.chapters.size)
        assertEquals("第二十六章 正文继续", result.chapters.last().displayTitle)
    }

    @Test
    fun choosesCleanerDuplicateDisplayTitleFromOtherSources() {
        val badSource = fixtureSource("bad")
        val cleanSource = fixtureSource("clean")
        val badBook = fixtureBook(badSource)
        val cleanBook = fixtureBook(cleanSource)
        val badCatalog = listOf(
            chapter(badSource, badBook, 0, "第十一卷 真仙降临 第第两千四百三十七章 龙岛")
        )
        val cleanCatalog = listOf(
            chapter(cleanSource, cleanBook, 0, "第两千四百三十七章 龙岛")
        )

        val result = ChapterListFusion().fuse(listOf(badCatalog, cleanCatalog))

        assertEquals(1, result.chapters.size)
        assertEquals(2437, result.chapters.first().ordinal)
        assertEquals("第两千四百三十七章 龙岛", result.chapters.first().displayTitle)
    }

    @Test
    fun dropsTrailingNonChapterExtrasFromLongOrdinalCatalog() {
        val source = fixtureSource("A")
        val book = fixtureBook(source)
        val rawCatalog = (1..25).map { index ->
            chapter(source, book, index - 1, "第${index}章 正文章节$index")
        } + listOf(
            chapter(source, book, 25, "《斗破苍穹：斗帝之路》手游·角色传记（上）"),
            chapter(source, book, 26, "《斗破苍穹：斗帝之路》手游·角色传记（下）")
        )

        val result = ChapterListFusion().fuse(listOf(rawCatalog))

        assertEquals(25, result.chapters.size)
        assertEquals("第25章 正文章节25", result.chapters.last().displayTitle)
    }

    @Test
    fun keepsLeadingNonOrdinalPrologueInOriginalPosition() {
        val source = fixtureSource("A")
        val book = fixtureBook(source)
        val rawCatalog = listOf(
            chapter(source, book, 0, "楔子"),
            chapter(source, book, 1, "第一章 陨落的天才"),
            chapter(source, book, 2, "第二章 斗之气三段")
        )

        val result = ChapterListFusion().fuse(listOf(rawCatalog))

        assertEquals("楔子", result.chapters.first().displayTitle)
        assertEquals("第二章 斗之气三段", result.chapters.last().displayTitle)
    }

    @Test
    fun keepsOriginalOrderWhenLongCatalogContainsNonOrdinalEntries() {
        val source = fixtureSource("A")
        val book = fixtureBook(source)
        val rawCatalog = (1..1200).map { index ->
            chapter(source, book, index - 1, "第${index}章 正文章节$index")
        }.toMutableList()
        rawCatalog.add(97, chapter(source, book, 50_000, "卷首杂谈"))

        val result = ChapterListFusion().fuse(listOf(rawCatalog))

        assertEquals(1201, result.chapters.size)
        assertEquals("第97章 正文章节97", result.chapters[96].displayTitle)
        assertEquals("卷首杂谈", result.chapters[97].displayTitle)
        assertEquals("第98章 正文章节98", result.chapters[98].displayTitle)
    }

    private fun fixtureSource(name: String): BookSource {
        return BookSource(
            sourceName = name,
            sourceUrl = "https://example.com/$name",
            sourceGroup = null,
            sourceComment = null,
            enabled = true,
            headers = emptyMap(),
            searchUrl = null,
            ruleSearch = LegadoRuleSet("ruleSearch", emptyMap()),
            ruleBookInfo = LegadoRuleSet("ruleBookInfo", emptyMap()),
            ruleToc = LegadoRuleSet("ruleToc", emptyMap()),
            ruleContent = LegadoRuleSet("ruleContent", emptyMap()),
            diagnostics = emptyList()
        )
    }

    private fun fixtureBook(source: BookSource): SourceBook {
        return SourceBook(
            source = source,
            name = "斗破苍穹",
            author = "天蚕土豆",
            bookUrl = source.sourceUrl,
            coverUrl = "",
            intro = "",
            kind = "",
            lastChapter = ""
        )
    }

    private fun chapter(source: BookSource, book: SourceBook, index: Int, name: String): SourceChapter {
        return SourceChapter(
            source = source,
            book = book,
            index = index,
            name = name,
            chapterUrl = "${source.sourceUrl}/$index.html"
        )
    }
}
