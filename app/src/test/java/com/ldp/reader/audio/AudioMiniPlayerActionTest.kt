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
        val nowPlaying = nowPlaying(
            isPlaying = true,
            audioUrl = "https://audio.example/1.mp3",
            playbackReady = true
        )

        assertEquals(AudioMiniPlayerCommand.TOGGLE_SERVICE, AudioMiniPlayerAction.playButtonCommand(nowPlaying))
    }

    @Test
    fun playButtonResumesServiceWhenPausedAudioUrlIsKnown() {
        val nowPlaying = nowPlaying(
            isPlaying = false,
            audioUrl = "https://audio.example/1.mp3",
            playbackReady = true
        )

        assertEquals(AudioMiniPlayerCommand.RESUME_SERVICE, AudioMiniPlayerAction.playButtonCommand(nowPlaying))
    }

    @Test
    fun playButtonOpensPlayerWhenPausedAudioUrlMustBeResolvedAgain() {
        val nowPlaying = nowPlaying(isPlaying = false, audioUrl = "")

        assertEquals(AudioMiniPlayerCommand.OPEN_PLAYER, AudioMiniPlayerAction.playButtonCommand(nowPlaying))
    }

    @Test
    fun playButtonOpensPlayerToResolveFreshAfterThePersistedUrlFailed() {
        val nowPlaying = nowPlaying(
            isPlaying = false,
            audioUrl = "https://audio.example/stale.mp3",
            playbackReady = false
        )

        assertEquals(AudioMiniPlayerCommand.OPEN_PLAYER, AudioMiniPlayerAction.playButtonCommand(nowPlaying))
    }

    @Test
    fun playButtonReplaysAnEndedEpisodeFromTheBeginning() {
        val nowPlaying = nowPlaying(
            isPlaying = false,
            audioUrl = "https://audio.example/ended.mp3",
            playbackReady = true,
            playbackEnded = true
        )

        assertEquals(AudioMiniPlayerCommand.REPLAY_SERVICE, AudioMiniPlayerAction.playButtonCommand(nowPlaying))
    }

    private fun nowPlaying(
        isPlaying: Boolean,
        audioUrl: String,
        playbackReady: Boolean = false,
        playbackEnded: Boolean = false
    ): AudioNowPlaying {
        return AudioNowPlaying(
            chapterRouteId = "chapter-route",
            bookRouteId = "book-route",
            bookTitle = "斗罗大陆",
            title = "第001集_斗罗大陆",
            isPlaying = isPlaying,
            audioUrl = audioUrl,
            playbackReady = playbackReady,
            playbackEnded = playbackEnded
        )
    }
}
