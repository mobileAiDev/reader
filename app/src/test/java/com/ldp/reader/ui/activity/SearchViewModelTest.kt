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
            cover = ""
        )
        val withCover = searchResult(
            routeId = "source://target-b",
            title = "灵源仙路",
            author = "春雾煮茶",
            cover = "file:///cover.jpg"
        )

        val merged = SearchViewModel.mergeVisibleSearchResults(
            previous = listOf(withoutCover),
            next = listOf(withCover)
        )

        assertEquals(listOf("source://target-b"), merged.map { it.routeId })
        assertEquals(listOf("file:///cover.jpg"), merged.map { it.cover })
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
    fun visibleSearchResultsUseLatestRankOrderAndKeepPreviousExtras() {
        val contained = searchResult(
            routeId = "source://xiandu-contained",
            title = "仙都传说",
            author = "仙都黄龙"
        )
        val exact = searchResult(
            routeId = "source://xiandu-exact",
            title = "仙都",
            author = "陈猿"
        )

        val merged = SearchViewModel.mergeVisibleSearchResults(
            previous = listOf(contained),
            next = listOf(exact, contained)
        )

        assertEquals(
            listOf("仙都" to "陈猿", "仙都传说" to "仙都黄龙"),
            merged.map { it.title to it.author }
        )
    }

    private fun searchResult(
        routeId: String,
        title: String,
        author: String,
        cover: String = ""
    ): BookSearchResult {
        return BookSearchResult().apply {
            this.routeId = routeId
            this.title = title
            this.author = author
            this.cover = cover
        }
    }
}
