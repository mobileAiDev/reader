package com.ldp.reader.sourceengine.content

import com.ldp.reader.sourceengine.model.ContentQualityReport

class ContentQualityScorer {
    fun score(
        rawLength: Int,
        cleanedLength: Int,
        paragraphCount: Int,
        removedLineCount: Int,
        duplicateLineCount: Int,
        pollutionMarkers: List<String>,
        belongingReport: ContentBelongingReport = ContentBelongingReport(true, 100, emptyList())
    ): ContentQualityReport {
        val warnings = ArrayList<String>()
        var score = 100
        if (cleanedLength < 80) {
            warnings.add("content-unusable")
            score -= 70
        } else if (cleanedLength < 200) {
            warnings.add("content-too-short")
            score -= 35
        }
        if (paragraphCount < 2) {
            warnings.add("few-paragraphs")
            score -= 15
        }
        if (pollutionMarkers.isNotEmpty()) {
            warnings.add("pollution-lines-removed")
            score -= (pollutionMarkers.distinct().size * 8).coerceAtMost(32)
        }
        if (duplicateLineCount > 0) {
            warnings.add("duplicate-lines-removed")
            score -= (duplicateLineCount * 4).coerceAtMost(20)
        }
        if (rawLength > 0 && cleanedLength * 100 / rawLength < 20) {
            warnings.add("cleanup-ratio-unusable")
            score -= 40
        } else if (rawLength > 0 && cleanedLength * 100 / rawLength < 55) {
            warnings.add("large-cleanup-ratio")
            score -= 10
        }
        if (!belongingReport.belongsToChapter) {
            warnings.add("content-may-belong-to-other-book")
            score -= 70
        } else if (belongingReport.score < 85) {
            warnings.add("content-coherence-warning")
            score -= 20
        }
        return ContentQualityReport(
            qualityScore = score.coerceIn(0, 100),
            rawLength = rawLength,
            cleanedLength = cleanedLength,
            paragraphCount = paragraphCount,
            removedLineCount = removedLineCount,
            duplicateLineCount = duplicateLineCount,
            pollutionMarkers = pollutionMarkers.distinct(),
            warnings = warnings,
            coherenceScore = belongingReport.score,
            coherenceMarkers = belongingReport.markers
        )
    }
}
