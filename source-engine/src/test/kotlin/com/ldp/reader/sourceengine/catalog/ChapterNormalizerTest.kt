package com.ldp.reader.sourceengine.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterNormalizerTest {
    @Test
    fun normalizesEnglishChapterNumberVariantsToSameOrdinalKey() {
        val normalizer = ChapterNormalizer()

        assertEquals("n:1", normalizer.normalize("1.Chapter 1").key)
        assertEquals("n:1", normalizer.normalize("1 Chapter 1").key)
        assertEquals("n:1", normalizer.normalize("Chapter 1").key)
    }
}
