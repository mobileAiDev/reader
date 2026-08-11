package com.ldp.reader.widget.page

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BookRecordChapterResolverTest {
    @Test
    fun `network catalog insertion restores the same chapter by link`() {
        val chapters = listOf(
            chapter("new", "https://book/chapter/new"),
            chapter("chapter 1", "https://book/chapter/1"),
            chapter("chapter 2", "https://book/chapter/2")
        )

        assertEquals(
            2,
            BookRecordChapterResolver.resolve(
                previousOrdinal = 1,
                savedLink = "https://book/chapter/2",
                savedTitle = "chapter 2",
                chapters = chapters
            )
        )
    }

    @Test
    fun `network catalog trimming restores the same chapter by link`() {
        val chapters = listOf(
            chapter("chapter 2", "https://book/chapter/2"),
            chapter("chapter 3", "https://book/chapter/3")
        )

        assertEquals(
            0,
            BookRecordChapterResolver.resolve(
                previousOrdinal = 1,
                savedLink = "https://book/chapter/2",
                savedTitle = "chapter 2",
                chapters = chapters
            )
        )
    }

    @Test
    fun `local parser ordinal change restores by normalized title`() {
        val chapters = listOf(
            chapter("序章"),
            chapter("第 五十一 章：返回火山"),
            chapter("第五十二章 返航")
        )

        assertEquals(
            1,
            BookRecordChapterResolver.resolve(
                previousOrdinal = 0,
                savedLink = null,
                savedTitle = "第　五十一　章: 返回火山",
                chapters = chapters
            )
        )
    }

    @Test
    fun `same normalized title prefers the candidate nearest old ordinal`() {
        val chapters = listOf(
            chapter("序章"),
            chapter("第一章 开始"),
            chapter("序章"),
            chapter("尾声")
        )

        assertEquals(
            2,
            BookRecordChapterResolver.resolve(
                previousOrdinal = 3,
                savedLink = null,
                savedTitle = "序章",
                chapters = chapters
            )
        )
    }

    @Test
    fun `legacy record without anchor leaves numeric fallback to page loader`() {
        assertNull(
            BookRecordChapterResolver.resolve(
                previousOrdinal = 99,
                savedLink = null,
                savedTitle = null,
                chapters = listOf(chapter("第一章"), chapter("第二章"))
            )
        )
    }

    private fun chapter(title: String, link: String? = null): TxtChapter {
        return TxtChapter().apply {
            this.title = title
            this.link = link
        }
    }
}
