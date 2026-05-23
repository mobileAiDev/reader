package com.ldp.reader.algorithmtest.core

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class NovelPollutionAnalyzer(
    private val config: AlgorithmConfig = AlgorithmConfig()
) {
    private val traceLines = ArrayList<String>()
    private val qualityGate = ChapterQualityGate()

    fun analyze(
        title: String,
        author: String,
        chapters: List<ChapterInput>,
        seedChapterIndexes: Set<Int>? = null,
        progress: ((String) -> Unit)? = null
    ): CleanReport {
        traceLines.clear()
        progress?.invoke("input_start chapters=${chapters.size}")
        val sortedChapters = chapters
            .sortedBy { chapter -> chapter.index }
        progress?.invoke("quality_start chapters=${sortedChapters.size}")
        val qualityResults = sortedChapters.map { chapter -> qualityGate.inspect(chapter) }
        val qualityCounts = qualityResults.groupingBy { result -> result.type }.eachCount()
        log(
            "quality",
            "chapters=${qualityResults.size} " +
                "clean=${qualityCounts[ChapterQualityType.CLEAN_STORY] ?: 0} " +
                "trimmed=${qualityCounts[ChapterQualityType.CLEAN_WITH_TRIM] ?: 0} " +
                "nonStory=${qualityCounts[ChapterQualityType.NON_STORY] ?: 0} " +
                "badExtraction=${qualityCounts[ChapterQualityType.BAD_EXTRACTION] ?: 0} " +
                "uncertain=${(qualityCounts[ChapterQualityType.TOO_SHORT_UNCERTAIN] ?: 0) + (qualityCounts[ChapterQualityType.MIXED_EXTRACTION_UNCERTAIN] ?: 0)}"
        )
        qualityResults
            .filter { result -> !result.usableForStory }
            .take(20)
            .forEach { result ->
                log(
                    "quality.skip",
                    "chapter=${result.chapterIndex} state=${result.type} confidence=${"%.2f".format(result.confidence)} " +
                        "removed=${result.metrics.removedChars}/${result.metrics.originalChars} title=${result.chapterTitle} " +
                        "reason=${result.reasons.joinToString(";")}"
                )
            }
        qualityResults
            .filter { result -> result.type == ChapterQualityType.CLEAN_WITH_TRIM }
            .take(20)
            .forEach { result ->
                log(
                    "quality.trim",
                    "chapter=${result.chapterIndex} confidence=${"%.2f".format(result.confidence)} " +
                        "removed=${result.metrics.removedChars}/${result.metrics.originalChars} title=${result.chapterTitle}"
                )
            }
        progress?.invoke("quality_done usable=${qualityResults.count { result -> result.usableForStory }}")
        val cleanedChapters = qualityResults
            .filter { result -> result.usableForStory }
            .map { result ->
                ChapterInput(
                    index = result.chapterIndex,
                    title = result.chapterTitle,
                    content = result.cleanText
                )
            }
            .filter { chapter -> chapter.content.isNotBlank() }
        log("input", "chapters=${cleanedChapters.size}/${qualityResults.size} title=$title author=$author")
        progress?.invoke("input_done chapters=${cleanedChapters.size}")
        if (cleanedChapters.size < 4) {
            log(
                "quality.abort",
                "usableChapters=${cleanedChapters.size} total=${qualityResults.size} " +
                    "reason=need-more-clean-story-context"
            )
            return CleanReport(
                title = title,
                author = author,
                chapterCount = cleanedChapters.size,
                chunkCount = 0,
                fingerprint = emptyFingerprint(title, author),
                chunkScores = emptyList(),
                suggestions = emptyList(),
                qualityResults = qualityResults,
                logs = traceLines.toList()
            )
        }

        progress?.invoke("split_start")
        val chunks = splitNovelIntoChunks(cleanedChapters)
        log("chunk", "chunks=${chunks.size} size=${config.chunkSize} overlap=${config.chunkOverlap}")
        progress?.invoke("split_done chunks=${chunks.size}")

        progress?.invoke("fingerprint_initial_start")
        var fingerprint = buildInitialFingerprint(title, author, chunks, seedChapterIndexes)
        progress?.invoke("fingerprint_initial_done core=${fingerprint.coreFeatures.size} support=${fingerprint.supportFeatures.size}")
        repeat(config.refineRounds.coerceAtLeast(0)) { round ->
            progress?.invoke("refine_score_start round=${round + 1}")
            val scored = chunks.map { chunk -> scoreChunk(chunk, fingerprint) }
            progress?.invoke("refine_score_done round=${round + 1} scored=${scored.size}")
            val cleanChunks = scored
                .filter { score -> score.belongScore >= config.cleanChunkThreshold }
                .map { score -> score.chunk }
                .ifEmpty { seedChunks(chunks, seedChapterIndexes) }
            log("refine", "round=${round + 1} cleanChunks=${cleanChunks.size}")
            progress?.invoke("refine_fingerprint_start round=${round + 1} cleanChunks=${cleanChunks.size}")
            fingerprint = buildFingerprint(title, author, cleanChunks)
            progress?.invoke("refine_fingerprint_done round=${round + 1} core=${fingerprint.coreFeatures.size} support=${fingerprint.supportFeatures.size}")
        }

        progress?.invoke("final_score_start chunks=${chunks.size}")
        val scoredChunks = chunks.map { chunk -> scoreChunk(chunk, fingerprint) }
        progress?.invoke("final_score_done chunks=${scoredChunks.size}")
        progress?.invoke("detect_start")
        val rawSuggestions = detectPollutedChapters(scoredChunks, fingerprint, progress)
        val suggestions = confirmActionLevels(rawSuggestions, qualityResults)
        progress?.invoke("detect_done suggestions=${suggestions.size}")
        log("report", "suggestions=${suggestions.size}")

        return CleanReport(
            title = title,
            author = author,
            chapterCount = cleanedChapters.size,
            chunkCount = chunks.size,
            fingerprint = fingerprint,
            chunkScores = scoredChunks,
            suggestions = suggestions,
            qualityResults = qualityResults,
            logs = traceLines.toList()
        )
    }

    private fun emptyFingerprint(title: String, author: String): NovelFingerprint {
        return NovelFingerprint(
            title = title,
            author = author,
            coreFeatures = emptyList(),
            supportFeatures = emptyList(),
            relationEdges = emptyMap()
        )
    }

    private fun confirmActionLevels(
        suggestions: List<CleanSuggestion>,
        qualityResults: List<ChapterQualityResult>
    ): List<CleanSuggestion> {
        if (suggestions.isEmpty()) return suggestions
        val qualityByChapter = qualityResults.associateBy { result -> result.chapterIndex }
        return suggestions.map { suggestion ->
            val quality = qualityByChapter[suggestion.chapterIndex]
            val confirmed = confirmedAction(suggestion, quality)
            if (confirmed != suggestion.action) {
                log(
                    "action.confirm",
                    "chapter=${suggestion.chapterIndex} from=${suggestion.action} to=$confirmed " +
                        "state=${suggestion.stateType} quality=${quality?.type} qConfidence=${quality?.confidence} " +
                        "removedRatio=${quality?.metrics?.removedCharRatio}"
                )
                suggestion.copy(
                    action = confirmed,
                    reasons = (suggestion.reasons + actionDowngradeReason(suggestion.action, confirmed, quality))
                        .distinct()
                        .take(8)
                )
            } else {
                log(
                    "action.confirm",
                    "chapter=${suggestion.chapterIndex} action=${suggestion.action} state=${suggestion.stateType} " +
                        "quality=${quality?.type} confidence=${suggestion.confidence}"
                )
                suggestion
            }
        }
    }

    private fun confirmedAction(
        suggestion: CleanSuggestion,
        quality: ChapterQualityResult?
    ): CleanAction {
        var action = suggestion.action
        if (quality == null || !quality.usableForStory) return minAction(action, CleanAction.MARK_ONLY)
        if (quality.confidence < 0.78) action = minAction(action, CleanAction.MARK_ONLY)
        if (quality.type == ChapterQualityType.CLEAN_WITH_TRIM && quality.metrics.removedCharRatio > 0.35) {
            action = minAction(action, CleanAction.SUGGEST_DELETE)
        }
        if (quality.metrics.cleanedChars < 400) action = minAction(action, CleanAction.SUGGEST_DELETE)
        val allowAutoDelete = suggestion.stateType == NovelStateOutputType.POLLUTED_SUFFIX &&
            suggestion.confidence >= 0.92 &&
            quality.confidence >= 0.82 &&
            quality.metrics.removedCharRatio <= 0.35 &&
            quality.metrics.cleanedChars >= 400
        if (action == CleanAction.AUTO_DELETE_ALLOWED && !allowAutoDelete) {
            action = CleanAction.SUGGEST_DELETE
        }
        return action
    }

    private fun minAction(left: CleanAction, right: CleanAction): CleanAction {
        return if (actionRank(left) <= actionRank(right)) left else right
    }

    private fun actionRank(action: CleanAction): Int {
        return when (action) {
            CleanAction.KEEP -> 0
            CleanAction.MARK_ONLY -> 1
            CleanAction.SUGGEST_DELETE -> 2
            CleanAction.AUTO_DELETE_ALLOWED -> 3
        }
    }

    private fun actionDowngradeReason(
        original: CleanAction,
        confirmed: CleanAction,
        quality: ChapterQualityResult?
    ): String {
        return "action downgraded after quality confirmation: $original -> $confirmed " +
            "quality=${quality?.type ?: "missing"}"
    }

    private fun buildInitialFingerprint(
        title: String,
        author: String,
        chunks: List<TextChunk>,
        seedChapterIndexes: Set<Int>? = null
    ): NovelFingerprint {
        val seed = seedChunks(chunks, seedChapterIndexes)
        log(
            "fingerprint.seed",
            "seedChunks=${seed.size} explicitSeedChapters=${seedChapterIndexes?.size ?: 0}"
        )
        return buildFingerprint(title, author, seed)
    }

    private fun seedChunks(
        chunks: List<TextChunk>,
        seedChapterIndexes: Set<Int>? = null
    ): List<TextChunk> {
        if (chunks.isEmpty()) return emptyList()
        if (!seedChapterIndexes.isNullOrEmpty()) {
            val explicitSeed = chunks.filter { chunk -> chunk.chapterIndex in seedChapterIndexes }
            if (explicitSeed.isNotEmpty()) return explicitSeed
        }
        val chapterIndexes = chunks
            .map { chunk -> chunk.chapterIndex }
            .distinct()
            .sorted()
        val seedChapterCount = max(1, (chapterIndexes.size * config.seedChapterRatio).toInt())
        val seedIndexSet = chapterIndexes.take(seedChapterCount).toSet()
        return chunks.filter { chunk -> chunk.chapterIndex in seedIndexSet }
            .ifEmpty { chunks.take(max(1, chunks.size * 7 / 10)) }
    }

    private fun buildFingerprint(
        title: String,
        author: String,
        chunks: List<TextChunk>
    ): NovelFingerprint {
        val stats = collectFingerprintTermStats(chunks)
        val scored = suppressContainedFeatures(stats.values
            .asSequence()
            .mapNotNull { stat -> stat.toFingerprintFeature() }
            .sortedWith(
                compareByDescending<FingerprintFeature> { feature -> feature.weight }
                    .thenByDescending { feature -> feature.chapterHitCount }
                    .thenByDescending { feature -> feature.totalHitCount }
                    .thenBy { feature -> feature.text }
            )
            .toList())

        val core = scored.take(config.coreFeatureLimit)
        val support = scored.drop(config.coreFeatureLimit).take(config.supportFeatureLimit)
        val relationEdges = buildRelationEdges(chunks, core + support)
        log(
            "fingerprint.build",
            "chunks=${chunks.size} terms=${stats.size} core=${core.size} support=${support.size} relations=${relationEdges.size}"
        )
        log(
            "fingerprint.top",
            core.take(10).joinToString("|") { feature ->
                "${feature.type}:${feature.text}:${feature.chapterHitCount}/${feature.totalHitCount}"
            }
        )
        return NovelFingerprint(title, author, core, support, relationEdges)
    }

    private fun suppressContainedFeatures(features: List<FingerprintFeature>): List<FingerprintFeature> {
        val kept = ArrayList<FingerprintFeature>()
        features.forEach { candidate ->
            val container = kept.firstOrNull { existing ->
                existing.text.length > candidate.text.length &&
                    existing.text.contains(candidate.text) &&
                    existing.chapterHitCount >= candidate.chapterHitCount &&
                    existing.totalHitCount >= candidate.totalHitCount / 2
            }
            val dominantShorter = kept.firstOrNull { existing ->
                existing.text.length < candidate.text.length &&
                    candidate.text.contains(existing.text) &&
                    existing.chapterHitCount >= candidate.chapterHitCount &&
                    existing.totalHitCount >= candidate.totalHitCount * 3
            }
            val candidateDominates = container != null &&
                candidate.totalHitCount >= container.totalHitCount * 2 &&
                candidate.chapterHitCount > container.chapterHitCount
            if (dominantShorter == null && (container == null || candidateDominates)) {
                kept.add(candidate)
            }
        }
        return kept
    }

    private fun MutableTermStats.toFingerprintFeature(): FingerprintFeature? {
        if (totalHitCount < config.minFeatureFrequency) return null
        if (chapterHitCount < config.minFeatureChapterCount) return null
        val featureType = classifyFingerprintTerm(this) ?: return null
        if (featureType == FeatureType.PHRASE && chapterHitCount < max(3, config.minFeatureChapterCount)) {
            return null
        }
        val weight = weight(featureType)
        if (weight <= 0.0) return null
        return FingerprintFeature(
            text = text,
            type = featureType,
            chapterHitCount = chapterHitCount,
            totalHitCount = totalHitCount,
            weight = weight
        )
    }

    private fun MutableTermStats.weight(type: FeatureType): Double {
        val chapterSpread = chapterHitCount.toDouble()
        val chunkSpread = chunkHitCount.toDouble()
        val total = totalHitCount.toDouble()
        val repeatPerChapter = total / chapterSpread.coerceAtLeast(1.0)
        val spreadScore = ln(1.0 + chapterSpread) * 12.0
        val repeatScore = ln(1.0 + min(total, chapterSpread * 18.0)) * 8.0
        val chunkScore = ln(1.0 + chunkSpread) * 4.0
        val localRepeatScore = min(8.0, repeatPerChapter)
        return typeBoost(type) *
            specificityScore(text, type) *
            termhoodScore(this, type) *
            (spreadScore + repeatScore + chunkScore + localRepeatScore)
    }

    private fun scoreChunk(
        chunk: TextChunk,
        fingerprint: NovelFingerprint
    ): ChunkScore {
        val stats = collectTermStats(listOf(chunk))
        var knownScore = 0.0
        var alienScore = 0.0
        var totalSpecificScore = 0.0
        val knownFeatures = ArrayList<String>()
        val alienFeatures = ArrayList<String>()
        val knownEntityTerms = ArrayList<String>()
        val alienEntityTerms = ArrayList<String>()

        stats.values.forEach { stat ->
            val knownWeight = fingerprint.featureWeights[stat.text]
            val knownType = fingerprint.featureTypes[stat.text]
            if (knownWeight != null && knownType != null) {
                val multiplier = 1.0 + ln(1.0 + stat.totalHitCount.toDouble()).coerceAtMost(2.5)
                knownScore += knownWeight * multiplier
                totalSpecificScore += estimatedFeatureWeight(stat.text, knownType) * multiplier
                knownFeatures.add(stat.text)
                if (knownType != FeatureType.PHRASE) knownEntityTerms.add(stat.text)
                return@forEach
            }

            val alienType = classifyChunkAlienTerm(stat) ?: return@forEach
            val hitMultiplier = min(4, stat.totalHitCount).toDouble()
            val estimated = estimatedFeatureWeight(stat.text, alienType) * hitMultiplier
            totalSpecificScore += estimated
            alienScore += estimated
            alienFeatures.add(stat.text)
            alienEntityTerms.add(stat.text)
        }

        val relationScore = scoreKnownRelations(knownEntityTerms, fingerprint)
        val alienRelationScore = scoreAlienRelations(alienEntityTerms)
        val containmentScore = knownScore / (knownScore + alienScore + 0.0001)
        val normal = knownScore + config.relationWeight * relationScore
        val identityStrength = (normal / (normal + config.smooth)).coerceIn(0.0, 1.0)
        val alienWeight = (1.0 - identityStrength).coerceIn(0.20, 1.0)
        val alien = (config.alienWeight * alienScore + config.alienRelationWeight * alienRelationScore) * alienWeight
        val belongScore = (normal / (normal + alien + config.smooth)).coerceIn(0.0, 1.0)

        return ChunkScore(
            chunk = chunk,
            knownScore = knownScore,
            alienScore = alienScore,
            relationScore = relationScore,
            alienRelationScore = alienRelationScore,
            containmentScore = containmentScore.coerceIn(0.0, 1.0),
            belongScore = belongScore,
            knownFeatures = knownFeatures.distinct().take(12),
            alienFeatures = alienFeatures.distinct().take(12),
            reasons = buildReasons(
                belongScore = belongScore,
                knownFeatures = knownFeatures,
                alienFeatures = alienFeatures,
                relationScore = relationScore,
                alienRelationScore = alienRelationScore
            )
        )
    }

    private fun detectPollutedChapters(
        scores: List<ChunkScore>,
        fingerprint: NovelFingerprint,
        progress: ((String) -> Unit)? = null
    ): List<CleanSuggestion> {
        val facts = scores.map { score -> structuralFact(score, fingerprint) }
        progress?.invoke("detect_facts_done facts=${facts.size}")
        val bookModel = buildStructuralBookModel(facts, fingerprint)
        progress?.invoke(
            "detect_model_done coreEntities=${bookModel.coreEntities.size} " +
                "prototypes=${bookModel.prototypeTerms.size} cleanFacts=${bookModel.cleanFactCount}"
        )
        val factsByChapter = facts.groupBy { fact -> fact.score.chunk.chapterIndex }
        val suggestions = ArrayList<CleanSuggestion>()
        factsByChapter
            .toSortedMap()
            .entries
            .forEachIndexed { ordinal, (_, chapterFacts) ->
                if (ordinal % 5 == 0) {
                    progress?.invoke(
                        "detect_chapter ordinal=${ordinal + 1}/${factsByChapter.size} " +
                            "chapter=${chapterFacts.first().score.chunk.chapterIndex} chunks=${chapterFacts.size}"
                    )
                }
                if (chapterFacts.first().score.chunk.chapterIndex == 0) return@forEachIndexed
                if (isNonStoryChapter(chapterFacts)) {
                    log(
                        "v3.skip",
                        "chapter=${chapterFacts.first().score.chunk.chapterIndex} state=NON_STORY reason=non-story " +
                            "title=${chapterFacts.first().score.chunk.chapterTitle}"
                    )
                    return@forEachIndexed
                }
                detectStructuralSuffix(chapterFacts, facts, bookModel)?.let { suggestion ->
                    suggestions.add(suggestion)
                    return@forEachIndexed
                }
                detectStructuralRun(chapterFacts, facts, bookModel)?.let { suggestion -> suggestions.add(suggestion) }
            }
        log(
            "v3.report",
            "chapters=${factsByChapter.size} suggestions=${suggestions.size} " +
                "state=NORMAL|NON_STORY|POLLUTED_SUFFIX|POLLUTED_RUN|UNCERTAIN " +
                "coreEntities=${bookModel.coreEntities.size} prototypes=${bookModel.prototypeTerms.size} " +
                "cleanFacts=${bookModel.cleanFactCount}"
        )
        return suggestions
    }

    private fun isNonStoryChapter(chapterFacts: List<StructuralChunkFact>): Boolean {
        if (chapterFacts.isEmpty()) return false
        val title = chapterFacts.first().score.chunk.chapterTitle
        val text = segmentText(chapterFacts)
        val titleHits = metaTitleSignals.count { signal -> title.contains(signal) }
        val bodyHits = metaBodySignals.count { signal -> text.contains(signal) }
        val authoringHits = metaAuthoringSignals.count { signal -> text.contains(signal) }
        val numberedChapter = isNumberedChapterTitle(title)
        val knownEntityCount = chapterFacts.flatMap { fact -> fact.knownEntities.keys }.toSet().size
        val alienEntityCount = chapterFacts.flatMap { fact -> fact.alienEntities.keys }.toSet().size

        val titleMeta = !numberedChapter && titleHits >= 1 && bodyHits >= 1
        val bodyMeta = bodyHits >= 4 && authoringHits >= 2
        val lowNarrativePressure = knownEntityCount <= 2 || authoringHits >= knownEntityCount
        val metaDominatesEntities = bodyHits + authoringHits >= alienEntityCount + knownEntityCount

        return titleMeta ||
            (bodyMeta && metaDominatesEntities) ||
            (bodyMeta && !numberedChapter && title.length <= 12) ||
            (bodyHits >= 6 && authoringHits >= 2 && lowNarrativePressure)
    }

    private fun isNumberedChapterTitle(title: String): Boolean {
        return Regex("""第.+章""").containsMatchIn(title)
    }

    private fun detectStructuralSuffix(
        chapterFacts: List<StructuralChunkFact>,
        allFacts: List<StructuralChunkFact>,
        bookModel: StructuralBookModel
    ): CleanSuggestion? {
        if (chapterFacts.size < 2) return null
        val judgmentStart = judgmentStart(chapterFacts.first().score.chunk)
        var best: StructuralDecision? = null
        for (split in 1 until chapterFacts.size) {
            val splitOffset = chapterFacts[split].score.chunk.startOffset
            if (splitOffset < judgmentStart) continue
            val prefix = chapterFacts.take(split)
            val suffix = chapterFacts.drop(split)
            if (suffix.size < config.minSuffixChunks) continue
            val evidenceChars = chapterFacts.last().score.chunk.endOffset - splitOffset
            if (evidenceChars < minimumAbnormalEvidenceChars(chapterFacts.first().score.chunk.chapterLength)) continue

            val prefixBelong = averageBelong(prefix)
            if (prefixBelong < config.normalBeforeThreshold) continue

            val structural = scoreStructuralSegment(
                prefix = prefix,
                evidence = suffix,
                allFacts = allFacts,
                bookModel = bookModel
            )
            if (structural.membershipLow >= 0.50 || structural.alienCluster >= 0.40) {
                log(
                        "v3.candidate",
                        "chapter=${chapterFacts.first().score.chunk.chapterIndex} type=suffix start=$splitOffset " +
                            structural.describe(suffix, bookModel)
                    )
                }
            if (!structural.isHighConfidencePollution()) continue
            val arcEvidence = sameBookArcEvidence(suffix, allFacts, bookModel)
            log(
                "v5.arc",
                "chapter=${chapterFacts.first().score.chunk.chapterIndex} type=suffix " +
                    "start=$splitOffset ${arcEvidence.describe()}"
            )
            if (arcEvidence.absorbs(structural)) {
                log(
                    "v5.absorb",
                    "chapter=${chapterFacts.first().score.chunk.chapterIndex} type=suffix " +
                        "start=$splitOffset ${arcEvidence.describe()} " +
                        "structural=${structural.describe(suffix, bookModel)}"
                )
                continue
            }
            val decision = structural.toSuggestionDecision(
                type = PollutionType.SUFFIX_POLLUTION,
                evidence = suffix,
                startOffset = splitOffset,
                bookModel = bookModel,
                arcEvidence = arcEvidence
            )
            if (best == null || decision.confidence > best.confidence) best = decision
        }
        return best?.toCleanSuggestion()
    }

    private fun detectStructuralRun(
        chapterFacts: List<StructuralChunkFact>,
        allFacts: List<StructuralChunkFact>,
        bookModel: StructuralBookModel
    ): CleanSuggestion? {
        val judgmentStart = judgmentStart(chapterFacts.first().score.chunk)
        val runs = ArrayList<List<StructuralChunkFact>>()
        var current = ArrayList<StructuralChunkFact>()
        chapterFacts.forEach { fact ->
            val inJudgmentArea = fact.score.chunk.endOffset >= judgmentStart
            if (inJudgmentArea && fact.score.belongScore < config.abnormalThreshold) {
                current.add(fact)
            } else {
                if (current.size >= config.minAbnormalRunLength) runs.add(current)
                current = ArrayList()
            }
        }
        if (current.size >= config.minAbnormalRunLength) runs.add(current)
        if (runs.isEmpty()) {
            val judgmentEvidence = chapterFacts.filter { fact -> fact.score.chunk.endOffset >= judgmentStart }
            if (judgmentEvidence.size >= config.minAbnormalRunLength) runs.add(judgmentEvidence)
        }

        return runs
            .mapNotNull { run ->
                val evidenceChars = run.last().score.chunk.endOffset - run.first().score.chunk.startOffset
                if (evidenceChars < minimumAbnormalEvidenceChars(chapterFacts.first().score.chunk.chapterLength)) {
                    return@mapNotNull null
                }
                val runStart = run.first().score.chunk.startOffset
                val prefix = chapterFacts.filter { fact -> fact.score.chunk.startOffset < runStart }
                val structural = scoreStructuralSegment(
                    prefix = prefix,
                    evidence = run,
                    allFacts = allFacts,
                    bookModel = bookModel
                )
                if (structural.membershipLow >= 0.50 || structural.alienCluster >= 0.40) {
                    log(
                        "v3.candidate",
                        "chapter=${chapterFacts.first().score.chunk.chapterIndex} type=run " +
                            "start=${run.first().score.chunk.startOffset} " + structural.describe(run, bookModel)
                    )
                }
                if (!structural.isHighConfidencePollution()) return@mapNotNull null
                val arcEvidence = sameBookArcEvidence(run, allFacts, bookModel)
                log(
                    "v5.arc",
                    "chapter=${chapterFacts.first().score.chunk.chapterIndex} type=run " +
                        "start=${run.first().score.chunk.startOffset} ${arcEvidence.describe()}"
                )
                if (arcEvidence.absorbs(structural)) {
                    log(
                        "v5.absorb",
                        "chapter=${chapterFacts.first().score.chunk.chapterIndex} type=run " +
                            "start=${run.first().score.chunk.startOffset} ${arcEvidence.describe()} " +
                            "structural=${structural.describe(run, bookModel)}"
                    )
                    return@mapNotNull null
                }
                structural.toSuggestionDecision(
                    type = PollutionType.LOCAL_ABNORMAL,
                    evidence = run,
                    startOffset = run.first().score.chunk.startOffset,
                    bookModel = bookModel,
                    arcEvidence = arcEvidence
                )
            }
            .maxByOrNull { decision -> decision.confidence }
            ?.toCleanSuggestion()
    }

    private fun structuralFact(
        score: ChunkScore,
        fingerprint: NovelFingerprint
    ): StructuralChunkFact {
        val stats = collectTermStats(listOf(score.chunk))
        val entities = LinkedHashMap<String, FeatureType>()
        val knownEntities = LinkedHashMap<String, FeatureType>()
        val alienEntities = LinkedHashMap<String, FeatureType>()
        val worldCounts = linkedMapOf<FeatureType, Int>()
        stats.values.forEach { stat ->
            val knownType = fingerprint.featureTypes[stat.text]
            if (knownType != null) {
                if (knownType != FeatureType.PHRASE) {
                    entities[stat.text] = knownType
                    knownEntities[stat.text] = knownType
                }
                worldCounts[knownType] = (worldCounts[knownType] ?: 0) + stat.totalHitCount
                return@forEach
            }

            val type = classifyStructuralEntity(stat) ?: return@forEach
            entities[stat.text] = type
            if (type in structuralAlienTypes) alienEntities[stat.text] = type
            worldCounts[type] = (worldCounts[type] ?: 0) + stat.totalHitCount
        }
        return StructuralChunkFact(
            score = score,
            termCounts = topicTermCounts(score.chunk.text),
            entities = entities,
            knownEntities = knownEntities,
            alienEntities = alienEntities,
            worldVector = normalizeTypeCounts(worldCounts),
            styleVector = styleVector(score.chunk.text)
        )
    }

    private fun classifyStructuralEntity(stat: MutableTermStats): FeatureType? {
        val type = classifyTypedTerm(stat.text) ?: return null
        if (type == FeatureType.CHARACTER) {
            if (!looksLikeStructuralCharacter(stat.text)) return null
            if (stat.text.length == 2 && stat.totalHitCount < config.minAlienFeatureFrequency) return null
            return type
        }
        if (!passesTypedTermQuality(stat, type)) return null
        return type
    }

    private fun buildStructuralBookModel(
        facts: List<StructuralChunkFact>,
        fingerprint: NovelFingerprint
    ): StructuralBookModel {
        val coreEntities = (fingerprint.coreFeatures + fingerprint.supportFeatures)
            .asSequence()
            .filter { feature -> feature.type != FeatureType.PHRASE }
            .map { feature -> feature.text }
            .toSet()
        val coreTerms = fingerprint.featureWeights.keys
        val cleanFacts = facts
            .filter { fact ->
                fact.score.belongScore >= config.cleanChunkThreshold ||
                    fact.score.knownScore >= config.smooth * 4.0
            }
            .ifEmpty { facts.take(max(1, facts.size * 7 / 10)) }
        val termIdf = buildTermIdf(cleanFacts)
        val prototypeTerms = buildPrototypeTerms(cleanFacts)
        val lexicalPrototypes = buildLexicalPrototypes(cleanFacts, termIdf)
        val worldProfile = averageWorldVector(cleanFacts)
        val styleProfile = averageStyleVector(cleanFacts)
        return StructuralBookModel(
            coreEntities = coreEntities,
            coreTerms = coreTerms,
            relationEdges = fingerprint.relationEdges.keys,
            prototypeTerms = prototypeTerms,
            termIdf = termIdf,
            lexicalPrototypes = lexicalPrototypes,
            worldProfile = worldProfile,
            styleProfile = styleProfile,
            cleanFactCount = cleanFacts.size
        )
    }

    private fun buildPrototypeTerms(cleanFacts: List<StructuralChunkFact>): List<Set<String>> {
        if (cleanFacts.isEmpty()) return emptyList()
        val prototypeCount = min(8, max(1, cleanFacts.size / 8))
        val bucketSize = max(1, (cleanFacts.size + prototypeCount - 1) / prototypeCount)
        return cleanFacts
            .chunked(bucketSize)
            .map { bucket ->
                bucket
                    .flatMap { fact -> fact.terms }
                    .groupingBy { term -> term }
                    .eachCount()
                    .entries
                    .sortedWith(compareByDescending<Map.Entry<String, Int>> { entry -> entry.value }.thenBy { entry -> entry.key })
                    .take(80)
                    .map { entry -> entry.key }
                    .toSet()
            }
            .filter { terms -> terms.isNotEmpty() }
    }

    private fun buildTermIdf(cleanFacts: List<StructuralChunkFact>): Map<String, Double> {
        if (cleanFacts.isEmpty()) return emptyMap()
        val documentCounts = LinkedHashMap<String, Int>()
        cleanFacts.forEach { fact ->
            fact.termCounts.keys.forEach { term ->
                documentCounts[term] = (documentCounts[term] ?: 0) + 1
            }
        }
        val totalDocuments = cleanFacts.size.toDouble()
        return documentCounts.mapValues { (_, count) -> ln(1.0 + totalDocuments / (1.0 + count)) }
    }

    private fun buildLexicalPrototypes(
        cleanFacts: List<StructuralChunkFact>,
        termIdf: Map<String, Double>
    ): List<Map<String, Double>> {
        if (cleanFacts.isEmpty() || termIdf.isEmpty()) return emptyList()
        val prototypeCount = min(12, max(1, cleanFacts.size / 8))
        val bucketSize = max(1, (cleanFacts.size + prototypeCount - 1) / prototypeCount)
        return cleanFacts
            .chunked(bucketSize)
            .map { bucket -> averageSparseVector(bucket, termIdf) }
            .filter { vector -> vector.isNotEmpty() }
    }

    private fun scoreStructuralSegment(
        prefix: List<StructuralChunkFact>,
        evidence: List<StructuralChunkFact>,
        allFacts: List<StructuralChunkFact>,
        bookModel: StructuralBookModel
    ): StructuralScores {
        val prefixBelong = averageBelong(prefix)
        val evidenceBelong = averageBelong(evidence)
        val evidenceChars = evidence.last().score.chunk.endOffset - evidence.first().score.chunk.startOffset
        val evidenceCoverage = (evidenceChars.toDouble() /
            evidence.first().score.chunk.chapterLength.coerceAtLeast(1)).coerceIn(0.0, 1.0)
        val breakScore = (prefixBelong - evidenceBelong).coerceIn(0.0, 1.0)
        val separation = prefixSuffixSeparation(prefix, evidence)
        val suffixCohesion = internalCohesion(evidence)
        val alienCluster = alienClusterScore(evidence, bookModel)
        val alienContinuity = alienContinuityScore(evidence, bookModel)
        val alienNovelty = alienNoveltyScore(prefix, evidence, bookModel)
        val alienEntityCount = segmentAlienEntities(evidence, bookModel).size
        val alienIdentityStrength = alienIdentityStrength(evidence, bookModel)
        val graphAbsorption = graphAbsorptionScore(evidence, bookModel)
        val worldConsistency = worldConsistencyScore(evidence, bookModel)
        val prototypeSimilarity = prototypeSimilarity(evidence, bookModel)
        val futureIntegration = futureBookIntegrationScore(evidence, allFacts, bookModel)
        val styleSimilarity = styleSimilarityScore(evidence, bookModel)
        val prefixAlienAbsorption = prefixAlienAbsorptionScore(prefix, evidence, bookModel)
        val titleAbsorption = titleAbsorptionScore(evidence)
        val expositoryScore = expositorySegmentScore(evidence)
        val membershipLow = (1.0 - evidenceBelong).coerceIn(0.0, 1.0)
        val oodScore = (1.0 - max(max(prototypeSimilarity, worldConsistency), styleSimilarity)).coerceIn(0.0, 1.0)
        val confidence = (
            0.18 * breakScore +
                0.16 * separation +
                0.12 * suffixCohesion +
                0.18 * membershipLow +
                0.20 * alienCluster +
                0.04 * alienNovelty +
                0.10 * (1.0 - graphAbsorption) +
                0.02 * oodScore +
                0.02 * (1.0 - futureIntegration)
            ).coerceIn(0.0, 1.0)
        val foreignRunConfidence = (
            0.34 * membershipLow +
                0.30 * alienCluster +
                0.08 * alienNovelty +
                0.16 * (1.0 - graphAbsorption) +
                0.08 * (1.0 - prototypeSimilarity) +
                0.04 * (1.0 - futureIntegration)
            ).coerceIn(0.0, 1.0)
        val shortWholeChapterConfidence = if (prefixBelong <= 0.05 && evidenceCoverage >= 0.55) {
            (
                0.20 * membershipLow +
                    0.22 * alienCluster +
                    0.12 * alienIdentityStrength +
                    0.18 * (1.0 - prototypeSimilarity) +
                    0.18 * alienNovelty +
                    0.10 * evidenceCoverage
                ).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        return StructuralScores(
            breakScore = breakScore,
            suffixCohesion = suffixCohesion,
            separation = separation,
            membershipLow = membershipLow,
            alienCluster = alienCluster,
            alienContinuity = alienContinuity,
            alienNovelty = alienNovelty,
            alienEntityCount = alienEntityCount,
            alienIdentityStrength = alienIdentityStrength,
            graphAbsorption = graphAbsorption,
            worldConsistency = worldConsistency,
            prototypeSimilarity = prototypeSimilarity,
            futureIntegration = futureIntegration,
            oodScore = oodScore,
            prefixBookStrength = prefixBelong,
            prefixAlienAbsorption = prefixAlienAbsorption,
            titleAbsorption = titleAbsorption,
            expositoryScore = expositoryScore,
            evidenceChars = evidenceChars,
            evidenceCoverage = evidenceCoverage,
            confidence = max(max(confidence, foreignRunConfidence), shortWholeChapterConfidence)
        )
    }

    private fun StructuralScores.isHighConfidencePollution(): Boolean {
        val localArcAbsorbed =
            prefixBookStrength >= 0.72 &&
                prefixAlienAbsorption >= 0.50 &&
                alienContinuity >= 0.55 &&
                worldConsistency >= 0.75 &&
                membershipLow <= 1.00
        if (localArcAbsorbed) return false

        val chapterPrefixAlreadyOwnsAlienLine =
            prefixBookStrength >= 0.55 &&
                alienNovelty <= 0.20 &&
                alienContinuity >= 0.50 &&
                worldConsistency >= 0.70
        if (chapterPrefixAlreadyOwnsAlienLine) return false

        val localRunContinuation =
            prefixAlienAbsorption >= 0.60 &&
                alienNovelty <= 0.30 &&
                separation <= 0.50 &&
                suffixCohesion >= 0.50
        if (localRunContinuation) return false

        val localSingleConcept =
            alienEntityCount in 1..2 &&
                alienContinuity >= 0.90 &&
                alienNovelty <= 0.20 &&
                worldConsistency >= 0.70 &&
                alienCluster < 0.70
        if (localSingleConcept) return false

        val titleAbsorbedWeakBreak =
            titleAbsorption > 0.0 &&
                alienCluster <= 0.05 &&
                confidence < 0.70
        if (titleAbsorbedWeakBreak) return false

        val sameBookConceptDrift =
            prefixBookStrength >= 0.85 &&
                worldConsistency >= 0.92 &&
                membershipLow < 0.95 &&
                alienIdentityStrength < 0.35 &&
                alienEntityCount <= 3 &&
                (suffixCohesion >= 0.45 || alienContinuity >= 0.75) &&
                evidenceChars >= 600
        if (sameBookConceptDrift) return false

        val sameBookExpositorySegment =
            worldConsistency >= 0.90 &&
                alienIdentityStrength < 0.35 &&
                alienEntityCount <= 3 &&
                expositoryScore >= 0.55 &&
                evidenceChars >= 800
        if (sameBookExpositorySegment) return false

        val sameBookReferenceSegment =
            worldConsistency >= 0.95 &&
                expositoryScore >= 0.85 &&
                alienIdentityStrength < 0.65 &&
                prefixBookStrength >= 0.85 &&
                evidenceChars >= 800
        if (sameBookReferenceSegment) return false

        val noForeignIdentityEvidence =
            alienEntityCount == 0 &&
                alienCluster <= 0.0 &&
                alienIdentityStrength <= 0.0
        if (noForeignIdentityEvidence && evidenceChars < 1_200) return false

        val wholeChapterForeignRun =
            prefixBookStrength <= 0.05 &&
                evidenceChars >= 800 &&
                membershipLow >= 0.90 &&
                alienCluster >= 0.70 &&
                graphAbsorption <= 0.15 &&
                (alienEntityCount >= 6 || alienIdentityStrength >= 0.35)
        if (wholeChapterForeignRun) return true

        val wholeChapterFragmentedRun =
            prefixBookStrength <= 0.05 &&
                evidenceChars >= 800 &&
                separation >= 0.80 &&
                membershipLow >= 0.90 &&
                alienCluster >= 0.60 &&
                alienEntityCount >= 5 &&
                graphAbsorption <= 0.15
        if (wholeChapterFragmentedRun) return true

        val strongForeignEntityRun =
            evidenceChars >= 800 &&
                alienCluster >= 0.90 &&
                alienEntityCount >= 6 &&
                alienNovelty >= 0.60 &&
                graphAbsorption <= 0.35 &&
                prefixAlienAbsorption <= 0.25
        if (strongForeignEntityRun) return true

        val denseAlienWholeChapterRun =
            prefixBookStrength <= 0.05 &&
                evidenceChars >= 1200 &&
                alienCluster >= 0.85 &&
                alienEntityCount >= 10 &&
                alienNovelty >= 0.85 &&
                graphAbsorption <= 0.45
        if (denseAlienWholeChapterRun) return true

        val shortWholeChapterForeignRun =
            prefixBookStrength <= 0.05 &&
                evidenceCoverage >= 0.55 &&
                evidenceChars >= 450 &&
                membershipLow >= 0.25 &&
                alienCluster >= 0.40 &&
                alienEntityCount >= 3 &&
                alienIdentityStrength >= 0.25 &&
                prototypeSimilarity <= 0.06 &&
                futureIntegration <= 0.10
        if (shortWholeChapterForeignRun) return true

        val shortSuffixForeignRun =
            evidenceCoverage >= 0.35 &&
                evidenceChars >= 320 &&
                breakScore >= 0.50 &&
                separation >= 0.85 &&
                membershipLow >= 0.55 &&
                alienCluster >= 0.60 &&
                alienIdentityStrength >= 0.35 &&
                graphAbsorption <= 0.40 &&
                futureIntegration <= 0.10
        if (shortSuffixForeignRun) return true

        val stateBreak =
            breakScore >= 0.25 &&
                separation >= 0.80 &&
                prototypeSimilarity <= 0.12 &&
                futureIntegration <= 0.50
        val memoryOutlier =
            membershipLow >= 0.64 &&
                graphAbsorption <= 0.45
        val alienGraphOutlier =
            alienCluster >= 0.50 &&
                (alienNovelty >= 0.70 || graphAbsorption <= 0.20)
        val sequenceOutlier =
            evidenceChars >= 450 &&
                breakScore >= 0.65 &&
                membershipLow >= 0.90 &&
                graphAbsorption <= 0.15
        val fragmentedOutlier =
            evidenceChars >= 320 &&
            breakScore >= 0.55 &&
                membershipLow >= 0.90 &&
                alienCluster >= 0.70 &&
                graphAbsorption <= 0.15
        val sequenceBoundaryOutlier =
            evidenceChars >= 320 &&
                breakScore >= 0.75 &&
                separation >= 0.85 &&
                membershipLow >= 0.95 &&
                graphAbsorption <= 0.15
        val strongAlienForeignCluster =
            separation >= 0.85 &&
                membershipLow >= 0.75 &&
                alienCluster >= 0.85 &&
                alienNovelty >= 0.75 &&
                graphAbsorption <= 0.20 &&
                prefixAlienAbsorption <= 0.20
        val strongMixedAlienDominance =
            evidenceChars >= 800 &&
                breakScore >= 0.45 &&
                membershipLow >= 0.55 &&
                alienCluster >= 0.85 &&
                alienEntityCount >= 4 &&
                alienNovelty >= 0.55 &&
                prefixAlienAbsorption <= 0.25

        return (confidence >= 0.70 && stateBreak && memoryOutlier && alienGraphOutlier) ||
            (confidence >= 0.60 && sequenceOutlier) ||
            (confidence >= 0.75 && fragmentedOutlier) ||
            (confidence >= 0.60 && sequenceBoundaryOutlier) ||
            (confidence >= 0.80 && strongAlienForeignCluster) ||
            (confidence >= 0.55 && strongMixedAlienDominance)
    }

    private fun StructuralScores.toSuggestionDecision(
        type: PollutionType,
        evidence: List<StructuralChunkFact>,
        startOffset: Int,
        bookModel: StructuralBookModel,
        arcEvidence: SameBookArcEvidence = SameBookArcEvidence.Empty
    ): StructuralDecision {
        val reasons = listOf(
            "v3 break=${fmt(breakScore)} separation=${fmt(separation)} cohesion=${fmt(suffixCohesion)} evidenceChars=$evidenceChars",
            "v3 membershipLow=${fmt(membershipLow)} alienCluster=${fmt(alienCluster)} alienContinuity=${fmt(alienContinuity)} alienNovelty=${fmt(alienNovelty)} alienEntityCount=$alienEntityCount alienIdentity=${fmt(alienIdentityStrength)}",
            "v3 graphAbsorption=${fmt(graphAbsorption)} prototype=${fmt(prototypeSimilarity)} prefixBook=${fmt(prefixBookStrength)} prefixAlien=${fmt(prefixAlienAbsorption)}",
            "v3 world=${fmt(worldConsistency)} futureIntegration=${fmt(futureIntegration)} titleAbsorption=${fmt(titleAbsorption)} expository=${fmt(expositoryScore)} ood=${fmt(oodScore)}",
            "v5 sameBookArc=${arcEvidence.describe()}",
            "v3 alienEntities=${topAlienEntities(evidence, bookModel).joinToString(",")}"
        ) + evidence
            .flatMap { fact -> fact.score.reasons }
            .distinct()
            .take(4)
        return StructuralDecision(
            type = type,
            scores = evidence.map { fact -> fact.score },
            startOffset = startOffset,
            confidence = confidence,
            reasons = reasons
        )
    }

    private fun StructuralScores.describe(
        evidence: List<StructuralChunkFact>,
        bookModel: StructuralBookModel
    ): String {
        return "confidence=${fmt(confidence)} break=${fmt(breakScore)} separation=${fmt(separation)} " +
            "cohesion=${fmt(suffixCohesion)} membershipLow=${fmt(membershipLow)} " +
            "alienCluster=${fmt(alienCluster)} alienContinuity=${fmt(alienContinuity)} " +
            "alienNovelty=${fmt(alienNovelty)} alienEntityCount=$alienEntityCount alienIdentity=${fmt(alienIdentityStrength)} graphAbsorption=${fmt(graphAbsorption)} " +
            "prototype=${fmt(prototypeSimilarity)} world=${fmt(worldConsistency)} " +
            "future=${fmt(futureIntegration)} titleAbsorption=${fmt(titleAbsorption)} expository=${fmt(expositoryScore)} prefixBook=${fmt(prefixBookStrength)} " +
            "prefixAlien=${fmt(prefixAlienAbsorption)} evidenceChars=$evidenceChars evidenceCoverage=${fmt(evidenceCoverage)} " +
            "aliens=${topAlienEntities(evidence, bookModel).joinToString("|")}"
    }

    private fun topAlienEntities(
        evidence: List<StructuralChunkFact>,
        bookModel: StructuralBookModel
    ): List<String> {
        return segmentAlienEntities(evidence, bookModel)
            .values
            .sortedWith(compareByDescending<SegmentEntity> { entity -> entity.count }.thenBy { entity -> entity.text })
            .take(12)
            .map { entity -> "${entity.text}:${entity.count}" }
    }

    private fun titleAbsorptionScore(evidence: List<StructuralChunkFact>): Double {
        if (evidence.isEmpty()) return 0.0
        val title = stripChapterOrdinal(evidence.first().score.chunk.chapterTitle)
        val segments = title.split(Regex("""[：:!！?？,，、\s]+"""))
            .map { segment -> normalizeTitleTerm(segment) }
            .filter { segment -> segment.length in 2..8 }
            .filterNot { segment -> isGenericTitleTerm(segment) }
        if (segments.isEmpty()) return 0.0

        val evidenceText = evidence.joinToString("\n") { fact -> fact.score.chunk.text }
        val absorbed = segments.count { segment ->
            evidenceText.contains(segment) ||
                titleSubterms(segment).any { term -> countTerm(evidenceText, term) >= 2 }
        }
        return (absorbed.toDouble() / segments.size).coerceIn(0.0, 1.0)
    }

    private fun expositorySegmentScore(evidence: List<StructuralChunkFact>): Double {
        if (evidence.isEmpty()) return 0.0
        val text = segmentText(evidence)
        if (text.length < 400) return 0.0
        val connectorHits = expositoryConnectors.count { connector -> text.contains(connector) }
        val listPunctuation = text.count { char -> char == '；' || char == ';' || char == '：' || char == ':' }
        val paragraphLike = text.count { char -> char == '\n' || char == '。' }
        val punctuationDensity = (listPunctuation.toDouble() / (paragraphLike + 1)).coerceIn(0.0, 1.0)
        val connectorScore = (connectorHits.toDouble() / 5.0).coerceIn(0.0, 1.0)
        return max(connectorScore, punctuationDensity).coerceIn(0.0, 1.0)
    }

    private fun stripChapterOrdinal(title: String): String {
        return title
            .replace(Regex("""^第[一二三四五六七八九十百千万零〇两\d]+[章节卷部回]+"""), "")
            .trim()
    }

    private fun titleSubterms(segment: String): List<String> {
        if (segment.length <= 2) return listOf(segment)
        val terms = ArrayList<String>()
        for (size in 2..min(4, segment.length)) {
            for (start in 0..segment.length - size) {
                val term = segment.substring(start, start + size)
                if (!isGenericTitleTerm(term)) terms.add(term)
            }
        }
        return terms.distinct()
    }

    private fun normalizeTitleTerm(value: String): String {
        return value.filter { ch -> ch in '\u4e00'..'\u9fff' || ch.isDigit() }
    }

    private fun isGenericTitleTerm(term: String): Boolean {
        return term in genericTerms || term in nonFeatureTerms || looksLikeActionOrClauseFragment(term)
    }

    private fun countTerm(text: String, term: String): Int {
        if (term.isEmpty()) return 0
        var count = 0
        var index = text.indexOf(term)
        while (index >= 0) {
            count++
            index = text.indexOf(term, index + term.length)
        }
        return count
    }

    private fun averageBelong(facts: List<StructuralChunkFact>): Double {
        if (facts.isEmpty()) return 0.0
        return facts.sumOf { fact -> fact.score.belongScore } / facts.size
    }

    private fun prefixSuffixSeparation(
        prefix: List<StructuralChunkFact>,
        suffix: List<StructuralChunkFact>
    ): Double {
        if (prefix.isEmpty() || suffix.isEmpty()) return 0.50
        val prefixTerms = prefix.flatMap { fact -> fact.terms }.toSet()
        val suffixTerms = suffix.flatMap { fact -> fact.terms }.toSet()
        return (1.0 - jaccard(prefixTerms, suffixTerms)).coerceIn(0.0, 1.0)
    }

    private fun internalCohesion(facts: List<StructuralChunkFact>): Double {
        if (facts.size <= 1) return 0.50
        var total = 0.0
        var pairs = 0
        for (left in facts.indices) {
            for (right in left + 1 until facts.size) {
                total += jaccard(facts[left].terms, facts[right].terms)
                pairs += 1
            }
        }
        return if (pairs == 0) 0.0 else (total / pairs).coerceIn(0.0, 1.0)
    }

    private fun alienClusterScore(
        evidence: List<StructuralChunkFact>,
        bookModel: StructuralBookModel
    ): Double {
        val segmentEntities = segmentAlienEntities(evidence, bookModel)
        val isolated = segmentEntities.keys
        if (isolated.size < 2) return 0.0
        var observedEdges = 0
        val possibleEdges = isolated.size * (isolated.size - 1) / 2
        evidence.forEach { fact ->
            val present = fact.alienEntities.keys.intersect(isolated).toList()
            observedEdges += present.size * (present.size - 1) / 2
        }
        val density = if (possibleEdges == 0) 0.0 else (observedEdges.toDouble() / possibleEdges).coerceIn(0.0, 1.0)
        val sizeScore = (isolated.size.toDouble() / 8.0).coerceIn(0.0, 1.0)
        val repeatedCharacters = segmentEntities.values.count { entity ->
            entity.type == FeatureType.CHARACTER && entity.count >= 2
        }
        val characterEntities = segmentEntities.values.count { entity -> entity.type == FeatureType.CHARACTER }
        val typedEntities = segmentEntities.values.count { entity -> entity.type != FeatureType.CHARACTER }
        if (isolated.size < 3 && repeatedCharacters == 0 && typedEntities < 2) return 0.0
        val repeatScore = max(
            ((repeatedCharacters + typedEntities).toDouble() / 5.0).coerceIn(0.0, 1.0),
            (characterEntities.toDouble() / 7.0).coerceIn(0.0, 1.0)
        )
        val spreadScore = (evidence.count { fact -> fact.alienEntities.keys.any { term -> term in isolated } }.toDouble() /
            evidence.size.coerceAtLeast(1)).coerceIn(0.0, 1.0)
        val knownPressure = evidence
            .flatMap { fact -> fact.knownEntities.keys }
            .toSet()
            .size
            .toDouble()
        val isolation = (1.0 - knownPressure / (knownPressure + isolated.size + 0.0001)).coerceIn(0.0, 1.0)
        return (0.30 * sizeScore + 0.30 * repeatScore + 0.15 * density + 0.10 * spreadScore + 0.15 * isolation)
            .coerceIn(0.0, 1.0)
    }

    private fun alienNoveltyScore(
        prefix: List<StructuralChunkFact>,
        evidence: List<StructuralChunkFact>,
        bookModel: StructuralBookModel
    ): Double {
        val aliens = segmentAlienEntities(evidence, bookModel).keys
        if (aliens.isEmpty()) return 0.0
        if (prefix.isEmpty()) return 1.0
        val prefixText = segmentText(prefix)
        val novel = aliens.count { term -> !prefixText.contains(term) }
        return (novel.toDouble() / aliens.size).coerceIn(0.0, 1.0)
    }

    private fun alienContinuityScore(
        evidence: List<StructuralChunkFact>,
        bookModel: StructuralBookModel
    ): Double {
        val entities = segmentAlienEntities(evidence, bookModel).values
        if (entities.isEmpty()) return 0.0
        val total = entities.sumOf { entity -> entity.count }.coerceAtLeast(1)
        val repeated = entities.filter { entity -> entity.count >= 2 }.sumOf { entity -> entity.count }
        val dominant = entities.maxOf { entity -> entity.count }
        return max(
            repeated.toDouble() / total,
            dominant.toDouble() / total
        ).coerceIn(0.0, 1.0)
    }

    private fun alienIdentityStrength(
        evidence: List<StructuralChunkFact>,
        bookModel: StructuralBookModel
    ): Double {
        val entities = segmentAlienEntities(evidence, bookModel).values
        if (entities.isEmpty()) return 0.0
        val raw = entities.sumOf { entity -> alienIdentityWeight(entity) }
        return (raw / 6.0).coerceIn(0.0, 1.0)
    }

    private fun alienIdentityWeight(entity: SegmentEntity): Double {
        return when (entity.type) {
            FeatureType.CHARACTER -> characterAlienIdentityWeight(entity)
            FeatureType.ORGANIZATION,
            FeatureType.LOCATION -> if (entity.count >= 2) 0.90 else 0.55
            FeatureType.SKILL,
            FeatureType.ITEM,
            FeatureType.CURRENCY,
            FeatureType.REALM,
            FeatureType.WORLD_TERM -> if (entity.count >= 2) 0.55 else 0.25
            FeatureType.PHRASE,
            FeatureType.RELATION_EDGE -> 0.0
        }
    }

    private fun characterAlienIdentityWeight(entity: SegmentEntity): Double {
        if (looksLikeRoleOrCreatureAlias(entity.text)) return if (entity.count >= 3) 0.20 else 0.10
        if (entity.count >= 3) return 1.00
        if (entity.count >= 2 && entity.text.length <= 3) return 0.80
        if (entity.count >= 2) return 0.55
        if (isStrongOneShotCharacterText(entity.text)) return 0.25
        return 0.10
    }

    private fun isStrongOneShotCharacterText(text: String): Boolean {
        if (text.length != 3) return false
        if (text.first() !in commonSurnameChars) return false
        return !looksLikeRoleOrCreatureAlias(text)
    }

    private fun looksLikeRoleOrCreatureAlias(text: String): Boolean {
        return text.any { char -> char in roleOrCreatureChars } ||
            personRoleTerms.any { role -> text.contains(role) }
    }

    private fun prefixAlienAbsorptionScore(
        prefix: List<StructuralChunkFact>,
        evidence: List<StructuralChunkFact>,
        bookModel: StructuralBookModel
    ): Double {
        if (prefix.isEmpty() || evidence.isEmpty()) return 0.0
        val evidenceStart = evidence.first().score.chunk.startOffset
        val strictPrefixText = segmentTextBefore(prefix, (evidenceStart - 80).coerceAtLeast(0))
        if (strictPrefixText.isBlank()) return 0.0
        val aliens = segmentAlienEntities(evidence, bookModel).values
        if (aliens.isEmpty()) return 0.0
        val total = aliens.sumOf { entity -> entity.count }.coerceAtLeast(1)
        val absorbed = aliens
            .filter { entity -> strictPrefixText.contains(entity.text) }
            .sumOf { entity -> entity.count }
        return (absorbed.toDouble() / total).coerceIn(0.0, 1.0)
    }

    private fun segmentAlienEntities(
        evidence: List<StructuralChunkFact>,
        bookModel: StructuralBookModel
    ): Map<String, SegmentEntity> {
        if (evidence.isEmpty()) return emptyMap()
        val text = segmentText(evidence)
        val chunk = evidence.first().score.chunk
        val segmentStats = collectTermStats(
            listOf(
                TextChunk(
                    chapterIndex = chunk.chapterIndex,
                    chapterTitle = chunk.chapterTitle,
                    chunkIndex = 0,
                    startOffset = evidence.first().score.chunk.startOffset,
                    endOffset = evidence.last().score.chunk.endOffset,
                    chapterLength = chunk.chapterLength,
                    text = text
                )
            )
        )
        val candidates = segmentStats.values
            .asSequence()
            .mapNotNull { stat ->
                if (stat.text in bookModel.coreEntities || stat.text in bookModel.coreTerms) return@mapNotNull null
                classifySegmentAlienCandidate(stat)
            }
            .toList()
        return promoteSegmentAlienCandidates(candidates)
            .associateBy { entity -> entity.text }
    }

    private fun classifySegmentAlienCandidate(stat: MutableTermStats): SegmentEntityCandidate? {
        if (looksLikeStructuralCharacter(stat.text)) {
            if (!passesCharacterTermQuality(stat)) return null
            return SegmentEntityCandidate(
                text = stat.text,
                type = FeatureType.CHARACTER,
                count = stat.totalHitCount,
                repeated = stat.totalHitCount >= 2,
                strongTyped = false
            )
        }
        val type = classifyTypedTerm(stat.text) ?: return null
        if (type !in structuralAlienTypes) return null
        if (!passesTypedTermQuality(stat, type)) return null
        val strongTyped = isStrongSegmentTypedEntity(stat, type)
        if (!strongTyped && stat.totalHitCount < 2) return null
        if (type == FeatureType.WORLD_TERM && stat.totalHitCount < 2) return null
        return SegmentEntityCandidate(
            text = stat.text,
            type = type,
            count = stat.totalHitCount,
            repeated = stat.totalHitCount >= 2,
            strongTyped = strongTyped
        )
    }

    private fun promoteSegmentAlienCandidates(candidates: List<SegmentEntityCandidate>): List<SegmentEntity> {
        val compactCandidates = suppressContainedSegmentCandidates(candidates)
        val strongTypedCount = compactCandidates.count { candidate -> candidate.type != FeatureType.CHARACTER && candidate.strongTyped }
        val repeatedCharacterCount = compactCandidates.count { candidate ->
            candidate.type == FeatureType.CHARACTER && candidate.repeated
        }
        val characterCluster = compactCandidates
            .filter { candidate -> candidate.type == FeatureType.CHARACTER && isStrongOneShotCharacterCandidate(candidate) }
            .map { candidate -> candidate.text.first() }
            .distinct()
            .size
        val hasOneShotCharacterSupport = strongTypedCount >= 2 ||
            characterCluster >= 3 ||
            (repeatedCharacterCount >= 1 && characterCluster >= 2)

        return compactCandidates
            .asSequence()
            .filter { candidate ->
                when {
                    candidate.repeated && candidate.type != FeatureType.CHARACTER -> true
                    candidate.repeated -> strongTypedCount >= 1 || characterCluster >= 2
                    candidate.type != FeatureType.CHARACTER -> candidate.strongTyped
                    candidate.text.length == 2 -> false
                    else -> hasOneShotCharacterSupport && isStrongOneShotCharacterCandidate(candidate)
                }
            }
            .map { candidate -> SegmentEntity(candidate.text, candidate.type, candidate.count) }
            .toList()
    }

    private fun suppressContainedSegmentCandidates(
        candidates: List<SegmentEntityCandidate>
    ): List<SegmentEntityCandidate> {
        val sorted = candidates.sortedWith(
            compareByDescending<SegmentEntityCandidate> { candidate -> candidate.count }
                .thenByDescending { candidate -> candidate.text.length }
                .thenBy { candidate -> candidate.text }
        )
        val kept = ArrayList<SegmentEntityCandidate>()
        sorted.forEach { candidate ->
            val coveredBySameEntity = kept.any { existing ->
                existing.type == candidate.type &&
                    (existing.text.contains(candidate.text) || candidate.text.contains(existing.text)) &&
                    existing.text.first() == candidate.text.first() &&
                    existing.count >= max(1, candidate.count / 2)
            }
            if (!coveredBySameEntity) kept.add(candidate)
        }
        return kept
    }

    private fun isStrongOneShotCharacterCandidate(candidate: SegmentEntityCandidate): Boolean {
        if (candidate.type != FeatureType.CHARACTER) return false
        if (candidate.text.length != 3) return false
        return candidate.text.first() in commonSurnameChars
    }

    private fun isStrongSegmentTypedEntity(stat: MutableTermStats, type: FeatureType): Boolean {
        val hasExplicitTypeSignal = hasTypedSignal(stat.text, type) && typedSignalExcess(stat.text, type) <= 2
        val hasRepeatedEvidence = stat.totalHitCount >= 2
        return hasExplicitTypeSignal || hasRepeatedEvidence
    }

    private fun segmentText(evidence: List<StructuralChunkFact>): String {
        val ordered = evidence.sortedWith(
            compareBy<StructuralChunkFact> { fact -> fact.score.chunk.chapterIndex }
                .thenBy { fact -> fact.score.chunk.startOffset }
        )
        val builder = StringBuilder()
        var appendedEnd = ordered.first().score.chunk.startOffset
        ordered.forEach { fact ->
            val chunk = fact.score.chunk
            val skip = (appendedEnd - chunk.startOffset).coerceIn(0, chunk.text.length)
            if (skip < chunk.text.length) builder.append(chunk.text.substring(skip))
            appendedEnd = max(appendedEnd, chunk.endOffset)
        }
        return builder.toString()
    }

    private fun segmentTextBefore(
        evidence: List<StructuralChunkFact>,
        endOffset: Int
    ): String {
        if (evidence.isEmpty()) return ""
        val ordered = evidence.sortedWith(
            compareBy<StructuralChunkFact> { fact -> fact.score.chunk.chapterIndex }
                .thenBy { fact -> fact.score.chunk.startOffset }
        )
        val builder = StringBuilder()
        var appendedEnd = ordered.first().score.chunk.startOffset
        ordered.forEach { fact ->
            val chunk = fact.score.chunk
            if (chunk.startOffset >= endOffset) return@forEach
            val effectiveEnd = min(chunk.endOffset, endOffset)
            val endInChunk = (effectiveEnd - chunk.startOffset).coerceIn(0, chunk.text.length)
            val skip = (appendedEnd - chunk.startOffset).coerceIn(0, endInChunk)
            if (skip < endInChunk) builder.append(chunk.text.substring(skip, endInChunk))
            appendedEnd = max(appendedEnd, effectiveEnd)
        }
        return builder.toString()
    }

    private fun graphAbsorptionScore(
        evidence: List<StructuralChunkFact>,
        bookModel: StructuralBookModel
    ): Double {
        val known = evidence.flatMap { fact -> fact.knownEntities.keys }.toSet()
        val alien = segmentAlienEntities(evidence, bookModel).keys
        val entityAbsorption = if (known.isEmpty() && alien.isEmpty()) {
            0.0
        } else {
            known.size.toDouble() / (known.size + alien.size).coerceAtLeast(1)
        }
        val relationHits = evidence.sumOf { fact ->
            val knownList = fact.knownEntities.keys.toList()
            var hits = 0
            for (left in knownList.indices) {
                for (right in left + 1 until knownList.size) {
                    if (edgeKey(knownList[left], knownList[right]) in bookModel.relationEdges) hits += 1
                }
            }
            hits
        }
        val relationAbsorption = (relationHits.toDouble() / 4.0).coerceIn(0.0, 1.0)
        val scoreAbsorption = (evidence.sumOf { fact -> fact.score.knownScore } /
            (evidence.sumOf { fact -> fact.score.knownScore + fact.score.alienScore } + 0.0001))
            .coerceIn(0.0, 1.0)
        return max(entityAbsorption, max(relationAbsorption, scoreAbsorption)).coerceIn(0.0, 1.0)
    }

    private fun worldConsistencyScore(
        evidence: List<StructuralChunkFact>,
        bookModel: StructuralBookModel
    ): Double {
        val vector = averageWorldVector(evidence)
        if (vector.isEmpty() || bookModel.worldProfile.isEmpty()) return 0.50
        return cosine(vector, bookModel.worldProfile)
    }

    private fun styleSimilarityScore(
        evidence: List<StructuralChunkFact>,
        bookModel: StructuralBookModel
    ): Double {
        val vector = averageStyleVector(evidence)
        if (vector.isEmpty() || bookModel.styleProfile.isEmpty()) return 0.50
        return cosine(vector, bookModel.styleProfile)
    }

    private fun prototypeSimilarity(
        evidence: List<StructuralChunkFact>,
        bookModel: StructuralBookModel
    ): Double {
        if (bookModel.prototypeTerms.isEmpty() && bookModel.lexicalPrototypes.isEmpty()) return 0.50
        val terms = evidence.flatMap { fact -> fact.terms }.toSet()
        val setSimilarity = if (terms.isEmpty() || bookModel.prototypeTerms.isEmpty()) {
            0.0
        } else {
            bookModel.prototypeTerms.maxOf { prototype -> jaccard(terms, prototype) }
        }
        val sparseVector = sparseVector(evidence.flatMap { fact -> fact.termCounts.entries }
            .groupingBy { entry -> entry.key }
            .fold(0) { total, entry -> total + entry.value }, bookModel.termIdf)
        val sparseSimilarity = if (sparseVector.isEmpty() || bookModel.lexicalPrototypes.isEmpty()) {
            0.0
        } else {
            bookModel.lexicalPrototypes.maxOf { prototype -> cosine(sparseVector, prototype) }
        }
        return max(setSimilarity, sparseSimilarity).coerceIn(0.0, 1.0)
    }

    private fun futureBookIntegrationScore(
        evidence: List<StructuralChunkFact>,
        allFacts: List<StructuralChunkFact>,
        bookModel: StructuralBookModel
    ): Double {
        val chapterIndex = evidence.first().score.chunk.chapterIndex
        val alienTerms = segmentAlienEntities(evidence, bookModel).keys
        if (alienTerms.isEmpty()) return 0.0
        val future = allFacts.filter { fact ->
            fact.score.chunk.chapterIndex > chapterIndex &&
                fact.score.chunk.chapterIndex <= chapterIndex + 3
        }
        if (future.isEmpty()) return 0.0
        val reusedFuture = future.filter { fact -> fact.entities.keys.any { term -> term in alienTerms } }
        if (reusedFuture.isEmpty()) return 0.0
        val integrated = reusedFuture.count { fact ->
            fact.knownEntities.keys.any { term -> term in bookModel.coreEntities } ||
                fact.score.knownScore >= config.smooth * 4.0
        }
        return (integrated.toDouble() / reusedFuture.size).coerceIn(0.0, 1.0)
    }

    private fun sameBookArcEvidence(
        evidence: List<StructuralChunkFact>,
        allFacts: List<StructuralChunkFact>,
        bookModel: StructuralBookModel
    ): SameBookArcEvidence {
        if (evidence.isEmpty() || allFacts.isEmpty()) return SameBookArcEvidence.Empty
        val chapterIndex = evidence.first().score.chunk.chapterIndex
        val alienTerms = segmentAlienEntities(evidence, bookModel).keys.toSet()
        if (alienTerms.isEmpty()) return SameBookArcEvidence.Empty
        val signatureTerms = sameBookSignatureTerms(evidence, bookModel, alienTerms)
        if (signatureTerms.isEmpty()) return SameBookArcEvidence.Empty
        val evidenceCounts = mergeTermCounts(evidence)
        val evidenceVector = sparseVector(evidenceCounts, bookModel.termIdf)

        val matches = allFacts
            .asSequence()
            .filter { fact -> fact.score.chunk.chapterIndex != chapterIndex }
            .mapNotNull { fact ->
                val factTerms = fact.termCounts.keys
                val reusedTerms = alienTerms.filter { term -> term in factTerms || fact.score.chunk.text.contains(term) }
                val signatureOverlap = jaccard(signatureTerms, factTerms)
                val sparseSimilarity = if (evidenceVector.isEmpty()) {
                    0.0
                } else {
                    cosine(evidenceVector, sparseVector(fact.termCounts, bookModel.termIdf))
                }
                val lexicalSimilarity = max(signatureOverlap, sparseSimilarity)
                if (reusedTerms.isEmpty() && lexicalSimilarity < 0.08) return@mapNotNull null
                SameBookArcMatch(
                    chapterIndex = fact.score.chunk.chapterIndex,
                    reusedTerms = reusedTerms.toSet(),
                    lexicalSimilarity = lexicalSimilarity,
                    bridgeScore = sameBookBridgeScore(fact, bookModel)
                )
            }
            .sortedWith(
                compareByDescending<SameBookArcMatch> { match -> match.reusedTerms.size }
                    .thenByDescending { match -> match.lexicalSimilarity }
                    .thenByDescending { match -> match.bridgeScore }
            )
            .take(24)
            .toList()
        if (matches.isEmpty()) return SameBookArcEvidence.Empty

        val pastMatches = matches.filter { match -> match.chapterIndex < chapterIndex }
        val futureMatches = matches.filter { match -> match.chapterIndex > chapterIndex }
        val reusedTerms = matches.flatMap { match -> match.reusedTerms }.toSet()
        val pastReusedTerms = pastMatches.flatMap { match -> match.reusedTerms }.toSet()
        val matchedChapters = matches.map { match -> match.chapterIndex }.distinct().sorted()
        val pastMatchedChapters = pastMatches.map { match -> match.chapterIndex }.distinct().sorted()
        val futureMatchedChapters = futureMatches.map { match -> match.chapterIndex }.distinct().sorted()
        val nearbyPastChapters = pastMatchedChapters.filter { index -> chapterIndex - index <= 3 }
        val nearbyFutureChapters = futureMatchedChapters.filter { index -> index - chapterIndex <= 3 }
        val entityReuse = reusedTerms.size.toDouble() / alienTerms.size.coerceAtLeast(1)
        val pastEntityReuse = pastReusedTerms.size.toDouble() / alienTerms.size.coerceAtLeast(1)
        val maxLexical = matches.maxOf { match -> match.lexicalSimilarity }
        val pastMaxLexical = pastMatches.maxOfOrNull { match -> match.lexicalSimilarity } ?: 0.0
        val maxBridge = matches.maxOf { match -> match.bridgeScore }
        val pastMaxBridge = pastMatches.maxOfOrNull { match -> match.bridgeScore } ?: 0.0
        val nearbyPastBridge = pastMatches
            .filter { match -> chapterIndex - match.chapterIndex <= 3 }
            .maxOfOrNull { match -> match.bridgeScore }
            ?: 0.0
        val supportScore = (
            0.28 * pastEntityReuse.coerceIn(0.0, 1.0) +
                0.22 * (pastMatchedChapters.size.toDouble() / 3.0).coerceIn(0.0, 1.0) +
                0.18 * if (nearbyPastChapters.isNotEmpty()) 1.0 else 0.0 +
                0.18 * (pastMaxLexical / 0.18).coerceIn(0.0, 1.0) +
                0.14 * max(pastMaxBridge, nearbyPastBridge).coerceIn(0.0, 1.0)
            ).coerceIn(0.0, 1.0)

        return SameBookArcEvidence(
            supportScore = supportScore,
            entityReuse = entityReuse.coerceIn(0.0, 1.0),
            pastEntityReuse = pastEntityReuse.coerceIn(0.0, 1.0),
            maxLexicalSimilarity = maxLexical.coerceIn(0.0, 1.0),
            pastMaxLexicalSimilarity = pastMaxLexical.coerceIn(0.0, 1.0),
            maxBridgeScore = maxBridge.coerceIn(0.0, 1.0),
            pastMaxBridgeScore = pastMaxBridge.coerceIn(0.0, 1.0),
            nearbyPastBridgeScore = nearbyPastBridge.coerceIn(0.0, 1.0),
            matchedChapters = matchedChapters.take(8),
            pastMatchedChapters = pastMatchedChapters.takeLast(8),
            futureMatchedChapters = futureMatchedChapters.take(8),
            nearbyPastChapters = nearbyPastChapters.takeLast(8),
            nearbyFutureChapters = nearbyFutureChapters.take(8),
            reusedTerms = reusedTerms.sorted().take(12),
            pastReusedTerms = pastReusedTerms.sorted().take(12)
        )
    }

    private fun sameBookSignatureTerms(
        evidence: List<StructuralChunkFact>,
        bookModel: StructuralBookModel,
        alienTerms: Set<String>
    ): Set<String> {
        val counts = mergeTermCounts(evidence)
        val distinctiveTerms = counts
            .asSequence()
            .filter { (term, _) -> term.length in 2..8 }
            .filter { (term, _) -> term !in genericTerms && term !in nonFeatureTerms }
            .filter { (term, _) -> !looksLikeActionOrClauseFragment(term) }
            .filter { (term, _) -> term !in bookModel.coreTerms }
            .sortedByDescending { (term, count) -> count * (bookModel.termIdf[term] ?: 1.0) }
            .take(80)
            .map { (term, _) -> term }
            .toSet()
        return (alienTerms + distinctiveTerms).filter { term -> term.length >= 2 }.toSet()
    }

    private fun mergeTermCounts(facts: List<StructuralChunkFact>): Map<String, Int> {
        if (facts.isEmpty()) return emptyMap()
        val merged = LinkedHashMap<String, Int>()
        facts.forEach { fact ->
            fact.termCounts.forEach { (term, count) ->
                merged[term] = (merged[term] ?: 0) + count
            }
        }
        return merged
    }

    private fun sameBookBridgeScore(
        fact: StructuralChunkFact,
        bookModel: StructuralBookModel
    ): Double {
        val knownCoreCount = fact.knownEntities.keys.count { term -> term in bookModel.coreEntities }
        val knownRatio = if (fact.entities.isEmpty()) {
            0.0
        } else {
            knownCoreCount.toDouble() / fact.entities.size
        }
        val scoreAbsorption = (fact.score.knownScore /
            (fact.score.knownScore + fact.score.alienScore + 0.0001)).coerceIn(0.0, 1.0)
        val coreHit = (knownCoreCount.toDouble() / 2.0).coerceIn(0.0, 1.0)
        return max(fact.score.belongScore, max(scoreAbsorption, max(knownRatio, coreHit))).coerceIn(0.0, 1.0)
    }

    private fun normalizeTypeCounts(counts: Map<FeatureType, Int>): Map<FeatureType, Double> {
        val total = counts.values.sum().toDouble()
        if (total <= 0.0) return emptyMap()
        return counts.mapValues { (_, count) -> count / total }
    }

    private fun looksLikeStructuralCharacter(term: String): Boolean {
        if (!looksLikeCharacter(term)) return false
        if (looksLikeCharacterFragment(term)) return false
        if (personRoleTerms.any { role -> term.contains(role) }) return false
        if (genericTerms.any { generic -> generic.length >= 2 && term.contains(generic) }) return false
        if (term.any { char -> char in nameNoiseChars }) return false
        if (term.length == 3 && term.drop(1) in compoundSurnames) return false
        if (term.length == 3 && term.take(2) !in compoundSurnames) {
            val given = term.drop(1)
            if (given.any { char -> char in weakActionPrefixChars || char in weakClauseChars }) return false
            if (given.any { char -> char in stopBoundaryChars || char in bodyPartChars }) {
                return false
            }
        }
        return true
    }

    private fun averageWorldVector(facts: List<StructuralChunkFact>): Map<FeatureType, Double> {
        if (facts.isEmpty()) return emptyMap()
        val totals = linkedMapOf<FeatureType, Double>()
        facts.forEach { fact ->
            fact.worldVector.forEach { (type, value) -> totals[type] = (totals[type] ?: 0.0) + value }
        }
        return totals.mapValues { (_, value) -> value / facts.size }
    }

    private fun styleVector(text: String): Map<String, Double> {
        val length = text.length.coerceAtLeast(1).toDouble()
        val paragraphs = text.split('\n').filter { line -> line.isNotBlank() }
        return mapOf(
            "dialogue" to (text.count { char -> char == '“' || char == '”' || char == '"' } / length),
            "comma" to (text.count { char -> char == '，' || char == ',' } / length),
            "period" to (text.count { char -> char == '。' || char == '！' || char == '？' } / length),
            "paragraph" to (paragraphs.size / length)
        )
    }

    private fun averageStyleVector(facts: List<StructuralChunkFact>): Map<String, Double> {
        if (facts.isEmpty()) return emptyMap()
        val totals = linkedMapOf<String, Double>()
        facts.forEach { fact ->
            fact.styleVector.forEach { (key, value) -> totals[key] = (totals[key] ?: 0.0) + value }
        }
        return totals.mapValues { (_, value) -> value / facts.size }
    }

    private fun jaccard(left: Set<String>, right: Set<String>): Double {
        if (left.isEmpty() || right.isEmpty()) return 0.0
        val intersection = left.intersect(right).size.toDouble()
        val union = left.size + right.size - intersection
        return (intersection / union.coerceAtLeast(1.0)).coerceIn(0.0, 1.0)
    }

    private fun <K> cosine(left: Map<K, Double>, right: Map<K, Double>): Double {
        val keys = left.keys + right.keys
        if (keys.isEmpty()) return 0.0
        var dot = 0.0
        var leftNorm = 0.0
        var rightNorm = 0.0
        keys.forEach { key ->
            val l = left[key] ?: 0.0
            val r = right[key] ?: 0.0
            dot += l * r
            leftNorm += l * l
            rightNorm += r * r
        }
        if (leftNorm <= 0.0 || rightNorm <= 0.0) return 0.0
        return (dot / (sqrt(leftNorm) * sqrt(rightNorm))).coerceIn(0.0, 1.0)
    }

    private fun fmt(value: Double): String {
        return "%.2f".format(value)
    }

    private fun topicTerms(text: String): Set<String> {
        return topicTermCounts(text).keys
    }

    private fun topicTermCounts(text: String): Map<String, Int> {
        return candidateOccurrences(text)
            .map { occurrence -> occurrence.term }
            .filter { term -> term.length in 2..5 }
            .filter { term -> term !in genericTerms && term !in nonFeatureTerms }
            .filterNot { term -> looksLikeActionOrClauseFragment(term) }
            .groupingBy { term -> term }
            .eachCount()
    }

    private fun sparseVector(
        termCounts: Map<String, Int>,
        termIdf: Map<String, Double>
    ): Map<String, Double> {
        if (termCounts.isEmpty() || termIdf.isEmpty()) return emptyMap()
        return termCounts.mapNotNull { (term, count) ->
            val idf = termIdf[term] ?: return@mapNotNull null
            term to (ln(1.0 + count.toDouble()) * idf)
        }.toMap()
    }

    private fun averageSparseVector(
        facts: List<StructuralChunkFact>,
        termIdf: Map<String, Double>
    ): Map<String, Double> {
        if (facts.isEmpty()) return emptyMap()
        val totals = LinkedHashMap<String, Double>()
        facts.forEach { fact ->
            sparseVector(fact.termCounts, termIdf).forEach { (term, value) ->
                totals[term] = (totals[term] ?: 0.0) + value
            }
        }
        return totals
            .mapValues { (_, value) -> value / facts.size }
            .entries
            .sortedByDescending { entry -> entry.value }
            .take(120)
            .associate { entry -> entry.key to entry.value }
    }

    private fun suggestion(
        type: PollutionType,
        scores: List<ChunkScore>,
        startOffset: Int,
        confidence: Double
    ): CleanSuggestion {
        val first = scores.first().chunk
        val action = when {
            confidence >= 0.90 && type == PollutionType.SUFFIX_POLLUTION -> CleanAction.AUTO_DELETE_ALLOWED
            confidence >= 0.70 -> CleanAction.SUGGEST_DELETE
            confidence >= 0.50 -> CleanAction.MARK_ONLY
            else -> CleanAction.KEEP
        }
        val reasons = scores.flatMap { score -> score.reasons }.distinct().take(8)
        return CleanSuggestion(
            chapterIndex = first.chapterIndex,
            chapterTitle = first.chapterTitle,
            pollutionType = type,
            startOffset = startOffset,
            endOffset = scores.last().chunk.endOffset,
            confidence = confidence,
            action = action,
            reasons = reasons,
            stateType = outputTypeFor(type)
        )
    }

    private fun outputTypeFor(type: PollutionType): NovelStateOutputType {
        return when (type) {
            PollutionType.SUFFIX_POLLUTION -> NovelStateOutputType.POLLUTED_SUFFIX
            PollutionType.LOCAL_ABNORMAL -> NovelStateOutputType.POLLUTED_RUN
        }
    }

    private fun splitNovelIntoChunks(chapters: List<ChapterInput>): List<TextChunk> {
        val chunks = ArrayList<TextChunk>()
        chapters.forEach { chapter ->
            val content = chapter.content
            val localChunkSize = if (content.length <= config.chunkSize * 2) {
                (config.chunkSize / 2).coerceIn(320, config.chunkSize)
            } else {
                config.chunkSize
            }
            val localOverlap = if (localChunkSize < config.chunkSize) {
                (localChunkSize / 3).coerceAtMost(config.chunkOverlap)
            } else {
                config.chunkOverlap
            }
            if (content.length <= localChunkSize) {
                chunks.add(
                    TextChunk(
                        chapterIndex = chapter.index,
                        chapterTitle = chapter.title,
                        chunkIndex = 0,
                        startOffset = 0,
                        endOffset = content.length,
                        chapterLength = content.length,
                        text = content
                    )
                )
                return@forEach
            }
            val step = (localChunkSize - localOverlap).coerceAtLeast(160)
            var start = 0
            var chunkIndex = 0
            while (start < content.length) {
                val end = min(content.length, start + localChunkSize)
                chunks.add(
                    TextChunk(
                        chapterIndex = chapter.index,
                        chapterTitle = chapter.title,
                        chunkIndex = chunkIndex,
                        startOffset = start,
                        endOffset = end,
                        chapterLength = content.length,
                        text = content.substring(start, end)
                    )
                )
                if (end == content.length) break
                start += step
                chunkIndex += 1
            }
        }
        return chunks
    }

    private fun collectTermStats(chunks: List<TextChunk>): Map<String, MutableTermStats> {
        val stats = LinkedHashMap<String, MutableTermStats>()
        chunks.forEach { chunk ->
            candidateOccurrences(chunk.text).forEach { occurrence ->
                val stat = stats.getOrPut(occurrence.term) { MutableTermStats(occurrence.term) }
                stat.record(chunk, occurrence)
            }
        }
        return stats
    }

    private fun collectFingerprintTermStats(chunks: List<TextChunk>): Map<String, MutableTermStats> {
        if (chunks.size <= 1) return collectTermStats(chunks)

        val lightStats = LinkedHashMap<String, LightTermStats>()
        chunks.forEach { chunk ->
            candidateOccurrences(chunk.text).forEach { occurrence ->
                val stat = lightStats.getOrPut(occurrence.term) { LightTermStats() }
                stat.record(chunk.chapterIndex)
            }
        }

        val keepTerms = lightStats
            .asSequence()
            .filter { (_, stat) ->
                stat.totalHitCount >= config.minFeatureFrequency &&
                    stat.chapterHitCount >= config.minFeatureChapterCount
            }
            .map { (term, _) -> term }
            .toHashSet()
        if (keepTerms.isEmpty()) return emptyMap()

        val stats = LinkedHashMap<String, MutableTermStats>()
        chunks.forEach { chunk ->
            candidateOccurrences(chunk.text).forEach occurrenceLoop@{ occurrence ->
                if (occurrence.term !in keepTerms) return@occurrenceLoop
                val stat = stats.getOrPut(occurrence.term) { MutableTermStats(occurrence.term) }
                stat.record(chunk, occurrence)
            }
        }
        return stats
    }

    private fun candidateOccurrences(text: String): Sequence<TermOccurrence> = sequence {
        chineseRuns(text).forEach { run ->
            for (size in 2..8) {
                if (run.length < size) continue
                for (index in 0..run.length - size) {
                    if (!canGenerateCandidateWindow(run, index, size)) continue
                    val term = run.substring(index, index + size)
                    if (!isRawCandidate(term)) continue
                    yield(
                        TermOccurrence(
                            term = term,
                            left = run.getOrNull(index - 1),
                            right = run.getOrNull(index + size),
                            isRunStart = index == 0,
                            isStandaloneRun = index == 0 && index + size == run.length
                        )
                    )
                }
            }
        }
    }

    private fun canGenerateCandidateWindow(run: String, index: Int, size: Int): Boolean {
        if (size !in 2..8) return false
        val end = index + size
        val first = run[index]
        val last = run[end - 1]
        if (first in weakBoundaryChars && !canStartEntityWindow(run, index, size)) return false
        if (first in stopBoundaryChars && !canStartEntityWindow(run, index, size)) return false
        if (last in weakBoundaryChars || last in stopBoundaryChars) return false
        for (offset in index until end) {
            if (run[offset] in stopInsideChars) return false
        }
        for (offset in index + 1 until end) {
            if (run[offset] != first) return true
        }
        return false
    }

    private fun canStartEntityWindow(run: String, index: Int, size: Int): Boolean {
        if (size < 2) return false
        val first = run[index]
        if (first in namePrefixChars || isSurnameChar(first)) return true
        return compoundSurnames.any { surname ->
            size > surname.length && index + surname.length <= run.length && run.startsWith(surname, index)
        }
    }

    private fun chineseRuns(value: String): List<String> {
        val runs = ArrayList<String>()
        val builder = StringBuilder()
        value.forEach { char ->
            if (char in '\u4e00'..'\u9fff') {
                builder.append(char)
            } else if (builder.isNotEmpty()) {
                runs.add(builder.toString())
                builder.clear()
            }
        }
        if (builder.isNotEmpty()) runs.add(builder.toString())
        return runs
    }

    private fun isRawCandidate(term: String): Boolean {
        if (term.length !in 2..8) return false
        if (term in hardStopTerms || term in nonFeatureTerms) return false
        if (term.any { char -> char in stopInsideChars }) return false
        if (term.first() in weakBoundaryChars && !canStartEntityCandidate(term)) return false
        if (term.first() in stopBoundaryChars && !canStartEntityCandidate(term)) return false
        if (term.last() in weakBoundaryChars) return false
        if (term.last() in stopBoundaryChars) return false
        if (term.count { char -> char == term.first() } == term.length) return false
        return true
    }

    private fun classifyFingerprintTerm(stat: MutableTermStats): FeatureType? {
        val term = stat.text
        if (term in nonFeatureTerms || term in genericTerms) return null
        val typed = classifyTypedTerm(term)
        if (typed != null) {
            return if (passesTypedTermQuality(stat, typed)) typed else null
        }
        return if (term.length >= 4 && termhoodScore(stat, FeatureType.PHRASE) >= 0.55) FeatureType.PHRASE else null
    }

    private fun classifyChunkAlienTerm(stat: MutableTermStats): FeatureType? {
        val type = classifyTypedTerm(stat.text) ?: return null
        if (!passesTypedTermQuality(stat, type)) return null
        if (type == FeatureType.CHARACTER) {
            if (!looksLikeStructuralCharacter(stat.text)) return null
            if (stat.totalHitCount < config.minAlienFeatureFrequency) return null
        }
        return type
    }

    private fun classifyTypedTerm(term: String): FeatureType? {
        if (term in nonFeatureTerms || term in genericTerms) return null
        if (looksLikeSkill(term)) return FeatureType.SKILL
        if (looksLikeItem(term)) return FeatureType.ITEM
        if (looksLikeRealm(term)) return FeatureType.REALM
        if (looksLikeCurrency(term)) return FeatureType.CURRENCY
        if (looksLikeOrganization(term)) return FeatureType.ORGANIZATION
        if (looksLikeLocation(term)) return FeatureType.LOCATION
        if (looksLikeWorldTerm(term)) return FeatureType.WORLD_TERM
        if (looksLikeCharacter(term)) return FeatureType.CHARACTER
        return null
    }

    private fun looksLikeCharacter(term: String): Boolean {
        if (term.length !in 2..4) return false
        if (term in nonCharacterTerms) return false
        val compoundSurname = compoundSurnames.firstOrNull { surname -> term.startsWith(surname) }
        if (compoundSurname != null) return looksLikeCompoundSurnameName(term, compoundSurname)
        if (term.length == 3 && term.last() in characterContinuationSuffixes) return false
        if (term.first() in namePrefixChars) return looksLikePrefixedName(term)
        if (!isSurnameChar(term.first())) return false
        if (term.last() in nonCharacterSuffixChars) return false
        if (term.length == 2 && term.last() in characterContinuationSuffixes) return false
        if (term.any { char -> char in organizationSuffixes || char in locationSuffixes }) return false
        return true
    }

    private fun looksLikeCompoundSurnameName(term: String, surname: String): Boolean {
        val given = term.drop(surname.length)
        if (given.length !in 1..2) return false
        if (term in commonNonEntityTerms || term in personRoleTerms || term in genericTerms) return false
        if (given.any { char -> char in nameNoiseChars }) return false
        if (given.last() in nonCharacterSuffixChars) return false
        if (given.any { char -> char in organizationSuffixes || char in locationSuffixes }) return false
        return true
    }

    private fun canStartEntityCandidate(term: String): Boolean {
        if (term.length < 2) return false
        return compoundSurnames.any { surname -> term.startsWith(surname) } ||
            isSurnameChar(term.first()) ||
            term.first() in namePrefixChars
    }

    private fun looksLikePrefixedName(term: String): Boolean {
        if (term.length !in 2..3) return false
        val body = term.drop(1)
        if (body.isBlank()) return false
        if (term in commonNonEntityTerms || term in personRoleTerms || term in genericTerms) return false
        if (body.any { char -> char in nameNoiseChars }) return false
        if (body.last() in nonCharacterSuffixChars) return false
        if (body.any { char -> char in organizationSuffixes || char in locationSuffixes }) return false
        return true
    }

    private fun isSurnameChar(char: Char): Boolean {
        return char in surnameChars || char in commonSurnameChars
    }

    private fun passesTypedTermQuality(stat: MutableTermStats, type: FeatureType): Boolean {
        val term = stat.text
        if (term in commonNonEntityTerms) return false
        if (isGenericTypedTerm(term, type)) return false
        if (looksLikeActionOrClauseFragment(term)) return false
        if (type == FeatureType.CHARACTER) return passesCharacterTermQuality(stat)
        if (looksLikeBodyPartFragment(term, type)) return false
        if (term.length <= 4) return true
        if (!hasTypedSignal(term, type)) return hasStableBoundaries(stat)
        return typedSignalExcess(term, type) <= 2 || hasStableBoundaries(stat)
    }

    private fun passesCharacterTermQuality(stat: MutableTermStats): Boolean {
        val term = stat.text
        if (!looksLikeStructuralCharacter(term)) return false
        if (term.length != 2) return true
        val requiredRunStarts = (stat.chapterHitCount / 3).coerceIn(2, 6)
        val contextualRunStarts = stat.runStartHitCount - stat.standaloneHitCount
        return contextualRunStarts >= requiredRunStarts
    }

    private fun hasTypedSignal(term: String, type: FeatureType): Boolean {
        return when (type) {
            FeatureType.ORGANIZATION -> organizationSignals.any { signal -> term.contains(signal) }
            FeatureType.LOCATION -> locationSignals.any { signal -> term.contains(signal) }
            FeatureType.SKILL -> skillSignals.any { signal -> term.contains(signal) }
            FeatureType.ITEM -> itemSignals.any { signal -> term.contains(signal) }
            FeatureType.CURRENCY -> currencySignals.any { signal -> term.contains(signal) }
            FeatureType.REALM -> realmSignals.any { signal -> term.contains(signal) }
            FeatureType.WORLD_TERM -> worldSignals.any { signal -> term.contains(signal) }
            FeatureType.PHRASE,
            FeatureType.RELATION_EDGE,
            FeatureType.CHARACTER -> true
        }
    }

    private fun isGenericTypedTerm(term: String, type: FeatureType): Boolean {
        return when (type) {
            FeatureType.ORGANIZATION -> organizationSignals.any { signal -> term == signal }
            FeatureType.REALM -> term.length <= 2 || realmSignals.any { signal -> term == signal }
            FeatureType.LOCATION -> locationSignals.any { signal -> term == signal }
            FeatureType.SKILL -> skillSignals.any { signal -> term == signal }
            FeatureType.ITEM -> itemSignals.any { signal -> term == signal }
            FeatureType.CURRENCY -> currencySignals.any { signal -> term == signal }
            FeatureType.WORLD_TERM -> worldSignals.any { signal -> term == signal }
            FeatureType.CHARACTER,
            FeatureType.PHRASE,
            FeatureType.RELATION_EDGE -> false
        }
    }

    private fun typedSignalExcess(term: String, type: FeatureType): Int {
        val signals = when (type) {
            FeatureType.ORGANIZATION -> organizationSignals
            FeatureType.LOCATION -> locationSignals
            FeatureType.SKILL -> skillSignals
            FeatureType.ITEM -> itemSignals
            FeatureType.CURRENCY -> currencySignals
            FeatureType.REALM -> realmSignals
            FeatureType.WORLD_TERM -> worldSignals
            FeatureType.PHRASE,
            FeatureType.RELATION_EDGE,
            FeatureType.CHARACTER -> emptyList()
        }
        val longest = signals.filter { signal -> term.contains(signal) }.maxOfOrNull { signal -> signal.length } ?: 0
        return term.length - longest
    }

    private fun hasStableBoundaries(stat: MutableTermStats): Boolean {
        val boundaryDiversity = stat.leftBoundaries.size + stat.rightBoundaries.size
        val spread = stat.chapterHitCount
        val repeats = stat.totalHitCount
        return spread >= 5 && repeats >= spread * 2 && boundaryDiversity >= 4
    }

    private fun looksLikeCharacterFragment(term: String): Boolean {
        if (term.length == 3 && term.last() in characterContinuationSuffixes) return true
        return false
    }

    private fun looksLikeBodyPartFragment(term: String, type: FeatureType): Boolean {
        if (type != FeatureType.SKILL && type != FeatureType.ITEM) return false
        if (skillSignals.any { signal -> term.contains(signal) }) return false
        if (itemSignals.any { signal -> term.contains(signal) }) return false
        return bodyPartTerms.any { bodyPart -> term.contains(bodyPart) }
    }

    private fun looksLikeActionOrClauseFragment(term: String): Boolean {
        if (term.first() in weakActionPrefixChars) return true
        if (term.length >= 4 && term.any { char -> char in weakClauseChars }) return true
        if (term.length >= 4 && term.take(2) in hardStopTerms) return true
        return false
    }

    private fun looksLikeOrganization(term: String): Boolean {
        return term.length >= 3 && (term.last() in organizationSuffixes || organizationSignals.any { signal -> term.endsWith(signal) })
    }

    private fun looksLikeLocation(term: String): Boolean {
        return term.length >= 3 && (term.last() in locationSuffixes || locationSignals.any { signal -> term.endsWith(signal) })
    }

    private fun looksLikeSkill(term: String): Boolean {
        return term.length >= 3 && (term.last() in skillSuffixes || skillSignals.any { signal -> term.contains(signal) })
    }

    private fun looksLikeItem(term: String): Boolean {
        return term.length >= 3 && (term.last() in itemSuffixes || itemSignals.any { signal -> term.contains(signal) })
    }

    private fun looksLikeRealm(term: String): Boolean {
        return term.length >= 2 && (realmSignals.any { signal -> term.contains(signal) } || term.last() in realmSuffixes)
    }

    private fun looksLikeCurrency(term: String): Boolean {
        return term.length >= 2 && (currencySignals.any { signal -> term.contains(signal) } || term.last() in currencySuffixes)
    }

    private fun looksLikeWorldTerm(term: String): Boolean {
        return term.length >= 3 && worldSignals.any { signal -> term.contains(signal) }
    }

    private fun buildRelationEdges(chunks: List<TextChunk>, features: List<FingerprintFeature>): Map<String, Double> {
        val entityFeatures = features
            .asSequence()
            .filter { feature -> feature.type != FeatureType.PHRASE }
            .distinctBy { feature -> feature.text }
            .sortedByDescending { feature -> feature.weight }
            .toList()
        val relationStats = LinkedHashMap<String, MutableRelationStats>()
        chunks.forEach { chunk ->
            val terms = entityFeatures
                .asSequence()
                .filter { feature -> chunk.text.contains(feature.text) }
                .take(18)
                .toList()
            for (left in terms.indices) {
                for (right in left + 1 until terms.size) {
                    val edge = edgeKey(terms[left].text, terms[right].text)
                    val stat = relationStats.getOrPut(edge) { MutableRelationStats(edge) }
                    stat.record(chunk.chapterIndex)
                }
            }
        }
        return relationStats.values
            .asSequence()
            .filter { stat -> stat.chapterHitCount >= config.minFeatureChapterCount }
            .sortedByDescending { stat -> stat.weight() }
            .take(config.supportFeatureLimit)
            .associate { stat -> stat.text to stat.weight() }
    }

    private fun scoreKnownRelations(terms: List<String>, fingerprint: NovelFingerprint): Double {
        val distinct = terms.distinct().take(18)
        var score = 0.0
        for (left in distinct.indices) {
            for (right in left + 1 until distinct.size) {
                score += fingerprint.relationEdges[edgeKey(distinct[left], distinct[right])] ?: 0.0
            }
        }
        return score
    }

    private fun scoreAlienRelations(terms: List<String>): Double {
        val distinct = terms.distinct().take(12)
        if (distinct.size < 2) return 0.0
        return (distinct.size * (distinct.size - 1) / 2).toDouble()
    }

    private fun edgeKey(left: String, right: String): String {
        return if (left <= right) "$left->$right" else "$right->$left"
    }

    private fun MutableRelationStats.weight(): Double {
        return ln(1.0 + chapterHitCount.toDouble()) * 8.0 + ln(1.0 + totalHitCount.toDouble()) * 4.0
    }

    private fun buildReasons(
        belongScore: Double,
        knownFeatures: List<String>,
        alienFeatures: List<String>,
        relationScore: Double,
        alienRelationScore: Double
    ): List<String> {
        val reasons = ArrayList<String>()
        if (belongScore < config.suspiciousThreshold) reasons.add("core fingerprint coverage is low")
        if (alienFeatures.distinct().size >= 3) {
            reasons.add("alien features: ${alienFeatures.distinct().take(8).joinToString(",")}")
        }
        if (knownFeatures.isEmpty()) reasons.add("no known identity features matched")
        if (relationScore <= 0.0 && alienRelationScore > 0.0) {
            reasons.add("alien relation edges are not connected to core graph")
        }
        return reasons
    }

    private fun estimatedFeatureWeight(term: String, type: FeatureType): Double {
        return typeBoost(type) * specificityScore(term, type) * 16.0
    }

    private fun typeBoost(type: FeatureType): Double {
        return when (type) {
            FeatureType.CHARACTER -> 1.9
            FeatureType.ORGANIZATION -> 1.7
            FeatureType.LOCATION -> 1.5
            FeatureType.SKILL -> 1.65
            FeatureType.ITEM -> 1.5
            FeatureType.CURRENCY -> 1.25
            FeatureType.REALM -> 1.35
            FeatureType.WORLD_TERM -> 1.45
            FeatureType.PHRASE -> 0.45
            FeatureType.RELATION_EDGE -> 2.0
        }
    }

    private fun specificityScore(term: String, type: FeatureType): Double {
        if (term in genericTerms) return 0.0
        val lengthScore = when (term.length) {
            2 -> 0.9
            3 -> 1.1
            4 -> 1.2
            else -> 1.28
        }
        val typeScore = if (type == FeatureType.PHRASE) 0.65 else 1.0
        return lengthScore * typeScore
    }

    private fun termhoodScore(stat: MutableTermStats, type: FeatureType): Double {
        if (stat.text.length >= 4 && stat.text.take(2) == stat.text.takeLast(2)) return 0.35
        if (type != FeatureType.PHRASE) return 1.0
        val leftDiversity = stat.leftBoundaries.size
        val rightDiversity = stat.rightBoundaries.size
        val boundaryScore = ((leftDiversity + rightDiversity).toDouble() / 6.0).coerceIn(0.35, 1.0)
        val repeatScore = (stat.totalHitCount.toDouble() / (stat.chapterHitCount * 2.0).coerceAtLeast(1.0))
            .coerceIn(0.45, 1.0)
        return boundaryScore * repeatScore
    }

    private fun judgmentStart(chunk: TextChunk): Int {
        return (chunk.chapterLength * config.judgmentStartRatio).toInt().coerceAtLeast(0)
    }

    private fun minimumAbnormalEvidenceChars(chapterLength: Int): Int {
        return max(320, min(1_200, chapterLength / 4))
    }

    private fun cleanChapterTitle(value: String): String {
        return value.replace(Regex("""\s+"""), " ").trim()
    }

    private fun normalizeText(value: String): String {
        return value
            .replace(Regex("""<[^>]+>"""), "\n")
            .replace(Regex("""(?i)&nbsp;|&amp;|&lt;|&gt;"""), " ")
            .replace(Regex("""(?m)^.*(最新网址|请收藏|无弹窗|手机阅读|加入书签).*$"""), "")
            .replace(Regex("""[ \t\r]+"""), " ")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }

    private fun log(stage: String, value: String) {
        traceLines.add("$stage: $value")
    }

    private data class StructuralChunkFact(
        val score: ChunkScore,
        val termCounts: Map<String, Int>,
        val entities: Map<String, FeatureType>,
        val knownEntities: Map<String, FeatureType>,
        val alienEntities: Map<String, FeatureType>,
        val worldVector: Map<FeatureType, Double>,
        val styleVector: Map<String, Double>
    ) {
        val terms: Set<String>
            get() = termCounts.keys
    }

    private data class StructuralBookModel(
        val coreEntities: Set<String>,
        val coreTerms: Set<String>,
        val relationEdges: Set<String>,
        val prototypeTerms: List<Set<String>>,
        val termIdf: Map<String, Double>,
        val lexicalPrototypes: List<Map<String, Double>>,
        val worldProfile: Map<FeatureType, Double>,
        val styleProfile: Map<String, Double>,
        val cleanFactCount: Int
    )

    private data class StructuralScores(
        val breakScore: Double,
        val suffixCohesion: Double,
        val separation: Double,
        val membershipLow: Double,
        val alienCluster: Double,
        val alienContinuity: Double,
        val alienNovelty: Double,
        val alienEntityCount: Int,
        val alienIdentityStrength: Double,
        val graphAbsorption: Double,
        val worldConsistency: Double,
        val prototypeSimilarity: Double,
        val futureIntegration: Double,
        val oodScore: Double,
        val prefixBookStrength: Double,
        val prefixAlienAbsorption: Double,
        val titleAbsorption: Double,
        val expositoryScore: Double,
        val evidenceChars: Int,
        val evidenceCoverage: Double,
        val confidence: Double
    )

    private data class SameBookArcEvidence(
        val supportScore: Double,
        val entityReuse: Double,
        val pastEntityReuse: Double,
        val maxLexicalSimilarity: Double,
        val pastMaxLexicalSimilarity: Double,
        val maxBridgeScore: Double,
        val pastMaxBridgeScore: Double,
        val nearbyPastBridgeScore: Double,
        val matchedChapters: List<Int>,
        val pastMatchedChapters: List<Int>,
        val futureMatchedChapters: List<Int>,
        val nearbyPastChapters: List<Int>,
        val nearbyFutureChapters: List<Int>,
        val reusedTerms: List<String>,
        val pastReusedTerms: List<String>
    ) {
        fun absorbs(scores: StructuralScores): Boolean {
            if (this == Empty) return false
            val hasPastAnchor = pastMatchedChapters.isNotEmpty() &&
                pastEntityReuse >= 0.18 &&
                (pastMaxLexicalSimilarity >= 0.08 || pastMaxBridgeScore >= 0.35)
            val nearbyPastArc = nearbyPastChapters.isNotEmpty() &&
                pastEntityReuse >= 0.18 &&
                (pastMaxLexicalSimilarity >= 0.06 || nearbyPastBridgeScore >= 0.30)
            val repeatedPastArc = pastMatchedChapters.size >= 2 &&
                pastEntityReuse >= 0.25 &&
                supportScore >= 0.42
            val longRangeKnownArc = pastMatchedChapters.isNotEmpty() &&
                pastEntityReuse >= 0.40 &&
                (pastMaxLexicalSimilarity >= 0.08 || pastMaxBridgeScore >= 0.35) &&
                scores.prefixBookStrength >= 0.60 &&
                scores.alienEntityCount <= 5 &&
                scores.alienIdentityStrength <= 0.70
            val strongForeignStart = scores.prefixBookStrength <= 0.05 &&
                scores.membershipLow >= 0.90 &&
                scores.alienCluster >= 0.75 &&
                pastMatchedChapters.isEmpty()
            if (strongForeignStart) return false
            return (hasPastAnchor && nearbyPastArc && scores.worldConsistency >= 0.60) ||
                (repeatedPastArc && scores.worldConsistency >= 0.70) ||
                longRangeKnownArc ||
                (supportScore >= 0.62 && pastMatchedChapters.isNotEmpty())
        }

        fun describe(): String {
            return "support=${fmtStatic(supportScore)} entityReuse=${fmtStatic(entityReuse)} " +
                "pastReuse=${fmtStatic(pastEntityReuse)} lexical=${fmtStatic(maxLexicalSimilarity)} " +
                "pastLexical=${fmtStatic(pastMaxLexicalSimilarity)} bridge=${fmtStatic(maxBridgeScore)} " +
                "pastBridge=${fmtStatic(pastMaxBridgeScore)} nearPastBridge=${fmtStatic(nearbyPastBridgeScore)} " +
                "past=${pastMatchedChapters.joinToString(",")} future=${futureMatchedChapters.joinToString(",")} " +
                "nearPast=${nearbyPastChapters.joinToString(",")} nearFuture=${nearbyFutureChapters.joinToString(",")} " +
                "terms=${pastReusedTerms.ifEmpty { reusedTerms }.joinToString("|")}"
        }

        companion object {
            val Empty = SameBookArcEvidence(
                supportScore = 0.0,
                entityReuse = 0.0,
                pastEntityReuse = 0.0,
                maxLexicalSimilarity = 0.0,
                pastMaxLexicalSimilarity = 0.0,
                maxBridgeScore = 0.0,
                pastMaxBridgeScore = 0.0,
                nearbyPastBridgeScore = 0.0,
                matchedChapters = emptyList(),
                pastMatchedChapters = emptyList(),
                futureMatchedChapters = emptyList(),
                nearbyPastChapters = emptyList(),
                nearbyFutureChapters = emptyList(),
                reusedTerms = emptyList(),
                pastReusedTerms = emptyList()
            )

            private fun fmtStatic(value: Double): String = "%.2f".format(value)
        }
    }

    private data class SameBookArcMatch(
        val chapterIndex: Int,
        val reusedTerms: Set<String>,
        val lexicalSimilarity: Double,
        val bridgeScore: Double
    )

    private data class SegmentEntity(
        val text: String,
        val type: FeatureType,
        val count: Int
    )

    private data class SegmentEntityCandidate(
        val text: String,
        val type: FeatureType,
        val count: Int,
        val repeated: Boolean,
        val strongTyped: Boolean
    )

    private data class StructuralDecision(
        val type: PollutionType,
        val scores: List<ChunkScore>,
        val startOffset: Int,
        val confidence: Double,
        val reasons: List<String>
    ) {
        fun toCleanSuggestion(): CleanSuggestion {
            val first = scores.first().chunk
            val action = when {
                confidence >= 0.90 && type == PollutionType.SUFFIX_POLLUTION -> CleanAction.AUTO_DELETE_ALLOWED
                confidence >= 0.70 -> CleanAction.SUGGEST_DELETE
                confidence >= 0.50 -> CleanAction.MARK_ONLY
                else -> CleanAction.KEEP
            }
            return CleanSuggestion(
                chapterIndex = first.chapterIndex,
                chapterTitle = first.chapterTitle,
                pollutionType = type,
                startOffset = startOffset,
                endOffset = scores.last().chunk.endOffset,
                confidence = confidence,
                action = action,
                reasons = reasons.take(8),
                stateType = when (type) {
                    PollutionType.SUFFIX_POLLUTION -> NovelStateOutputType.POLLUTED_SUFFIX
                    PollutionType.LOCAL_ABNORMAL -> NovelStateOutputType.POLLUTED_RUN
                }
            )
        }
    }

    private data class TermOccurrence(
        val term: String,
        val left: Char?,
        val right: Char?,
        val isRunStart: Boolean,
        val isStandaloneRun: Boolean
    )

    private data class MutableTermStats(
        val text: String,
        var totalHitCount: Int = 0,
        var chapterHitCount: Int = 0,
        var chunkHitCount: Int = 0,
        var runStartHitCount: Int = 0,
        var standaloneHitCount: Int = 0,
        private var lastChapterIndex: Int = Int.MIN_VALUE,
        private var lastChunkChapterIndex: Int = Int.MIN_VALUE,
        private var lastChunkIndex: Int = Int.MIN_VALUE,
        val leftBoundaries: MutableSet<Char> = LinkedHashSet(),
        val rightBoundaries: MutableSet<Char> = LinkedHashSet()
    ) {
        fun record(chunk: TextChunk, occurrence: TermOccurrence) {
            totalHitCount += 1
            if (occurrence.isRunStart) runStartHitCount += 1
            if (occurrence.isStandaloneRun) standaloneHitCount += 1
            if (lastChapterIndex != chunk.chapterIndex) {
                chapterHitCount += 1
                lastChapterIndex = chunk.chapterIndex
            }
            if (lastChunkChapterIndex != chunk.chapterIndex || lastChunkIndex != chunk.chunkIndex) {
                chunkHitCount += 1
                lastChunkChapterIndex = chunk.chapterIndex
                lastChunkIndex = chunk.chunkIndex
            }
            if (leftBoundaries.size < 12) occurrence.left?.let { char -> leftBoundaries.add(char) }
            if (rightBoundaries.size < 12) occurrence.right?.let { char -> rightBoundaries.add(char) }
        }
    }

    private data class MutableRelationStats(
        val text: String,
        var totalHitCount: Int = 0,
        var chapterHitCount: Int = 0,
        private var lastChapterIndex: Int = Int.MIN_VALUE
    ) {
        fun record(chapterIndex: Int) {
            totalHitCount += 1
            if (lastChapterIndex != chapterIndex) {
                chapterHitCount += 1
                lastChapterIndex = chapterIndex
            }
        }
    }

    private data class LightTermStats(
        var totalHitCount: Int = 0,
        var chapterHitCount: Int = 0,
        private var lastChapterIndex: Int = Int.MIN_VALUE
    ) {
        fun record(chapterIndex: Int) {
            totalHitCount += 1
            if (lastChapterIndex != chapterIndex) {
                chapterHitCount += 1
                lastChapterIndex = chapterIndex
            }
        }
    }

    private companion object {
        private val stopInsideChars = setOf(
            '的', '了', '着', '过', '是', '在', '有', '和', '与', '及', '或', '也', '都', '就', '还', '又',
            '很', '更', '最', '这', '那', '哪', '个', '些', '么', '吗', '呢', '啊', '吧', '把', '被', '从',
            '对', '为', '以', '而', '并', '但', '却', '所', '其', '他', '她', '它', '你', '我', '谁', '不',
            '没', '再', '才', '让', '给'
        )
        private val stopBoundaryChars = setOf(
            '上', '下', '中', '里', '外', '内', '前', '后', '左', '右', '边', '处', '时', '间', '次', '种',
            '样', '般', '然', '于', '将', '向', '只', '可', '能', '会'
        )
        private val weakBoundaryChars = setOf(
            '一', '二', '两', '三', '四', '五', '六', '七', '八', '九', '十', '百', '千', '万', '亿', '第'
        )
        private val hardStopTerms = setOf(
            "一个", "这个", "那个", "自己", "他们", "她们", "我们", "你们", "不是", "没有", "还是", "只是",
            "已经", "可以", "因为", "所以", "但是", "如果", "时候", "什么", "怎么", "这里", "那里", "起来",
            "下去", "说道", "问道", "知道", "不能", "不会", "不用", "不知", "之后", "之前", "正在", "仍然",
            "依旧", "突然", "看着", "看向", "低声", "开口", "本章", "章节", "正文", "时间", "先前", "对方"
        )
        private val genericTerms = hardStopTerms + setOf(
            "修炼", "宗门", "长老", "公司", "总裁", "合同", "手机", "电脑", "现金", "灵气", "法宝", "弟子",
            "师兄", "师姐", "师父", "老者", "男子", "女子", "少年", "少女", "皇帝", "王爷", "任何",
            "周围", "庞大", "许多", "众人", "所有", "一些", "不少", "众多", "缓缓", "无法", "虽说",
            "然而", "然后", "几乎", "甚至", "显然", "片刻", "短短", "此刻", "此时", "顿时", "终于",
            "青年", "老人", "小子", "安静", "高手"
        )
        private val nonFeatureTerms = emptySet<String>()
        private val nonCharacterTerms = nonFeatureTerms + genericTerms
        private val commonNonEntityTerms = genericTerms + setOf("几分钟", "短几分钟")
        private val bodyPartTerms = setOf(
            "心", "脸", "手", "身", "嘴", "面", "眼", "眸", "眉", "头", "声", "肩", "背", "腰", "腿", "脚",
            "手掌", "掌心", "脸色", "身形", "身影", "目光"
        )
        private val bodyPartChars = bodyPartTerms.flatMap { term -> term.toList() }.toSet()
        private val characterContinuationSuffixes = setOf(
            '心', '脸', '手', '身', '嘴', '面', '眼', '眸', '眉', '头', '声', '色', '影', '气', '力', '步',
            '掌', '指', '腿', '脚', '肩', '背', '腰', '微', '笑', '等'
        )
        private val weakActionPrefixChars = setOf(
            '进', '涌', '缓', '往', '飞', '带', '穿', '越', '说', '虽', '何', '任', '幸', '得', '将', '让',
            '被', '使', '向', '朝', '从', '入', '出', '回', '人', '地', '锐', '尖', '干', '短', '当', '笼',
            '罩', '阵', '自', '涡', '片'
        )
        private val weakClauseChars = setOf(
            '进', '涌', '缓', '往', '飞', '带', '穿', '越', '说', '何', '任', '幸', '得', '将', '让', '被',
            '使', '向', '朝', '从', '入', '出', '回', '当', '笼', '罩', '自', '涡', '到', '下'
        )

        private val surnameChars = setOf(
            '赵', '钱', '孙', '李', '周', '吴', '郑', '王', '冯', '陈', '卫', '蒋', '沈', '韩', '杨',
            '朱', '秦', '许', '何', '吕', '张', '曹', '严', '华', '金', '魏', '姜', '谢', '苏', '潘',
            '范', '彭', '马', '方', '任', '袁', '柳', '唐', '薛', '雷', '贺', '罗', '安', '傅', '顾',
            '孟', '黄', '萧', '尹', '姚', '汪', '宋', '庞', '梁', '杜', '蓝', '季', '钟', '徐', '高',
            '夏', '蔡', '田', '胡', '陆', '莫', '宁', '白', '卓', '楚', '墨', '言', '兰', '江', '林',
            '叶', '龙', '牧', '洛', '青', '程', '黎', '乔', '温', '桑', '石', '古', '丁',
            '施', '孔', '俞', '戚', '鲍', '史', '苗', '花', '费', '岑', '汤', '滕', '殷', '常',
            '乐', '于', '齐', '康', '伍', '余', '元', '卜', '平', '和', '穆', '肖', '尤', '祝',
            '左', '崔', '吉', '龚', '邢', '裴', '荣', '翁', '荀', '羊', '惠', '甄', '曲', '封',
            '芮', '储', '靳', '松', '井', '段', '富', '巫', '乌', '焦', '巴', '弓', '谷', '车',
            '侯', '全', '班', '仰', '秋', '仲', '伊', '宫', '仇', '栾', '甘', '厉', '戎', '武',
            '符', '刘', '景', '詹', '束', '韶', '郜', '薄', '印', '宿', '怀', '从', '鄂', '索',
            '赖', '蔺', '屠', '蒙', '池', '阴', '郁', '胥', '苍', '双', '闻', '莘', '党', '翟',
            '谭', '贡', '劳', '逄', '姬', '申', '扶', '冉', '宰', '雍', '桑', '桂', '牛', '寿',
            '通', '边', '扈', '燕', '冀', '浦', '尚', '农', '别', '庄', '晏', '柴', '瞿', '阎',
            '连', '习', '艾', '鱼', '容', '向', '易', '慎', '戈', '廖', '终', '居', '衡', '步',
            '都', '耿', '满', '弘', '匡', '文', '寇', '广', '禄', '冷', '沐', '时'
        )
        private val commonSurnameChars = setOf(
            '王', '李', '张', '刘', '陈', '杨', '黄', '赵', '吴', '周', '徐', '孙', '马', '朱', '胡',
            '郭', '何', '林', '高', '梁', '郑', '罗', '宋', '谢', '唐', '韩', '曹', '许', '邓', '萧',
            '冯', '曾', '程', '蔡', '彭', '潘', '袁', '于', '董', '余', '苏', '叶', '吕', '魏', '蒋',
            '田', '杜', '丁', '沈', '姜', '范', '江', '傅', '钟', '卢', '汪', '戴', '崔', '任', '陆',
            '廖', '姚', '方', '金', '邱', '夏', '谭', '韦', '贾', '邹', '石', '熊', '孟', '秦', '白',
            '侯', '段', '雷', '龙', '史', '陶', '黎', '贺', '顾', '毛', '郝', '龚', '邵', '万'
        )
        private val compoundSurnames = setOf(
            "欧阳", "司马", "上官", "诸葛", "东方", "皇甫", "尉迟", "公孙", "令狐", "宇文", "司徒", "慕容",
            "夏侯", "长孙", "南宫", "端木", "百里", "东郭", "西门", "独孤", "呼延", "轩辕"
        )
        private val personRoleTerms = genericTerms + setOf(
            "男人", "女人", "妇女", "同伴", "公子", "姑娘", "大人", "道人", "先生", "夫人", "师尊", "师叔"
        )
        private val roleOrCreatureChars = setOf(
            '男', '女', '妇', '夫', '狐', '狼', '虎', '犬', '兽', '妖', '鬼', '魔'
        )
        private val expositoryConnectors = listOf(
            "是指", "可出现", "表现为", "包括", "另外还有", "以下表现", "受到损", "产生", "作用"
        )
        private val namePrefixChars = setOf('老', '小', '阿')
        private val nameNoiseChars = weakActionPrefixChars + weakClauseChars + stopInsideChars + stopBoundaryChars + bodyPartChars
        private val nonCharacterSuffixChars = setOf(
            '朝', '国', '城', '港', '府', '山', '海', '湖', '河', '楼', '船', '门', '寺', '院', '街',
            '巷', '氏', '钱', '银', '两', '斤', '章', '回', '卷', '时', '间', '说', '道', '问', '看',
            '听', '想', '站', '走', '来', '去', '家'
        )

        private val organizationSuffixes = setOf('宗', '门', '派', '盟', '阁', '府', '司', '院', '堂', '族', '朝', '国', '队', '会', '宫')
        private val locationSuffixes = setOf('山', '城', '州', '岛', '海', '域', '界', '谷', '峰', '洞', '港', '湖', '河', '郡', '村', '镇')
        private val skillSuffixes = setOf('诀', '法', '术', '阵', '咒', '功', '拳', '掌', '式', '禁')
        private val itemSuffixes = setOf(
            '剑', '刀', '枪', '鼎', '钟', '珠', '印', '符', '书', '炉', '甲', '丹', '药', '镜',
            '塔', '幡', '旗', '环', '镯', '瓶', '舟', '车', '船', '舰', '梭', '辇'
        )
        private val realmSuffixes = setOf('境', '期', '阶', '品')
        private val currencySuffixes = setOf('石', '币', '钱', '金', '银', '玉', '晶', '矿')

        private val organizationSignals = listOf("宗门", "仙宗", "魔宗", "剑宗", "书院", "联盟", "王朝", "集团", "董事会", "公司", "政府", "家族")
        private val locationSignals = listOf("洞府", "山脉", "仙界", "魔界", "秘境", "星域", "区域")
        private val skillSignals = listOf("神通", "阵法", "剑气", "禁制", "真元", "剑诀", "心法")
        private val itemSignals = listOf("法宝", "灵石", "玉简", "符箓", "飞剑", "丹药", "葫芦")
        private val realmSignals = listOf("筑基", "金丹", "元婴", "炼气", "化神", "婴变", "问鼎", "阴虚", "阳实")
        private val currencySignals = listOf("灵石", "金币", "银票", "晶石")
        private val worldSignals = listOf("灵气", "妖气", "仙人", "魔气", "洞府", "轮回", "天道", "本源", "星域")
        private val metaTitleSignals = listOf(
            "请假", "感言", "完本", "完结", "新书", "预告", "抽奖", "中奖", "活动", "月票", "求票",
            "休息", "歇", "公告", "通知"
        )
        private val metaBodySignals = listOf(
            "请假", "感谢支持", "月票", "订阅", "全订", "盟主", "打赏", "起点", "评论", "读者",
            "章节", "更新", "作者", "新书", "完本", "完结", "上架", "均订", "编辑", "公众号",
            "作家", "书友", "投票", "中奖", "抽奖", "加更", "请一天", "休息一天", "写完", "码字",
            "这本书"
        )
        private val metaAuthoringSignals = listOf(
            "我写", "写这", "这本书", "新书", "读者", "作者", "编辑", "章节", "更新", "码字",
            "月票", "感谢", "订阅", "完本", "完结"
        )
        private val structuralAlienTypes = setOf(
            FeatureType.CHARACTER,
            FeatureType.ORGANIZATION,
            FeatureType.LOCATION,
            FeatureType.REALM,
            FeatureType.WORLD_TERM
        )
    }
}
