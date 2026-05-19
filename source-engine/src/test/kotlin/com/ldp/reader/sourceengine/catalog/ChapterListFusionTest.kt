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
            chapter(source, book, 35, "第二十六章 正文继续")
        )

        val result = ChapterListFusion().fuse(listOf(rawCatalog))

        assertEquals(26, result.chapters.size)
        assertEquals("第二十六章 正文继续", result.chapters.last().displayTitle)
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
