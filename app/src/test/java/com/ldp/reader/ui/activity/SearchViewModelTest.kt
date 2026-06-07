package com.ldp.reader.ui.activity

import com.ldp.reader.model.bean.BookSearchResult
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchViewModelTest {
    @Test
    fun progressiveSearchResultsAppendLaterDifferentBookInsteadOfReplacingVisibleBook() {
        val target = searchResult(
            routeId = "source://target",
            title = "灵源仙路",
            author = "春雾煮茶"
        )
        val containedTitleBook = searchResult(
            routeId = "source://contained",
            title = "源仙路",
            author = "萧不鸣"
        )

        val merged = SearchViewModel.mergeVisibleSearchResults(
            previous = listOf(target),
            next = listOf(containedTitleBook)
        )

        assertEquals(
            listOf("灵源仙路" to "春雾煮茶", "源仙路" to "萧不鸣"),
            merged.map { it.title to it.author }
        )
    }

    @Test
    fun progressiveSearchResultsUpgradeSameVisibleBookInPlace() {
        val withoutCover = searchResult(
            routeId = "source://target-a",
            title = "灵源仙路",
            author = "春雾煮茶",
            cover = "",
            sourceCount = 3
        )
        val withCover = searchResult(
            routeId = "source://target-b",
            title = "灵源仙路",
            author = "春雾煮茶",
            cover = "file:///cover.jpg",
            sourceCount = 1
        )

        val merged = SearchViewModel.mergeVisibleSearchResults(
            previous = listOf(withoutCover),
            next = listOf(withCover)
        )

        assertEquals(listOf("source://target-b"), merged.map { it.routeId })
        assertEquals(listOf("file:///cover.jpg"), merged.map { it.cover })
        assertEquals(listOf(3), merged.map { it.sourceCount })
    }

    @Test
    fun progressiveSearchResultsDoNotDowngradeSameVisibleBookMetadata() {
        val richResult = searchResult(
            routeId = "source://target-rich",
            title = "凡人修仙传",
            author = "忘语",
            cover = "https://img.example/fanren.jpg",
            desc = "一个普通山村小子，偶然下进入到当地江湖小门派。"
        )
        val sparseResult = searchResult(
            routeId = "source://target-sparse",
            title = "凡人修仙传",
            author = "忘语",
            cover = "",
            desc = ""
        )

        val merged = SearchViewModel.mergeVisibleSearchResults(
            previous = listOf(richResult),
            next = listOf(sparseResult)
        )

        assertEquals(listOf("source://target-rich"), merged.map { it.routeId })
        assertEquals(listOf("https://img.example/fanren.jpg"), merged.map { it.cover })
        assertEquals(
            listOf("一个普通山村小子，偶然下进入到当地江湖小门派。"),
            merged.map { it.desc }
        )
        assertEquals(
            listOf(listOf("https://img.example/fanren.jpg")),
            merged.map { it.coverCandidates }
        )
    }

    @Test
    fun progressiveSearchResultsKeepExistingCoverCandidateWhenRouteUpdates() {
        val earlyResult = searchResult(
            routeId = "source://target-early",
            title = "凡人修仙传",
            author = "忘语",
            cover = "https://www.3yt.la/DownFiles/Book/BookCover/55015.gif",
            desc = "早期简介"
        )
        val laterResult = searchResult(
            routeId = "source://target-later",
            title = "凡人修仙传",
            author = "忘语",
            cover = "https://www.cxzz958.com/files/article/image/0/306/306s.jpg",
            desc = "后续简介"
        )

        val merged = SearchViewModel.mergeVisibleSearchResults(
            previous = listOf(earlyResult),
            next = listOf(laterResult)
        )

        assertEquals("source://target-later", merged.first().routeId)
        assertEquals("https://www.3yt.la/DownFiles/Book/BookCover/55015.gif", merged.first().cover)
        assertEquals("后续简介", merged.first().desc)
        assertEquals(
            listOf(
                "https://www.3yt.la/DownFiles/Book/BookCover/55015.gif",
                "https://www.cxzz958.com/files/article/image/0/306/306s.jpg"
            ),
            merged.first().coverCandidates
        )
    }

    @Test
    fun progressiveSearchResultsKeepXianluAndXiantuAsDifferentBooks() {
        val xianlu = searchResult(
            routeId = "source://xianlu",
            title = "灵源仙路",
            author = "春雾煮茶"
        )
        val xiantu = searchResult(
            routeId = "source://xiantu",
            title = "灵源仙途",
            author = "春雾煮茶"
        )

        val merged = SearchViewModel.mergeVisibleSearchResults(
            previous = listOf(xianlu),
            next = listOf(xiantu)
        )

        assertEquals(
            listOf("灵源仙路" to "春雾煮茶", "灵源仙途" to "春雾煮茶"),
            merged.map { it.title to it.author }
        )
    }

    @Test
    fun visibleSearchResultsKeepEarlierDifferentBookWhenFinalResultIsNarrower() {
        val target = searchResult(
            routeId = "source://target-final",
            title = "灵源仙路",
            author = "春雾煮茶"
        )
        val containedTitleBook = searchResult(
            routeId = "source://contained",
            title = "源仙路",
            author = "萧不鸣"
        )
        val finalTarget = searchResult(
            routeId = "source://target-final-updated",
            title = "灵源仙路",
            author = "春雾煮茶",
            cover = "file:///final-cover.jpg"
        )

        val merged = SearchViewModel.mergeVisibleSearchResults(
            previous = listOf(target, containedTitleBook),
            next = listOf(finalTarget)
        )

        assertEquals(
            listOf("灵源仙路" to "春雾煮茶", "源仙路" to "萧不鸣"),
            merged.map { it.title to it.author }
        )
        assertEquals("source://target-final-updated", merged.first().routeId)
    }

    @Test
    fun visibleSearchResultsRankMoreSourcesFirst() {
        val contained = searchResult(
            routeId = "source://xiandu-contained",
            title = "仙都传说",
            author = "仙都黄龙",
            sourceCount = 1
        )
        val exact = searchResult(
            routeId = "source://xiandu-exact",
            title = "仙都",
            author = "陈猿",
            sourceCount = 8
        )

        val merged = SearchViewModel.mergeVisibleSearchResults(
            previous = listOf(contained),
            next = listOf(contained, exact)
        )

        assertEquals(
            listOf("仙都" to "陈猿", "仙都传说" to "仙都黄龙"),
            merged.map { it.title to it.author }
        )
        assertEquals(listOf(8, 1), merged.map { it.sourceCount })
    }

    private fun searchResult(
        routeId: String,
        title: String,
        author: String,
        cover: String = "",
        desc: String = "",
        sourceCount: Int = 0
    ): BookSearchResult {
        return BookSearchResult().apply {
            this.routeId = routeId
            this.title = title
            this.author = author
            this.cover = cover
            this.desc = desc
            this.sourceCount = sourceCount
        }
    }
}
