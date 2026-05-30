package com.ldp.reader.sourceengine.content.v8

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sqrt

interface V8SemanticModel {
    fun build(
        referenceTexts: List<String>,
        currentText: String,
        futureTexts: List<String>,
        config: V8PsbmtConfig
    ): V8SemanticSpace
}

interface V8SemanticCacheStatsProvider {
    fun cacheStats(): V8SemanticCacheStats
}

data class V8SemanticCacheStats(
    val memoryHits: Long = 0,
    val diskHits: Long = 0,
    val onnxRuns: Long = 0,
    val diskWrites: Long = 0,
    val diskEvictions: Long = 0
) {
    fun deltaSince(previous: V8SemanticCacheStats): V8SemanticCacheStats {
        return V8SemanticCacheStats(
            memoryHits = memoryHits - previous.memoryHits,
            diskHits = diskHits - previous.diskHits,
            onnxRuns = onnxRuns - previous.onnxRuns,
            diskWrites = diskWrites - previous.diskWrites,
            diskEvictions = diskEvictions - previous.diskEvictions
        )
    }
}

class V8SparseSemanticModel : V8SemanticModel {
    override fun build(
        referenceTexts: List<String>,
        currentText: String,
        futureTexts: List<String>,
        config: V8PsbmtConfig
    ): V8SemanticSpace {
        val referenceWindows = referenceTexts.flatMap { text ->
            v8SlidingWindows(text.take(5_000), config.windowSize, config.windowStride, config.minWindowChars)
        }
        val allWindows = referenceWindows +
            v8SlidingWindows(currentText.take(2_800), config.windowSize, config.windowStride, config.minWindowChars) +
            futureTexts.flatMap { text ->
                v8SlidingWindows(text.take(2_800), config.windowSize, config.windowStride, config.minWindowChars)
            }
        val idf = v8BuildIdf(allWindows, config)
        val referenceVectors = referenceWindows.map { window ->
            v8SparseVector(window, idf, config.semanticMinGram, config.semanticMaxGram)
        }
        return V8SemanticSpace(
            referenceWindows = referenceWindows,
            referenceVectors = referenceVectors,
            idf = idf,
            vectorizer = { text -> v8SparseVector(text, idf, config.semanticMinGram, config.semanticMaxGram) },
            config = config
        )
    }
}

