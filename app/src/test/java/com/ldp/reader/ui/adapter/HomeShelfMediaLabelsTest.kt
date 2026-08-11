package com.ldp.reader.ui.adapter

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeShelfMediaLabelsTest {
    @Test
    fun comicCardShowsRealChapterLabelAndOneBasedPageTogether() {
        assertEquals(
            "第十二话 重逢 · 第4页",
            HomeShelfMediaLabels.comicProgressText("第十二话 重逢", 11, 3)
        )
    }

    @Test
    fun descendingSourceIndexNeverOverridesTheRealChapterLabel() {
        assertEquals(
            "第一话 开端 · 第1页",
            HomeShelfMediaLabels.comicProgressText("第一话 开端", 995, 0)
        )
    }

    @Test
    fun comicCardFallsBackToChapterTitleAndFirstPage() {
        assertEquals(
            "番外 · 第1页",
            HomeShelfMediaLabels.comicProgressText(" 番外 ", -1, -1)
        )
    }
}
