package com.ldp.reader.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioDetailShelfActionTest {
    @Test
    fun `already shelved audio keeps its saved episode`() {
        assertEquals(
            AudioDetailShelfAction.KEEP_EXISTING,
            AudioDetailShelfAction.decide(isAlreadyAdded = true)
        )
    }

    @Test
    fun `new audio can be initialized from the first episode`() {
        assertEquals(
            AudioDetailShelfAction.ADD_INITIAL,
            AudioDetailShelfAction.decide(isAlreadyAdded = false)
        )
    }
}
