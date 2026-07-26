package com.ldp.reader.sourceengine.content.v8

import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

class V8CharGramEquivalenceTest {
    @Test
    fun directCountingIsBitEquivalentToLegacyMaterializedGrams() {
        val random = Random(20260726)
        val alphabet = charArrayOf(
            '天', '地', '玄', '黄', '中', '文', '章', '节',
            'a', 'b', 'C', '1', '9', ' ', '\n', '\t', '，', '。', '!'
        )
        val samples = buildList {
            add("")
            add("abc 123")
            add("天地玄黄")
            add("天 地\n玄\t黄")
            add("abc中def中文123")
            add("中中中中中中")
            add("🙂中🙂文🙂")
            repeat(500) {
                add(
                    buildString {
                        repeat(random.nextInt(0, 301)) {
                            append(alphabet[random.nextInt(alphabet.size)])
                        }
                    }
                )
            }
        }

        samples.forEachIndexed { sampleIndex, text ->
            val legacyVector = legacyCharVector(text)
            val optimizedVector = v8CharVector(text)

            assertEquals("keys sample=$sampleIndex", legacyVector.keys.toList(), optimizedVector.keys.toList())
            legacyVector.forEach { (gram, expected) ->
                assertEquals(
                    "vector sample=$sampleIndex gram=$gram",
                    expected.toBits(),
                    optimizedVector.getValue(gram).toBits()
                )
            }
            assertEquals(
                "repeat ratio sample=$sampleIndex",
                legacyRepeatedNgramRatio(text).toBits(),
                v8RepeatedNgramRatio(text).toBits()
            )
        }
    }

    private fun legacyCharVector(text: String): Map<String, Double> {
        val counts = LinkedHashMap<String, Double>()
        legacyCharGrams(text, 2, 4).forEach { gram ->
            counts[gram] = (counts[gram] ?: 0.0) + 1.0
        }
        val norm = sqrt(counts.values.sumOf { value -> value * value })
        if (norm <= 0.0) return emptyMap()
        return counts.mapValues { (_, value) -> value / norm }
    }

    private fun legacyRepeatedNgramRatio(text: String): Double {
        val grams = legacyCharGrams(text, 2, 4)
        if (grams.isEmpty()) return 1.0
        val counts = LinkedHashMap<String, Int>()
        grams.forEach { gram -> counts[gram] = (counts[gram] ?: 0) + 1 }
        val repeated = grams.count { gram -> (counts[gram] ?: 0) > 1 }
        return repeated.toDouble() / grams.size
    }

    private fun legacyCharGrams(text: String, minSize: Int, maxSize: Int): List<String> {
        val compact = text.filterNot { char -> char.isWhitespace() }
        val grams = ArrayList<String>()
        for (size in minSize..maxSize) {
            if (compact.length < size) continue
            for (index in 0..compact.length - size) {
                val gram = compact.substring(index, index + size)
                if (gram.any { char -> char in '\u4e00'..'\u9fff' }) grams.add(gram)
            }
        }
        return grams
    }
}
