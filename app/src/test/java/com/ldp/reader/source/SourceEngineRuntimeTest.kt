package com.ldp.reader.source

import com.ldp.reader.sourceengine.legado.LegadoRuleSet
import com.ldp.reader.sourceengine.model.BookSource
import org.junit.Assert.assertSame
import org.junit.Test

class SourceEngineRuntimeTest {
    @Test
    fun findSourcePrefersExactHistoricalUrlAlias() {
        val canonical = source("http://www.biquge001.com/")
        val historical = source("http://www.biquge001.com/      ")

        assertSame(historical, SourceEngineRuntime.findSource(historical.sourceUrl, listOf(canonical, historical)))
    }

    @Test
    fun findSourceFallsBackToSchemeAndSlashCompatibleUrl() {
        val source = source("https://www.xbiquge.la")

        assertSame(source, SourceEngineRuntime.findSource("www.xbiquge.la", listOf(source)))
    }

    private fun source(url: String): BookSource {
        val emptyRule = LegadoRuleSet("test", emptyMap())
        return BookSource(
            sourceName = url,
            sourceUrl = url,
            sourceGroup = null,
            sourceComment = null,
            enabled = true,
            headers = emptyMap(),
            searchUrl = null,
            ruleSearch = emptyRule,
            ruleBookInfo = emptyRule,
            ruleToc = emptyRule,
            ruleContent = emptyRule,
            diagnostics = emptyList()
        )
    }
}