class V8BgeSemanticModel(
    modelFile: File,
    vocabFile: File,
    private val maxTokens: Int = 256,
    maxEmbeddingCacheEntries: Int = 512,
    private val diskCacheDir: File? = null,
    private val maxDiskCacheEntries: Int = 50_000,
    private val maxDiskCacheBytes: Long = 512L * 1024L * 1024L,
    private val cacheNamespace: String = "v8-bge-embedding-v1"
) : V8SemanticModel, V8SemanticCacheStatsProvider, AutoCloseable {
    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = environment.createSession(modelFile.absolutePath, OrtSession.SessionOptions())
    private val tokenizer = V8BgeTokenizer(vocabFile.readLines(Charsets.UTF_8))
    private val modelFingerprint = listOf(modelFile, File(modelFile.parentFile, "${modelFile.name}_data"), vocabFile)
        .filter { file -> file.isFile }
        .joinToString("|") { file -> "${file.name}:${file.length()}:${file.lastModified()}" }
    private val embeddingCache = object : LinkedHashMap<String, V8SparseVector>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V8SparseVector>?): Boolean {
            return size > maxEmbeddingCacheEntries
        }
    }
    private var diskCacheWriteCount = 0

    val inputNames: Set<String>
        get() = session.inputNames

    val outputNames: Set<String>
        get() = session.outputNames

    override fun build(
        referenceTexts: List<String>,
        currentText: String,
        futureTexts: List<String>,
        config: V8PsbmtConfig
    ): V8SemanticSpace {
        val referenceWindows = referenceTexts.flatMap { text ->
            v8SlidingWindows(text.take(5_000), config.windowSize, config.windowStride, config.minWindowChars)
        }
        val allWindows = referenceWindows +
            v8SlidingWindows(currentText.take(2_800), config.windowSize, config.windowStride, config.minWindowChars) +
            futureTexts.flatMap { text ->
                v8SlidingWindows(text.take(2_800), config.windowSize, config.windowStride, config.minWindowChars)
            }
        val idf = v8BuildIdf(allWindows, config)
        val referenceVectors = referenceWindows.map(::embed)
        return V8SemanticSpace(
            referenceWindows = referenceWindows,
            referenceVectors = referenceVectors,
            idf = idf,
            vectorizer = ::embed,
            config = config
        )
    }

    fun embed(text: String): V8SparseVector {
        synchronized(embeddingCache) {
            embeddingCache[text]?.let { cached ->
                memoryHits += 1
                return cached
            }
        }
        val vector = readDiskEmbedding(text)?.also { diskHits += 1 } ?: runBge(text).also { result ->
            onnxRuns += 1
            writeDiskEmbedding(text, result)
        }
        synchronized(embeddingCache) {
            embeddingCache[text] = vector
        }
        return vector
    }

    override fun close() {
        session.close()
    }

    private fun readEmbedding(value: Any): FloatArray {
        @Suppress("UNCHECKED_CAST")
        return when (value) {
            is Array<*> -> {
                val first = value.firstOrNull()
                when (first) {
                    is FloatArray -> first
                    is Array<*> -> clsPool(first as Array<FloatArray>)
                    else -> error("Unsupported BGE output tensor value: ${value.javaClass.name}")
                }
            }
            is FloatArray -> value
            else -> error("Unsupported BGE output tensor value: ${value.javaClass.name}")
        }
    }

    private fun clsPool(tokens: Array<FloatArray>): FloatArray {
        return tokens.firstOrNull() ?: FloatArray(0)
    }

    private fun denseToSparse(values: FloatArray): V8SparseVector {
        var norm = 0.0
        values.forEach { value -> norm += value.toDouble() * value.toDouble() }
        val divisor = sqrt(norm).takeIf { it > 0.0 } ?: return emptyMap()
        val result = LinkedHashMap<String, Double>()
        values.forEachIndexed { index, value ->
            val normalized = value.toDouble() / divisor
            if (normalized != 0.0) result["bge:$index"] = normalized
        }
        return result
    }

    private fun Map<String, OnnxTensor>.useAll(block: () -> V8SparseVector): V8SparseVector {
        try {
            return block()
        } finally {
            values.forEach { tensor -> tensor.close() }
        }
    }

    private fun readDiskEmbedding(text: String): V8SparseVector? {
        val file = diskCacheFile(text) ?: return null
        if (!file.isFile) return null
        return runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                if (input.readInt() != DISK_CACHE_MAGIC) return@runCatching null
                if (input.readInt() != DISK_CACHE_VERSION) return@runCatching null
                val size = input.readInt()
                if (size <= 0 || size > MAX_REASONABLE_BGE_DIMENSIONS) return@runCatching null
                val values = LinkedHashMap<String, Double>()
                repeat(size) { index ->
                    val value = input.readDouble()
                    if (value != 0.0) values["bge:$index"] = value
                }
                file.setLastModified(System.currentTimeMillis())
                values
            }
        }.getOrNull()
    }

    private fun writeDiskEmbedding(text: String, vector: V8SparseVector) {
        val file = diskCacheFile(text) ?: return
        runCatching {
            file.parentFile?.mkdirs()
            val maxIndex = vector.keys
                .asSequence()
                .mapNotNull { key -> key.removePrefix("bge:").toIntOrNull() }
                .maxOrNull()
                ?: return
            if (maxIndex + 1 > MAX_REASONABLE_BGE_DIMENSIONS) return
            val temp = File(file.parentFile, "${file.name}.tmp")
            DataOutputStream(temp.outputStream().buffered()).use { output ->
                output.writeInt(DISK_CACHE_MAGIC)
                output.writeInt(DISK_CACHE_VERSION)
                output.writeInt(maxIndex + 1)
                for (index in 0..maxIndex) {
                    output.writeDouble(vector["bge:$index"] ?: 0.0)
                }
            }
            if (file.exists()) file.delete()
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
            diskCacheWriteCount += 1
            diskWrites += 1
            if (diskCacheWriteCount % DISK_CACHE_EVICT_INTERVAL == 0) evictDiskCache()
        }
    }

    private fun diskCacheFile(text: String): File? {
        val dir = diskCacheDir ?: return null
        val key = md5(
            buildString {
                append(cacheNamespace).append('\n')
                append("model=").append(modelFingerprint).append('\n')
                append("maxTokens=").append(maxTokens).append('\n')
                append("length=").append(text.length).append('\n')
                append(text)
            }
        )
        return File(dir, "${key}.bin")
    }

    private fun evictDiskCache() {
        val dir = diskCacheDir ?: return
        val files = dir.listFiles { file -> file.isFile && file.extension == "bin" }?.toMutableList() ?: return
        var totalBytes = files.sumOf { file -> file.length() }
        if (files.size <= maxDiskCacheEntries && totalBytes <= maxDiskCacheBytes) return
        files.sortBy { file -> file.lastModified() }
        while (files.isNotEmpty() && (files.size > maxDiskCacheEntries || totalBytes > maxDiskCacheBytes)) {
            val file = files.removeAt(0)
            val length = file.length()
            if (file.delete()) {
                totalBytes -= length
                diskEvictions += 1
            }
        }
    }

    private fun runBge(text: String): V8SparseVector {
        val encoded = tokenizer.encode(text, maxTokens)
        val inputs = LinkedHashMap<String, OnnxTensor>()
        inputs["input_ids"] = OnnxTensor.createTensor(environment, arrayOf(encoded.inputIds))
        inputs["attention_mask"] = OnnxTensor.createTensor(environment, arrayOf(encoded.attentionMask))
        if (session.inputNames.contains("token_type_ids")) {
            inputs["token_type_ids"] = OnnxTensor.createTensor(environment, arrayOf(LongArray(encoded.inputIds.size)))
        }
        return inputs.useAll {
            session.run(inputs).use { output ->
                val names = session.outputNames
                val selectedName = when {
                    names.contains("sentence_embedding") -> "sentence_embedding"
                    names.contains("pooler_output") -> "pooler_output"
                    names.contains("last_hidden_state") -> "last_hidden_state"
                    else -> names.first()
                }
                denseToSparse(readEmbedding(output[selectedName].get().value))
            }
        }
    }

    override fun cacheStats(): V8SemanticCacheStats {
        return V8SemanticCacheStats(
            memoryHits = memoryHits,
            diskHits = diskHits,
            onnxRuns = onnxRuns,
            diskWrites = diskWrites,
            diskEvictions = diskEvictions
        )
    }

    private fun md5(value: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { byte -> "%02x".format(byte) }
    }

    private companion object {
        private const val DISK_CACHE_MAGIC = 0x56384247
        private const val DISK_CACHE_VERSION = 1
        private const val DISK_CACHE_EVICT_INTERVAL = 512
        private const val MAX_REASONABLE_BGE_DIMENSIONS = 4096
    }

    @Volatile
    private var memoryHits: Long = 0
    @Volatile
    private var diskHits: Long = 0
    @Volatile
    private var onnxRuns: Long = 0
    @Volatile
    private var diskWrites: Long = 0
    @Volatile
    private var diskEvictions: Long = 0
}

