package com.ldp.reader.audio

enum class AudioDetailShelfAction {
    KEEP_EXISTING,
    ADD_INITIAL;

    companion object {
        fun decide(isAlreadyAdded: Boolean): AudioDetailShelfAction {
            return if (isAlreadyAdded) KEEP_EXISTING else ADD_INITIAL
        }
    }
}
