package com.ldp.reader.source

import com.google.gson.Gson
import com.google.gson.stream.JsonReader
import com.ldp.reader.sourceengine.content.v8.V8ChapterQualityType
import com.ldp.reader.sourceengine.content.v8.V8ChapterMarkResult
import com.ldp.reader.sourceengine.content.v8.V8ChapterMarkState
import com.ldp.reader.sourceengine.content.v8.V8TailBoundarySelector
import com.ldp.reader.utils.Constant
import java.io.File
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.Locale

internal class SourceEngineV8MarkCache(
    private val rootDirectory: () -> File = { File(Constant.BOOK_CACHE_PATH, CACHE_DIR_NAME) }
) {
    init {
        deleteObsoleteCacheDirectories()
        deleteStaleCurrentCacheFiles()
    }

    fun load(identity: Identity): CachedMarks? {
        val file = fileFor(identity)
        if (!file.exists()) return null
        val cached = readCacheFile(file) ?: return null
        return cached.takeIf { entry -> entry.identity == identity }
    }

    fun replayCandidates(identity: Identity): Iterable<CachedMarks> {
        val dir = rootDirectory()
        if (!dir.isDirectory) return emptyList()
        val headers = dir.listFiles { file -> file.isFile && file.extension == "json" }
            ?.mapNotNull { file -> readReplayHeader(file) }
            ?.filter { header -> catalogReplayCompatible(identity, header.identity) }
            ?.sortedByDescending { header -> header.createdAtMs }
            .orEmpty()
        return Iterable {
            headers.asSequence()
                .mapNotNull { header ->
                    readCacheFile(header.file)
                        ?.takeIf { cached -> catalogReplayCompatible(identity, cached.identity) }
                }
                .iterator()
        }
    }

    fun summariesForBook(bookName: String?, author: String?): List<Summary> {
        val normalizedBookName = normalizedIdentityPart(bookName)
        val normalizedAuthor = normalizedIdentityPart(author)
        if (normalizedBookName.isBlank()) return emptyList()
        return summaries()
            .filter { summary ->
                normalizedIdentityPart(summary.identity.bookName) == normalizedBookName &&
                    (
                        normalizedAuthor.isBlank() ||
                            normalizedIdentityPart(summary.identity.author) == normalizedAuthor
                        )
            }
    }

    fun summaries(): List<Summary> {
        val dir = rootDirectory()
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { file -> file.isFile && file.extension == "json" }
            ?.mapNotNull { file -> readSummary(file) }
            ?.sortedByDescending { summary -> summary.createdAtMs }
            .orEmpty()
    }

    fun save(
        identity: Identity,
        sourceLabel: String,
        marks: List<V8ChapterMarkResult>,
        contentDigest: String,
        targetChapterIndexes: List<Int>,
        inputFingerprintsByChapterIndex: Map<Int, InputFingerprint> = emptyMap()
    ): Boolean {
        val file = fileFor(identity)
        return runCatching {
            file.parentFile?.mkdirs()
            val entry = Entry(
                schemaVersion = SCHEMA_VERSION,
                identity = identity,
                sourceLabel = sourceLabel,
                createdAtMs = System.currentTimeMillis(),
                contentDigest = contentDigest,
                targetChapterIndexes = targetChapterIndexes,
                inputFingerprintsByChapterIndex = inputFingerprintsByChapterIndex,
                marks = marks
            )
            file.writer(Charsets.UTF_8).buffered().use { writer ->
                gson.toJson(entry, writer)
            }
            true
        }.getOrDefault(false)
    }

    internal fun fileFor(identity: Identity): File {
        val key = gson.toJson(identity) + "\nversion=$SCHEMA_VERSION"
        return File(rootDirectory(), "${md5(key)}.json")
    }

    data class Identity(
        val sourceBookKey: String,
        val sourceUrl: String,
        val bookUrl: String,
        val bookName: String,
        val author: String,
        val catalogSize: Int,
        val firstTitle: String,
        val lastTitle: String,
        val tailTitleDigest: String
    )

    data class CachedMarks(
        val identity: Identity,
        val sourceLabel: String,
        val marks: List<V8ChapterMarkResult>,
        val contentDigest: String,
        val targetChapterIndexes: List<Int>,
        val inputFingerprintsByChapterIndex: Map<Int, InputFingerprint>,
        val createdAtMs: Long
    )

    data class InputFingerprint(
        val inputDigest: String,
        val normalizedLength: Int,
        val tokenHashes: List<String>
    )

    data class Summary(
        val identity: Identity,
        val sourceLabel: String,
        val createdAtMs: Long,
        val marks: Int
    )

    private data class Entry(
        val schemaVersion: Int,
        val identity: Identity,
        val sourceLabel: String,
        val createdAtMs: Long,
        val contentDigest: String,
        val targetChapterIndexes: List<Int>,
        val inputFingerprintsByChapterIndex: Map<Int, InputFingerprint>?,
        val marks: List<V8ChapterMarkResult>
    )

    private fun readCacheFile(file: File): CachedMarks? {
        return runCatching {
            val entry = file.reader(Charsets.UTF_8).buffered().use { reader ->
                gson.fromJson(reader, Entry::class.java)
            } ?: return null
            if (entry.schemaVersion != SCHEMA_VERSION) return null
            val stableMarks = V8TailBoundarySelector.refreshCachedStableMarks(entry.marks)
            if (stableMarks.none { mark -> mark.state != V8ChapterMarkState.INCONCLUSIVE }) return null
            CachedMarks(
                identity = entry.identity,
                sourceLabel = entry.sourceLabel,
                marks = stableMarks,
                contentDigest = entry.contentDigest,
                targetChapterIndexes = entry.targetChapterIndexes,
                inputFingerprintsByChapterIndex = entry.inputFingerprintsByChapterIndex.orEmpty(),
                createdAtMs = entry.createdAtMs
            )
        }.getOrNull()
    }

    private fun readReplayHeader(file: File): ReplayHeader? {
        return runCatching {
            var schemaVersion: Int? = null
            var identity: Identity? = null
            var createdAtMs: Long? = null
            JsonReader(file.reader(Charsets.UTF_8).buffered()).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "schemaVersion" -> schemaVersion = reader.nextInt()
                        "identity" -> identity = gson.fromJson(reader, Identity::class.java)
                        "createdAtMs" -> createdAtMs = reader.nextLong()
                        else -> reader.skipValue()
                    }
                    if (schemaVersion != null && identity != null && createdAtMs != null) {
                        return@use ReplayHeader(
                            file = file,
                            identity = identity ?: return@use null,
                            createdAtMs = createdAtMs ?: return@use null
                        )
                    }
                }
                null
            }?.takeIf { header -> schemaVersion == SCHEMA_VERSION }
        }.getOrNull()
    }

    private fun readSummary(file: File): Summary? {
        return runCatching {
            var schemaVersion: Int? = null
            var identity: Identity? = null
            var sourceLabel: String? = null
            var createdAtMs = 0L
            val marks = ArrayList<V8ChapterMarkResult>()
            JsonReader(file.reader(Charsets.UTF_8).buffered()).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "schemaVersion" -> schemaVersion = reader.nextInt()
                        "identity" -> identity = gson.fromJson(reader, Identity::class.java)
                        "sourceLabel" -> sourceLabel = reader.nextString()
                        "createdAtMs" -> createdAtMs = reader.nextLong()
                        "marks" -> {
                            reader.beginArray()
                            while (reader.hasNext()) {
                                val mark: V8ChapterMarkResult =
                                    gson.fromJson(reader, V8ChapterMarkResult::class.java)
                                marks.add(mark)
                            }
                            reader.endArray()
                        }
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
            }
            if (schemaVersion != SCHEMA_VERSION) return@runCatching null
            val stableMarks = V8TailBoundarySelector.refreshCachedStableMarks(marks)
            if (stableMarks.none { mark -> mark.state != V8ChapterMarkState.INCONCLUSIVE }) {
                return@runCatching null
            }
            Summary(
                identity = identity ?: return@runCatching null,
                sourceLabel = sourceLabel ?: return@runCatching null,
                createdAtMs = createdAtMs,
                marks = stableMarks.size
            )
        }.getOrNull()
    }

    private fun catalogReplayCompatible(left: Identity, right: Identity): Boolean {
        return normalizedIdentityPart(left.bookName) == normalizedIdentityPart(right.bookName) &&
            (
                normalizedIdentityPart(left.author).isBlank() ||
                    normalizedIdentityPart(right.author).isBlank() ||
                    normalizedIdentityPart(left.author) == normalizedIdentityPart(right.author)
                ) &&
            left.catalogSize == right.catalogSize &&
            normalizedIdentityPart(left.firstTitle) == normalizedIdentityPart(right.firstTitle) &&
            normalizedIdentityPart(left.lastTitle) == normalizedIdentityPart(right.lastTitle) &&
            left.tailTitleDigest == right.tailTitleDigest
    }

    private fun md5(value: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charset.defaultCharset()))
        return bytes.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun normalizedIdentityPart(value: String?): String {
        return value
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.replace(Regex("""\s+"""), "")
            .orEmpty()
    }

    private fun deleteObsoleteCacheDirectories() {
        val cacheRoot = rootDirectory().parentFile ?: return
        cacheRoot.listFiles()?.forEach { dir ->
            if (dir.isDirectory && isObsoleteIntegrityCacheDirectory(dir.name)) {
                dir.deleteRecursively()
            }
        }
    }

    private fun isObsoleteIntegrityCacheDirectory(name: String): Boolean {
        if (name == CACHE_DIR_NAME) return false
        return name.startsWith("source_engine_") &&
            (name.endsWith("_marks") || name.endsWith("_term_stats"))
    }

    private fun deleteStaleCurrentCacheFiles() {
        val dir = rootDirectory()
        if (!dir.isDirectory) return
        dir.listFiles { file -> file.isFile && file.extension == "json" }?.forEach { file ->
            val isCurrent = readSchemaVersion(file) == SCHEMA_VERSION
            if (!isCurrent) file.delete()
        }
    }

    private fun readSchemaVersion(file: File): Int? {
        return runCatching {
            JsonReader(file.reader(Charsets.UTF_8).buffered()).use { reader ->
                var schemaVersion: Int? = null
                reader.beginObject()
                while (reader.hasNext()) {
                    if (reader.nextName() == "schemaVersion") {
                        schemaVersion = reader.nextInt()
                    } else {
                        reader.skipValue()
                    }
                }
                reader.endObject()
                schemaVersion
            }
        }.getOrNull()
    }

    private data class ReplayHeader(
        val file: File,
        val identity: Identity,
        val createdAtMs: Long
    )

    private companion object {
        private const val CACHE_DIR_NAME = "source_engine_v8_marks"
        private const val SCHEMA_VERSION = SOURCE_ENGINE_INTEGRITY_MARK_SCHEMA_VERSION
        private val gson = Gson()
    }
}

