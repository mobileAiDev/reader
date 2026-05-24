package com.ldp.reader.sourceengine.content.v5

enum class FeatureType {
    CHARACTER,
    ORGANIZATION,
    LOCATION,
    SKILL,
    ITEM,
    CURRENCY,
    REALM,
    WORLD_TERM,
    PHRASE,
    RELATION_EDGE
}

data class AlgorithmConfig(
    val chunkSize: Int = 800,
    val chunkOverlap: Int = 150,
    val seedChapterRatio: Double = 0.70,
    val minFeatureFrequency: Int = 3,
    val minFeatureChapterCount: Int = 2,
    val minAlienFeatureFrequency: Int = 2,
    val coreFeatureLimit: Int = 500,
    val supportFeatureLimit: Int = 1_000,
    val refineRounds: Int = 2,
    val cleanChunkThreshold: Double = 0.58,
    val abnormalThreshold: Double = 0.40,
    val suspiciousThreshold: Double = 0.55,
    val minReportedConfidence: Double = 0.70,
    val minAbnormalRunLength: Int = 2,
    val minSuffixChunks: Int = 1,
    val normalBeforeThreshold: Double = 0.58,
    val abnormalAfterThreshold: Double = 0.45,
    val judgmentStartRatio: Double = 1.0 / 3.0,
    val relationWeight: Double = 1.4,
    val alienWeight: Double = 1.7,
    val alienRelationWeight: Double = 1.1,
    val smooth: Double = 24.0
)

data class ChapterInput(
    val index: Int,
    val title: String,
    val content: String
)

enum class ChapterQualityType {
    CLEAN_STORY,
    CLEAN_WITH_TRIM,
    NON_STORY,
    BAD_EXTRACTION,
    TOO_SHORT_UNCERTAIN,
    MIXED_EXTRACTION_UNCERTAIN
}

data class QualityMetrics(
    val originalChars: Int,
    val cleanedChars: Int,
    val removedChars: Int,
    val totalLines: Int,
    val removedLines: Int,
    val shellLines: Int,
    val codeLines: Int,
    val navLines: Int,
    val siteLines: Int,
    val metaLines: Int,
    val chineseCharRatio: Double,
    val narrativeSentenceCount: Int,
    val shellLineRatio: Double,
    val removedCharRatio: Double
)

data class ChapterQualityResult(
    val chapterIndex: Int,
    val chapterTitle: String,
    val type: ChapterQualityType,
    val cleanText: String,
    val confidence: Double,
    val reasons: List<String>,
    val metrics: QualityMetrics
) {
    val usableForStory: Boolean
        get() = type == ChapterQualityType.CLEAN_STORY || type == ChapterQualityType.CLEAN_WITH_TRIM
}

data class TextChunk(
    val chapterIndex: Int,
    val chapterTitle: String,
    val chunkIndex: Int,
    val startOffset: Int,
    val endOffset: Int,
    val chapterLength: Int,
    val text: String
)

data class FingerprintFeature(
    val text: String,
    val type: FeatureType,
    val chapterHitCount: Int,
    val totalHitCount: Int,
    val weight: Double
)

data class NovelFingerprint(
    val title: String,
    val author: String,
    val coreFeatures: List<FingerprintFeature>,
    val supportFeatures: List<FingerprintFeature>,
    val relationEdges: Map<String, Double>
) {
    val featureWeights: Map<String, Double> =
        (coreFeatures + supportFeatures).associate { feature -> feature.text to feature.weight }

    val featureTypes: Map<String, FeatureType> =
        (coreFeatures + supportFeatures).associate { feature -> feature.text to feature.type }
}

data class ChunkScore(
    val chunk: TextChunk,
    val knownScore: Double,
    val alienScore: Double,
    val relationScore: Double,
    val alienRelationScore: Double,
    val containmentScore: Double,
    val belongScore: Double,
    val knownFeatures: List<String>,
    val alienFeatures: List<String>,
    val reasons: List<String>
)

enum class PollutionType {
    LOCAL_ABNORMAL,
    SUFFIX_POLLUTION
}

