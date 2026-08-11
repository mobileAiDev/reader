package com.ldp.reader.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComicColdResumePolicyTest {
    @Test
    fun `current nonempty saved route pages can publish before catalog`() {
        assertTrue(ComicColdResumePolicy.canPublishFastPages(requestIsCurrent = true, pageCount = 18))
    }

    @Test
    fun `empty or stale fast result cannot replace current reader state`() {
        assertFalse(ComicColdResumePolicy.canPublishFastPages(requestIsCurrent = true, pageCount = 0))
        assertFalse(ComicColdResumePolicy.canPublishFastPages(requestIsCurrent = false, pageCount = 18))
    }

    @Test
    fun `successful fast route remains stable when catalog later resolves another source`() {
        assertEquals(
            "source-a-chapter",
            ComicColdResumePolicy.displayRouteAfterCatalog(
                savedRouteId = "source-a-chapter",
                resolvedRouteId = "source-b-chapter",
                fastPagesPublished = true
            )
        )
    }

    @Test
    fun `catalog route is used when saved route could not render`() {
        assertEquals(
            "source-b-chapter",
            ComicColdResumePolicy.displayRouteAfterCatalog(
                savedRouteId = "source-a-chapter",
                resolvedRouteId = "source-b-chapter",
                fastPagesPublished = false
            )
        )
    }
}
