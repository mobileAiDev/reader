package com.ldp.reader.sourceengine.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterNormalizerTest {
    @Test
    fun normalizesEnglishChapterNumberVariantsToSameOrdinalKey() {
        val normalizer = ChapterNormalizer()

        assertEquals("n:1", normalizer.normalize("1.Chapter 1").key)
        assertEquals("n:1", normalizer.normalize("1 Chapter 1").key)
        assertEquals("n:1", normalizer.normalize("Chapter 1").key)
    }

    @Test
    fun repairsTruncatedCatalogMarkerBeforeChapterOrdinal() {
        val normalizer = ChapterNormalizer()

        val title = normalizer.normalize("录 第五百九十一章 融合...")

        assertEquals(591, title.ordinal)
        assertEquals("第五百九十一章 融合...", title.displayTitle)
    }

    @Test
    fun stripsBookAndCatalogMarkerBeforeChapterOrdinal() {
        val normalizer = ChapterNormalizer()

        val title = normalizer.normalize("斗破苍穹 VIP章节目录 第五百九十一章 融合...")

        assertEquals(591, title.ordinal)
        assertEquals("第五百九十一章 融合...", title.displayTitle)
    }

    @Test
    fun keepsLegitimateMemoirPrefixBeforeChapterOrdinal() {
        val normalizer = ChapterNormalizer()

        val title = normalizer.normalize("回忆录 第一章 开始")

        assertEquals(1, title.ordinal)
        assertEquals("回忆录 第一章 开始", title.displayTitle)
    }

    @Test
    fun compilesEachHotPatternOnlyOnceForLargeCatalogNormalization() {
        val compiledPatterns = ArrayList<String>()
        val normalizer = ChapterNormalizer { pattern ->
            compiledPatterns += pattern
            Regex(pattern)
        }

        repeat(1_900) { index ->
            normalizer.normalize("第${index + 1}章 正文章节${index + 1}")
            normalizer.normalize("作者的话")
            normalizer.normalize("第十一卷 真仙降临 第第两千四百三十七章 龙岛")
        }

        assertEquals(6, compiledPatterns.distinct().size)
        assertTrue(
            "hot patterns must be compiled once, calls=${compiledPatterns.size}",
            compiledPatterns.size <= compiledPatterns.distinct().size
        )
    }

    @Test
    fun cleanNumericCatalogUsesOnlyOneOrdinalRegexScanPerTitle() {
        val scans = LinkedHashMap<ChapterNormalizerRegexWork, Int>()
        val normalizer = ChapterNormalizer(
            hotRegexCompiler = { pattern -> Regex(pattern) },
            normalizeProbe = {},
            workProbe = ChapterNormalizerWorkProbe { work ->
                scans[work] = scans.getOrDefault(work, 0) + 1
            }
        )
        val catalogSize = 2_054

        repeat(catalogSize) { index ->
            val ordinal = index + 1
            val title = normalizer.normalize("第${ordinal}章 正文$ordinal")

            assertEquals(ordinal, title.ordinal)
            assertEquals("第${ordinal}章 正文$ordinal", title.displayTitle)
        }

        assertEquals(catalogSize, scans[ChapterNormalizerRegexWork.CHINESE_ORDINAL])
        assertEquals(
            "clean numeric titles should not enter whitespace or malformed-repair regexes: $scans",
            catalogSize,
            scans.values.sum()
        )
    }

    @Test
    fun optimizedPathPreservesComplexDirtyAndVolumePrefixedTitles() {
        val normalizer = ChapterNormalizer()
        val expected = listOf(
            "第十一卷 真仙降临 第第两千四百三十七章 龙岛" to
                (2437 to "第两千四百三十七章 龙岛"),
            "第十一卷 真仙降临 第两千四百三十八章 三收获" to
                (2438 to "第两千四百三十八章 收获"),
            "第十一卷 真仙降临 第两千四百三十九章第 交易、大会" to
                (2439 to "第两千四百三十九章 交易、大会"),
            "第十一卷 真仙降临 第两千四百四十一一章 人族之变" to
                (2441 to "第两千四百四十一章 人族之变")
        )

        expected.forEach { (rawTitle, result) ->
            val title = normalizer.normalize(rawTitle)

            assertEquals(result.first, title.ordinal)
            assertEquals(result.second, title.displayTitle)
        }
    }
}
