package com.ldp.reader.ui.activity

enum class BookDetailWorkTransition {
    START_READING,
    LEAVE_DETAIL
}

data class BookDetailWorkCancellationPlan(
    val cancelDetailRefresh: Boolean,
    val cancelPendingShelfAdd: Boolean,
    val cancelContentTier: Boolean
)

object BookDetailWorkCancellationPolicy {
    fun forTransition(transition: BookDetailWorkTransition): BookDetailWorkCancellationPlan {
        return when (transition) {
            BookDetailWorkTransition.START_READING -> BookDetailWorkCancellationPlan(
                cancelDetailRefresh = true,
                cancelPendingShelfAdd = false,
                cancelContentTier = true
            )

            BookDetailWorkTransition.LEAVE_DETAIL -> BookDetailWorkCancellationPlan(
                cancelDetailRefresh = true,
                cancelPendingShelfAdd = true,
                cancelContentTier = true
            )
        }
    }
}
