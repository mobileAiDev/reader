package com.ldp.reader.ui.activity

import com.ldp.reader.model.bean.BookDetailBeanInOwn
import kotlinx.coroutines.CancellationException

internal enum class BookDetailRefreshPhase {
    LOADING,
    PRELIMINARY,
    READY,
    PARTIAL_FAILURE,
    FAILURE
}

internal data class BookDetailRefreshState(
    val phase: BookDetailRefreshPhase
) {
    val keepsContent: Boolean
        get() = phase == BookDetailRefreshPhase.PRELIMINARY ||
            phase == BookDetailRefreshPhase.READY ||
            phase == BookDetailRefreshPhase.PARTIAL_FAILURE

    val retryable: Boolean
        get() = phase == BookDetailRefreshPhase.PARTIAL_FAILURE ||
            phase == BookDetailRefreshPhase.FAILURE

    companion object {
        val Loading = BookDetailRefreshState(BookDetailRefreshPhase.LOADING)
        val Preliminary = BookDetailRefreshState(BookDetailRefreshPhase.PRELIMINARY)
        val Ready = BookDetailRefreshState(BookDetailRefreshPhase.READY)
        val PartialFailure = BookDetailRefreshState(BookDetailRefreshPhase.PARTIAL_FAILURE)
        val Failure = BookDetailRefreshState(BookDetailRefreshPhase.FAILURE)
    }
}

internal sealed interface BookDetailRefreshOutcome {
    val state: BookDetailRefreshState

    data class Ready(
        val detail: BookDetailBeanInOwn
    ) : BookDetailRefreshOutcome {
        override val state: BookDetailRefreshState = BookDetailRefreshState.Ready
    }

    data class Failed(
        val error: Throwable,
        override val state: BookDetailRefreshState
    ) : BookDetailRefreshOutcome
}

internal suspend fun loadBookDetailWithPreliminaryFallback(
    preliminaryPublished: Boolean,
    load: suspend () -> BookDetailBeanInOwn
): BookDetailRefreshOutcome {
    return try {
        BookDetailRefreshOutcome.Ready(load())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        BookDetailRefreshOutcome.Failed(
            error = error,
            state = if (preliminaryPublished) {
                BookDetailRefreshState.PartialFailure
            } else {
                BookDetailRefreshState.Failure
            }
        )
    }
}
