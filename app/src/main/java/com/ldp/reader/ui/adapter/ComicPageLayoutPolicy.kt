package com.ldp.reader.ui.adapter

internal enum class ComicPageLayoutMode {
    VERTICAL_CONTINUOUS,
    HORIZONTAL_PAGED
}

internal enum class ComicPageScaleType {
    FIT_CENTER
}

internal data class ComicPageLayoutSpec(
    val itemFillsViewportWidth: Boolean,
    val itemFillsViewportHeight: Boolean,
    val imageFillsPageWidth: Boolean,
    val imageFillsPageHeight: Boolean,
    val adjustViewBounds: Boolean,
    val scaleType: ComicPageScaleType,
    val constrainDecodeToViewport: Boolean
)

internal fun localComicPageLayoutMode(horizontalMode: Boolean): ComicPageLayoutMode {
    return if (horizontalMode) {
        ComicPageLayoutMode.HORIZONTAL_PAGED
    } else {
        ComicPageLayoutMode.VERTICAL_CONTINUOUS
    }
}

internal fun comicPageLayoutSpec(mode: ComicPageLayoutMode): ComicPageLayoutSpec {
    val horizontalPaged = mode == ComicPageLayoutMode.HORIZONTAL_PAGED
    return ComicPageLayoutSpec(
        itemFillsViewportWidth = true,
        itemFillsViewportHeight = horizontalPaged,
        imageFillsPageWidth = true,
        imageFillsPageHeight = horizontalPaged,
        adjustViewBounds = !horizontalPaged,
        scaleType = ComicPageScaleType.FIT_CENTER,
        constrainDecodeToViewport = horizontalPaged
    )
}
