package com.ldp.reader.ui.activity

import com.ldp.reader.widget.page.PageLoader

internal enum class ReaderCatalogRetryAction {
    RETRY_AFTER_DELAY,
    PUBLISH_ERROR
}

internal enum class ReaderCatalogManualRetryAction {
    NO_RETRY,
    REFRESH_LOCAL_CATALOG,
    RELOAD_REMOTE_CATALOG
}

internal object ReaderCatalogRetryPolicy {
    const val MAX_TOTAL_ATTEMPTS = 3

    fun afterFailure(
        isSourceEngineRequest: Boolean,
        failedAttempt: Int
    ): ReaderCatalogRetryAction {
        return if (isSourceEngineRequest && failedAttempt < MAX_TOTAL_ATTEMPTS) {
            ReaderCatalogRetryAction.RETRY_AFTER_DELAY
        } else {
            ReaderCatalogRetryAction.PUBLISH_ERROR
        }
    }

    fun manualRetry(
        pageStatus: Int,
        isLocalBook: Boolean
    ): ReaderCatalogManualRetryAction {
        if (pageStatus != PageLoader.STATUS_CATALOG_ERROR) {
            return ReaderCatalogManualRetryAction.NO_RETRY
        }
        return if (isLocalBook) {
            ReaderCatalogManualRetryAction.REFRESH_LOCAL_CATALOG
        } else {
            ReaderCatalogManualRetryAction.RELOAD_REMOTE_CATALOG
        }
    }
}
