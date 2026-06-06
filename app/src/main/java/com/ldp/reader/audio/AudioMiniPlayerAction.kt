package com.ldp.reader.audio

enum class AudioMiniPlayerCommand {
    TOGGLE_SERVICE,
    RESUME_SERVICE,
    OPEN_PLAYER
}

object AudioMiniPlayerAction {
    fun playButtonCommand(nowPlaying: AudioNowPlaying?): AudioMiniPlayerCommand? {
        nowPlaying ?: return null
        return when {
            nowPlaying.isPlaying -> AudioMiniPlayerCommand.TOGGLE_SERVICE
            nowPlaying.audioUrl.isNotBlank() -> AudioMiniPlayerCommand.RESUME_SERVICE
            else -> AudioMiniPlayerCommand.OPEN_PLAYER
        }
    }
}
