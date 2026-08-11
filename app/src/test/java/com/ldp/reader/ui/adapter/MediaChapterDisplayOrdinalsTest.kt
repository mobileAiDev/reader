package com.ldp.reader.ui.adapter

import com.ldp.reader.media.MediaChapterItem
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaChapterDisplayOrdinalsTest {
    @Test
    fun chronologicalNumbersFollowCatalogPositionWithoutChangingSourceIdentity() {
        val sourceOrder = listOf(
            episode(routeId = "route-3", title = "第3集", sourceIndex = 0),
            episode(routeId = "route-2", title = "第2集", sourceIndex = 1),
            episode(routeId = "route-1", title = "第1集", sourceIndex = 2)
        )
        val chronological = sourceOrder.asReversed()

        val ordinals = MediaChapterDisplayOrdinals.chronological(chronological)

        assertEquals(listOf(1, 2, 3), chronological.map(ordinals::forChapter))
        assertEquals(listOf(2, 1, 0), chronological.map { it.index })
        assertEquals(listOf("route-1", "route-2", "route-3"), chronological.map { it.routeId })
    }

    @Test
    fun reversePresentationKeepsNumbersFromTheFullChronologicalCatalog() {
        val chronological = listOf(
            episode(routeId = "route-1", title = "开头", sourceIndex = 90),
            episode(routeId = "route-2", title = "中间", sourceIndex = 80),
            episode(routeId = "route-3", title = "结尾", sourceIndex = 70)
        )
        val ordinals = MediaChapterDisplayOrdinals.chronological(chronological)

        assertEquals(listOf(3, 2, 1), chronological.asReversed().map(ordinals::forChapter))
    }

    @Test
    fun normalizedComicOrderUsesCatalogPositionsAndPreservesSourceIndexes() {
        val chronological = listOf(
            episode(routeId = "comic-1", title = "第1话", sourceIndex = 3),
            episode(routeId = "comic-2", title = "第2话", sourceIndex = 2),
            episode(routeId = "comic-3", title = "第3话", sourceIndex = 1)
        )
        val ordinals = MediaChapterDisplayOrdinals.chronological(chronological)

        assertEquals(listOf(1, 2, 3), chronological.map(ordinals::forChapter))
        assertEquals(listOf(3, 2, 1), chronological.map { it.index })
    }

    @Test
    fun sourceIndexModeRemainsAvailableForUnnormalizedCallers() {
        val chapter = episode(routeId = "other-route", title = "章节", sourceIndex = 7)

        assertEquals(8, MediaChapterDisplayOrdinals.sourceIndexes().forChapter(chapter))
    }

    private fun episode(routeId: String, title: String, sourceIndex: Int): MediaChapterItem {
        return MediaChapterItem(routeId = routeId, title = title, index = sourceIndex)
    }
}
