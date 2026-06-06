package com.ldp.reader.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioMiniPlayerActionTest {
    @Test
    fun playButtonHasNoCommandWithoutNowPlaying() {
        assertNull(AudioMiniPlayerAction.playButtonCommand(null))
    }

    @Test
    fun playButtonTogglesWhenAudioIsCurrentlyPlaying() {
        val nowPlaying = nowPlaying(isPlaying = true, audioUrl = "https://audio.example/1.mp3")

        assertEquals(AudioMiniPlayerCommand.TOGGLE_SERVICE, AudioMiniPlayerAction.playButtonCommand(nowPlaying))
    }

    @Test
    fun playButtonResumesServiceWhenPausedAudioUrlIsKnown() {
        val nowPlaying = nowPlaying(isPlaying = false, audioUrl = "https://audio.example/1.mp3")

        assertEquals(AudioMiniPlayerCommand.RESUME_SERVICE, AudioMiniPlayerAction.playButtonCommand(nowPlaying))
    }

    @Test
    fun playButtonOpensPlayerWhenPausedAudioUrlMustBeResolvedAgain() {
        val nowPlaying = nowPlaying(isPlaying = false, audioUrl = "")

        assertEquals(AudioMiniPlayerCommand.OPEN_PLAYER, AudioMiniPlayerAction.playButtonCommand(nowPlaying))
    }

    private fun nowPlaying(isPlaying: Boolean, audioUrl: String): AudioNowPlaying {
        return AudioNowPlaying(
            chapterRouteId = "chapter-route",
            bookRouteId = "book-route",
            bookTitle = "斗罗大陆",
            title = "第001集_斗罗大陆",
            isPlaying = isPlaying,
            audioUrl = audioUrl
        )
    }
}
