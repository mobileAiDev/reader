package com.ldp.reader.widget.page

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderConfigurationReadingAnchorTest {

    @Test
    fun chapterTextOffsetRestoresTheSameSentenceAcrossPortraitAndLandscapePagination() {
        val portraitPages = pages(
            "0000000000",
            "1111111111",
            "目标句子在这里，旋转后仍应看见",
            "3333333333"
        )
        val landscapePages = pages(
            "0000000000111111",
            "1111目标句子在这里，旋转后仍应看见3333",
            "333333"
        )

        val anchor = ReaderPaginationReflowPolicy.captureConfigurationAnchor(
            chapterOrdinal = 7,
            chapterLink = "chapter-7",
            chapterTitle = "第八章",
            pages = portraitPages,
            currentPagePosition = 2
        )

        val restoredPage = ReaderPaginationReflowPolicy.restorePagePosition(
            anchor = requireNotNull(anchor),
            pages = landscapePages
        )

        assertEquals(1, restoredPage)
        assertTrue(landscapePages[restoredPage].lines.orEmpty().joinToString("").contains("目标句子"))
    }

    @Test
    fun relativeProgressRestoresPositionWhenPagesDoNotExposeText() {
        val portraitPages = pages("", "", "", "", "", "", "", "")
        val landscapePages = pages("", "", "", "")

        val anchor = requireNotNull(
            ReaderPaginationReflowPolicy.captureConfigurationAnchor(
                chapterOrdinal = 2,
                chapterLink = null,
                chapterTitle = "第三章",
                pages = portraitPages,
                currentPagePosition = 5
            )
        )

        assertEquals(
            2,
            ReaderPaginationReflowPolicy.restorePagePosition(anchor, landscapePages)
        )
    }

    @Test
    fun repeatedPortraitLandscapeRecreationDoesNotDriftBeforeTheTargetSentence() {
        val portraitPages = pages(
            "0000000000",
            "1111111111",
            "目标句子在这里，旋转后仍应看见",
            "3333333333"
        )
        val landscapePages = pages(
            "0000000000111111",
            "1111目标句子在这里，旋转后仍应看见3333",
            "333333"
        )
        val portraitAnchor = requireNotNull(
            ReaderPaginationReflowPolicy.captureConfigurationAnchor(
                chapterOrdinal = 7,
                chapterLink = "chapter-7",
                chapterTitle = "第八章",
                pages = portraitPages,
                currentPagePosition = 2
            )
        )
        val landscapePosition = ReaderPaginationReflowPolicy.restorePagePosition(
            portraitAnchor,
            landscapePages
        )

        val landscapeAnchor = requireNotNull(
            ReaderPaginationReflowPolicy.captureConfigurationAnchor(
                chapterOrdinal = 7,
                chapterLink = "chapter-7",
                chapterTitle = "第八章",
                pages = landscapePages,
                currentPagePosition = landscapePosition,
                retainedChapterTextOffset = portraitAnchor.chapterTextOffset
            )
        )

        assertEquals(2, ReaderPaginationReflowPolicy.restorePagePosition(landscapeAnchor, portraitPages))
    }

    private fun pages(vararg contents: String): List<TxtPage> = contents.mapIndexed { index, text ->
        TxtPage().apply {
            position = index
            lines = listOf(text)
        }
    }
}
