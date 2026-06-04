package com.ldp.reader.media

import com.ldp.reader.media.MediaSourceBookDetail
import kotlin.math.max

internal object MediaCatalogCompleteness {
    fun expectedCount(detail: MediaSourceBookDetail): Int {
        return expectedCount(detail.lastChapter, detail.intro)
    }

    fun expectedCount(latest: String, intro: String): Int {
        return expectedCountFrom(latest)
            ?: expectedCountFrom(intro)
            ?: 0
    }

    fun shouldPreferFullerCatalog(
        expectedCount: Int,
        readableChapterCount: Int,
        fullerChapterCount: Int
    ): Boolean {
        if (fullerChapterCount <= readableChapterCount) return false
        if (readableChapterCount <= 12 && fullerChapterCount >= max(12, readableChapterCount * 2)) {
            return true
        }
        if (expectedCount >= 30) {
            return readableChapterCount < expectedCount * 0.5 &&
                fullerChapterCount >= max(readableChapterCount * 2, expectedCount / 4)
        }
        return readableChapterCount <= 12 &&
            fullerChapterCount >= max(30, readableChapterCount * 3)
    }

    private fun expectedCountFrom(value: String): Int? {
        return COUNT_PATTERN.findAll(value)
            .mapNotNull { match -> match.groupValues.getOrNull(1)?.toIntOrNull() }
            .filter { it in 2..20_000 }
            .maxOrNull()
    }

    private val COUNT_PATTERN = Regex("""(\d{1,5})\s*(?:话|集|章|回|卷)""")
}
