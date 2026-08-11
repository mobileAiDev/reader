package com.ldp.reader.audio

import com.ldp.reader.media.MediaChapterItem
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioChapterOrderTest {
    @Test
    fun latestFirstSourceIsNormalizedToChronologicalOrder() {
        val sourceOrder = listOf(
            episode("route-3", "第3集", sourceIndex = 0),
            episode("route-2", "第2集", sourceIndex = 1),
            episode("route-1", "第1集", sourceIndex = 2)
        )

        val chronological = AudioChapterOrder.chronological(sourceOrder)

        assertEquals(listOf("route-1", "route-2", "route-3"), chronological.map { it.routeId })
        assertEquals(listOf(2, 1, 0), chronological.map { it.index })
    }

    @Test
    fun unorderedSourceIsSortedByEpisodeOrdinal() {
        val sourceOrder = listOf(
            episode("route-3", "003 大结局"),
            episode("route-1", "001 开始"),
            episode("route-2", "002 继续")
        )

        assertEquals(
            listOf("route-1", "route-2", "route-3"),
            AudioChapterOrder.chronological(sourceOrder).map { it.routeId }
        )
    }

    @Test
    fun audioSpecificBareEpisodeNumbersAreRecognized() {
        val sourceOrder = listOf(
            episode("route-3", "3集 大结局"),
            episode("route-2", "2集 继续"),
            episode("route-1", "1集 开始")
        )

        assertEquals(
            listOf("route-1", "route-2", "route-3"),
            AudioChapterOrder.chronological(sourceOrder).map { it.routeId }
        )
    }

    @Test
    fun duplicateOrdinalsPreserveSourceOrderToAvoidCrossVolumeReordering() {
        val sourceOrder = listOf(
            episode("volume-1-1", "第一集"),
            episode("volume-1-2", "第二集"),
            episode("volume-2-1", "第一集"),
            episode("volume-2-2", "第二集")
        )

        assertEquals(sourceOrder, AudioChapterOrder.chronological(sourceOrder))
    }

    private fun episode(routeId: String, title: String, sourceIndex: Int = 0): MediaChapterItem {
        return MediaChapterItem(routeId = routeId, title = title, index = sourceIndex)
    }
}
