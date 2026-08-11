package com.ldp.reader.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Test

class MainBackPressStateTest {
    @Test
    fun firstBackPressShowsExitPrompt() {
        val state = MainBackPressState(REPEAT_WINDOW_MILLIS)

        assertEquals(
            MainBackAction.SHOW_EXIT_PROMPT,
            state.onBackPressed(nowMillis = 10_000L)
        )
    }

    @Test
    fun backPressAfterWindowStartsANewExitGesture() {
        val state = MainBackPressState(REPEAT_WINDOW_MILLIS)

        assertEquals(
            MainBackAction.SHOW_EXIT_PROMPT,
            state.onBackPressed(nowMillis = 10_000L)
        )
        assertEquals(
            MainBackAction.SHOW_EXIT_PROMPT,
            state.onBackPressed(nowMillis = 12_001L)
        )
    }

    @Test
    fun secondBackPressInsideWindowExits() {
        val state = MainBackPressState(REPEAT_WINDOW_MILLIS)

        assertEquals(
            MainBackAction.SHOW_EXIT_PROMPT,
            state.onBackPressed(nowMillis = 10_000L)
        )
        assertEquals(
            MainBackAction.EXIT,
            state.onBackPressed(nowMillis = 11_999L)
        )
    }

    @Test
    fun dismissingEachHomeLayerDoesNotArmOrReuseExitGesture() {
        MainBackLayer.entries.forEach { layer ->
            val freshState = MainBackPressState(REPEAT_WINDOW_MILLIS)

            assertEquals(
                MainBackAction.LAYER_DISMISSED,
                freshState.onBackPressed(nowMillis = 9_000L, dismissedLayer = layer)
            )
            assertEquals(
                "fresh layer=$layer",
                MainBackAction.SHOW_EXIT_PROMPT,
                freshState.onBackPressed(nowMillis = 9_100L)
            )

            val state = MainBackPressState(REPEAT_WINDOW_MILLIS)

            assertEquals(
                MainBackAction.SHOW_EXIT_PROMPT,
                state.onBackPressed(nowMillis = 10_000L)
            )
            assertEquals(
                MainBackAction.LAYER_DISMISSED,
                state.onBackPressed(nowMillis = 10_500L, dismissedLayer = layer)
            )
            assertEquals(
                "layer=$layer",
                MainBackAction.SHOW_EXIT_PROMPT,
                state.onBackPressed(nowMillis = 10_600L)
            )
        }
    }

    @Test
    fun leavingTheForegroundClearsAnArmedExitGesture() {
        val state = MainBackPressState(REPEAT_WINDOW_MILLIS)

        assertEquals(MainBackAction.SHOW_EXIT_PROMPT, state.onBackPressed(10_000L))
        state.reset()

        assertEquals(MainBackAction.SHOW_EXIT_PROMPT, state.onBackPressed(10_500L))
    }

    private companion object {
        const val REPEAT_WINDOW_MILLIS = 2_000L
    }
}
