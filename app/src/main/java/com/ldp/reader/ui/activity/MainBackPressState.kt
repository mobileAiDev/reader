package com.ldp.reader.ui.activity

internal class MainBackPressState(
    private val repeatWindowMillis: Long
) {
    private var firstBackPressedAtMillis: Long? = null

    fun reset() {
        firstBackPressedAtMillis = null
    }

    fun onBackPressed(
        nowMillis: Long,
        dismissedLayer: MainBackLayer? = null
    ): MainBackAction {
        if (dismissedLayer != null) {
            firstBackPressedAtMillis = null
            return MainBackAction.LAYER_DISMISSED
        }
        val elapsedMillis = firstBackPressedAtMillis?.let { firstPress ->
            nowMillis - firstPress
        }
        return if (elapsedMillis != null && elapsedMillis in 0..repeatWindowMillis) {
            firstBackPressedAtMillis = null
            MainBackAction.EXIT
        } else {
            firstBackPressedAtMillis = nowMillis
            MainBackAction.SHOW_EXIT_PROMPT
        }
    }
}

internal enum class MainBackLayer {
    MORE_MENU,
    BOOKSHELF_EDIT
}

internal enum class MainBackAction {
    LAYER_DISMISSED,
    SHOW_EXIT_PROMPT,
    EXIT
}