internal object SourceEngineV8MarkCachePolicy {
    fun shouldSave(
        marks: List<V8ChapterMarkResult>,
        inputLengthsByChapterIndex: Map<Int, Int>
    ): Boolean {
        return cacheableMarks(marks, inputLengthsByChapterIndex).isNotEmpty()
    }

    fun cacheableMarks(
        marks: List<V8ChapterMarkResult>,
        inputLengthsByChapterIndex: Map<Int, Int>
    ): List<V8ChapterMarkResult> {
        if (marks.none { mark -> mark.state != V8ChapterMarkState.INCONCLUSIVE }) return emptyList()
        val fragileIndexes = fragileThinInconclusiveIndexes(marks, inputLengthsByChapterIndex)
        if (fragileIndexes.isEmpty()) return marks
        val stableMarks = marks.filterNot { mark -> mark.chapterIndex in fragileIndexes }
        return when {
            stableMarks.any { mark -> mark.state.isBadForTail } -> stableMarks
            stableMarks.size >= MIN_STABLE_CLEAN_MARKS -> stableMarks
            else -> emptyList()
        }
    }

    fun fragileThinInconclusiveIndexes(
        marks: List<V8ChapterMarkResult>,
        inputLengthsByChapterIndex: Map<Int, Int>
    ): List<Int> {
        return marks
            .filter { mark ->
                mark.state == V8ChapterMarkState.INCONCLUSIVE &&
                    mark.qualityType == V8ChapterQualityType.TOO_SHORT_UNCERTAIN &&
                    (inputLengthsByChapterIndex[mark.chapterIndex] ?: 0) < MIN_CACHEABLE_INPUT_CHARS
            }
            .map { mark -> mark.chapterIndex }
    }

    private const val MIN_CACHEABLE_INPUT_CHARS = 120
    private const val MIN_STABLE_CLEAN_MARKS = 3
}
