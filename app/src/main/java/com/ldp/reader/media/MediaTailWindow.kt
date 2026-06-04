package com.ldp.reader.media

internal data class MediaTailWindowHit<T>(
    val item: T,
    val itemCount: Int,
    val offsetFromLatest: Int
)

internal object MediaTailWindow {
    const val DEFAULT_SIZE = 5

    fun <T> latestFirst(items: List<T>, maxCount: Int = DEFAULT_SIZE): List<T> {
        if (items.isEmpty() || maxCount <= 0) return emptyList()
        return items.takeLast(maxCount).asReversed()
    }

    fun <T> firstUsable(
        items: List<T>,
        maxCount: Int = DEFAULT_SIZE,
        itemCount: (T) -> Int
    ): MediaTailWindowHit<T>? {
        latestFirst(items, maxCount).forEachIndexed { index, item ->
            val count = runCatching { itemCount(item) }.getOrDefault(0)
            if (count > 0) {
                return MediaTailWindowHit(
                    item = item,
                    itemCount = count,
                    offsetFromLatest = index
                )
            }
        }
        return null
    }

    fun <T> firstUsableLatestThenStart(
        items: List<T>,
        maxTailCount: Int = DEFAULT_SIZE,
        maxStartCount: Int = 3,
        itemCount: (T) -> Int
    ): MediaTailWindowHit<T>? {
        if (items.isEmpty()) return null
        val candidates = ArrayList<Pair<Int, T>>()
        val seen = LinkedHashSet<Int>()
        val tailStart = maxOf(0, items.size - maxTailCount)
        for (index in items.lastIndex downTo tailStart) {
            if (seen.add(index)) candidates.add(index to items[index])
        }
        val startEnd = minOf(items.size, maxStartCount)
        for (index in 0 until startEnd) {
            if (seen.add(index)) candidates.add(index to items[index])
        }
        candidates.forEach { (index, item) ->
            val count = runCatching { itemCount(item) }.getOrDefault(0)
            if (count > 0) {
                return MediaTailWindowHit(
                    item = item,
                    itemCount = count,
                    offsetFromLatest = items.lastIndex - index
                )
            }
        }
        return null
    }
}
