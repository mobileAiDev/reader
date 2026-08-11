package com.ldp.reader.sourceengine.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class CatalogNavigationPollutionFilterTest {
    @Test
    fun `filters navigation labels from generic cached entries without copying the rule`() {
        val before = (1..160).map { ordinal -> Entry("第${ordinal}章 正文$ordinal") }
        val pollution = listOf(
            Entry("378. 第381章"),
            Entry("VIP. 第381章(最新)")
        )
        val after = (161..390).map { ordinal -> Entry("第${ordinal}章 正文$ordinal") }
        val tail = listOf(
            Entry("一个普通人的日常（一）"),
            Entry("不是诈尸，不是遭了阿蒙")
        )
        val input = before + pollution + after + tail

        val result = CatalogNavigationPollutionFilter().filter(input) { entry -> entry.title }

        assertEquals(listOf(160, 161), result.removedOriginalIndices)
        assertEquals(392, result.entries.size)
        assertSame(input[159], result.entries[159])
        assertSame(input[162], result.entries[160])
        assertEquals(tail.map { it.title }, result.entries.takeLast(tail.size).map { it.title })
    }

    @Test
    fun `filters explicit latest navigation block when volume restart means no later ordinal duplicate`() {
        val before = (1..160).map { ordinal -> Entry("第${ordinal}章 卷内正文$ordinal") }
        val pollution = listOf(
            Entry("378. 第381章"),
            Entry("VIP. 第381章(最新)")
        )
        val after = (161..300).map { ordinal -> Entry("第${ordinal}章 卷内正文$ordinal") }

        val result = CatalogNavigationPollutionFilter().filter(before + pollution + after) { entry ->
            entry.title
        }

        assertEquals(listOf(160, 161), result.removedOriginalIndices)
        assertEquals("第160章 卷内正文160", result.entries[159].title)
        assertEquals("第161章 卷内正文161", result.entries[160].title)
    }

    @Test
    fun `keeps navigation shaped row without latest marker or canonical duplicate`() {
        val before = (1..160).map { ordinal -> Entry("第${ordinal}章 正文$ordinal") }
        val candidate = Entry("378. 第381章")
        val after = (161..300).map { ordinal -> Entry("第${ordinal}章 正文$ordinal") }
        val input = before + candidate + after

        val result = CatalogNavigationPollutionFilter().filter(input) { entry -> entry.title }

        assertEquals(emptyList<Int>(), result.removedOriginalIndices)
        assertSame(candidate, result.entries[160])
    }

    private data class Entry(val title: String)
}
