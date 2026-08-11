package com.ldp.reader.source

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BundledNovelSourceRuleTest {
    @Test
    fun jiuwuShubaoMirrorsDoNotLeaveCatalogMarkerTailInChapterTitle() {
        val cases = listOf(
            "VIP章节目录 第五百九十一章 融合..." to "第五百九十一章 融合...",
            "VIP章节第五百九十一章 融合..." to "第五百九十一章 融合...",
            "章节目录：第五百九十一章 融合..." to "第五百九十一章 融合...",
            "正文第一章 开始" to "第一章 开始",
            "正文卷 第一章 开始" to "第一章 开始",
            "正文回忆录 第一章" to "正文回忆录 第一章",
            "VIP章节拍卖会 第一章" to "VIP章节拍卖会 第一章"
        )

        listOf("http://www.95shubao.la", "http://www.95shubao.cc").forEach { sourceUrl ->
            val source = bundledSources().first { it.get("bookSourceUrl").asString == sourceUrl }
            val replacePattern = source
                .getAsJsonObject("ruleToc")
                .get("chapterName")
                .asString
                .substringAfter("##")

            cases.forEach { (rawTitle, expected) ->
                val cleaned = Regex(replacePattern).replace(rawTitle, "").trim()
                assertEquals("$sourceUrl: $rawTitle", expected, cleaned)
            }
        }
    }

    @Test
    fun bundledChapterTitleRulesDoNotUseWildcardAfterKnownCatalogMarkers() {
        val titleRules = bundledSources().flatMap { source ->
            listOfNotNull(
                source.getAsJsonObject("ruleToc")?.get("chapterName")?.asString,
                source.getAsJsonObject("ruleBookInfo")?.get("lastChapter")?.asString,
                source.getAsJsonObject("ruleSearch")?.get("lastChapter")?.asString,
                source.getAsJsonObject("ruleExplore")?.get("lastChapter")?.asString
            )
        }
        val destructiveMarkers = listOf(
            "正文卷.",
            "正文.",
            "VIP卷.",
            "默认卷.",
            "VIP章节.",
            "免费章节.",
            "章节目录.",
            "最新章节."
        )

        destructiveMarkers.forEach { marker ->
            assertFalse("chapter title rule still contains destructive marker: $marker", titleRules.any { marker in it })
        }
        val safeMarkerPrefix =
            "(?:VIP章节目录|章节目录|VIP章节|免费章节|最新章节|正文卷|正文|VIP卷|默认卷)[\\s:：._·、-]*"
        val hardenedRules = titleRules.filter { safeMarkerPrefix in it }
        assertEquals(48, hardenedRules.size)
        assertTrue(hardenedRules.all { "(?=第|[0-9０-９" in it })
    }

    @Test
    fun wensangContentRuleRemovesItsKnownInlineCaretMarkdownArtifact() {
        val source = bundledSources().first {
            it.get("bookSourceUrl").asString == "http://www.wensang.net"
        }
        val replaceRule = source
            .getAsJsonObject("ruleContent")
            .get("replaceRegex")
            .asString
            .substringAfter("##")

        assertEquals(
            "晶莹剔透的宽敞经脉中。乳白色的火焰如潮水般疯狂涌动着。",
            Regex(replaceRule).replace(
                "晶莹剔透的宽敞经脉中。^^\u00a0\u00a0**乳白色的火焰如潮水般疯狂涌动着。",
                ""
            )
        )
    }

    private fun bundledSources() = JsonParser.parseString(
        File("src/main/assets/source-engine/book-sources.json").readText()
    ).asJsonArray.map { it.asJsonObject }
}