enum class NovelStateOutputType {
    NORMAL,
    NON_STORY,
    BAD_EXTRACTION,
    POLLUTED_SUFFIX,
    POLLUTED_RUN,
    UNCERTAIN
}

enum class CleanAction {
    KEEP,
    MARK_ONLY,
    SUGGEST_DELETE,
    AUTO_DELETE_ALLOWED
}

data class CleanSuggestion(
    val chapterIndex: Int,
    val chapterTitle: String,
    val pollutionType: PollutionType,
    val startOffset: Int,
    val endOffset: Int,
    val confidence: Double,
    val action: CleanAction,
    val reasons: List<String>,
    val stateType: NovelStateOutputType = NovelStateOutputType.UNCERTAIN
)

data class V5BoundaryBackfillCandidate(
    val chapterIndex: Int,
    val chapterTitle: String,
    val stateType: NovelStateOutputType,
    val action: CleanAction,
    val confidence: Double,
    val reasons: List<String>
)

internal const val V5_SHORT_FRAGMENTED_FULL_CHAPTER_REASON = "short fragmented full-chapter pollution"

data class CleanReport(
    val title: String,
    val author: String,
    val chapterCount: Int,
    val chunkCount: Int,
    val fingerprint: NovelFingerprint,
    val chunkScores: List<ChunkScore>,
    val suggestions: List<CleanSuggestion>,
    val boundaryBackfillCandidates: List<V5BoundaryBackfillCandidate> = emptyList(),
    val qualityResults: List<ChapterQualityResult> = emptyList(),
    val logs: List<String>
) {
    fun humanSummary(maxFeatures: Int = 12): String {
        val builder = StringBuilder()
        builder.appendLine("Novel: $title / $author")
        builder.appendLine("Chapters: $chapterCount, chunks: $chunkCount")
        if (qualityResults.isNotEmpty()) {
            val counts = qualityResults.groupingBy { result -> result.type }.eachCount()
            builder.appendLine(
                "Quality: clean=${counts[ChapterQualityType.CLEAN_STORY] ?: 0}, " +
                    "trimmed=${counts[ChapterQualityType.CLEAN_WITH_TRIM] ?: 0}, " +
                    "nonStory=${counts[ChapterQualityType.NON_STORY] ?: 0}, " +
                    "badExtraction=${counts[ChapterQualityType.BAD_EXTRACTION] ?: 0}, " +
                    "uncertain=${(counts[ChapterQualityType.TOO_SHORT_UNCERTAIN] ?: 0) + (counts[ChapterQualityType.MIXED_EXTRACTION_UNCERTAIN] ?: 0)}"
            )
            qualityResults
                .filter { result -> !result.usableForStory }
                .take(8)
                .forEach { result ->
                    builder.appendLine(
                        "- quality chapter ${result.chapterIndex + 1} ${result.chapterTitle}: " +
                            "${result.type} confidence=${"%.2f".format(result.confidence)} " +
                            "removed=${result.metrics.removedChars}/${result.metrics.originalChars}"
                    )
                    result.reasons.take(3).forEach { reason -> builder.appendLine("  * $reason") }
                }
        }
        builder.appendLine("Core features: ${fingerprint.coreFeatures.size}, support: ${fingerprint.supportFeatures.size}")
        builder.appendLine("Top features:")
        fingerprint.coreFeatures.take(maxFeatures).forEach { feature ->
            builder.appendLine(
                "- ${feature.type}:${feature.text} weight=${"%.1f".format(feature.weight)} " +
                    "chapters=${feature.chapterHitCount} hits=${feature.totalHitCount}"
            )
        }
        builder.appendLine("Suggestions: ${suggestions.size}")
        suggestions.forEach { suggestion ->
            builder.appendLine(
                "- chapter ${suggestion.chapterIndex + 1} ${suggestion.chapterTitle}: " +
                    "${suggestion.stateType}/${suggestion.pollutionType} start=${suggestion.startOffset} " +
                    "confidence=${"%.2f".format(suggestion.confidence)} action=${suggestion.action}"
            )
            suggestion.reasons.take(4).forEach { reason -> builder.appendLine("  * $reason") }
        }
        return builder.toString()
    }
}
