package com.ldp.reader.audio

enum class AudioPlayerPrimaryAction {
    PLAY,
    PAUSE,
    REPLAY_FROM_START
}

data class AudioPlayerControlDecision(
    val action: AudioPlayerPrimaryAction
) {
    val showsPause: Boolean
        get() = action == AudioPlayerPrimaryAction.PAUSE

    val contentDescription: String
        get() = if (showsPause) "暂停" else "播放"
}

object AudioPlayerControlPolicy {
    fun resolve(
        playbackEnded: Boolean,
        isPlaying: Boolean,
        playWhenReady: Boolean
    ): AudioPlayerControlDecision {
        val action = when {
            playbackEnded -> AudioPlayerPrimaryAction.REPLAY_FROM_START
            isPlaying || playWhenReady -> AudioPlayerPrimaryAction.PAUSE
            else -> AudioPlayerPrimaryAction.PLAY
        }
        return AudioPlayerControlDecision(action)
    }
}
