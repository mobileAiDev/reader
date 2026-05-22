package com.ldp.reader.source

import com.ldp.reader.sourceengine.legado.LegadoRuleSet
import com.ldp.reader.sourceengine.model.BookSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceEngineCompatibilityTest {
    @Test
    fun allowsOptionalCoverJsWhenCriticalRulesArePlain() {
        val source = source(
            ruleSearch = mapOf(
                "bookList" to ".grid tr",
                "name" to "td.0@a@text",
                "bookUrl" to "td.0@a@href",
                "coverUrl" to "td.0@a@href<js>result</js>"
            )
        )

        assertTrue(SourceEngineCompatibility.isCompatible(source))
    }

    @Test
    fun allowsExecutableSuffixAfterPlainChapterNameRule() {
        val source = source(
            ruleToc = mapOf(
                "chapterList" to ".chapter a",
                "chapterName" to "text\n@js:result.replace('正文','')",
                "chapterUrl" to "href"
            )
        )

        assertTrue(SourceEngineCompatibility.isCompatible(source))
    }

    @Test
    fun rejectsCriticalRuleThatIsOnlyExecutableJs() {
        val source = source(
            ruleToc = mapOf(
                "chapterList" to ".chapter a",
                "chapterName" to "text",
                "chapterUrl" to "@js:source.getKey() + result"
            )
        )

        assertFalse(SourceEngineCompatibility.isCompatible(source))
    }

    @Test
    fun allowsSupportedBaseUrlVariableInCriticalRules() {
        val source = source(
            ruleToc = mapOf(
                "chapterList" to "$.data",
                "chapterName" to "$.title",
                "chapterUrl" to "@get:{url}p{{$.ordernum}}.html"
            ),
            ruleBookInfo = mapOf(
                "tocUrl" to """
                    {{java.put("url",baseUrl);
                        "https://example.org/novel/clist/"}},{
                      "body": "bid={{baseUrl.match(/(\d+).${'$'}/)[1]}}",
                      "method": "POST"
                    }
                """.trimIndent()
            )
        )

        assertTrue(SourceEngineCompatibility.isCompatible(source))
    }

    @Test
    fun doesNotBlockAdultSourceLabelsByPolicy() {
        val source = source(sourceName = "海棠成人小说")

        assertTrue(SourceEngineCompatibility.isCompatible(source))
    }

    @Test
    fun stillBlocksFanficSourceLabels() {
        val source = source(sourceName = "斗破同人源")

        assertFalse(SourceEngineCompatibility.isCompatible(source))
    }

    private fun source(
        sourceName: String = "fixture",
        ruleSearch: Map<String, String> = mapOf(
            "bookList" to ".grid tr",
            "name" to "td.0@a@text",
            "bookUrl" to "td.0@a@href"
        ),
        ruleToc: Map<String, String> = mapOf(
            "chapterList" to ".chapter a",
            "chapterName" to "text",
            "chapterUrl" to "href"
        ),
        ruleBookInfo: Map<String, String> = emptyMap()
    ): BookSource {
        return BookSource(
            sourceName = sourceName,
            sourceUrl = "https://example.org",
            sourceGroup = null,
            sourceComment = null,
            enabled = true,
            headers = emptyMap(),
            searchUrl = "/search?q={{key}}",
            ruleSearch = LegadoRuleSet("ruleSearch", ruleSearch),
            ruleBookInfo = LegadoRuleSet("ruleBookInfo", ruleBookInfo),
            ruleToc = LegadoRuleSet("ruleToc", ruleToc),
            ruleContent = LegadoRuleSet("ruleContent", mapOf("content" to "#content@html")),
            diagnostics = emptyList()
        )
    }
}
