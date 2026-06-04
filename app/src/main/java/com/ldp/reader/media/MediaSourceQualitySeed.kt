package com.ldp.reader.media

import com.ldp.reader.media.MediaSourceDefinition
import java.util.Locale

data class MediaSourceQualitySeed(
    private val records: Map<String, SourceSeed>
) {
    fun recordFor(kind: ReaderMediaKind, source: MediaSourceDefinition): SourceSeed? {
        return records[seedKey(kind, source.sourceUrl)]
            ?: records[seedKey(kind, source.sourceName)]
    }

    data class SourceSeed(
        val kind: ReaderMediaKind,
        val sourceUrl: String,
        val sourceName: String,
        val tier: Int,
        val score: Int,
        val note: String
    )

    companion object {
        fun empty(): MediaSourceQualitySeed = MediaSourceQualitySeed(emptyMap())

        fun fromTsv(text: String): MediaSourceQualitySeed {
            val records = LinkedHashMap<String, SourceSeed>()
            text.lineSequence()
                .map { line -> line.trimEnd() }
                .filter { line -> line.isNotBlank() && !line.trimStart().startsWith("#") }
                .filterNot { line -> line.startsWith("kind\t", ignoreCase = true) }
                .forEach { line ->
                    val parts = line.split('\t')
                    if (parts.size < MIN_COLUMNS) return@forEach
                    val kind = ReaderMediaKind.fromSeedKey(parts[0].trim()) ?: return@forEach
                    if (kind == ReaderMediaKind.NOVEL) return@forEach
                    val sourceUrl = parts[1].trim()
                    val sourceName = parts[2].trim()
                    if (sourceUrl.isBlank() && sourceName.isBlank()) return@forEach
                    val seed = SourceSeed(
                        kind = kind,
                        sourceUrl = sourceUrl,
                        sourceName = sourceName,
                        tier = parts[3].toIntOrNull()?.coerceIn(1, 3) ?: DEFAULT_TIER,
                        score = parts[4].toIntOrNull()?.coerceIn(0, 10_000) ?: DEFAULT_SCORE,
                        note = parts.getOrNull(5).orEmpty()
                    )
                    listOf(sourceUrl, sourceName)
                        .filter { it.isNotBlank() }
                        .forEach { key -> records[seedKey(kind, key)] = seed }
                }
            return MediaSourceQualitySeed(records)
        }

        private const val MIN_COLUMNS = 5
        private const val DEFAULT_TIER = 2
        private const val DEFAULT_SCORE = 5_000

        internal fun seedKey(kind: ReaderMediaKind, value: String): String {
            return kind.seedKey + "|" + normalize(value)
        }

        internal fun normalize(value: String): String {
            return value.trim()
                .substringBefore('#')
                .removePrefix("http://")
                .removePrefix("https://")
                .trimEnd('/')
                .lowercase(Locale.ROOT)
        }
    }
}
