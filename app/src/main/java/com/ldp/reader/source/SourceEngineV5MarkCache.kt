package com.ldp.reader.source

import com.google.gson.Gson
import com.ldp.reader.sourceengine.content.v5.ChapterQualityType
import com.ldp.reader.sourceengine.content.v5.V5ChapterMarkResult
import com.ldp.reader.sourceengine.content.v5.V5ChapterMarkState
import com.ldp.reader.utils.Constant
import java.io.File
import java.nio.charset.Charset
import java.security.MessageDigest

internal class SourceEngineV5MarkCache(
    private val rootDirectory: () -> File = { File(Constant.BOOK_CACHE_PATH, CACHE_DIR_NAME) }
) {
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
                createdAtMs = entry.createdAtMs
            )
        }.getOrNull()
    }

    fun save(
        identity: Identity,
        sourceLabel: String,
        marks: List<V5ChapterMarkResult>
    ): Boolean {
        val file = fileFor(identity)
        return runCatching {
            file.parentFile?.mkdirs()
            val entry = Entry(
                schemaVersion = SCHEMA_VERSION,
                identity = identity,
                sourceLabel = sourceLabel,
                createdAtMs = System.currentTimeMillis(),
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
        val marks: List<V5ChapterMarkResult>,
        val createdAtMs: Long
    )

    private data class Entry(
        val schemaVersion: Int,
        val identity: Identity,
        val sourceLabel: String,
        val createdAtMs: Long,
        val marks: List<V5ChapterMarkResult>
    )

    private fun md5(value: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charset.defaultCharset()))
        return bytes.joinToString("") { byte -> "%02x".format(byte) }
    }

    private companion object {
        private const val CACHE_DIR_NAME = "source_engine_v5_marks"
        private const val SCHEMA_VERSION = SOURCE_ENGINE_INTEGRITY_MARK_SCHEMA_VERSION
        private val gson = Gson()
    }
}

internal object SourceEngineV5MarkCachePolicy {
    fun shouldSave(
        marks: List<V5ChapterMarkResult>,
        inputLengthsByChapterIndex: Map<Int, Int>
    ): Boolean {
        return fragileThinInconclusiveIndexes(marks, inputLengthsByChapterIndex).isEmpty()
    }

    fun fragileThinInconclusiveIndexes(
        marks: List<V5ChapterMarkResult>,
        inputLengthsByChapterIndex: Map<Int, Int>
    ): List<Int> {
        return marks
            .filter { mark ->
                mark.state == V5ChapterMarkState.INCONCLUSIVE &&
                    mark.qualityType == ChapterQualityType.TOO_SHORT_UNCERTAIN &&
                    (inputLengthsByChapterIndex[mark.chapterIndex] ?: 0) < MIN_CACHEABLE_INPUT_CHARS
            }
            .map { mark -> mark.chapterIndex }
    }

    private const val MIN_CACHEABLE_INPUT_CHARS = 120
}