class V8SemanticSpace(
    val referenceWindows: List<String>,
    private val referenceVectors: List<V8SparseVector>,
    val idf: Map<String, Double>,
    private val vectorizer: (String) -> V8SparseVector,
    private val config: V8PsbmtConfig
) {
    private val vectorCache = LinkedHashMap<String, V8SparseVector>()

    fun referenceSupport(segment: String): Double {
        val vectors = segmentVectors(segment)
        if (vectors.isEmpty() || referenceVectors.isEmpty()) return 0.0
        return v8Median(vectors.map { vector ->
            referenceVectors.map { reference -> v8Cosine(vector, reference) }.topMean(8)
        })
    }

    fun referenceSelfSupport(window: String, referenceWindowIndex: Int): Double {
        val vector = vector(window)
        val values = referenceVectors.mapIndexedNotNull { index, reference ->
            if (index == referenceWindowIndex) null else v8Cosine(vector, reference)
        }
        return values.topMean(8)
    }

    fun segmentVectors(text: String): List<V8SparseVector> {
        return v8SlidingWindows(text, config.windowSize, config.windowStride, config.minWindowChars)
            .map { window -> vector(window) }
    }

    fun crossSupport(left: String, rightVectors: List<V8SparseVector>): Double {
        if (rightVectors.isEmpty()) return 0.0
        val leftScores = segmentVectors(left).map { vector ->
            rightVectors.map { rightVector -> v8Cosine(vector, rightVector) }.topMean(4)
        }
        return v8Median(leftScores)
    }

    private fun vector(text: String): V8SparseVector {
        return vectorCache.getOrPut(text) { vectorizer(text) }
    }
}

