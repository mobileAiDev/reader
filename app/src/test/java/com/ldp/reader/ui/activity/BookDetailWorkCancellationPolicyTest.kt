package com.ldp.reader.ui.activity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookDetailWorkCancellationPolicyTest {
    @Test
    fun startingReadingKeepsPendingShelfCatalogRefreshAlive() {
        val plan = BookDetailWorkCancellationPolicy.forTransition(
            BookDetailWorkTransition.START_READING
        )

        assertTrue(plan.cancelDetailRefresh)
        assertFalse(plan.cancelPendingShelfAdd)
        assertTrue(plan.cancelContentTier)
    }

    @Test
    fun leavingDetailCancelsEveryOutstandingDetailJob() {
        val plan = BookDetailWorkCancellationPolicy.forTransition(
            BookDetailWorkTransition.LEAVE_DETAIL
        )

        assertTrue(plan.cancelDetailRefresh)
        assertTrue(plan.cancelPendingShelfAdd)
        assertTrue(plan.cancelContentTier)
    }
}
