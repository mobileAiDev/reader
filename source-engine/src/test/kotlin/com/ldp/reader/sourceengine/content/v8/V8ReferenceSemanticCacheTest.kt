package com.ldp.reader.sourceengine.content.v8

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class V8ReferenceSemanticCacheTest {
    @Test
    fun sameReferenceTextsReuseBitEquivalentSemanticCalibration() {
        val config = V8PsbmtConfig(
            windowSize = 8,
            windowStride = 5,
            minWindowChars = 4
        )
        var vectorizations = 0
        val vectorizer = { text: String ->
            vectorizations += 1
            testVector(text)
        }
        val cache = V8ReferenceSemanticCache(vectorizer)
        val references = listOf(
            "青山洞府阵旗灵石缓步而行",
            "坊市丹炉符箓飞舟悄然升空",
            "宗门长老剑光护山大阵开启"
        )

        val first = cache.getOrBuild(references, config)
        val vectorizationsAfterFirstBuild = vectorizations
        val second = cache.getOrBuild(references.toList(), config)

        assertSame(first, second)
        assertEquals(vectorizationsAfterFirstBuild, vectorizations)
        assertTrue(first.referenceWindows.isNotEmpty())
        assertEquals(first.referenceWindows.size, first.referenceVectors.size)
        assertEquals(first.referenceWindows.size, first.referenceSelfSupports.size)

        val legacySpace = V8SemanticSpace(
            referenceWindows = first.referenceWindows,
            referenceVectors = first.referenceVectors,
            idf = emptyMap(),
            vectorizer = vectorizer,
            config = config
        )
        val cachedSpace = V8SemanticSpace(
            referenceWindows = first.referenceWindows,
            referenceVectors = first.referenceVectors,
            idf = emptyMap(),
            vectorizer = vectorizer,
            config = config,
            referenceSelfSupports = first.referenceSelfSupports
        )
        val beforeCachedCalibration = vectorizations
        val cachedValues = first.referenceWindows.mapIndexed { index, window ->
            cachedSpace.referenceSelfSupport(window, index)
        }
        assertEquals(beforeCachedCalibration, vectorizations)

        first.referenceWindows.forEachIndexed { index, window ->
            assertEquals(
                legacySpace.referenceSelfSupport(window, index).toBits(),
                cachedValues[index].toBits()
            )
        }
    }

    @Test
    fun changedReferenceOrWindowConfigInvalidatesTheSingleEntryCache() {
        val baseConfig = V8PsbmtConfig(
            windowSize = 8,
            windowStride = 5,
            minWindowChars = 4
        )
        var vectorizations = 0
        val cache = V8ReferenceSemanticCache { text ->
            vectorizations += 1
            testVector(text)
        }
        val firstReferences = listOf(
            "青山洞府阵旗灵石缓步而行",
            "坊市丹炉符箓飞舟悄然升空"
        )
        val secondReferences = listOf(
            "青山洞府阵旗灵石缓步而行",
            "刑警审讯监控录像案件编号"
        )

        val first = cache.getOrBuild(firstReferences, baseConfig)
        val afterFirst = vectorizations
        val changedReference = cache.getOrBuild(secondReferences, baseConfig)
        val afterChangedReference = vectorizations
        val changedConfig = cache.getOrBuild(
            secondReferences,
            baseConfig.copy(windowStride = 4)
        )
        val afterChangedConfig = vectorizations
        val evictedFirst = cache.getOrBuild(firstReferences, baseConfig)

        assertNotSame(first, changedReference)
        assertNotSame(changedReference, changedConfig)
        assertNotSame(first, evictedFirst)
        assertTrue(afterChangedReference > afterFirst)
        assertTrue(afterChangedConfig > afterChangedReference)
        assertTrue(vectorizations > afterChangedConfig)
        assertEquals(1, cache.cachedEntryCount())
    }

    private fun testVector(text: String): V8SparseVector {
        val shared = text.count { char -> char in '\u4e00'..'\u9fff' }.toDouble()
        return linkedMapOf(
            "shared" to shared / 32.0,
            "first:${text.firstOrNull()}" to 0.5,
            "last:${text.lastOrNull()}" to 0.25
        )
    }
}
