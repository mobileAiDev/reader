package com.ldp.reader.sourceengine.search

import com.ldp.reader.sourceengine.legado.LegadoRuleSet
import com.ldp.reader.sourceengine.model.BookSource
import com.ldp.reader.sourceengine.model.SourceBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookSearchRankerTest {
    @Test
    fun ranksExactAndPartialTitleMatchesBeforeUnrelatedResults() {
        val source = fixtureSource("A")
        val ranked = BookSearchRanker().rank(
            keyword = "斗破",
            candidates = listOf(
                SearchCandidate(fixtureBook(source, "斗罗大陆", "唐家三少"), sourceIndex = 0),
                SearchCandidate(fixtureBook(source, "斗破苍穹", "作者：天蚕土豆"), sourceIndex = 8),
                SearchCandidate(fixtureBook(source, "苍穹榜", "天蚕土豆"), sourceIndex = 1)
            ),
            limit = 10
        )

        assertEquals("斗破苍穹", ranked.first().book.name)
        assertTrue(ranked.none { it.book.name == "斗罗大陆" })
    }

    @Test
    fun ranksAuthorMatchesWhenQueryIsAuthorName() {
        val source = fixtureSource("A")
        val ranked = BookSearchRanker().rank(
            keyword = "天蚕",
            candidates = listOf(
                SearchCandidate(fixtureBook(source, "遮天", "辰东"), sourceIndex = 0),
                SearchCandidate(fixtureBook(source, "斗破苍穹", "作者：天蚕土豆"), sourceIndex = 2),
                SearchCandidate(fixtureBook(source, "武动乾坤", "天蚕土豆"), sourceIndex = 3),
                SearchCandidate(fixtureBook(source, "天蚕土豆新书", "万相之王"), sourceIndex = 1)
            ),
            limit = 10
        )

        assertEquals(listOf("斗破苍穹", "武动乾坤"), ranked.map { it.book.name })
    }

    @Test
    fun keepsBestDuplicateCandidateBeforeSorting() {
        val source = fixtureSource("A")
        val ranked = BookSearchRanker().rank(
            keyword = "完美世界",
            candidates = listOf(
                SearchCandidate(fixtureBook(source, "完美世界 最新章节", "辰东"), sourceIndex = 20),
                SearchCandidate(fixtureBook(source, "完美世界", "辰东"), sourceIndex = 1),
                SearchCandidate(fixtureBook(source, "圣墟", "辰东"), sourceIndex = 0)
            ),
            limit = 10
        )

        assertEquals("完美世界", ranked.first().book.name)
        assertEquals(1, ranked.count { it.book.name.contains("完美世界") })
    }

    @Test
    fun filtersFanficPollutedTitles() {
        val source = fixtureSource("A")
        val ranked = BookSearchRanker().rank(
            keyword = "斗破苍穹",
            candidates = listOf(
                SearchCandidate(fixtureBook(source, "斗破苍穹", "天蚕土豆"), sourceIndex = 0),
                SearchCandidate(fixtureBook(source, "斗破苍穹同人改写版", "污染源"), sourceIndex = 1)
            ),
            limit = 10
        )

        assertEquals(listOf("斗破苍穹"), ranked.map { it.book.name })
    }

    @Test
    fun keepsAdultMarkedTitlesWhenTheyMatchTheQuery() {
        val source = fixtureSource("A")
        val ranked = BookSearchRanker().rank(
            keyword = "斗破苍穹后宫恶堕篇",
            candidates = listOf(
                SearchCandidate(fixtureBook(source, "斗破苍穹后宫恶堕篇", "污染源"), sourceIndex = 0)
            ),
            limit = 10
        )

        assertEquals(listOf("斗破苍穹后宫恶堕篇"), ranked.map { it.book.name })
    }

    @Test
    fun exactOriginalOutranksEarlierDerivativeTitles() {
        val source = fixtureSource("A")
        val ranked = BookSearchRanker().rank(
            keyword = "斗破苍穹",
            candidates = listOf(
                SearchCandidate(fixtureBook(source, "斗破苍穹", ""), sourceIndex = 0),
                SearchCandidate(fixtureBook(source, "斗破苍穹之始于云岚", "小龙哥"), sourceIndex = 0),
                SearchCandidate(fixtureBook(source, "斗破苍穹之大世界", "醉百川"), sourceIndex = 1),
                SearchCandidate(fixtureBook(source, "斗破苍穹", "天蚕土豆"), sourceIndex = 160)
            ),
            limit = 10
        )

        assertEquals("斗破苍穹", ranked.first().book.name)
        assertEquals("天蚕土豆", ranked.first().book.author)
    }

    @Test
    fun partialTitlePrefersCompactCanonicalTitleBeforeLongDerivatives() {
        val source = fixtureSource("A")
        val secondSource = fixtureSource("B")
        val thirdSource = fixtureSource("C")
        val ranked = BookSearchRanker().rank(
            keyword = "斗破",
            candidates = listOf(
                SearchCandidate(fixtureBook(source, "斗破宫墙", "可爱深红"), sourceIndex = 0),
                SearchCandidate(fixtureBook(source, "斗破苍穹之始于云岚", "小龙哥"), sourceIndex = 1),
                SearchCandidate(fixtureBook(source, "斗破苍穹同人-TOV改写版", "Yuri"), sourceIndex = 1),
                SearchCandidate(fixtureBook(source, "斗破苍穹", "天蚕土豆"), sourceIndex = 80),
                SearchCandidate(fixtureBook(secondSource, "斗破苍穹", "天蚕土豆"), sourceIndex = 81),
                SearchCandidate(fixtureBook(thirdSource, "斗破苍穹", "天蚕土豆"), sourceIndex = 82)
            ),
            limit = 10
        )

        assertEquals("斗破苍穹", ranked.first().book.name)
    }

    @Test
    fun shortPartialTitleDemotesColonSeparatedDerivativeTitles() {
        val source = fixtureSource("A")
        val ranked = BookSearchRanker().rank(
            keyword = "斗破",
            candidates = listOf(
                SearchCandidate(fixtureBook(source, "斗破：阳帝", "叫我老唐"), sourceIndex = 0),
                SearchCandidate(fixtureBook(source, "斗破苍穹", "天蚕土豆"), sourceIndex = 40)
            ),
            limit = 10
        )

        assertEquals("斗破苍穹", ranked.first().book.name)
    }

    @Test
    fun shortPartialTitleDoesNotPromoteReorderedCharacterNoise() {
        val source = fixtureSource("A")
        val ranked = BookSearchRanker().rank(
            keyword = "斗破",
            candidates = listOf(
                SearchCandidate(fixtureBook(source, "破天斗尊", "为何吃橘子"), sourceIndex = 0),
                SearchCandidate(fixtureBook(source, "斗穹破天", "洪荒一虫"), sourceIndex = 1),
                SearchCandidate(fixtureBook(source, "NBA：克利夫兰名宿孙大圣！", "斗破星穹"), sourceIndex = 2),
                SearchCandidate(fixtureBook(source, "斗破苍穹", "天蚕土豆"), sourceIndex = 80)
            ),
            limit = 10
        )

        assertEquals("斗破苍穹", ranked.first().book.name)
        assertTrue(ranked.none { it.book.name == "破天斗尊" })
    }

    @Test
    fun aliasTitleOutranksReorderedCharacterNoise() {
        val source = fixtureSource("A")
        val ranked = BookSearchRanker().rank(
            keyword = "灵源仙路",
            candidates = listOf(
                SearchCandidate(fixtureBook(source, "仙路灵源", "古群"), sourceIndex = 0),
                SearchCandidate(fixtureBook(source, "灵源仙途：我养的灵兽太懂感恩了", "春雾煮茶"), sourceIndex = 20)
            ),
            limit = 10
        )

        assertEquals("灵源仙途：我养的灵兽太懂感恩了", ranked.first().book.name)
        assertTrue(ranked.none { it.book.name == "仙路灵源" })
    }

    @Test
    fun completedTitleSearchOutranksAmbiguousShortPrefixConsensus() {
        val noisySources = (0 until 17).map { fixtureSource("noise$it") }
        val canonicalSource = fixtureSource("canonical")
        val candidates = noisySources.mapIndexed { index, source ->
            SearchCandidate(
                fixtureBook(source, "凡人狂徒", "十九护法"),
                sourceIndex = index,
                searchQuery = "凡人"
            )
        } + SearchCandidate(
            fixtureBook(canonicalSource, "凡人修仙传", "忘语"),
            sourceIndex = 80,
            searchQuery = "凡人修仙传"
        )

        val ranked = BookSearchRanker().rank(
            keyword = "凡人",
            candidates = candidates,
            limit = 10
        )

        assertEquals("凡人修仙传", ranked.first().book.name)
    }

    @Test
    fun exactUserTitleOutranksCompletedTitleCandidate() {
        val source = fixtureSource("A")
        val ranked = BookSearchRanker().rank(
            keyword = "斩神",
            candidates = listOf(
                SearchCandidate(fixtureBook(source, "斩神", "天刈留香"), sourceIndex = 0, searchQuery = "斩神"),
                SearchCandidate(
                    fixtureBook(source, "我在精神病院学斩神", "三九音域"),
                    sourceIndex = 20,
                    searchQuery = "我在精神病院学斩神"
                )
            ),
            limit = 10
        )

        assertEquals("斩神", ranked.first().book.name)
    }

    @Test
    fun completedAuthorTitleSearchOutranksTitleContainingAuthorName() {
        val source = fixtureSource("A")
        val ranked = BookSearchRanker().rank(
            keyword = "猫腻",
            candidates = listOf(
                SearchCandidate(fixtureBook(source, "新婚，老公有猫腻", "薄情哒兔子"), sourceIndex = 0, searchQuery = "猫腻"),
                SearchCandidate(fixtureBook(source, "庆余年", "猫腻"), sourceIndex = 20, searchQuery = "庆余年"),
                SearchCandidate(fixtureBook(source, "将夜", "猫腻"), sourceIndex = 21, searchQuery = "将夜")
            ),
            limit = 10
        )

        assertEquals("庆余年", ranked.first().book.name)
    }

    @Test
    fun filtersAuthorOnlyFalsePositiveWhenTitleMatchesExist() {
        val source = fixtureSource("A")
        val ranked = BookSearchRanker().rank(
            keyword = "微微一笑很倾城",
            candidates = listOf(
                SearchCandidate(fixtureBook(source, "微微一笑很倾城", "顾漫"), sourceIndex = 0),
                SearchCandidate(fixtureBook(source, "人在亮剑，我的麾下猛将如云", "微微一笑很倾城"), sourceIndex = 1)
            ),
            limit = 10
        )

        assertEquals(listOf("微微一笑很倾城"), ranked.map { it.book.name })
    }

    @Test
    fun exactShortTitleOutranksHighConsensusContainingTitle() {
        val noisyCandidates = (0 until 8).map { index ->
            SearchCandidate(
                fixtureBook(fixtureSource("noise$index"), "网游之武破云巅", "就很突然"),
                sourceIndex = index,
                searchQuery = "破云"
            )
        }
        val ranked = BookSearchRanker().rank(
            keyword = "破云",
            candidates = noisyCandidates + SearchCandidate(
                fixtureBook(fixtureSource("exact"), "破云", "淮上"),
                sourceIndex = 20,
                searchQuery = "破云"
            ),
            limit = 10
        )

        assertEquals("破云", ranked.first().book.name)
        assertEquals("淮上", ranked.first().book.author)
    }

    @Test
    fun keepsMultiSourcePrefixTitleWhenExactTitleExists() {
        val candidates = listOf(
            SearchCandidate(fixtureBook(fixtureSource("exactA"), "青山", "会说话的肘子"), sourceIndex = 0),
            SearchCandidate(fixtureBook(fixtureSource("exactB"), "青山", "会说话的肘子"), sourceIndex = 1),
            SearchCandidate(fixtureBook(fixtureSource("prefixA"), "青山有幸", "更俗"), sourceIndex = 2),
            SearchCandidate(fixtureBook(fixtureSource("prefixB"), "青山有幸", "更俗"), sourceIndex = 3),
            SearchCandidate(fixtureBook(fixtureSource("single"), "青山不见", "弱证据"), sourceIndex = 4)
        )

        val ranked = BookSearchRanker().rank(
            keyword = "青山",
            candidates = candidates,
            limit = 10
        )

        assertEquals(listOf("青山" to "会说话的肘子", "青山有幸" to "更俗"), ranked.map { it.book.name to it.book.author })
    }

    @Test
    fun keepsSameTitleDifferentAuthorsSeparate() {
        val ranked = BookSearchRanker().rank(
            keyword = "难哄",
            candidates = listOf(
                SearchCandidate(fixtureBook(fixtureSource("A"), "难哄", "竹已"), sourceIndex = 0),
                SearchCandidate(fixtureBook(fixtureSource("B"), "难哄", "糖不甜"), sourceIndex = 1)
            ),
            limit = 10
        )

        assertEquals(listOf("竹已", "糖不甜"), ranked.map { it.book.author })
    }

    @Test
    fun cleansRepeatedAuthorSuffixForDisplayAndDedupe() {
        val source = fixtureSource("A")
        val ranker = BookSearchRanker()
        val ranked = ranker.rank(
            keyword = "天蚕土豆",
            candidates = listOf(
                SearchCandidate(fixtureBook(source, "元尊天蚕土豆", "天蚕土豆"), sourceIndex = 0),
                SearchCandidate(fixtureBook(source, "元尊", "天蚕土豆"), sourceIndex = 1)
            ),
            limit = 10
        )

        assertEquals(1, ranked.size)
        assertEquals("元尊", ranker.displayTitle(ranked.first().book))
    }

    private fun fixtureSource(name: String): BookSource {
        return BookSource(
            sourceName = name,
            sourceUrl = "https://example.com/$name",
            sourceGroup = null,
            sourceComment = null,
            enabled = true,
            headers = emptyMap(),
            searchUrl = null,
            ruleSearch = LegadoRuleSet("ruleSearch", emptyMap()),
            ruleBookInfo = LegadoRuleSet("ruleBookInfo", emptyMap()),
            ruleToc = LegadoRuleSet("ruleToc", emptyMap()),
            ruleContent = LegadoRuleSet("ruleContent", emptyMap()),
            diagnostics = emptyList()
        )
    }

    private fun fixtureBook(source: BookSource, name: String, author: String): SourceBook {
        return SourceBook(
            source = source,
            name = name,
            author = author,
            bookUrl = "${source.sourceUrl}/$name",
            coverUrl = "",
            intro = "",
            kind = "",
            lastChapter = ""
        )
    }
}
