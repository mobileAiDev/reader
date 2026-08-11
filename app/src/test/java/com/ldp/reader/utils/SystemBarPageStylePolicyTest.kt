package com.ldp.reader.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemBarPageStylePolicyTest {
    @Test
    fun `light regular page keeps the gesture area on the same surface`() {
        val plan = SystemBarPageStylePolicy.regularPage(
            surfaceColor = 0xFFFFFFFF.toInt(),
            surfaceIsLight = true
        )

        assertEquals(0xFFFFFFFF.toInt(), plan.navigationBarColor)
        assertTrue(plan.darkNavigationBarIcons)
        assertFalse(plan.drawBehindNavigationBar)
        assertEquals(BottomInsetOwner.SYSTEM_DECOR, plan.bottomInsetOwner)
    }

    @Test
    fun `old three button navigation keeps readable light controls on a dark fallback`() {
        val darkFallback = 0xFF101114.toInt()

        val plan = SystemBarPageStylePolicy.regularPage(
            surfaceColor = 0xFFFFFFFF.toInt(),
            surfaceIsLight = true,
            supportsDarkNavigationIcons = false,
            darkNavigationFallbackColor = darkFallback
        )

        assertEquals(darkFallback, plan.navigationBarColor)
        assertFalse(plan.darkNavigationBarIcons)
        assertFalse(plan.drawBehindNavigationBar)
        assertEquals(BottomInsetOwner.SYSTEM_DECOR, plan.bottomInsetOwner)
    }
}
