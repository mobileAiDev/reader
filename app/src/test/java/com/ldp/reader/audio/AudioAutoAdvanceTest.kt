package com.ldp.reader.audio

import com.ldp.reader.media.MediaLegadoRuleSet
import com.ldp.reader.media.MediaRouteChapterSnapshot
import com.ldp.reader.media.MediaSourceBook
import com.ldp.reader.media.MediaSourceChapter
import com.ldp.reader.media.MediaSourceDefinition
import com.ldp.reader.media.MediaSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioAutoAdvanceTest {
    @Test
    fun nextEpisodeReturnsFollowingRouteWhenAvailable() {
        val chapters = listOf(
            snapshot("route-1", 0, "第1集"),
            snapshot("route-2", 1, "第2集"),
            snapshot("route-3", 2, "第3集")
        )

        val next = AudioAutoAdvance.nextEpisode(chapters, "route-2")

        assertEquals("route-3", next?.routeId)
    }

    @Test
    fun nextEpisodeReturnsNullAtLastEpisode() {
        val chapters = listOf(
            snapshot("route-1", 0, "第1集"),
            snapshot("route-2", 1, "第2集")
        )

        val next = AudioAutoAdvance.nextEpisode(chapters, "route-2")

        assertNull(next)
    }

    @Test
    fun nextEpisodeReturnsNullForUnknownCurrentRoute() {
        val chapters = listOf(snapshot("route-1", 0, "第1集"))

        val next = AudioAutoAdvance.nextEpisode(chapters, "missing")

        assertNull(next)
    }

    private fun snapshot(routeId: String, index: Int, name: String): MediaRouteChapterSnapshot {
        return MediaRouteChapterSnapshot(
            routeId = routeId,
            chapter = MediaSourceChapter(
                source = source(),
                book = book(),
                index = index,
                name = name,
                chapterUrl = "https://audio.example/$index"
            )
        )
    }

    private fun book(): MediaSourceBook {
        return MediaSourceBook(
            source = source(),
            name = "凡人修仙传",
            author = "忘语",
            bookUrl = "https://audio.example/book",
            coverUrl = "https://audio.example/cover.jpg",
            intro = "",
            kind = "audio",
            lastChapter = "第3集"
        )
    }

    private fun source(): MediaSourceDefinition {
        val emptyRules = MediaLegadoRuleSet("", emptyMap())
        return MediaSourceDefinition(
            sourceName = "音频源",
            sourceUrl = "https://audio.example",
            sourceType = MediaSourceType.AUDIO,
            sourceGroup = null,
            sourceComment = null,
            enabled = true,
            headers = emptyMap(),
            searchUrl = null,
            ruleSearch = emptyRules,
            ruleBookInfo = emptyRules,
            ruleToc = emptyRules,
            ruleContent = emptyRules,
            diagnostics = emptyList()
        )
    }
}
