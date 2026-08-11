package com.ldp.reader.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComicChapterContentGateTest {
    @Test
    fun rejectsBaoziChapterWhoseEveryImageBelongsToAnotherComic() {
        val chapter = chapter(
            bookUrl = "https://cn.webmota.com/comic/douluodalu-fengxuandongman",
            chapterUrl = "https://cn.webmota.com/comic/chapter/douluodalu-fengxuandongman/0_995.html"
        )
        val promotionalPages = (1..6).map { page ->
            MediaRequest(
                "https://s2.bzcdn.net/scomic/meishaonianbangjiashijian-aojianyixi2/0/2-df6a/$page.jpg"
            )
        }

        val decision = ComicChapterContentGate.evaluate(chapter, promotionalPages)

        assertFalse(decision.acceptable)
        assertEquals("baozi_asset_book_mismatch", decision.reason)
    }

    @Test
    fun acceptsShortBaoziChapterWhenImagesBelongToTheCurrentComic() {
        val chapter = chapter(
            bookUrl = "https://cn.webmota.com/comic/douluodalu-fengxuandongman",
            chapterUrl = "https://cn.webmota.com/comic/chapter/douluodalu-fengxuandongman/0_994.html"
        )
        val shortLegalChapter = (1..6).map { page ->
            MediaRequest(
                "https://s1.bzcdn.net/scomic/douluodalu-fengxuandongman/0/994-2ke5/$page.jpg"
            )
        }

        val decision = ComicChapterContentGate.evaluate(chapter, shortLegalChapter)

        assertTrue(decision.acceptable)
    }

    @Test
    fun rejectsMixedBaoziChapterEvenWhenOneImageBelongsToCurrentComic() {
        val chapter = chapter(
            bookUrl = "https://cn.webmota.com/comic/douluodalu-fengxuandongman",
            chapterUrl = "https://cn.webmota.com/comic/chapter/douluodalu-fengxuandongman/0_994.html"
        )
        val pages = listOf(
            MediaRequest("https://s1.bzcdn.net/scomic/douluodalu-fengxuandongman/0/994/1.jpg"),
            MediaRequest("https://s2.bzcdn.net/scomic/promotional-book/0/2/2.jpg"),
            MediaRequest("https://s2.bzcdn.net/scomic/promotional-book/0/2/3.jpg")
        )

        val decision = ComicChapterContentGate.evaluate(chapter, pages)

        assertFalse(decision.acceptable)
        assertEquals("baozi_asset_mixed_book_mismatch", decision.reason)
    }

    @Test
    fun rejectsSparseOwnedAssetHiddenAmongUnrelatedCdnPages() {
        val chapter = chapter(
            bookUrl = "https://cn.webmota.com/comic/douluodalu-fengxuandongman",
            chapterUrl = "https://cn.webmota.com/comic/chapter/douluodalu-fengxuandongman/0_994.html"
        )
        val pages = listOf(
            MediaRequest("https://s1.bzcdn.net/scomic/douluodalu-fengxuandongman/0/994/1.jpg"),
            MediaRequest("https://ads.example/promo/1.jpg"),
            MediaRequest("https://ads.example/promo/2.jpg")
        )

        val decision = ComicChapterContentGate.evaluate(chapter, pages)

        assertFalse(decision.acceptable)
        assertEquals("baozi_asset_ownership_too_sparse", decision.reason)
    }

    @Test
    fun acceptsTheSameComicWhenOnlyTheSourceSuffixDiffers() {
        val chapter = chapter(
            bookUrl = "https://cn.webmota.com/comic/douluodalu-fengxuandongman",
            chapterUrl = "https://cn.webmota.com/comic/chapter/douluodalu-fengxuandongman/0_994.html"
        )
        val pages = listOf(
            MediaRequest("https://s1.bzcdn.net/scomic/douluodalu-shenmanjun/0/994/1.jpg")
        )

        val decision = ComicChapterContentGate.evaluate(chapter, pages)

        assertTrue(decision.acceptable)
    }

    @Test
    fun doesNotApplyBaoziPathPolicyToAnUnrelatedCdn() {
        val chapter = chapter(
            bookUrl = "https://reader.example/comic/current-book",
            chapterUrl = "https://reader.example/chapter/7"
        )
        val pages = listOf(
            MediaRequest("https://cdn.example/assets/unrelated-storage-id/1.jpg"),
            MediaRequest("https://cdn.example/assets/unrelated-storage-id/2.jpg")
        )

        val decision = ComicChapterContentGate.evaluate(chapter, pages)

        assertTrue(decision.acceptable)
    }

    private fun chapter(bookUrl: String, chapterUrl: String): MediaSourceChapter {
        val source = MediaSourceDefinition(
            sourceName = "漫画源",
            sourceUrl = "https://reader.example",
            sourceType = MediaSourceType.COMIC,
            sourceGroup = null,
            sourceComment = null,
            enabled = true,
            headers = emptyMap(),
            searchUrl = null,
            ruleSearch = emptyRuleSet,
            ruleBookInfo = emptyRuleSet,
            ruleToc = emptyRuleSet,
            ruleContent = emptyRuleSet,
            diagnostics = emptyList()
        )
        val book = MediaSourceBook(
            source = source,
            name = "测试漫画",
            author = "",
            bookUrl = bookUrl,
            coverUrl = "",
            intro = "",
            kind = "",
            lastChapter = ""
        )
        return MediaSourceChapter(
            source = source,
            book = book,
            index = 0,
            name = "短章",
            chapterUrl = chapterUrl
        )
    }

    private companion object {
        val emptyRuleSet = MediaLegadoRuleSet("", emptyMap())
    }
}
