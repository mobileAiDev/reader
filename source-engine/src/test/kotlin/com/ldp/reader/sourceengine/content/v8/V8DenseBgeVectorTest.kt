package com.ldp.reader.sourceengine.content.v8

import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class V8DenseBgeVectorTest {
    @Test
    fun primitiveDenseStoragePreservesLegacyMapContract() {
        val input = floatArrayOf(3.0f, 0.0f, -4.0f, Float.MIN_VALUE)
        val legacy = legacyNormalizedMap(input)
        val optimized = v8NormalizedDenseBgeVector(input)

        assertTrue(optimized is V8DenseBgeVector)
        assertEquals(legacy, optimized)
        assertEquals(optimized, legacy)
        assertEquals(legacy.hashCode(), optimized.hashCode())
        assertTrue(optimized.entries.contains(legacy.entries.first()))
        assertEquals(legacy.keys.toList(), optimized.keys.toList())
        assertEquals(legacy.values.toList(), optimized.values.toList())
        assertEquals(legacy["bge:2"], optimized["bge:2"])
        assertNull(optimized["bge:1"])
        assertNull(optimized["bge:01"])
        assertNull(optimized["other:2"])
        assertEquals(input.size * Double.SIZE_BYTES, (optimized as V8DenseBgeVector).retainedValueBytes)
    }

    @Test
    fun optimizedCosineIsBitEquivalentToLegacyMapCalculation() {
        val leftValues = floatArrayOf(3.0f, 0.0f, 4.0f, 1.0f, 2.0f)
        val rightValues = floatArrayOf(6.0f, 2.0f, 8.0f, 1.0f, 0.5f)
        val legacyLeft = legacyNormalizedMap(leftValues)
        val legacyRight = legacyNormalizedMap(rightValues)
        val denseLeft = v8NormalizedDenseBgeVector(leftValues)
        val denseRight = v8NormalizedDenseBgeVector(rightValues)
        val expectedBits = legacyCosine(legacyLeft, legacyRight).toBits()

        assertEquals(expectedBits, v8Cosine(denseLeft, denseRight).toBits())
        assertEquals(expectedBits, v8Cosine(denseLeft, legacyRight).toBits())
        assertEquals(expectedBits, v8Cosine(legacyLeft, denseRight).toBits())
    }

    @Test
    fun fiveHundredTwelveDimensionsRetainOnlyFourKiBOfNumericPayload() {
        val optimized = v8NormalizedDenseBgeVector(
            FloatArray(512) { index -> (index + 1).toFloat() }
        ) as V8DenseBgeVector

        assertEquals(512, optimized.dimensions)
        assertEquals(512, optimized.size)
        assertEquals(4 * 1024, optimized.retainedValueBytes)
    }

    @Test
    fun compactDiskCacheRoundTripsAndMigratesV1WithoutAnotherOnnxRun() {
        val modelDir = firstExistingDirectory(
            "app/src/main/assets/bge-small-zh-v1.5-onnx",
            "../app/src/main/assets/bge-small-zh-v1.5-onnx"
        )
        val modelFile = File(modelDir, "model_quantized.onnx")
        val modelDataFile = File(modelDir, "model_quantized.onnx_data")
        val vocabFile = File(modelDir, "vocab.txt")
        assumeTrue(
            "missing BGE test assets: ${modelDir.absolutePath}",
            modelFile.isFile && modelDataFile.isFile && vocabFile.isFile
        )

        val cacheDir = Files.createTempDirectory("v8-dense-bge-cache").toFile()
        try {
            val first = V8BgeSemanticModel(
                modelFile = modelFile,
                vocabFile = vocabFile,
                maxTokens = 160,
                maxEmbeddingCacheEntries = 1,
                diskCacheDir = cacheDir
            ).use { model ->
                model.embed("方夕收起阵旗，沿着青山洞府缓步而行。").also {
                    assertTrue(it is V8DenseBgeVector)
                    assertEquals(1L, model.cacheStats().onnxRuns)
                    assertEquals(1L, model.cacheStats().diskWrites)
                    assertEquals(1, model.cacheStats().memoryEntries)
                    assertEquals(4L * 1024L, model.cacheStats().memoryValueBytes)
                }
            }
            val cacheFile = cacheDir.listFiles()?.single()
            assertEquals(4L * 1024L, cacheFile?.length())

            val restored = V8BgeSemanticModel(
                modelFile = modelFile,
                vocabFile = vocabFile,
                maxTokens = 160,
                maxEmbeddingCacheEntries = 1,
                diskCacheDir = cacheDir
            ).use { model ->
                model.embed("方夕收起阵旗，沿着青山洞府缓步而行。").also {
                    assertEquals(1L, model.cacheStats().diskHits)
                    assertEquals(0L, model.cacheStats().onnxRuns)
                }
            }

            assertEquals(first, restored)
            assertEquals(1.0.toBits(), v8Cosine(first, restored).toBits())

            writeLegacyV1Cache(cacheFile!!, first as V8DenseBgeVector)
            assertEquals(4L * 1024L + 12L, cacheFile.length())
            val migrated = V8BgeSemanticModel(
                modelFile = modelFile,
                vocabFile = vocabFile,
                maxTokens = 160,
                maxEmbeddingCacheEntries = 1,
                diskCacheDir = cacheDir
            ).use { model ->
                model.embed("方夕收起阵旗，沿着青山洞府缓步而行。").also {
                    assertEquals(1L, model.cacheStats().diskHits)
                    assertEquals(0L, model.cacheStats().onnxRuns)
                    assertEquals(1L, model.cacheStats().diskMigrations)
                }
            }

            assertEquals(first, migrated)
            assertEquals(1.0.toBits(), v8Cosine(first, migrated).toBits())
            assertEquals(4L * 1024L, cacheFile.length())
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    private fun legacyNormalizedMap(values: FloatArray): Map<String, Double> {
        var norm = 0.0
        values.forEach { value -> norm += value.toDouble() * value.toDouble() }
        val divisor = sqrt(norm)
        return buildMap {
            values.forEachIndexed { index, value ->
                val normalized = value.toDouble() / divisor
                if (normalized != 0.0) put("bge:$index", normalized)
            }
        }
    }

    private fun legacyCosine(left: Map<String, Double>, right: Map<String, Double>): Double {
        val smaller = if (left.size <= right.size) left else right
        val larger = if (left.size <= right.size) right else left
        var dot = 0.0
        smaller.forEach { (term, value) -> dot += value * (larger[term] ?: 0.0) }
        return dot.coerceIn(0.0, 1.0)
    }

    private fun firstExistingDirectory(vararg paths: String): File {
        return paths.map(::File).firstOrNull { file -> file.isDirectory } ?: File(paths.first())
    }

    private fun writeLegacyV1Cache(file: File, vector: V8DenseBgeVector) {
        DataOutputStream(file.outputStream().buffered()).use { output ->
            output.writeInt(0x56384247)
            output.writeInt(1)
            output.writeInt(vector.dimensions)
            for (index in 0 until vector.dimensions) {
                output.writeDouble(vector.valueAt(index))
            }
        }
    }
}
