package com.ldp.reader.ui.adapter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComicPageLayoutPolicyTest {
    @Test
    fun `very tall image in horizontal paging is fit inside one viewport`() {
        val spec = comicPageLayoutSpec(ComicPageLayoutMode.HORIZONTAL_PAGED)

        assertTrue(spec.itemFillsViewportWidth)
        assertTrue(spec.itemFillsViewportHeight)
        assertTrue(spec.imageFillsPageWidth)
        assertTrue(spec.imageFillsPageHeight)
        assertFalse(spec.adjustViewBounds)
        assertEquals(ComicPageScaleType.FIT_CENTER, spec.scaleType)
        assertTrue(spec.constrainDecodeToViewport)
    }

    @Test
    fun `vertical continuous mode keeps tall image height driven by its aspect ratio`() {
        val spec = comicPageLayoutSpec(ComicPageLayoutMode.VERTICAL_CONTINUOUS)

        assertTrue(spec.itemFillsViewportWidth)
        assertFalse(spec.itemFillsViewportHeight)
        assertTrue(spec.imageFillsPageWidth)
        assertFalse(spec.imageFillsPageHeight)
        assertTrue(spec.adjustViewBounds)
        assertEquals(ComicPageScaleType.FIT_CENTER, spec.scaleType)
        assertFalse(spec.constrainDecodeToViewport)
    }
}
