package com.ldp.reader.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCatalogStatePolicyTest {
    @Test
    fun loadingCatalogKeepsOrderingDisabledAndShowsLoading() {
        val state = MediaCatalogStatePolicy.loading()

        assertEquals("加载中...", state.message)
        assertFalse(state.orderEnabled)
        assertFalse(state.retryEnabled)
    }

    @Test
    fun emptyCatalogOffersRetryWithoutEnablingOrdering() {
        val state = MediaCatalogStatePolicy.resolved(itemCount = 0, unit = "集")

        assertEquals("暂时没有可用目录，点击重试", state.message)
        assertFalse(state.orderEnabled)
        assertTrue(state.retryEnabled)
    }

    @Test
    fun populatedCatalogEnablesOrderingAndShowsTheResolvedCount() {
        val state = MediaCatalogStatePolicy.resolved(itemCount = 51, unit = "话")

        assertEquals("共 51 话", state.message)
        assertTrue(state.orderEnabled)
        assertFalse(state.retryEnabled)
    }

    @Test
    fun persistedShelfProgressWinsWhenCatalogIsOpenedOrResumed() {
        assertEquals(
            50,
            MediaCatalogStatePolicy.selectedIndex(currentIndex = -1, shelfIndex = 50)
        )
    }

    @Test
    fun clickedSelectionIsRetainedWhenThereIsNoShelfProgress() {
        assertEquals(
            7,
            MediaCatalogStatePolicy.selectedIndex(currentIndex = 7, shelfIndex = -1)
        )
    }
}
