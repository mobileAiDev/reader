package com.ldp.reader.audio

enum class AudioMiniPlayerCommand {
    TOGGLE_SERVICE,
    RESUME_SERVICE,
    REPLAY_SERVICE,
    OPEN_PLAYER
}

object AudioMiniPlayerAction {
    fun playButtonCommand(nowPlaying: AudioNowPlaying?): AudioMiniPlayerCommand? {
        nowPlaying ?: return null
        return when {
            nowPlaying.isPlaying -> AudioMiniPlayerCommand.TOGGLE_SERVICE
            !nowPlaying.playbackReady || nowPlaying.audioUrl.isBlank() -> AudioMiniPlayerCommand.OPEN_PLAYER
            nowPlaying.playbackEnded -> AudioMiniPlayerCommand.REPLAY_SERVICE
            else -> AudioMiniPlayerCommand.RESUME_SERVICE
        }
    }
}
