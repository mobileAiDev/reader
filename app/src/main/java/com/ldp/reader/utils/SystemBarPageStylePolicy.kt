package com.ldp.reader.utils

internal enum class BottomInsetOwner {
    SYSTEM_DECOR,
    APP_FOOTER,
    SCROLL_CONTENT
}

internal data class SystemBarPageStylePlan(
    val navigationBarColor: Int,
    val darkNavigationBarIcons: Boolean,
    val drawBehindNavigationBar: Boolean,
    val bottomInsetOwner: BottomInsetOwner
)

/** Describes one owner for the bottom system inset, preventing stacked padding. */
internal object SystemBarPageStylePolicy {
    fun regularPage(
        surfaceColor: Int,
        surfaceIsLight: Boolean,
        supportsDarkNavigationIcons: Boolean = true,
        darkNavigationFallbackColor: Int = surfaceColor
    ): SystemBarPageStylePlan {
        val needsDarkFallback = surfaceIsLight && !supportsDarkNavigationIcons
        return SystemBarPageStylePlan(
            navigationBarColor = if (needsDarkFallback) {
                darkNavigationFallbackColor
            } else {
                surfaceColor
            },
            darkNavigationBarIcons = surfaceIsLight && supportsDarkNavigationIcons,
            drawBehindNavigationBar = false,
            bottomInsetOwner = BottomInsetOwner.SYSTEM_DECOR
        )
    }
}