class V8IdentitySketch private constructor(
    private val weights: Map<String, Double>,
    private val idf: Map<String, Double>,
    private val config: V8PsbmtConfig
) {
    private val supportCache = LinkedHashMap<String, Double>()

    val size: Int
        get() = weights.size

    fun support(segment: String): Double {
        return supportCache.getOrPut(segment) {
            val grams = v8CharNgramCounts(v8Compact(segment), config.identityMinGram, config.identityMaxGram, chineseOnly = true)
            var shared = 0.0
            var total = 0.0
            grams.forEach { (gram, count) ->
                val base = sqrt(gram.length.toDouble()) * (idf[gram] ?: 0.2) * count
                total += base
                val weight = weights[gram]
                if (weight != null) shared += min(base, weight * count)
            }
            if (total <= 0.0) 0.0 else (shared / total).coerceIn(0.0, 1.0)
        }
    }

    companion object {
        fun build(
            referenceTexts: List<String>,
            idf: Map<String, Double>,
            config: V8PsbmtConfig
        ): V8IdentitySketch {
            val frequency = LinkedHashMap<String, Int>()
            val coverage = LinkedHashMap<String, Int>()
            referenceTexts.forEach { text ->
                val counts = v8CharNgramCounts(v8Compact(text.take(5_000)), config.identityMinGram, config.identityMaxGram, chineseOnly = true)
                counts.forEach { (gram, count) -> frequency[gram] = (frequency[gram] ?: 0) + count }
                counts.keys.forEach { gram -> coverage[gram] = (coverage[gram] ?: 0) + 1 }
            }
            val weights = LinkedHashMap<String, Double>()
            frequency.forEach { (gram, count) ->
                val rarity = idf[gram] ?: 0.2
                val weight = ln(1.0 + count) *
                    ln(1.0 + (coverage[gram] ?: 1)) *
                    rarity *
                    sqrt(gram.length.toDouble())
                if (weight >= config.minIdentityWeight) weights[gram] = weight
            }
            return V8IdentitySketch(weights, idf, config)
        }
    }
}

typealias V8SparseVector = Map<String, Double>

internal fun v8BuildIdf(windows: List<String>, config: V8PsbmtConfig): Map<String, Double> {
    val df = LinkedHashMap<String, Int>()
    windows.forEach { window ->
        v8CharNgramCounts(v8Compact(window), config.semanticMinGram, config.identityMaxGram, chineseOnly = false)
            .keys
            .forEach { gram -> df[gram] = (df[gram] ?: 0) + 1 }
    }
    val documentCount = windows.size.coerceAtLeast(1)
    return df.mapValues { (_, count) -> ln((documentCount + 1.0) / (count + 1.0)) + 0.2 }
}

internal fun v8SlidingWindows(text: String, size: Int, stride: Int, minChars: Int): List<String> {
    val compact = v8Compact(text)
    if (compact.length < minChars) return emptyList()
    if (compact.length <= size) return listOf(compact)
    val windows = ArrayList<String>()
    var start = 0
    while (start + minChars <= compact.length) {
        windows.add(compact.substring(start, min(compact.length, start + size)))
        if (start + size >= compact.length) break
        start += stride
    }
    return windows
}

internal fun v8Median(values: List<Double>): Double {
    if (values.isEmpty()) return 0.0
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2.0
}

private fun v8SparseVector(text: String, idf: Map<String, Double>, minGram: Int, maxGram: Int): V8SparseVector {
    val values = LinkedHashMap<String, Double>()
    v8CharNgramCounts(v8Compact(text), minGram, maxGram, chineseOnly = false).forEach { (gram, count) ->
        values[gram] = (1.0 + ln(count.toDouble())) * (idf[gram] ?: 0.2) * sqrt(gram.length.toDouble())
    }
    return v8Normalize(values)
}

private fun v8Normalize(values: Map<String, Double>): V8SparseVector {
    val norm = sqrt(values.values.sumOf { value -> value * value })
    if (norm <= 0.0) return emptyMap()
    return values.mapValues { (_, value) -> value / norm }
}

