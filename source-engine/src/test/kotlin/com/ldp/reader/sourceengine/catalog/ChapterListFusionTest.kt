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
    fun keepsNonOrdinalFinalExtrasAtTailWhenLatestPanelDuplicatesThemAtHead() {
        val source = fixtureSource("A")
        val book = fixtureBook(source)
        val finalExtras = listOf(
            "一个普通人的日常（一）",
            "一个普通人的日常（二）",
            "一个普通人的日常（三）",
            "一个普通人的日常（四）",
            "一个普通人的日常（五）",
            "一个普通人的日常（六）",
            "一个普通人的日常（七）",
            "一个普通人的日常（八）",
            "不是诈尸，不是遭了阿蒙"
        )
        val latestPanel = finalExtras.mapIndexed { index, title ->
            chapter(source, book, 10_000 + index, title)
        }.asReversed()
        val mainCatalog = (1..30).map { ordinal ->
            val title = if (ordinal == 1) "第一章 绯红" else "第${ordinal}章 正文$ordinal"
            chapter(source, book, ordinal - 1, title)
        }
        val canonicalTail = finalExtras.mapIndexed { index, title ->
            chapter(source, book, 10_000 + index, title)
        }

        val result = ChapterListFusion().fuse(listOf(latestPanel + mainCatalog + canonicalTail))

        assertEquals(mainCatalog.size + finalExtras.size, result.chapters.size)
        assertEquals("第一章 绯红", result.chapters.first().displayTitle)
        assertEquals(finalExtras, result.chapters.takeLast(finalExtras.size).map { it.displayTitle })
        finalExtras.forEach { title ->
            assertEquals(1, result.chapters.count { chapter -> chapter.displayTitle == title })
        }
    }

    @Test
    fun keepsSameTitledNonOrdinalProloguesWhenTheirUrlsDifferFromTailEntries() {
        val source = fixtureSource("A")
        val book = fixtureBook(source)
        val prologues = listOf(
            chapter(source, book, 90_000, "作者的话"),
            chapter(source, book, 90_001, "楔子")
        )
        val mainCatalog = (1..30).map { ordinal ->
            chapter(source, book, ordinal, "第${ordinal}章 正文$ordinal")
        }
        val tailEntries = listOf(
            chapter(source, book, 91_000, "楔子"),
            chapter(source, book, 91_001, "作者的话")
        )

        val result = ChapterListFusion().fuse(listOf(prologues + mainCatalog + tailEntries))

        assertEquals(32, result.chapters.size)
        assertEquals("作者的话", result.chapters.first().displayTitle)
        assertEquals("楔子", result.chapters[1].displayTitle)
        assertEquals("第30章 正文30", result.chapters.last().displayTitle)
    }

    @Test
    fun keepsNonOrdinalFinalExtrasWhenTheyOnlyAppearAtTheNormalTail() {
        val source = fixtureSource("A")
        val book = fixtureBook(source)
        val finalExtras = listOf(
            "一个普通人的日常（一）",
            "一个普通人的日常（二）",
            "一个普通人的日常（三）",
            "一个普通人的日常（四）",
            "一个普通人的日常（五）",
            "一个普通人的日常（六）",
            "一个普通人的日常（七）",
            "一个普通人的日常（八）",
            "不是诈尸，不是遭了阿蒙"
        )
        val mainCatalog = (1..30).map { ordinal ->
            chapter(source, book, ordinal - 1, "第${ordinal}章 正文$ordinal")
        }
        val canonicalTail = finalExtras.mapIndexed { index, title ->
            chapter(source, book, 10_000 + index, title)
        }

        val result = ChapterListFusion().fuse(listOf(mainCatalog + canonicalTail))

        assertEquals(mainCatalog.size + finalExtras.size, result.chapters.size)
        assertEquals(finalExtras, result.chapters.takeLast(finalExtras.size).map { it.displayTitle })
        finalExtras.forEach { title ->
            assertEquals(1, result.chapters.count { chapter -> chapter.displayTitle == title })
        }
    }

    @Test
    fun dropsMidCatalogNavigationLabelsOnlyWhenRealChapterAppearsLater() {
        val source = fixtureSource("A")
        val book = fixtureBook(source)
        val beforePollution = (1..160).map { ordinal ->
            chapter(source, book, ordinal - 1, "第${ordinal}章 正文章节$ordinal")
        }
        val navigationLabels = listOf(
            chapter(source, book, 10_000, "378. 第381章"),
            chapter(source, book, 10_001, "VIP. 第381章(最新)")
        )
        val afterPollution = (161..390).map { ordinal ->
            chapter(source, book, ordinal + 10_000, "第${ordinal}章 正文章节$ordinal")
        }
        val titledChapter = chapter(source, book, 19_999, "378. 第381章 真正标题")
        val finalExtras = listOf(
            "一个普通人的日常（一）",
            "一个普通人的日常（二）",
            "不是诈尸，不是遭了阿蒙"
        ).mapIndexed { index, title ->
            chapter(source, book, 20_000 + index, title)
        }

        val result = ChapterListFusion().fuse(
            listOf(beforePollution + navigationLabels + afterPollution + titledChapter + finalExtras)
        )

        assertEquals(390 + 1 + finalExtras.size, result.chapters.size)
        assertEquals("第160章 正文章节160", result.chapters[159].displayTitle)
        assertEquals("第161章 正文章节161", result.chapters[160].displayTitle)
        assertEquals(0, result.chapters.count { chapter -> chapter.displayTitle == "378. 第381章" })
        assertEquals(0, result.chapters.count { chapter -> chapter.displayTitle == "VIP. 第381章(最新)" })
        assertEquals(1, result.chapters.count { chapter -> chapter.displayTitle == "第381章 正文章节381" })
        assertEquals(1, result.chapters.count { chapter -> chapter.displayTitle == "378. 第381章 真正标题" })
        assertEquals(finalExtras.map { it.name }, result.chapters.takeLast(finalExtras.size).map { it.displayTitle })
    }

    @Test
    fun keepsNavigationShapedOrTitledChaptersWithoutAllPollutionEvidence() {
        val source = fixtureSource("A")
        val book = fixtureBook(source)
        val mainCatalog = (1..160).map { ordinal ->
            chapter(source, book, ordinal - 1, "第${ordinal}章 正文章节$ordinal")
        } + listOf(
            chapter(source, book, 10_000, "378. 第381章"),
            chapter(source, book, 10_001, "VIP. 第381章(最新)"),
            chapter(source, book, 10_002, "第161章 正文章节161"),
            chapter(source, book, 10_003, "378. 第381章 真正标题")
        ) + (162..200).map { ordinal ->
            chapter(source, book, ordinal + 10_000, "第${ordinal}章 正文章节$ordinal")
        }

        val result = ChapterListFusion().fuse(listOf(mainCatalog))

        assertEquals(1, result.chapters.count { chapter -> chapter.displayTitle == "378. 第381章" })
        assertEquals(1, result.chapters.count { chapter -> chapter.displayTitle == "VIP. 第381章(最新)" })
        assertEquals(1, result.chapters.count { chapter -> chapter.displayTitle == "378. 第381章 真正标题" })
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
    fun keepsRestartedSideStoryAfterTerminalMainChapterWithoutDuplicateEvidence() {
        val source = fixtureSource("A")
        val book = fixtureBook(source)
        val mainCatalog = (1..120).map { index ->
            chapter(source, book, index - 1, "第${index}章 主线$index")
        } + chapter(source, book, 120, "第一百二十一章 结束，也是开始")
        val sideStory = (1..15).map { index ->
            chapter(source, book, 120 + index, "第${index}章 外传$index")
        }

        val result = ChapterListFusion().fuse(listOf(mainCatalog + sideStory))

        assertEquals(136, result.chapters.size)
        assertEquals("第15章 外传15", result.chapters.last().displayTitle)
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
    fun dropsTrailingDuplicateBlockButKeepsNonOrdinalSeparator() {
        val source = fixtureSource("A")
        val book = fixtureBook(source)
        val mainCatalog = (1..1389).map { index ->
            chapter(source, book, index - 1, "第${index}章 正文章节$index")
        } + chapter(source, book, 1389, "卷末总结")
        val repeatedTail = (1..42).map { index ->
            chapter(source, book, 1390 + index - 1, "第${index}章 正文章节$index")
        }

        val result = ChapterListFusion().fuse(listOf(mainCatalog + repeatedTail))

        assertEquals(1390, result.chapters.size)
        assertEquals("卷末总结", result.chapters.last().displayTitle)
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
    fun keepsUnclassifiedNonOrdinalTailWithoutDuplicateEvidence() {
        val source = fixtureSource("A")
        val book = fixtureBook(source)
        val rawCatalog = (1..25).map { index ->
            chapter(source, book, index - 1, "第${index}章 正文章节$index")
        } + listOf(
            chapter(source, book, 25, "《斗破苍穹：斗帝之路》手游·角色传记（上）"),
            chapter(source, book, 26, "《斗破苍穹：斗帝之路》手游·角色传记（下）")
        )

        val result = ChapterListFusion().fuse(listOf(rawCatalog))

        assertEquals(27, result.chapters.size)
        assertEquals("《斗破苍穹：斗帝之路》手游·角色传记（下）", result.chapters.last().displayTitle)
    }

    @Test
    fun keepsLeadingNonOrdinalPrologueInOriginalPosition() {
        val source = fixtureSource("A")
        val book = fixtureBook(source)
        val rawCatalog = listOf(
            chapter(source, book, 0, "楔子"),
            chapter(source, book, 1, "序章"),
            chapter(source, book, 2, "第一章 陨落的天才"),
            chapter(source, book, 3, "第二章 斗之气三段")
        )

        val result = ChapterListFusion().fuse(listOf(rawCatalog))

        assertEquals("楔子", result.chapters.first().displayTitle)
        assertEquals("序章", result.chapters[1].displayTitle)
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

    @Test
    fun scansLargeAscendingCatalogWithinLinearOrdinalVisitBudget() {
        val source = fixtureSource("large")
        val book = fixtureBook(source)
        val catalogSize = 1_900
        val rawCatalog = (1..catalogSize).map { ordinal ->
            chapter(source, book, ordinal - 1, "第${ordinal}章 正文章节$ordinal")
        }
        var ordinalVisits = 0
        val fusion = ChapterListFusion(
            normalizer = ChapterNormalizer(),
            workProbe = ChapterListFusionWorkProbe { count -> ordinalVisits += count }
        )

        val result = fusion.fuse(listOf(rawCatalog))

        assertEquals(catalogSize, result.chapters.size)
        assertEquals("第1章 正文章节1", result.chapters.first().displayTitle)
        assertEquals("第1900章 正文章节1900", result.chapters.last().displayTitle)
        assertTrue(
            "catalog ordinal visits must stay linear: visits=$ordinalVisits, size=$catalogSize",
            ordinalVisits <= catalogSize * 12
        )
    }

    @Test
    fun normalizesEachUniqueTitleOncePerFusion() {
        val source = fixtureSource("normalization-cache")
        val book = fixtureBook(source)
        val catalogSize = 2_054
        val rawCatalog = (1..catalogSize).map { ordinal ->
            chapter(source, book, ordinal - 1, "第${ordinal}章 正文$ordinal")
        }
        var normalizeCalls = 0
        val normalizer = ChapterNormalizer(
            hotRegexCompiler = { pattern -> Regex(pattern) },
            normalizeProbe = { normalizeCalls++ }
        )

        val result = ChapterListFusion(normalizer).fuse(listOf(rawCatalog))

        assertEquals(catalogSize, result.chapters.size)
        assertEquals(catalogSize, normalizeCalls)
    }

    @Test
    fun skipsDisplayTitlePenaltyForSingleSourceChapterGroup() {
        val source = fixtureSource("single-source-display")
        val book = fixtureBook(source)
        val chapters = listOf(
            chapter(source, book, 0, "第一章 初见"),
            chapter(source, book, 1, "第1章 初见")
        )
        val probe = CountingWorkProbe()

        val result = ChapterListFusion(ChapterNormalizer(), probe).fuse(listOf(chapters))

        assertEquals(1, result.chapters.size)
        assertEquals("第一章 初见", result.chapters.single().displayTitle)
        assertEquals(0, probe.catalogTitlePenaltyEvaluations)
    }

    @Test
    fun stillScoresDisplayTitlesAcrossDifferentSources() {
        val firstSource = fixtureSource("display-a")
        val secondSource = fixtureSource("display-b")
        val firstBook = fixtureBook(firstSource)
        val secondBook = fixtureBook(secondSource)
        val probe = CountingWorkProbe()

        val result = ChapterListFusion(ChapterNormalizer(), probe).fuse(
            listOf(
                listOf(chapter(firstSource, firstBook, 0, "第1章 初 见")),
                listOf(chapter(secondSource, secondBook, 0, "第一章 初见"))
            )
        )

        assertEquals(1, result.chapters.size)
        assertEquals("第一章 初见", result.chapters.single().displayTitle)
        assertEquals(2, probe.catalogTitlePenaltyEvaluations)
    }

    @Test
    fun commonChapterTitlesAvoidSuffixRegexFallback() {
        val source = fixtureSource("common-suffix")
        val book = fixtureBook(source)
        val rawCatalog = buildList {
            add(chapter(source, book, 0, "第一章 正文1"))
            (2..2_053).forEach { ordinal ->
                add(chapter(source, book, ordinal - 1, "第${ordinal}章 正文$ordinal"))
            }
            add(chapter(source, book, 2_053, "第两千零五十四章 正文2054"))
        }
        val probe = CountingWorkProbe()

        val result = ChapterListFusion(ChapterNormalizer(), probe).fuse(listOf(rawCatalog))

        assertEquals(2_054, result.chapters.size)
        assertEquals("第一章 正文1", result.chapters.first().displayTitle)
        assertEquals("第两千零五十四章 正文2054", result.chapters.last().displayTitle)
        assertEquals(0, probe.titleSuffixRegexFallbacks)
    }

    @Test
    fun keepsCommonTitlesWithSameOrdinalAndDifferentSuffixesSeparate() {
        val firstSource = fixtureSource("suffix-a")
        val secondSource = fixtureSource("suffix-b")
        val firstBook = fixtureBook(firstSource)
        val secondBook = fixtureBook(secondSource)

        val result = ChapterListFusion().fuse(
            listOf(
                listOf(chapter(firstSource, firstBook, 0, "第1章 初见")),
                listOf(chapter(secondSource, secondBook, 0, "第一章 重逢"))
            )
        )

        assertEquals(2, result.chapters.size)
        assertEquals(listOf("第1章 初见", "第一章 重逢"), result.chapters.map { it.displayTitle })
    }

    @Test
    fun complexAndVolumeTitlesStillUseSuffixRegexFallback() {
        val source = fixtureSource("complex-suffix")
        val book = fixtureBook(source)
        val probe = CountingWorkProbe()

        val result = ChapterListFusion(ChapterNormalizer(), probe).fuse(
            listOf(
                listOf(
                    chapter(source, book, 0, "第一卷 序章"),
                    chapter(source, book, 1, "前言 第一章 开始")
                )
            )
        )

        assertEquals(listOf("第一卷 序章", "前言 第一章 开始"), result.chapters.map { it.displayTitle })
        assertEquals(2, probe.titleSuffixRegexFallbacks)
    }

    private class CountingWorkProbe : ChapterListFusionWorkProbe {
        var catalogTitlePenaltyEvaluations = 0
        var titleSuffixRegexFallbacks = 0

        override fun onCatalogOrdinalVisits(count: Int) = Unit

        override fun onCatalogTitlePenaltyEvaluations(count: Int) {
            catalogTitlePenaltyEvaluations += count
        }

        override fun onTitleSuffixRegexFallbacks(count: Int) {
            titleSuffixRegexFallbacks += count
        }
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
