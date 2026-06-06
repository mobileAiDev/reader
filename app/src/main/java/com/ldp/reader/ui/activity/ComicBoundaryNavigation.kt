package com.ldp.reader.ui.activity

import kotlin.math.abs

object ComicBoundaryNavigation {
    fun target(
        horizontal: Boolean,
        controlsVisible: Boolean,
        itemCount: Int,
        deltaX: Float,
        deltaY: Float,
        canScrollForward: Boolean,
        canScrollBackward: Boolean,
        firstPageVisible: Boolean = false,
        lastPageVisible: Boolean = false,
        distance: Float
    ): ComicBoundaryDirection? {
        if (controlsVisible || itemCount <= 0) return null
        return when (gestureDirection(horizontal, deltaX, deltaY, distance)) {
            ComicBoundaryDirection.NEXT -> if (!canScrollForward || lastPageVisible) ComicBoundaryDirection.NEXT else null
            ComicBoundaryDirection.PREVIOUS -> if (!canScrollBackward || firstPageVisible) ComicBoundaryDirection.PREVIOUS else null
            null -> null
        }
    }

    fun blockedTarget(
        horizontal: Boolean,
        controlsVisible: Boolean,
        itemCount: Int,
        deltaX: Float,
        deltaY: Float,
        canScrollForward: Boolean,
        canScrollBackward: Boolean,
        firstPageVisible: Boolean,
        lastPageVisible: Boolean,
        distance: Float
    ): ComicBoundaryDirection? {
        if (controlsVisible || itemCount <= 0) return null
        return when (gestureDirection(horizontal, deltaX, deltaY, distance)) {
            ComicBoundaryDirection.NEXT -> if (canScrollForward && lastPageVisible) ComicBoundaryDirection.NEXT else null
            ComicBoundaryDirection.PREVIOUS -> if (canScrollBackward && firstPageVisible) ComicBoundaryDirection.PREVIOUS else null
            null -> null
        }
    }

    private fun gestureDirection(
        horizontal: Boolean,
        deltaX: Float,
        deltaY: Float,
        distance: Float
    ): ComicBoundaryDirection? {
        return if (horizontal) {
            if (abs(deltaX) < distance || abs(deltaX) <= abs(deltaY)) {
                null
            } else if (deltaX < 0f) {
                ComicBoundaryDirection.NEXT
            } else {
                ComicBoundaryDirection.PREVIOUS
            }
        } else {
            if (abs(deltaY) < distance || abs(deltaY) <= abs(deltaX)) {
                null
            } else if (deltaY < 0f) {
                ComicBoundaryDirection.NEXT
            } else {
                ComicBoundaryDirection.PREVIOUS
            }
        }
    }
}

enum class ComicBoundaryDirection(val traceName: String, val offset: Int) {
    NEXT("next", 1),
    PREVIOUS("previous", -1)
}
