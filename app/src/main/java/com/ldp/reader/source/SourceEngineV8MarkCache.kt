package com.ldp.reader.source

import com.google.gson.Gson
import com.ldp.reader.sourceengine.content.v8.V8ChapterQualityType
import com.ldp.reader.sourceengine.content.v8.V8ChapterMarkResult
import com.ldp.reader.sourceengine.content.v8.V8ChapterMarkState
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
        return runCatching {
            val entry = gson.fromJson(file.readText(Charsets.UTF_8), Entry::class.java) ?: return null
            if (entry.schemaVersion != SCHEMA_VERSION || entry.identity != identity) return null
            CachedMarks(
                identity = entry.identity,
                sourceLabel = entry.sourceLabel,
                marks = entry.marks,
                contentDigest = entry.contentDigest,
                targetChapterIndexes = entry.targetChapterIndexes,
                createdAtMs = entry.createdAtMs
            )
        }.getOrNull()
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
            ?.mapNotNull { file ->
                runCatching {
                    val entry = gson.fromJson(file.readText(Charsets.UTF_8), Entry::class.java) ?: return@runCatching null
                    if (entry.schemaVersion != SCHEMA_VERSION) return@runCatching null
                    Summary(
                        identity = entry.identity,
                        sourceLabel = entry.sourceLabel,
                        createdAtMs = entry.createdAtMs,
                        marks = entry.marks.size
                    )
                }.getOrNull()
            }
            ?.sortedByDescending { summary -> summary.createdAtMs }
            .orEmpty()
    }

    fun save(
        identity: Identity,
        sourceLabel: String,
        marks: List<V8ChapterMarkResult>,
        contentDigest: String,
        targetChapterIndexes: List<Int>
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
                marks = marks
            )
            file.writeText(gson.toJson(entry), Charsets.UTF_8)
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
        val createdAtMs: Long
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
        val marks: List<V8ChapterMarkResult>
    )

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
            val isCurrent = runCatching {
                gson.fromJson(file.readText(Charsets.UTF_8), SchemaProbe::class.java)?.schemaVersion == SCHEMA_VERSION
            }.getOrDefault(false)
            if (!isCurrent) file.delete()
        }
    }

    private data class SchemaProbe(
        val schemaVersion: Int?
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
