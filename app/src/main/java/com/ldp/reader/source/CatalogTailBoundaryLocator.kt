package com.ldp.reader.source

internal data class CatalogTailProbeResult(
    val keepUntil: Int,
    val checkedCount: Int,
    val method: String
)

internal class CatalogTailBoundaryLocator(
    private val maxBacktrackChapters: Int,
    private val tailWindowChapters: Int = DEFAULT_TAIL_WINDOW_CHAPTERS
) {
    suspend fun locate(
        chapterCount: Int,
        isReadable: suspend (Int) -> Boolean
    ): CatalogTailProbeResult {
        if (chapterCount <= 0) {
            return CatalogTailProbeResult(0, 0, "empty")
        }
        val checked = mutableMapOf<Int, Boolean>()

        suspend fun probe(index: Int): Boolean {
            checked[index]?.let { return it }
            val readable = isReadable(index)
            checked[index] = readable
            return readable
        }

        val lastIndex = chapterCount - 1
        val tailWindowStart = (chapterCount - tailWindowChapters).coerceAtLeast(0)

        var firstBadSuffixIndex = lastIndex + 1
        for (index in lastIndex downTo tailWindowStart) {
            if (probe(index)) break
            firstBadSuffixIndex = index
        }

        if (firstBadSuffixIndex > lastIndex) {
            return CatalogTailProbeResult(chapterCount, checked.size, "tail-readable-window")
        }

        val readableBeforeSuffix = firstBadSuffixIndex > tailWindowStart
        if (readableBeforeSuffix) {
            return CatalogTailProbeResult(
                keepUntil = firstBadSuffixIndex,
                checkedCount = checked.size,
                method = "tail-contiguous-bad-suffix"
            )
        }

        val firstBadIndex = firstBadSuffixIndex
        val minIndex = (chapterCount - maxBacktrackChapters).coerceAtLeast(0)
        var nearestBadIndex = firstBadIndex
        var step = 1
        var readableAnchor: Int? = null
        while (nearestBadIndex > minIndex) {
            val index = (firstBadIndex - step).coerceAtLeast(minIndex)
            if (index >= nearestBadIndex) break
            if (probe(index)) {
                readableAnchor = index
                break
            }
            nearestBadIndex = index
            if (index == minIndex) break
            step = (step * 2).coerceAtMost(maxBacktrackChapters)
        }

        val anchor = readableAnchor ?: return CatalogTailProbeResult(
            keepUntil = minIndex,
            checkedCount = checked.size,
            method = "exponential-no-readable-anchor"
        )

        var lowReadable = anchor
        var highBad = nearestBadIndex
        while (highBad - lowReadable > 1) {
            val midpoint = lowReadable + (highBad - lowReadable) / 2
            if (probe(midpoint)) {
                lowReadable = midpoint
            } else {
                highBad = midpoint
            }
        }

        return CatalogTailProbeResult(
            keepUntil = lowReadable + 1,
            checkedCount = checked.size,
            method = "tail-window-exponential-binary"
        )
    }

    private companion object {
        private const val DEFAULT_TAIL_WINDOW_CHAPTERS = 4
    }
}
