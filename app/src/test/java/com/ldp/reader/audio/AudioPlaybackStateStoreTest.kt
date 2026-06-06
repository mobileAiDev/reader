package com.ldp.reader.audio

import com.ldp.reader.media.MediaLegadoRuleSet
import com.ldp.reader.media.MediaRouteRegistry
import com.ldp.reader.media.MediaSourceBook
import com.ldp.reader.media.MediaSourceChapter
import com.ldp.reader.media.MediaSourceDefinition
import com.ldp.reader.media.MediaSourceType
import com.ldp.reader.media.ReaderMediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioPlaybackStateStoreTest {
    @Test
    fun nowPlayingSnapshotRestoresChapterRoutesAfterRegistryReset() {
        MediaRouteRegistry.clearForTest()
        val book = sampleBook()
        val bookRouteId = MediaRouteRegistry.registerBook(ReaderMediaKind.AUDIO, book)
        val chapterRouteId = MediaRouteRegistry.registerChapter(
            ReaderMediaKind.AUDIO,
            sampleChapter(book),
            bookRouteId
        )
        val snapshot = requireNotNull(MediaRouteRegistry.snapshotBookRoute(bookRouteId))
        val nowPlaying = AudioNowPlaying(
            chapterRouteId = chapterRouteId,
            bookRouteId = bookRouteId,
            bookTitle = book.name,
            title = "第1集",
            routeSnapshot = snapshot
        )

        MediaRouteRegistry.clearForTest()

        assertNull(MediaRouteRegistry.chapter(chapterRouteId))
        assertTrue(AudioPlaybackStateStore.restoreRouteSnapshot(nowPlaying))
        assertEquals(bookRouteId, MediaRouteRegistry.bookRouteForChapter(chapterRouteId))
    }

    private fun sampleBook(): MediaSourceBook {
        return MediaSourceBook(
            source = sampleSource(),
            name = "庆余年",
            author = "猫腻",
            bookUrl = "https://audio.example/book/qyn",
            coverUrl = "https://audio.example/cover.jpg",
            intro = "权谋故事",
            kind = "audio",
            lastChapter = "第1集"
        )
    }

    private fun sampleChapter(book: MediaSourceBook): MediaSourceChapter {
        return MediaSourceChapter(
            source = book.source,
            book = book,
            index = 0,
            name = "第1集",
            chapterUrl = "https://audio.example/chapter/1"
        )
    }

    private fun sampleSource(): MediaSourceDefinition {
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