private fun v8Cosine(left: V8SparseVector, right: V8SparseVector): Double {
    if (left.isEmpty() || right.isEmpty()) return 0.0
    val smaller = if (left.size <= right.size) left else right
    val larger = if (left.size <= right.size) right else left
    var dot = 0.0
    smaller.forEach { (term, value) -> dot += value * (larger[term] ?: 0.0) }
    return dot.coerceIn(0.0, 1.0)
}

private fun v8CharNgramCounts(text: String, minGram: Int, maxGram: Int, chineseOnly: Boolean): Map<String, Int> {
    val counts = LinkedHashMap<String, Int>()
    if (text.isEmpty()) return counts
    for (size in minGram..maxGram) {
        if (text.length < size) continue
        for (index in 0..text.length - size) {
            val gram = text.substring(index, index + size)
            if (chineseOnly && !gram.any { char -> char in '\u4e00'..'\u9fff' }) continue
            counts[gram] = (counts[gram] ?: 0) + 1
        }
    }
    return counts
}

private fun v8Compact(text: String): String {
    return text
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .filterNot { char -> char.isWhitespace() }
}

private fun List<Double>.topMean(limit: Int): Double {
    if (isEmpty()) return 0.0
    return sortedDescending().take(limit).average()
}

private class V8BgeTokenizer(vocabLines: List<String>) {
    private val vocab = vocabLines
        .filter { line -> line.isNotBlank() }
        .mapIndexed { index, token -> token to index.toLong() }
        .toMap()

    private val clsId = requireId("[CLS]")
    private val sepId = requireId("[SEP]")
    private val unkId = requireId("[UNK]")
    private val padId = requireId("[PAD]")

    fun encode(text: String, maxTokens: Int): V8BgeEncoding {
        require(maxTokens >= 8) { "maxTokens must be at least 8" }
        val tokenIds = ArrayList<Long>()
        tokenIds.add(clsId)
        basicTokens(text).forEach { token ->
            wordPiece(token).forEach pieceLoop@{ piece ->
                if (tokenIds.size >= maxTokens - 1) return@pieceLoop
                tokenIds.add(vocab[piece] ?: unkId)
            }
        }
        tokenIds.add(sepId)
        while (tokenIds.size < maxTokens) tokenIds.add(padId)
        val firstPadding = tokenIds.indexOf(padId).takeIf { index -> index >= 0 } ?: tokenIds.size
        val attention = LongArray(maxTokens) { index -> if (index < firstPadding) 1L else 0L }
        return V8BgeEncoding(tokenIds.toLongArray(), attention)
    }

    private fun requireId(token: String): Long {
        return requireNotNull(vocab[token]) { "Missing BGE vocab token: $token" }
    }

    private fun basicTokens(text: String): List<String> {
        val tokens = ArrayList<String>()
        val buffer = StringBuilder()
        fun flush() {
            if (buffer.isNotEmpty()) {
                tokens.add(buffer.toString().lowercase(Locale.ROOT))
                buffer.setLength(0)
            }
        }
        text.forEach { char ->
            when {
                char.isWhitespace() -> flush()
                char.isCjk() -> {
                    flush()
                    tokens.add(char.toString())
                }
                char.isPunctuation() -> {
                    flush()
                    tokens.add(char.toString())
                }
                else -> buffer.append(char)
            }
        }
        flush()
        return tokens
    }

    private fun wordPiece(token: String): List<String> {
        if (vocab.containsKey(token)) return listOf(token)
        val pieces = ArrayList<String>()
        var start = 0
        while (start < token.length) {
            var end = token.length
            var current: String? = null
            while (start < end) {
                val piece = token.substring(start, end).let { value ->
                    if (start == 0) value else "##$value"
                }
                if (vocab.containsKey(piece)) {
                    current = piece
                    break
                }
                end -= 1
            }
            if (current == null) return listOf("[UNK]")
            pieces.add(current)
            start = end
        }
        return pieces
    }

    private fun Char.isCjk(): Boolean {
        return this in '\u4e00'..'\u9fff'
    }

    private fun Char.isPunctuation(): Boolean {
        return when {
            this in '!'..'/' -> true
            this in ':'..'@' -> true
            this in '['..'`' -> true
            this in '{'..'~' -> true
            this in '\u3000'..'\u303f' -> true
            this in '\uff00'..'\uffef' -> true
            else -> false
        }
    }
}

private data class V8BgeEncoding(
    val inputIds: LongArray,
    val attentionMask: LongArray
)
