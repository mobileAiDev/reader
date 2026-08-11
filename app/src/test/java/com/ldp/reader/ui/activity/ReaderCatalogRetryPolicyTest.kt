package com.ldp.reader.ui.activity

import com.ldp.reader.widget.page.PageLoader
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderCatalogRetryPolicyTest {
    @Test
    fun `local catalog error retries local parser but chapter error stays on chapter retry path`() {
        assertEquals(
            ReaderCatalogManualRetryAction.REFRESH_LOCAL_CATALOG,
            ReaderCatalogRetryPolicy.manualRetry(
                pageStatus = PageLoader.STATUS_CATALOG_ERROR,
                isLocalBook = true
            )
        )
        assertEquals(
            ReaderCatalogManualRetryAction.NO_RETRY,
            ReaderCatalogRetryPolicy.manualRetry(
                pageStatus = PageLoader.STATUS_ERROR,
                isLocalBook = true
            )
        )
        assertEquals(
            ReaderCatalogManualRetryAction.NO_RETRY,
            ReaderCatalogRetryPolicy.manualRetry(
                pageStatus = PageLoader.STATUS_DOCUMENT_ERROR,
                isLocalBook = true
            )
        )
    }

    @Test
    fun `remote catalog error keeps the existing view model reload route`() {
        assertEquals(
            ReaderCatalogManualRetryAction.RELOAD_REMOTE_CATALOG,
            ReaderCatalogRetryPolicy.manualRetry(
                pageStatus = PageLoader.STATUS_CATALOG_ERROR,
                isLocalBook = false
            )
        )
    }

    @Test
    fun `source engine catalog failure stops after three total attempts`() {
        val actions = (1..3).map { failedAttempt ->
            ReaderCatalogRetryPolicy.afterFailure(
                isSourceEngineRequest = true,
                failedAttempt = failedAttempt
            )
        }

        assertEquals(
            listOf(
                ReaderCatalogRetryAction.RETRY_AFTER_DELAY,
                ReaderCatalogRetryAction.RETRY_AFTER_DELAY,
                ReaderCatalogRetryAction.PUBLISH_ERROR
            ),
            actions
        )
    }

    @Test
    fun `non source catalog failure publishes an error immediately`() {
        assertEquals(
            ReaderCatalogRetryAction.PUBLISH_ERROR,
            ReaderCatalogRetryPolicy.afterFailure(
                isSourceEngineRequest = false,
                failedAttempt = 1
            )
        )
    }
}
