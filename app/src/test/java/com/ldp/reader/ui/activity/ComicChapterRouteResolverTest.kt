package com.ldp.reader.ui.activity

import com.ldp.reader.media.MediaChapterItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComicChapterRouteResolverTest {
    @Test
    fun sourceSwitchResolvesTheSameChapterByNormalizedTitleInsteadOfRawIndex() {
        val sourceB = listOf(
            chapter("b-52", 0, "第52话"),
            chapter("b-51", 1, "第51话 决战"),
            chapter("b-50", 2, "第50话")
        )

        val resolved = ComicChapterRouteResolver.resolve(
            chapters = sourceB,
            savedRouteId = "a-51",
            savedTitle = "第五十一话：决战",
            savedSourceIndex = 50
        )

        assertEquals("b-51", resolved?.routeId)
    }

    @Test
    fun unmatchedNamedChapterDoesNotGuessByAnotherSourcesIndex() {
        val sourceB = listOf(
            chapter("b-prologue", 50, "序章"),
            chapter("b-1", 51, "第1话")
        )

        val resolved = ComicChapterRouteResolver.resolve(
            chapters = sourceB,
            savedRouteId = "a-special",
            savedTitle = "作者番外",
            savedSourceIndex = 50
        )

        assertNull(resolved)
    }

    @Test
    fun exactRouteIdentityWinsBeforeCrossSourceMatching() {
        val source = listOf(
            chapter("same-route", 9, "第10话"),
            chapter("duplicate-title", 10, "第10话")
        )

        val resolved = ComicChapterRouteResolver.resolve(
            chapters = source,
            savedRouteId = "same-route",
            savedTitle = "第10话",
            savedSourceIndex = 10
        )

        assertEquals("same-route", resolved?.routeId)
    }

    @Test
    fun legacyEntryWithoutTitleMayUseItsSourceIndexAsLastResort() {
        val source = listOf(
            chapter("b-0", 0, "序章"),
            chapter("b-1", 1, "第一话")
        )

        val resolved = ComicChapterRouteResolver.resolve(
            chapters = source,
            savedRouteId = "missing",
            savedTitle = "",
            savedSourceIndex = 1
        )

        assertEquals("b-1", resolved?.routeId)
    }

    private fun chapter(routeId: String, index: Int, title: String): MediaChapterItem {
        return MediaChapterItem(routeId, title, index)
    }
}
