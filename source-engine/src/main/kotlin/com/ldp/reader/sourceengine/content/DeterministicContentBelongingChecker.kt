package com.ldp.reader.sourceengine.content

import com.ldp.reader.sourceengine.catalog.ChapterNormalizer

enum class ContentBelongingDetector {
    REFERENCE,
    STRONG_DOMAIN_SHIFT,
    FRAGMENTED_TAIL,
    COHERENT_FOREIGN_TAIL,
    SHORT_PREFIX_FOREIGN_TAIL,
    HARD_BREAK_FRAGMENTED_TAIL,
    FINGERPRINT,
    EMBEDDED_METADATA
}

class DeterministicContentBelongingChecker(
    private val normalizer: ChapterNormalizer = ChapterNormalizer(),
    private val enabledDetectors: Set<ContentBelongingDetector> = ContentBelongingDetector.values().toSet()
) : ContentBelongingChecker {
    override fun inspect(input: ContentInspectionInput): ContentBelongingReport {
        val content = input.cleanedContent.trim()
        if (content.isBlank()) {
            return ContentBelongingReport(false, 0, listOf("blank-content"))
        }

        val markers = ArrayList<String>()
        if (ContentBelongingDetector.REFERENCE in enabledDetectors) {
            markers.addAll(referenceDivergenceMarkers(content, input.referenceContents))
        }
        if (ContentBelongingDetector.STRONG_DOMAIN_SHIFT in enabledDetectors) {
            markers.addAll(strongDomainShiftTailMarkers(content))
        }
        if (ContentBelongingDetector.FRAGMENTED_TAIL in enabledDetectors) {
            markers.addAll(fragmentedTailMarkers(content))
        }
        if (ContentBelongingDetector.COHERENT_FOREIGN_TAIL in enabledDetectors) {
            markers.addAll(coherentForeignTailMarkers(content))
        }
        if (ContentBelongingDetector.SHORT_PREFIX_FOREIGN_TAIL in enabledDetectors) {
            markers.addAll(shortPrefixForeignTailMarkers(content))
        }
        if (ContentBelongingDetector.HARD_BREAK_FRAGMENTED_TAIL in enabledDetectors) {
            markers.addAll(hardBreakFragmentedTailMarkers(content))
        }
        if (ContentBelongingDetector.FINGERPRINT in enabledDetectors) {
            markers.addAll(fingerprintDivergenceTailMarkers(content, input.bookFingerprint))
        }
        if (ContentBelongingDetector.EMBEDDED_METADATA in enabledDetectors) {
            val currentChapterKey = normalizer.normalize(input.chapterTitle).key
            val lineOffsets = lineOffsets(content)
            lineOffsets.forEach { lineOffset ->
                val line = lineOffset.line.trim()
                if (lineOffset.offset < INTRO_TRUST_CHARS || line.length > MAX_HEADING_LINE_CHARS) {
                    return@forEach
                }
                if (looksLikeChapterHeading(line)) {
                    val headingKey = normalizer.normalize(line).key
                    if (headingKey != currentChapterKey) {
                        markers.add("embedded-chapter-heading")
                        if (lineOffset.offset <= VALID_PREFIX_MAX_CHARS) {
                            markers.add("foreign-content-after-valid-prefix")
                        }
                    }
                }
                if (looksLikeBookMetadata(line)) {
                    markers.add("embedded-book-metadata")
                }
            }
        }

        val distinctMarkers = markers.distinct()
        val hasFingerprintTailDivergence =
            "fingerprint-body-divergence" in distinctMarkers ||
                "character-fingerprint-divergence-tail" in distinctMarkers ||
                "environment-fingerprint-divergence-tail" in distinctMarkers ||
                "fingerprint-body-too-short" in distinctMarkers
        var score = 100
        if ("embedded-chapter-heading" in distinctMarkers) score -= 65
        if ("foreign-content-after-valid-prefix" in distinctMarkers) score -= 20
        if ("embedded-book-metadata" in distinctMarkers) score -= 30
        if ("cross-source-tail-divergence" in distinctMarkers) score -= 80
        if ("fragmented-tail-after-valid-prefix" in distinctMarkers) {
            score -= if (hasFingerprintTailDivergence) 75 else 20
        }
        if ("coherent-foreign-tail-after-valid-prefix" in distinctMarkers) {
            score -= if (hasFingerprintTailDivergence) 75 else 20
        }
        if ("short-prefix-foreign-tail" in distinctMarkers) {
            score -= if (hasFingerprintTailDivergence) 75 else 20
        }
        if ("strong-domain-shift-tail-after-valid-prefix" in distinctMarkers) {
            score -= if (hasFingerprintTailDivergence) 75 else 20
        }
        if ("unpunctuated-line-break-after-valid-prefix" in distinctMarkers && hasFingerprintTailDivergence) score -= 20
        if ("high-risk-unpunctuated-break-offset" in distinctMarkers && hasFingerprintTailDivergence) score -= 10
        if ("fingerprint-body-divergence" in distinctMarkers) score -= 85
        if ("fingerprint-body-too-short" in distinctMarkers) score -= 85
        if ("character-fingerprint-divergence-tail" in distinctMarkers) score -= 70
        if ("environment-fingerprint-divergence-tail" in distinctMarkers) score -= 55
        if (!hasFingerprintTailDivergence && "cross-source-tail-divergence" !in distinctMarkers) {
            score = score.coerceAtLeast(MIN_BELONGING_SCORE)
        }

        return ContentBelongingReport(
            belongsToChapter = score >= MIN_BELONGING_SCORE,
            score = score.coerceIn(0, 100),
            markers = distinctMarkers
        )
    }

    private fun referenceDivergenceMarkers(
        content: String,
        references: List<String>
    ): List<String> {
        if (references.isEmpty()) return emptyList()
        val normalized = normalizeForComparison(content)
        if (normalized.length < MIN_REFERENCE_COMPARE_CHARS) return emptyList()
        val candidatePrefix = normalized.take(REFERENCE_PREFIX_CHARS)
        val candidateTail = normalized.drop(REFERENCE_PREFIX_CHARS)
            .take(REFERENCE_TAIL_COMPARE_CHARS)
        if (candidateTail.length < MIN_REFERENCE_TAIL_CHARS) return emptyList()

        var bestPrefixSimilarity = 0.0
        var bestTailSimilarity = 0.0
        references.asSequence()
            .map { normalizeForComparison(it) }
            .filter { it.length >= MIN_REFERENCE_COMPARE_CHARS }
            .forEach { reference ->
                bestPrefixSimilarity = maxOf(
                    bestPrefixSimilarity,
                    ngramContainment(candidatePrefix, reference.take(REFERENCE_PREFIX_CHARS + REFERENCE_ALIGNMENT_SLOP))
                )
                bestTailSimilarity = maxOf(
                    bestTailSimilarity,
                    ngramContainment(candidateTail, reference.drop(REFERENCE_PREFIX_CHARS / 2))
                )
            }

        return if (
            bestPrefixSimilarity >= MIN_REFERENCE_PREFIX_SIMILARITY &&
            bestTailSimilarity <= MAX_REFERENCE_TAIL_SIMILARITY
        ) {
            listOf("cross-source-tail-divergence", "foreign-content-after-valid-prefix")
        } else {
            emptyList()
        }
    }

    private fun fingerprintDivergenceTailMarkers(
        content: String,
        fingerprint: BookContentFingerprint?
    ): List<String> {
        if (fingerprint?.usable != true) return emptyList()
        val normalizedContentLength = TextFingerprintSignals.normalizeForComparison(content).length
        if (fingerprint.isTooShortForFingerprint(normalizedContentLength)) {
            return fingerprintTooShortMarkers()
        }
        val bodyText = content.drop(content.length / FINGERPRINT_PREFIX_DROP_DIVISOR)
        val bodyIndex = TextFingerprintSignals.index(bodyText)
        if (bodyIndex.normalized.length < MIN_FINGERPRINT_TAIL_CHARS) {
            return fingerprintTooShortMarkers()
        }

        val fingerprintMatch = fingerprint.match(bodyIndex)
        if (fingerprintMatch.matches) {
            return emptyList()
        }
        return buildList {
            add("fingerprint-body-divergence")
            if (!fingerprintMatch.strongCharacterMatch && !fingerprintMatch.balancedMatch) {
                add("character-fingerprint-divergence-tail")
            }
            if (!fingerprintMatch.strongEnvironmentMatch && !fingerprintMatch.balancedMatch) {
                add("environment-fingerprint-divergence-tail")
            }
            add("foreign-content-after-valid-prefix")
        }
    }

    private fun BookContentFingerprint.isTooShortForFingerprint(normalizedContentLength: Int): Boolean {
        return normalizedContentLength < MIN_FINGERPRINT_CONTENT_CHARS
    }

    private fun fingerprintTooShortMarkers(): List<String> {
        return listOf(
            "fingerprint-body-too-short",
            "fingerprint-body-divergence",
            "character-fingerprint-divergence-tail",
            "environment-fingerprint-divergence-tail",
            "foreign-content-after-valid-prefix"
        )
    }

    private fun strongDomainShiftTailMarkers(content: String): List<String> {
        val normalized = normalizeForComparison(content)
        if (normalized.length < MIN_STRONG_DOMAIN_SHIFT_CONTENT_CHARS) return emptyList()

        val prefixText = content.take(STRONG_DOMAIN_SHIFT_PREFIX_CHARS)
        val tailText = content.drop(STRONG_DOMAIN_SHIFT_TAIL_START_CHARS)
        val normalizedTail = normalizeForComparison(tailText)
        if (normalizedTail.length < MIN_STRONG_DOMAIN_SHIFT_TAIL_CHARS) return emptyList()

        val domainTermCount = STRONG_DOMAIN_SHIFT_TERMS.count { term -> tailText.contains(term) }
        if (domainTermCount < MIN_STRONG_DOMAIN_SHIFT_TERMS) return emptyList()

        val prefixTailOverlap = overlapRatio(
            ngrams(normalizedTail, TOKEN_NGRAM_SIZE),
            ngrams(normalizeForComparison(prefixText), TOKEN_NGRAM_SIZE)
        )
        return if (prefixTailOverlap <= MAX_STRONG_DOMAIN_SHIFT_PREFIX_TAIL_OVERLAP) {
            listOf(
                "strong-domain-shift-tail-after-valid-prefix",
                "foreign-content-after-valid-prefix",
                "foreign-domain-tail-marker"
            )
        } else {
            emptyList()
        }
    }

    private fun shortPrefixForeignTailMarkers(content: String): List<String> {
        val normalized = normalizeForComparison(content)
        if (normalized.length < MIN_SHORT_PREFIX_CONTENT_CHARS) return emptyList()

        val prefixText = content.take(SHORT_PREFIX_CHARS)
        val tailText = content.drop(SHORT_PREFIX_TAIL_START_CHARS)
        val normalizedTail = normalizeForComparison(tailText)
        if (normalizedTail.length < MIN_SHORT_PREFIX_TAIL_CHARS) return emptyList()
        if (continuingNarrativeNameCount(prefixText, tailText) >= MIN_CONTINUING_NAME_FOREIGN_TAIL_SUPPRESS_COUNT) {
            return emptyList()
        }

        val domainShift = tailDomainShiftMarkers(tailText)
        if (domainShift.isEmpty()) return emptyList()

        val prefixTailOverlap = overlapRatio(
            ngrams(normalizedTail, TOKEN_NGRAM_SIZE),
            ngrams(normalizeForComparison(prefixText), TOKEN_NGRAM_SIZE)
        )
        val tailNames = personLikeNames(tailText).toSet()
        if (
            prefixTailOverlap <= MAX_SHORT_PREFIX_TAIL_OVERLAP &&
            tailNames.size >= MIN_SHORT_PREFIX_TAIL_NAMES
        ) {
            return listOf("short-prefix-foreign-tail", "foreign-content-after-valid-prefix") + domainShift
        }
        return emptyList()
    }

    private fun hardBreakFragmentedTailMarkers(content: String): List<String> {
        val normalized = normalizeForComparison(content)
        if (normalized.length < MIN_HARD_BREAK_FRAGMENTED_CONTENT_CHARS) return emptyList()

        lineOffsets(content).zipWithNext { current, next ->
            val line = current.line.trim()
            val nextLine = next.line.trim()
            if (line.length < MIN_HARD_BREAK_LINE_CHARS ||
                nextLine.length < MIN_HARD_BREAK_NEXT_LINE_CHARS
            ) {
                return@zipWithNext
            }
            val breakOffset = next.offset
            if (breakOffset !in HARD_BREAK_FRAGMENTED_OFFSET_RANGE) return@zipWithNext
            if (line.lastOrNull() in SENTENCE_ENDING_CHARS) return@zipWithNext

            val tailText = content.drop(breakOffset)
            val normalizedTail = normalizeForComparison(tailText)
            if (normalizedTail.length < MIN_HARD_BREAK_FRAGMENTED_TAIL_CHARS) return@zipWithNext

            val tailParagraphs = paragraphOffsets(tailText)
                .filter { normalizeForComparison(it.line).length >= MIN_HARD_BREAK_FRAGMENT_PARAGRAPH_CHARS }
            if (tailParagraphs.size < MIN_HARD_BREAK_FRAGMENTED_TAIL_PARAGRAPHS) return@zipWithNext

            val prefixText = content.take(breakOffset)
            val prefixTokens = ngrams(normalizeForComparison(prefixText), TOKEN_NGRAM_SIZE)
            val tailTokens = ngrams(normalizedTail, TOKEN_NGRAM_SIZE)
            val prefixTailOverlap = overlapRatio(tailTokens, prefixTokens)
            if (prefixTailOverlap > MAX_HARD_BREAK_FRAGMENTED_PREFIX_TAIL_OVERLAP) return@zipWithNext

            val prefixNames = personLikeNames(prefixText).toSet()
            val tailNames = personLikeNames(tailText).toSet()
            val newTailNames = tailNames.count { name -> name !in prefixNames }
            val environmentTerms = TextFingerprintSignals.environmentTerms(tailText).toSet()
            if (newTailNames < MIN_HARD_BREAK_NEW_TAIL_NAMES &&
                environmentTerms.size < MIN_HARD_BREAK_ENVIRONMENT_TERMS
            ) {
                return@zipWithNext
            }

            val adjacentSimilarities = tailParagraphs.zipWithNext { left, right ->
                ngramJaccard(
                    normalizeForComparison(left.line).take(FRAGMENT_PARAGRAPH_COMPARE_CHARS),
                    normalizeForComparison(right.line).take(FRAGMENT_PARAGRAPH_COMPARE_CHARS)
                )
            }
            if (adjacentSimilarities.isEmpty()) return@zipWithNext
            val lowSimilarityPairs = adjacentSimilarities.count {
                it <= MAX_HARD_BREAK_FRAGMENTED_ADJACENT_SIMILARITY
            }
            val enoughParagraphJumps = lowSimilarityPairs >= maxOf(
                MIN_HARD_BREAK_FRAGMENTED_LOW_SIMILARITY_PAIRS,
                adjacentSimilarities.size / 3
            )
            if (!enoughParagraphJumps) return@zipWithNext

            return listOf(
                "unpunctuated-line-break-after-valid-prefix",
                "high-risk-unpunctuated-break-offset",
                "fragmented-tail-after-unpunctuated-break",
                "foreign-content-after-valid-prefix"
            )
        }
        return emptyList()
    }

    private fun coherentForeignTailMarkers(content: String): List<String> {
        val normalized = normalizeForComparison(content)
        if (normalized.length < MIN_COHERENT_FOREIGN_CONTENT_CHARS) return emptyList()

        val prefixText = content.take(COHERENT_FOREIGN_PREFIX_CHARS)
        val tailText = content.drop(COHERENT_FOREIGN_TAIL_START_CHARS)
        val normalizedTail = normalizeForComparison(tailText)
        if (normalizedTail.length < MIN_COHERENT_FOREIGN_TAIL_CHARS) return emptyList()
        if (continuingNarrativeNameCount(prefixText, tailText) >= MIN_CONTINUING_NAME_FOREIGN_TAIL_SUPPRESS_COUNT) {
            return emptyList()
        }

        val domainShift = tailDomainShiftMarkers(tailText)
        if (domainShift.isEmpty()) return emptyList()

        val prefixNames = personLikeNames(prefixText).toSet()
        val tailNames = personLikeNames(tailText).toSet()
        if (tailNames.size < MIN_COHERENT_FOREIGN_TAIL_NAMES) return emptyList()

        val continuingNames = prefixNames.count { name -> normalizedTail.contains(name) }
        val newTailNames = tailNames.count { name -> name !in prefixNames }
        if (
            continuingNames > MAX_COHERENT_FOREIGN_CONTINUING_NAMES ||
            newTailNames < MIN_COHERENT_FOREIGN_NEW_NAMES
        ) {
            return emptyList()
        }

        val prefixTailOverlap = overlapRatio(
            ngrams(normalizedTail, TOKEN_NGRAM_SIZE),
            ngrams(normalizeForComparison(prefixText), TOKEN_NGRAM_SIZE)
        )
        return if (prefixTailOverlap <= MAX_COHERENT_FOREIGN_PREFIX_TAIL_OVERLAP) {
            listOf("coherent-foreign-tail-after-valid-prefix", "foreign-content-after-valid-prefix") + domainShift
        } else {
            emptyList()
        }
    }

    private fun fragmentedTailMarkers(content: String): List<String> {
        val normalized = normalizeForComparison(content)
        if (normalized.length < MIN_FRAGMENTED_CONTENT_CHARS) return emptyList()
        val paragraphs = paragraphOffsets(content)
            .filter { normalizeForComparison(it.line).length >= MIN_FRAGMENT_PARAGRAPH_CHARS }
        val tailParagraphs = paragraphs.filter { it.offset >= FRAGMENT_TAIL_START_CHARS }
        if (tailParagraphs.size < MIN_FRAGMENT_TAIL_PARAGRAPHS) return emptyList()

        val prefix = content.take(FRAGMENT_TAIL_START_CHARS)
        val prefixTokens = ngrams(normalizeForComparison(prefix), TOKEN_NGRAM_SIZE)
        val tailText = tailParagraphs.joinToString("\n") { it.line }
        val tailTokens = ngrams(normalizeForComparison(tailText), TOKEN_NGRAM_SIZE)
        if (prefixTokens.isEmpty() || tailTokens.isEmpty()) return emptyList()

        val prefixTailOverlap = overlapRatio(tailTokens, prefixTokens)
        val domainShift = tailDomainShiftMarkers(tailText)
        if (continuingNarrativeNameCount(prefix, tailText) >= MIN_CONTINUING_NAME_FOREIGN_TAIL_SUPPRESS_COUNT) {
            return emptyList()
        }
        val personNames = personLikeNames(tailText)
        val uniqueNameCount = personNames.toSet().size
        val nameParagraphCount = tailParagraphs.count { personLikeNames(it.line).isNotEmpty() }
        val adjacentSimilarities = tailParagraphs.zipWithNext { left, right ->
            ngramJaccard(
                normalizeForComparison(left.line).take(FRAGMENT_PARAGRAPH_COMPARE_CHARS),
                normalizeForComparison(right.line).take(FRAGMENT_PARAGRAPH_COMPARE_CHARS)
            )
        }
        if (adjacentSimilarities.isEmpty()) return emptyList()
        val lowSimilarityPairs = adjacentSimilarities.count { it <= MAX_FRAGMENT_ADJACENT_SIMILARITY }
        val enoughParagraphJumps = lowSimilarityPairs >= maxOf(
            MIN_FRAGMENT_LOW_SIMILARITY_PAIRS,
            adjacentSimilarities.size / 2
        )

        return if (
            domainShift.isNotEmpty() &&
            uniqueNameCount >= MIN_FRAGMENT_UNIQUE_NAMES &&
            nameParagraphCount >= MIN_FRAGMENT_NAME_PARAGRAPHS &&
            prefixTailOverlap <= MAX_FRAGMENT_PREFIX_TAIL_OVERLAP &&
            enoughParagraphJumps
        ) {
            listOf("fragmented-tail-after-valid-prefix", "foreign-content-after-valid-prefix") + domainShift
        } else {
            emptyList()
        }
    }

    private fun lineOffsets(content: String): List<LineOffset> {
        val result = ArrayList<LineOffset>()
        var offset = 0
        content.lines().forEach { line ->
            result.add(LineOffset(line, offset))
            offset += line.length + 1
        }
        return result
    }

    private fun paragraphOffsets(content: String): List<LineOffset> {
        return lineOffsets(content).filter { it.line.isNotBlank() }
    }

    private fun looksLikeChapterHeading(line: String): Boolean {
        return CHAPTER_HEADING_PATTERNS.any { it.containsMatchIn(line) }
    }

    private fun looksLikeBookMetadata(line: String): Boolean {
        return BOOK_METADATA_PATTERNS.any { it.containsMatchIn(line) }
    }

    private fun tailDomainShiftMarkers(tailText: String): List<String> {
        return if (FOREIGN_TAIL_PATTERNS.any { pattern -> pattern.containsMatchIn(tailText) }) {
            listOf("foreign-domain-tail-marker")
        } else {
            emptyList()
        }
    }

    private fun continuingNarrativeNameCount(prefixText: String, tailText: String): Int {
        val prefixNames = personLikeNames(prefixText).toSet()
        if (prefixNames.isEmpty()) return 0
        val normalizedTail = normalizeForComparison(tailText)
        return prefixNames.count { name -> normalizedTail.contains(name) }
    }

    private fun normalizeForComparison(value: String): String {
        return value
            .lowercase()
            .filter { char -> char.isLetterOrDigit() || char in '\u4e00'..'\u9fff' }
    }

    private fun ngramContainment(left: String, right: String, size: Int = REFERENCE_NGRAM_SIZE): Double {
        val leftGrams = ngrams(left, size)
        if (leftGrams.isEmpty()) return 0.0
        val rightGrams = ngrams(right, size)
        if (rightGrams.isEmpty()) return 0.0
        return leftGrams.count { it in rightGrams }.toDouble() / leftGrams.size
    }

    private fun ngramJaccard(left: String, right: String, size: Int = TOKEN_NGRAM_SIZE): Double {
        val leftGrams = ngrams(left, size)
        val rightGrams = ngrams(right, size)
        if (leftGrams.isEmpty() || rightGrams.isEmpty()) return 0.0
        val intersection = leftGrams.count { it in rightGrams }
        val union = leftGrams.size + rightGrams.size - intersection
        return if (union == 0) 0.0 else intersection.toDouble() / union
    }

    private fun overlapRatio(left: Set<String>, right: Set<String>): Double {
        if (left.isEmpty() || right.isEmpty()) return 0.0
        return left.count { it in right }.toDouble() / left.size
    }

    private fun ngrams(value: String, size: Int): Set<String> {
        if (value.length < size) return emptySet()
        return (0..value.length - size).mapTo(LinkedHashSet()) { index ->
            value.substring(index, index + size)
        }
    }

    private fun personLikeNames(value: String): List<String> {
        return TextFingerprintSignals.personLikeNames(value)
    }

    private data class LineOffset(
        val line: String,
        val offset: Int
    )

    companion object {
        private const val INTRO_TRUST_CHARS = 160
        private const val VALID_PREFIX_MAX_CHARS = 420
        private const val MAX_HEADING_LINE_CHARS = 80
        private const val MIN_BELONGING_SCORE = 70
        private const val MIN_REFERENCE_COMPARE_CHARS = 360
        private const val REFERENCE_PREFIX_CHARS = 260
        private const val REFERENCE_ALIGNMENT_SLOP = 120
        private const val REFERENCE_TAIL_COMPARE_CHARS = 900
        private const val MIN_REFERENCE_TAIL_CHARS = 160
        private const val REFERENCE_NGRAM_SIZE = 5
        private const val MIN_REFERENCE_PREFIX_SIMILARITY = 0.30
        private const val MAX_REFERENCE_TAIL_SIMILARITY = 0.16
        private const val MIN_FRAGMENTED_CONTENT_CHARS = 500
        private const val FRAGMENT_TAIL_START_CHARS = 240
        private const val MIN_FRAGMENT_PARAGRAPH_CHARS = 24
        private const val MIN_FRAGMENT_TAIL_PARAGRAPHS = 5
        private const val TOKEN_NGRAM_SIZE = 2
        private const val FRAGMENT_PARAGRAPH_COMPARE_CHARS = 160
        private const val MAX_FRAGMENT_ADJACENT_SIMILARITY = 0.055
        private const val MIN_FRAGMENT_LOW_SIMILARITY_PAIRS = 3
        private const val MIN_FRAGMENT_UNIQUE_NAMES = 5
        private const val MIN_FRAGMENT_NAME_PARAGRAPHS = 3
        private const val MAX_FRAGMENT_PREFIX_TAIL_OVERLAP = 0.22
        private const val MIN_CONTINUING_NAME_FOREIGN_TAIL_SUPPRESS_COUNT = 1
        private const val MIN_SHORT_PREFIX_CONTENT_CHARS = 220
        private const val SHORT_PREFIX_CHARS = 120
        private const val SHORT_PREFIX_TAIL_START_CHARS = 120
        private const val MIN_SHORT_PREFIX_TAIL_CHARS = 100
        private const val MIN_SHORT_PREFIX_TAIL_NAMES = 2
        private const val MAX_SHORT_PREFIX_TAIL_OVERLAP = 0.20
        private const val MIN_COHERENT_FOREIGN_CONTENT_CHARS = 420
        private const val COHERENT_FOREIGN_PREFIX_CHARS = 180
        private const val COHERENT_FOREIGN_TAIL_START_CHARS = 240
        private const val MIN_COHERENT_FOREIGN_TAIL_CHARS = 180
        private const val MIN_COHERENT_FOREIGN_TAIL_NAMES = 2
        private const val MIN_COHERENT_FOREIGN_NEW_NAMES = 2
        private const val MAX_COHERENT_FOREIGN_CONTINUING_NAMES = 1
        private const val MAX_COHERENT_FOREIGN_PREFIX_TAIL_OVERLAP = 0.18
        private const val MIN_STRONG_DOMAIN_SHIFT_CONTENT_CHARS = 150
        private const val STRONG_DOMAIN_SHIFT_PREFIX_CHARS = 120
        private const val STRONG_DOMAIN_SHIFT_TAIL_START_CHARS = 120
        private const val MIN_STRONG_DOMAIN_SHIFT_TAIL_CHARS = 60
        private const val MIN_STRONG_DOMAIN_SHIFT_TERMS = 3
        private const val MAX_STRONG_DOMAIN_SHIFT_PREFIX_TAIL_OVERLAP = 0.24
        private const val MIN_HARD_BREAK_FRAGMENTED_CONTENT_CHARS = 420
        private const val MIN_HARD_BREAK_LINE_CHARS = 8
        private const val MIN_HARD_BREAK_NEXT_LINE_CHARS = 18
        private const val MIN_HARD_BREAK_FRAGMENTED_TAIL_CHARS = 280
        private const val MIN_HARD_BREAK_FRAGMENT_PARAGRAPH_CHARS = 18
        private const val MIN_HARD_BREAK_FRAGMENTED_TAIL_PARAGRAPHS = 6
        private const val MIN_HARD_BREAK_FRAGMENTED_LOW_SIMILARITY_PAIRS = 4
        private const val MIN_HARD_BREAK_NEW_TAIL_NAMES = 8
        private const val MIN_HARD_BREAK_ENVIRONMENT_TERMS = 18
        private const val MAX_HARD_BREAK_FRAGMENTED_PREFIX_TAIL_OVERLAP = 0.18
        private const val MAX_HARD_BREAK_FRAGMENTED_ADJACENT_SIMILARITY = 0.065
        private const val MIN_FINGERPRINT_CONTENT_CHARS = 220
        private const val MIN_FINGERPRINT_TAIL_CHARS = 80
        private const val FINGERPRINT_PREFIX_DROP_DIVISOR = 3
        private val HARD_BREAK_FRAGMENTED_OFFSET_RANGE = 80..1_600
        private val SENTENCE_ENDING_CHARS = setOf(
            '。', '！', '？', '；', '：', '…', '.', '!', '?', ';', ':', '”', '’', '"', '\'', '）', ')', '》', '】',
            '」', '』'
        )

        private val CHAPTER_HEADING_PATTERNS = listOf(
            Regex("""^\s*第\s*[0-9０-９零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+\s*[章节回话卷].{0,50}$"""),
            Regex("""^\s*[0-9０-９]{1,5}\s*[.、]\s*.{1,50}$""")
        )

        private val BOOK_METADATA_PATTERNS = listOf(
            Regex("""^\s*(书名|小说名|作者|作家)\s*[:：].{1,50}$"""),
            Regex("""^\s*《[^》]{1,30}》\s*(作者|简介|最新章节)?.{0,30}$""")
        )

        private val FOREIGN_TAIL_PATTERNS = listOf(
            Regex("""(飞碟|任务者|安德莉亚|半妖|蚊虫嗡嗡|道师的追杀)"""),
            Regex("""(暴虐的王爷|王府里|选择题|选项|冰窖|黎筱雨|薇娅|万丈巨剑插在沙漠)"""),
            Regex("""(芝加哥|GMC|俱乐部|影院|电视发行|网络播放|飞机播映|麻省理工|剑桥|哈佛|百校汇演)"""),
            Regex("""(国足|迈巴赫|异能者|胡八一|王胖子|黄毛|唐宁|桃式|艾利亚|聂茴|法庭|专家证人|罗宋汤|足球)""")
        )

        private val STRONG_DOMAIN_SHIFT_TERMS = listOf(
            "14寸电视机",
            "电视机",
            "折木乙宇",
            "委员会",
            "副委员长",
            "政务司",
            "工业局",
            "商业局",
            "农业局",
            "教育局",
            "卫生局",
            "士兵",
            "挎着枪",
            "股市",
            "跌停",
            "高管",
            "公寓",
            "高中",
            "同学",
            "学生会",
            "社死现场",
            "程耀",
            "姜悦兮",
            "洛基",
            "沈幼清",
            "建安帝",
            "李宓",
            "油纸伞",
            "半妖",
            "无良道师",
            "蚊虫",
            "毛毛虫",
            "登山服",
            "橙白相间",
            "黑盒",
            "白色模板"
        )

    }
}
