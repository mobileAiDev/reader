package com.ldp.reader.sourceengine.content.v8

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

class V8TopMeanTest {
    @Test
    fun boundedTopMeanMatchesLegacySortingBitForBit() {
        val cases = mutableListOf(
            doubleArrayOf(),
            doubleArrayOf(0.0),
            doubleArrayOf(0.1, 0.9, 0.3, 0.7),
            doubleArrayOf(0.5, 0.5, 0.5, 0.5),
            doubleArrayOf(-0.0, 0.0, Double.MIN_VALUE, 1.0),
            doubleArrayOf(Double.NaN, 0.25, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
        )
        val random = Random(0x5A17)
        repeat(1_000) {
            cases += DoubleArray(random.nextInt(1, 129)) {
                random.nextInt(0, 10_001) / 10_000.0
            }
        }

        cases.forEach { values ->
            listOf(1, 4, 8, 16, values.size.coerceAtLeast(1), values.size + 3)
                .distinct()
                .forEach { limit ->
                    assertEquals(
                        "size=${values.size}, limit=$limit",
                        legacyTopMean(values, limit).toBits(),
                        v8TopMeanBounded(values, limit).toBits()
                    )
                }
        }
    }

    private fun legacyTopMean(values: DoubleArray, limit: Int): Double {
        if (values.isEmpty()) return 0.0
        return values.toList().sortedDescending().take(limit).average()
    }
}
