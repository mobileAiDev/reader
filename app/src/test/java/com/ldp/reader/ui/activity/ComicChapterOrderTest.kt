package com.ldp.reader.ui.activity

import com.ldp.reader.media.MediaChapterItem
import org.junit.Assert.assertEquals
import org.junit.Test

class ComicChapterOrderTest {
    @Test
    fun latestFirstSourceIsNormalizedToChronologicalReadingOrder() {
        val sourceOrder = listOf(
            chapter(0, "第344话 大结局"),
            chapter(1, "第343话"),
            chapter(2, "第2话"),
            chapter(3, "第1话 开始")
        )

        val result = ComicChapterOrder.resolve(sourceOrder, "更新至第344话")

        assertEquals(ComicChapterOrder.Direction.DESCENDING, result.sourceDirection)
        assertEquals(sourceOrder.last(), result.first)
        assertEquals(sourceOrder.first(), result.latest)
        assertEquals(sourceOrder.asReversed(), result.chronological)
        assertEquals(
            sourceOrder.last(),
            ComicReadingResume.chapter(result.chronological, savedChapterRouteId = null, savedChapterIndex = null)
        )
    }

    @Test
    fun oldestFirstSourceKeepsItsOrder() {
        val sourceOrder = listOf(
            chapter(0, "序章"),
            chapter(1, "第1话"),
            chapter(2, "第2话"),
            chapter(3, "第344话 大结局")
        )

        val result = ComicChapterOrder.resolve(sourceOrder, "第344话 大结局")

        assertEquals(ComicChapterOrder.Direction.ASCENDING, result.sourceDirection)
        assertEquals(sourceOrder.first(), result.first)
        assertEquals(sourceOrder.last(), result.latest)
        assertEquals(sourceOrder, result.chronological)
    }

    @Test
    fun latestMetadataDeterminesDirectionForNonNumericTitles() {
        val sourceOrder = listOf(
            chapter(0, "终幕"),
            chapter(1, "中幕"),
            chapter(2, "开幕")
        )

        val result = ComicChapterOrder.resolve(sourceOrder, "终幕")

        assertEquals(ComicChapterOrder.Direction.DESCENDING, result.sourceDirection)
        assertEquals("开幕", result.first?.title)
        assertEquals("终幕", result.latest?.title)
    }

    @Test
    fun unknownOrderPreservesSourceOrderInsteadOfGuessing() {
        val sourceOrder = listOf(chapter(4, "楔子"), chapter(9, "外传"))

        val result = ComicChapterOrder.resolve(sourceOrder, null)

        assertEquals(ComicChapterOrder.Direction.UNKNOWN, result.sourceDirection)
        assertEquals(sourceOrder, result.chronological)
        assertEquals(sourceOrder.first(), result.first)
        assertEquals(sourceOrder.last(), result.latest)
    }

    @Test
    fun ascendingMajoritySurvivesAnOrdinalRestart() {
        val sourceOrder = listOf(
            chapter(0, "第1话"),
            chapter(1, "第2话"),
            chapter(2, "第3话"),
            chapter(3, "番外第1话"),
            chapter(4, "番外第2话")
        )

        val result = ComicChapterOrder.resolve(sourceOrder, null)

        assertEquals(ComicChapterOrder.Direction.ASCENDING, result.sourceDirection)
        assertEquals(sourceOrder, result.chronological)
    }

    private fun chapter(index: Int, title: String): MediaChapterItem {
        return MediaChapterItem("chapter-$index", title, index)
    }
}
