package com.ldp.reader.sourceengine.content.v8
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class V8PsbmtDetector(
    private val config: V8PsbmtConfig = V8PsbmtConfig(),
    private val semanticModel: V8SemanticModel = V8SparseSemanticModel(),
    maxResultCacheEntries: Int = 256,
    maxCleanCacheEntries: Int = 512
) {
    private val resultCacheEnabled = maxResultCacheEntries > 0
    private val resultCache = object : LinkedHashMap<String, V8PsbmtResult>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V8PsbmtResult>?): Boolean {
            return size > maxResultCacheEntries.coerceAtLeast(0)
        }
    }
    private val cleanCacheEnabled = maxCleanCacheEntries > 0
    private val cleanCache = object : LinkedHashMap<String, V8CleanText>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V8CleanText>?): Boolean {
            return size > maxCleanCacheEntries.coerceAtLeast(0)
        }
    }

    fun detect(input: V8PsbmtInput): V8PsbmtResult {
        val startedAtMs = System.currentTimeMillis()
        val cacheKey = if (resultCacheEnabled) input.cacheKey() else null
        if (cacheKey != null) {
            synchronized(resultCache) {
                resultCache[cacheKey]?.let { cached ->
                    return cached.copy(
                        ms = System.currentTimeMillis() - startedAtMs,
                        cacheHit = true
                    )
                }
            }
        }
        val current = clean(input.current.text, input.current.title)
        if (current.body.length < config.minCurrentChars || current.noiseRatio > config.maxNoiseRatio) {
            return terminal(V8PsbmtStatus.SOURCE_QUALITY_PROBLEM, V8PsbmtType.SOURCE_QUALITY_PROBLEM, current, startedAtMs)
                .also { result -> cacheKey?.let { key -> cacheResult(key, result) } }
        }

        val references = input.previousChapters
            .filter { chapter -> chapter.trusted }
            .takeLast(config.maxReferenceChapters)
            .map { chapter -> clean(chapter.text, chapter.title).body.take(config.maxReferenceCharsPerChapter) }
            .filter { text -> text.length >= config.minReferenceChapterChars }
        if (references.size < config.minReferenceChapters) {
            return terminal(V8PsbmtStatus.INSUFFICIENT_CONTEXT, V8PsbmtType.INSUFFICIENT_CONTEXT, current, startedAtMs)
                .also { result -> cacheKey?.let { key -> cacheResult(key, result) } }
        }

        val futureTexts = input.nextChapters
            .take(config.maxFutureChapters)
            .map { chapter -> clean(chapter.text, chapter.title).body }
            .filter { text -> text.length >= config.minFutureChapterChars }

        val semanticSpace = semanticModel.build(references, current.body, futureTexts, config)
        if (semanticSpace.referenceWindows.size < config.minReferenceWindows) {
            return terminal(V8PsbmtStatus.INSUFFICIENT_CONTEXT, V8PsbmtType.INSUFFICIENT_CONTEXT, current, startedAtMs)
                .also { result -> cacheKey?.let { key -> cacheResult(key, result) } }
        }

        val identitySketch = V8IdentitySketch.build(references, semanticSpace.idf, config)
        if (identitySketch.size < config.minIdentitySketchTerms) {
            return terminal(V8PsbmtStatus.INSUFFICIENT_CONTEXT, V8PsbmtType.INSUFFICIENT_CONTEXT, current, startedAtMs)
                .also { result -> cacheKey?.let { key -> cacheResult(key, result) } }
        }

        val support = V8Support(semanticSpace, identitySketch, config)
        val calibration = calibrateReference(support, semanticSpace.referenceWindows)
        val currentWindows = indexedWindows(current.body)
            .map { window -> window.withSupport(support.membership(window.text), support.identity(window.text)) }
        val future = buildFutureEvidence(futureTexts, support, calibration)
        val candidates = scanCandidates(current.body, currentWindows, support, calibration, future)
        val whole = wholeChapter(currentWindows, future, calibration)
        val decision = decide(candidates, whole)

        return V8PsbmtResult(
            status = decision.status,
            type = decision.type,
            offset = decision.offset,
            confidence = decision.confidence,
            cleanedLength = current.body.length,
            removedChars = current.removedChars,
            noiseRatio = current.noiseRatio,
            ms = System.currentTimeMillis() - startedAtMs,
            evidence = decision.evidence,
            candidates = candidates.sortedByDescending { candidate -> candidate.score }.take(config.maxReturnedCandidates)
        ).also { result -> cacheKey?.let { key -> cacheResult(key, result) } }
    }

    private fun cacheResult(cacheKey: String, result: V8PsbmtResult) {
        synchronized(resultCache) {
            resultCache[cacheKey] = result.copy(cacheHit = false)
        }
    }

    private fun scanCandidates(
        text: String,
        currentWindows: List<V8Window>,
        support: V8Support,
        calibration: V8Calibration,
        future: V8FutureEvidence
    ): List<V8Candidate> {
        val offsets = candidateOffsets(text)
        return offsets.mapNotNull { offset ->
            if (offset >= text.length) return@mapNotNull null
            val postWindows = currentWindows
                .filter { window ->
                    window.start < offset + config.maxSuffixChars &&
                        window.end > offset &&
                        window.end - max(window.start, offset) >= config.minPostWindowOverlap
                }
                .take(config.maxSuffixWindows)
            if (postWindows.sumOf { window -> window.end - window.start } < config.minSuffixChars) return@mapNotNull null

            val prefix = text.substring(0, offset)
            val suffixText = text.substring(offset, min(text.length, offset + config.maxFragmentChars))
            val prefixSupport = support.membership(prefix)
            val prefixIdentity = support.identity(prefix)
            val suffixSupports = postWindows.map { window -> window.support }
            val suffixIdentities = postWindows.map { window -> window.identity }
            val suffixMedian = suffixSupports.median()
            val suffixLowRatio = suffixSupports.count { value -> value < calibration.lowThreshold }
                .toDouble() / suffixSupports.size.coerceAtLeast(1)
            val longestLowRun = suffixSupports.longestRun { value -> value < calibration.lowThreshold }
            val suffixIdentityLowRatio = suffixIdentities.count { value -> value < config.identityLowThreshold }
                .toDouble() / suffixIdentities.size.coerceAtLeast(1)
            val belongDrop = prefixSupport - suffixMedian
            val identityDrop = prefixIdentity - suffixIdentities.median()
            val futureSupport = future.suffixSupport(postWindows, support)
            val futureIdentitySupport = future.suffixIdentitySupport(postWindows)
            val localRupture = localRupture(text, offset)
            val suffixRepeatRatio = repeatedNgramRatio(suffixText)
            val fragmentTail = offset <= config.maxFragmentOffset &&
                suffixText.length >= config.minFragmentChars &&
                suffixRepeatRatio <= config.fragmentRepeatRatio &&
                localRupture >= config.fragmentLocalRupture
            val futureRescue = future.trusted &&
                futureSupport >= config.futureRescueThreshold &&
                !(fragmentTail && future.fragmentRisk)
            val tailRisk = !future.trusted &&
                future.available &&
                futureSupport >= config.tailClusterFutureThreshold &&
                futureIdentitySupport >= config.tailClusterFutureIdentityThreshold &&
                (suffixLowRatio >= config.tailClusterLowRatio ||
                    suffixIdentityLowRatio >= config.tailClusterIdentityLowRatio)
            val suffixSafe = suffixLowRatio <= config.safeSuffixLowRatio &&
                suffixMedian >= calibration.lowThreshold &&
                (futureRescue || suffixMedian >= calibration.normalThreshold)
            val persistentLow = suffixLowRatio >= config.wrongSuffixLowRatio &&
                longestLowRun >= config.wrongLongestLowRun
            val suffixAbsolutelyLow = suffixMedian < calibration.lowThreshold && persistentLow
            val prefixBelongs = prefixSupport >= calibration.prefixThreshold
            val fragmentPrefixBelongs = fragmentTail &&
                offset >= config.minFragmentPrefixChars &&
                prefixSupport >= calibration.lowThreshold * config.fragmentPrefixThresholdRatio

            val score = candidateScore(
                prefixSupport = prefixSupport,
                suffixMedian = suffixMedian,
                suffixLowRatio = suffixLowRatio,
                longestLowRun = longestLowRun,
                suffixIdentityLowRatio = suffixIdentityLowRatio,
                localRupture = localRupture,
                suffixRepeatRatio = suffixRepeatRatio,
                futureRescue = futureRescue,
                tailRisk = tailRisk,
                calibration = calibration,
                offset = offset
            )

            V8Candidate(
                offset = offset,
                score = score,
                prefixSupport = prefixSupport,
                prefixIdentity = prefixIdentity,
                suffixSupportMedian = suffixMedian,
                suffixLowRatio = suffixLowRatio,
                longestLowRun = longestLowRun,
                suffixIdentityLowRatio = suffixIdentityLowRatio,
                localRupture = localRupture,
                suffixRepeatRatio = suffixRepeatRatio,
                belongDrop = belongDrop,
                identityDrop = identityDrop,
                futureTrusted = future.trusted,
                futureSupport = futureSupport,
                futureIdentitySupport = futureIdentitySupport,
                futureFragmentRisk = future.fragmentRisk,
                futureRescue = futureRescue,
                tailRisk = tailRisk,
                suffixSafe = suffixSafe,
                prefixBelongs = prefixBelongs,
                fragmentPrefixBelongs = fragmentPrefixBelongs,
                suffixAbsolutelyLow = suffixAbsolutelyLow,
                fragmentTail = fragmentTail,
                lowThreshold = calibration.lowThreshold,
                normalThreshold = calibration.normalThreshold,
                referenceMedian = calibration.median
            )
        }
    }

    private fun decide(candidates: List<V8Candidate>, whole: V8WholeChapter): V8Decision {
        val wrong = candidates
            .filter { candidate ->
                (candidate.prefixBelongs || candidate.fragmentPrefixBelongs) &&
                    (candidate.suffixAbsolutelyLow || candidate.fragmentTail) &&
                    !candidate.futureRescue &&
                    !candidate.tailRisk &&
                    !candidate.suffixSafe
            }
            .maxByOrNull { candidate -> candidate.score }
        if (wrong != null) {
            return V8Decision(
                status = V8PsbmtStatus.WRONG_CONFIRMED,
                type = V8PsbmtType.PREFIX_SUFFIX_FOREIGN_TEXT,
                offset = wrong.offset,
                confidence = wrong.score,
                evidence = wrong.toEvidence()
            )
        }

        val tailRisk = candidates.maxByOrNull { candidate -> if (candidate.tailRisk) candidate.score else -1.0 }
        if (tailRisk?.tailRisk == true || whole.tailRisk) {
            val tailEvidence = tailRisk?.toEvidence() ?: emptyMap()
            return V8Decision(
                status = V8PsbmtStatus.SUSPECT_RECHECK_REQUIRED,
                type = V8PsbmtType.POSSIBLE_TAIL_CLUSTER,
                offset = tailRisk?.offset,
                confidence = max(tailRisk?.score ?: 0.0, whole.score),
                evidence = tailEvidence + whole.toEvidence("whole")
            )
        }

        val suspect = candidates
            .filter { candidate ->
                !candidate.suffixSafe &&
                    !candidate.futureRescue &&
                    candidate.prefixBelongs &&
                    candidate.offset <= config.maxFragmentOffset &&
                    ((candidate.suffixLowRatio >= config.suspectSuffixLowRatio &&
                        candidate.longestLowRun >= config.suspectLongestLowRun) ||
                        (candidate.localRupture >= config.suspectLocalRupture &&
                            candidate.suffixRepeatRatio <= config.suspectFragmentRepeatRatio))
            }
            .maxByOrNull { candidate -> candidate.score }
        if (suspect != null) {
            return V8Decision(
                status = V8PsbmtStatus.SUSPECT_RECHECK_REQUIRED,
                type = V8PsbmtType.PREFIX_SUFFIX_FOREIGN_TEXT,
                offset = suspect.offset,
                confidence = suspect.score,
                evidence = suspect.toEvidence()
            )
        }

        if (whole.lowRatio >= config.wholeChapterWrongLowRatio && !whole.futureRescue) {
            return V8Decision(
                status = V8PsbmtStatus.SUSPECT_RECHECK_REQUIRED,
                type = V8PsbmtType.WHOLE_CHAPTER_FOREIGN_TEXT,
                offset = null,
                confidence = whole.score,
                evidence = whole.toEvidence("whole")
            )
        }

        val best = candidates.maxByOrNull { candidate -> candidate.score }
        return V8Decision(
            status = V8PsbmtStatus.NORMAL,
            type = V8PsbmtType.NONE,
            offset = best?.offset,
            confidence = 1.0 - (best?.score ?: 0.0),
            evidence = (best?.toEvidence() ?: emptyMap()) + whole.toEvidence("whole")
        )
    }

    private fun candidateScore(
        prefixSupport: Double,
        suffixMedian: Double,
        suffixLowRatio: Double,
        longestLowRun: Int,
        suffixIdentityLowRatio: Double,
        localRupture: Double,
        suffixRepeatRatio: Double,
        futureRescue: Boolean,
        tailRisk: Boolean,
        calibration: V8Calibration,
        offset: Int
    ): Double {
        val prefixNorm = (prefixSupport / calibration.normalThreshold.coerceAtLeast(0.001)).coerceIn(0.0, 1.0)
        val suffixWeak = ((calibration.lowThreshold - suffixMedian) / calibration.lowThreshold.coerceAtLeast(0.001))
            .coerceIn(0.0, 1.0)
        val lowRunNorm = (longestLowRun.toDouble() / config.wrongLongestLowRun).coerceIn(0.0, 1.0)
        val fragmentNorm = ((config.suspectFragmentRepeatRatio - suffixRepeatRatio) / config.suspectFragmentRepeatRatio)
            .coerceIn(0.0, 1.0)
        return (
            0.30 * suffixLowRatio +
                0.25 * suffixWeak +
                0.18 * lowRunNorm +
                0.12 * prefixNorm +
                0.08 * suffixIdentityLowRatio +
                0.10 * localRupture +
                0.14 * fragmentNorm +
                positionBonus(offset) +
                if (tailRisk) 0.10 else 0.0 -
                    if (futureRescue) 0.35 else 0.0
            ).coerceIn(0.0, 1.0)
    }

    private fun wholeChapter(
        windows: List<V8Window>,
        future: V8FutureEvidence,
        calibration: V8Calibration
    ): V8WholeChapter {
        if (windows.isEmpty()) {
            return V8WholeChapter(0.0, 0.0, 0.0, false, false)
        }
        val supports = windows.map { window -> window.support }
        val median = supports.median()
        val lowRatio = supports.count { value -> value < calibration.lowThreshold }.toDouble() / supports.size
        val futureSupport = future.suffixSupport(windows.take(config.maxSuffixWindows), null)
        val futureRescue = future.trusted &&
            futureSupport >= config.futureRescueThreshold
        val tailRisk = !future.trusted && future.available && futureSupport >= config.tailClusterFutureThreshold && lowRatio >= config.tailClusterLowRatio
        val weak = ((calibration.lowThreshold - median) / calibration.lowThreshold.coerceAtLeast(0.001)).coerceIn(0.0, 1.0)
        return V8WholeChapter(
            score = (0.60 * lowRatio + 0.30 * weak + if (tailRisk) 0.10 else 0.0 - if (futureRescue) 0.35 else 0.0)
                .coerceIn(0.0, 1.0),
            supportMedian = median,
            lowRatio = lowRatio,
            futureRescue = futureRescue,
            tailRisk = tailRisk
        )
    }

    private fun buildFutureEvidence(
        futureTexts: List<String>,
        support: V8Support,
        calibration: V8Calibration
    ): V8FutureEvidence {
        val text = futureTexts.joinToString("")
        if (text.length < config.minFutureChapterChars) return V8FutureEvidence.Unavailable
        val windows = indexedWindows(text).map { window ->
            window.withSupport(support.membership(window.text), support.identity(window.text))
        }
        if (windows.isEmpty()) return V8FutureEvidence.Unavailable
        val trust = windows.map { window -> window.support }.median()
        val fragmentRisk = hasEarlyFragmentRisk(text)
        return V8FutureEvidence.Available(
            windows = windows,
            vectors = support.vectors(text.take(config.maxFutureCharsForTrust)),
            sketch = support.sketch(text.take(config.maxFutureCharsForTrust)),
            trust = trust,
            fragmentRisk = fragmentRisk,
            trusted = trust >= calibration.futureTrustThreshold
        )
    }

    private fun hasEarlyFragmentRisk(text: String): Boolean {
        return candidateOffsets(text).any { offset ->
            if (offset > config.maxFragmentOffset || offset >= text.length) return@any false
            val suffixText = text.substring(offset, min(text.length, offset + config.maxFragmentChars))
            suffixText.length >= config.minFragmentChars &&
                repeatedNgramRatio(suffixText) <= config.fragmentRepeatRatio &&
                localRupture(text, offset) >= config.fragmentLocalRupture
        }
    }

    private fun calibrateReference(support: V8Support, referenceWindows: List<String>): V8Calibration {
        val values = referenceWindows.mapIndexedNotNull { index, window ->
            support.referenceMembership(window, index).takeIf { value -> value > 0.0 }
        }
        val median = values.median()
        val mad = values.map { value -> abs(value - median) }.median()
        val lowThreshold = (median - max(config.lowThresholdMinDrop, config.lowThresholdMadMultiplier * mad))
            .coerceIn(config.minLowThreshold, config.maxLowThreshold)
        val normalThreshold = (median - max(config.normalThresholdMinDrop, config.normalThresholdMadMultiplier * mad))
            .coerceAtLeast(lowThreshold + config.minNormalThresholdGap)
            .coerceAtMost(config.maxNormalThreshold)
        return V8Calibration(
            median = median,
            mad = mad,
            lowThreshold = lowThreshold,
            normalThreshold = normalThreshold,
            prefixThreshold = (normalThreshold * config.prefixThresholdRatio).coerceAtLeast(lowThreshold),
            futureTrustThreshold = (normalThreshold * config.futureTrustThresholdRatio).coerceAtLeast(lowThreshold)
        )
    }

    private fun candidateOffsets(text: String): List<Int> {
        val end = min(
            config.maxSparseCandidateOffset,
            max(config.minCandidateOffset, (text.length * config.maxCandidateRatio).toInt())
        )
        val offsets = LinkedHashSet<Int>()
        var dense = config.minCandidateOffset
        while (dense <= min(config.maxDenseCandidateOffset, end)) {
            offsets.add(dense)
            dense += config.denseCandidateStride
        }
        var sparse = config.firstSparseCandidateOffset
        while (sparse <= end) {
            offsets.add(sparse)
            sparse += config.sparseCandidateStride
        }
        return offsets.toList()
    }

    private fun indexedWindows(text: String): List<V8Window> {
        val compactText = compact(text)
        if (compactText.length < config.minWindowChars) return emptyList()
        if (compactText.length <= config.windowSize) return listOf(V8Window(0, compactText.length, compactText))
        val windows = ArrayList<V8Window>()
        var start = 0
        while (start + config.minWindowChars <= compactText.length) {
            val end = min(compactText.length, start + config.windowSize)
            windows.add(V8Window(start, end, compactText.substring(start, end)))
            if (end >= compactText.length) break
            start += config.windowStride
        }
        return windows
    }

    private fun localRupture(text: String, offset: Int): Double {
        val left = compact(text.substring(max(0, offset - config.localRuptureChars), offset))
        val right = compact(text.substring(offset, min(text.length, offset + config.localRuptureChars)))
        if (left.length < config.minWindowChars || right.length < config.minWindowChars) return 0.0
        return (1.0 - cosine(charVector(left), charVector(right))).coerceIn(0.0, 1.0)
    }

    private fun repeatedNgramRatio(text: String): Double {
        val grams = charGrams(text, 2, 4)
        if (grams.isEmpty()) return 1.0
        val counts = LinkedHashMap<String, Int>()
        grams.forEach { gram -> counts[gram] = (counts[gram] ?: 0) + 1 }
        val repeated = grams.count { gram -> (counts[gram] ?: 0) > 1 }
        return repeated.toDouble() / grams.size
    }

    private fun clean(raw: String, chapterTitle: String): V8CleanText {
        val cacheKey = if (cleanCacheEnabled) cleanCacheKey(raw, chapterTitle) else null
        if (cacheKey != null) {
            synchronized(cleanCache) {
                cleanCache[cacheKey]?.let { cached -> return cached }
            }
        }
        val normalized = raw
            .replace(Regex("""(?i)<\s*br\s*/?\s*>"""), "\n")
            .replace(Regex("""(?i)</\s*p\s*>"""), "\n")
            .replace(Regex("""<[^>]+>"""), "")
            .replace('\u00a0', ' ')
            .replace("&nbsp;", " ")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        val kept = ArrayList<String>()
        var removedChars = 0
        normalized.lines()
            .map { line -> line.trim() }
            .filter { line -> line.isNotBlank() }
            .forEachIndexed { index, line ->
                val remove = (index == 0 && chapterTitle.isNotBlank() && titleKey(line) == titleKey(chapterTitle)) ||
                    isShellLine(line) ||
                    kept.lastOrNull() == line
                if (remove) {
                    removedChars += line.length
                } else {
                    kept.add(line)
                }
            }
        val body = kept.joinToString("").trim()
        val rawChars = normalized.filterNot { char -> char.isWhitespace() }.length.coerceAtLeast(1)
        return V8CleanText(body, removedChars, removedChars.toDouble() / rawChars).also { result ->
            if (cacheKey != null) {
                synchronized(cleanCache) {
                    cleanCache[cacheKey] = result
                }
            }
        }
    }

    private fun cleanCacheKey(raw: String, chapterTitle: String): String {
        val digest = MessageDigest.getInstance("MD5")
        fun update(value: String) {
            digest.update(value.toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        update(chapterTitle)
        update(raw.length.toString())
        update(raw)
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun isShellLine(line: String): Boolean {
        if (line.length > config.maxShellLineChars) return false
        return shellPatterns.any { pattern -> pattern.containsMatchIn(line) }
    }

    private fun titleKey(value: String): String {
        return value.replace(Regex("""[\s\p{Punct}，。！？、；：“”‘’（）【】《》]+"""), "").lowercase()
    }

    private fun positionBonus(offset: Int): Double {
        return when (offset) {
            in 128..148 -> 0.04
            in 86..105 -> 0.03
            in 158..190 -> 0.02
            else -> 0.0
        }
    }

    private fun terminal(
        status: V8PsbmtStatus,
        type: V8PsbmtType,
        current: V8CleanText,
        startedAtMs: Long
    ): V8PsbmtResult {
        return V8PsbmtResult(
            status = status,
            type = type,
            offset = null,
            confidence = 0.0,
            cleanedLength = current.body.length,
            removedChars = current.removedChars,
            noiseRatio = current.noiseRatio,
            ms = System.currentTimeMillis() - startedAtMs,
            evidence = emptyMap(),
            candidates = emptyList()
        )
    }

    private val shellPatterns = listOf(
        Regex("""(?i)(https?://|www\.|\.com|\.net|\.org|最新网址|最新地址|网址)"""),
        Regex("""(请收藏|加入书签|收藏本站|方便下次阅读)"""),
        Regex("""(上一章|下一章|返回目录|点击下一页|本章未完)"""),
        Regex("""(手机用户|手机版|客户端|APP|app下载|微信公众号)"""),
        Regex("""(推荐票|月票|求票|投票)""")
    )
}

data class V8PsbmtConfig(
    val minCurrentChars: Int = 300,
    val minReferenceChapterChars: Int = 300,
    val minFutureChapterChars: Int = 220,
    val minReferenceChapters: Int = 2,
    val maxReferenceChapters: Int = 3,
    val maxFutureChapters: Int = 1,
    val maxReferenceCharsPerChapter: Int = 2_600,
    val minReferenceWindows: Int = 6,
    val maxNoiseRatio: Double = 0.50,
    val maxShellLineChars: Int = 96,
    val windowSize: Int = 192,
    val windowStride: Int = 192,
    val minWindowChars: Int = 64,
    val semanticMinGram: Int = 2,
    val semanticMaxGram: Int = 4,
    val identityMinGram: Int = 3,
    val identityMaxGram: Int = 6,
    val minIdentityWeight: Double = 1.0,
    val semanticWeight: Double = 0.68,
    val identityWeight: Double = 0.32,
    val minIdentitySketchTerms: Int = 80,
    val minCandidateOffset: Int = 64,
    val maxDenseCandidateOffset: Int = 260,
    val firstSparseCandidateOffset: Int = 284,
    val maxSparseCandidateOffset: Int = 800,
    val maxCandidateRatio: Double = 0.55,
    val denseCandidateStride: Int = 8,
    val sparseCandidateStride: Int = 24,
    val minSuffixChars: Int = 220,
    val maxSuffixChars: Int = 960,
    val maxSuffixWindows: Int = 10,
    val minPostWindowOverlap: Int = 80,
    val minFragmentChars: Int = 500,
    val maxFragmentChars: Int = 3_200,
    val maxFutureCharsForTrust: Int = 3_200,
    val minLowThreshold: Double = 0.35,
    val maxLowThreshold: Double = 0.72,
    val lowThresholdMinDrop: Double = 0.18,
    val lowThresholdMadMultiplier: Double = 3.0,
    val normalThresholdMinDrop: Double = 0.10,
    val normalThresholdMadMultiplier: Double = 2.0,
    val minNormalThresholdGap: Double = 0.06,
    val maxNormalThreshold: Double = 0.86,
    val prefixThresholdRatio: Double = 0.82,
    val futureTrustThresholdRatio: Double = 0.92,
    val safeSuffixLowRatio: Double = 0.0,
    val identityLowThreshold: Double = 0.08,
    val wrongSuffixLowRatio: Double = 0.70,
    val wrongLongestLowRun: Int = 3,
    val suspectSuffixLowRatio: Double = 0.50,
    val suspectLongestLowRun: Int = 2,
    val futureRescueThreshold: Double = 0.55,
    val tailClusterFutureThreshold: Double = 0.86,
    val tailClusterFutureIdentityThreshold: Double = 0.08,
    val tailClusterLowRatio: Double = 0.45,
    val tailClusterIdentityLowRatio: Double = 0.80,
    val localRuptureChars: Int = 192,
    val suspectLocalRupture: Double = 0.86,
    val fragmentLocalRupture: Double = 0.86,
    val fragmentRepeatRatio: Double = 0.11,
    val suspectFragmentRepeatRatio: Double = 0.11,
    val maxFragmentOffset: Int = 320,
    val minFragmentPrefixChars: Int = 48,
    val fragmentPrefixThresholdRatio: Double = 0.55,
    val wholeChapterWrongLowRatio: Double = 0.95,
    val maxReturnedCandidates: Int = 12
)

data class V8PsbmtInput(
    val previousChapters: List<V8ChapterContext>,
    val current: V8ChapterContext,
    val nextChapters: List<V8ChapterContext> = emptyList()
)

data class V8ChapterContext(
    val index: Int,
    val title: String,
    val text: String,
    val trusted: Boolean = true
)

data class V8PsbmtResult(
    val status: V8PsbmtStatus,
    val type: V8PsbmtType,
    val offset: Int?,
    val confidence: Double,
    val cleanedLength: Int,
    val removedChars: Int,
    val noiseRatio: Double,
    val ms: Long,
    val evidence: Map<String, Any?>,
    val candidates: List<V8Candidate>,
    val cacheHit: Boolean = false
)

private fun V8PsbmtInput.cacheKey(): String {
    val digest = MessageDigest.getInstance("MD5")
    fun update(value: String) {
        digest.update(value.toByteArray(Charsets.UTF_8))
        digest.update(0)
    }
    fun updateChapter(role: String, chapter: V8ChapterContext) {
        update(role)
        update(chapter.index.toString())
        update(chapter.title)
        update(chapter.trusted.toString())
        update(chapter.text.length.toString())
        update(chapter.text)
    }
    update("previous")
    previousChapters.forEach { chapter -> updateChapter("p", chapter) }
    update("current")
    updateChapter("c", current)
    update("next")
    nextChapters.forEach { chapter -> updateChapter("n", chapter) }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

enum class V8PsbmtStatus {
    NORMAL,
    WRONG_CONFIRMED,
    SUSPECT_RECHECK_REQUIRED,
    INSUFFICIENT_CONTEXT,
    SOURCE_QUALITY_PROBLEM
}

enum class V8PsbmtType {
    NONE,
    PREFIX_SUFFIX_FOREIGN_TEXT,
    WHOLE_CHAPTER_FOREIGN_TEXT,
    POSSIBLE_TAIL_CLUSTER,
    INSUFFICIENT_CONTEXT,
    SOURCE_QUALITY_PROBLEM
}

data class V8Candidate(
    val offset: Int,
    val score: Double,
    val prefixSupport: Double,
    val prefixIdentity: Double,
    val suffixSupportMedian: Double,
    val suffixLowRatio: Double,
    val longestLowRun: Int,
    val suffixIdentityLowRatio: Double,
    val localRupture: Double,
    val suffixRepeatRatio: Double,
    val belongDrop: Double,
    val identityDrop: Double,
    val futureTrusted: Boolean,
    val futureSupport: Double,
    val futureIdentitySupport: Double,
    val futureFragmentRisk: Boolean,
    val futureRescue: Boolean,
    val tailRisk: Boolean,
    val suffixSafe: Boolean,
    val prefixBelongs: Boolean,
    val fragmentPrefixBelongs: Boolean,
    val suffixAbsolutelyLow: Boolean,
    val fragmentTail: Boolean,
    val lowThreshold: Double,
    val normalThreshold: Double,
    val referenceMedian: Double
) {
    fun toEvidence(): Map<String, Any?> {
        return mapOf(
            "offset" to offset,
            "score" to score,
            "prefixSupport" to prefixSupport,
            "prefixIdentity" to prefixIdentity,
            "suffixSupportMedian" to suffixSupportMedian,
            "suffixLowRatio" to suffixLowRatio,
            "longestLowRun" to longestLowRun,
            "suffixIdentityLowRatio" to suffixIdentityLowRatio,
            "localRupture" to localRupture,
            "suffixRepeatRatio" to suffixRepeatRatio,
            "belongDrop" to belongDrop,
            "identityDrop" to identityDrop,
            "futureTrusted" to futureTrusted,
            "futureSupport" to futureSupport,
            "futureIdentitySupport" to futureIdentitySupport,
            "futureFragmentRisk" to futureFragmentRisk,
            "futureRescue" to futureRescue,
            "tailRisk" to tailRisk,
            "suffixSafe" to suffixSafe,
            "prefixBelongs" to prefixBelongs,
            "fragmentPrefixBelongs" to fragmentPrefixBelongs,
            "suffixAbsolutelyLow" to suffixAbsolutelyLow,
            "fragmentTail" to fragmentTail,
            "lowThreshold" to lowThreshold,
            "normalThreshold" to normalThreshold,
            "referenceMedian" to referenceMedian
        )
    }
}

private class V8Support(
    private val semanticSpace: V8SemanticSpace,
    private val identitySketch: V8IdentitySketch,
    private val config: V8PsbmtConfig
) {
    private val membershipCache = LinkedHashMap<String, Double>()
    private val identityCache = LinkedHashMap<String, Double>()

    fun membership(segment: String): Double {
        return membershipCache.getOrPut(segment) {
            config.semanticWeight * semanticSpace.referenceSupport(segment) +
                config.identityWeight * identitySketch.support(segment)
        }
    }

    fun identity(segment: String): Double {
        return identityCache.getOrPut(segment) { identitySketch.support(segment) }
    }

    fun referenceMembership(window: String, referenceWindowIndex: Int): Double {
        return config.semanticWeight * semanticSpace.referenceSelfSupport(window, referenceWindowIndex) +
            config.identityWeight * identitySketch.support(window)
    }

    fun vectors(text: String): List<V8SparseVector> {
        return semanticSpace.segmentVectors(text)
    }

    fun crossSupport(left: String, rightVectors: List<V8SparseVector>): Double {
        return semanticSpace.crossSupport(left, rightVectors)
    }

    fun sketch(text: String): V8IdentitySketch {
        return V8IdentitySketch.build(listOf(text), semanticSpace.idf, config)
    }
}

private sealed class V8FutureEvidence {
    abstract val available: Boolean
    abstract val trusted: Boolean
    abstract val fragmentRisk: Boolean
    abstract fun suffixSupport(windows: List<V8Window>, support: V8Support?): Double
    abstract fun suffixIdentitySupport(windows: List<V8Window>): Double

    object Unavailable : V8FutureEvidence() {
        override val available = false
        override val trusted = false
        override val fragmentRisk = false
        override fun suffixSupport(windows: List<V8Window>, support: V8Support?): Double = 0.0
        override fun suffixIdentitySupport(windows: List<V8Window>): Double = 0.0
    }

    data class Available(
        val windows: List<V8Window>,
        val vectors: List<V8SparseVector>,
        val sketch: V8IdentitySketch,
        val trust: Double,
        override val fragmentRisk: Boolean,
        override val trusted: Boolean
    ) : V8FutureEvidence() {
        override val available = true

        override fun suffixSupport(windows: List<V8Window>, support: V8Support?): Double {
            if (vectors.isEmpty() || windows.isEmpty() || support == null) return 0.0
            return windows.map { window -> support.crossSupport(window.text, vectors) }.median()
        }

        override fun suffixIdentitySupport(windows: List<V8Window>): Double {
            if (windows.isEmpty()) return 0.0
            return windows.map { window -> sketch.support(window.text) }.median()
        }
    }
}

private data class V8Decision(
    val status: V8PsbmtStatus,
    val type: V8PsbmtType,
    val offset: Int?,
    val confidence: Double,
    val evidence: Map<String, Any?>
)

private data class V8CleanText(
    val body: String,
    val removedChars: Int,
    val noiseRatio: Double
)

private data class V8Calibration(
    val median: Double,
    val mad: Double,
    val lowThreshold: Double,
    val normalThreshold: Double,
    val prefixThreshold: Double,
    val futureTrustThreshold: Double
)

private data class V8WholeChapter(
    val score: Double,
    val supportMedian: Double,
    val lowRatio: Double,
    val futureRescue: Boolean,
    val tailRisk: Boolean
) {
    fun toEvidence(prefix: String): Map<String, Any?> {
        return mapOf(
            "${prefix}Score" to score,
            "${prefix}SupportMedian" to supportMedian,
            "${prefix}LowRatio" to lowRatio,
            "${prefix}FutureRescue" to futureRescue,
            "${prefix}TailRisk" to tailRisk
        )
    }
}

private data class V8Window(
    val start: Int,
    val end: Int,
    val text: String,
    val support: Double = 0.0,
    val identity: Double = 0.0
) {
    fun withSupport(support: Double, identity: Double): V8Window {
        return copy(support = support, identity = identity)
    }
}

private fun compact(text: String): String {
    return text.filterNot { char -> char.isWhitespace() }
}

private fun charVector(text: String): Map<String, Double> {
    val counts = LinkedHashMap<String, Double>()
    charGrams(text, 2, 4).forEach { gram -> counts[gram] = (counts[gram] ?: 0.0) + 1.0 }
    val norm = kotlin.math.sqrt(counts.values.sumOf { value -> value * value })
    if (norm <= 0.0) return emptyMap()
    return counts.mapValues { (_, value) -> value / norm }
}

private fun charGrams(text: String, minSize: Int, maxSize: Int): List<String> {
    val compact = compact(text)
    val grams = ArrayList<String>()
    for (size in minSize..maxSize) {
        if (compact.length < size) continue
        for (index in 0..compact.length - size) {
            val gram = compact.substring(index, index + size)
            if (gram.any { char -> char in '\u4e00'..'\u9fff' }) grams.add(gram)
        }
    }
    return grams
}

private fun cosine(left: Map<String, Double>, right: Map<String, Double>): Double {
    if (left.isEmpty() || right.isEmpty()) return 0.0
    val smaller = if (left.size <= right.size) left else right
    val larger = if (left.size <= right.size) right else left
    var dot = 0.0
    smaller.forEach { (key, value) -> dot += value * (larger[key] ?: 0.0) }
    return dot.coerceIn(0.0, 1.0)
}

private fun List<Double>.median(): Double {
    if (isEmpty()) return 0.0
    val sorted = sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) {
        sorted[middle]
    } else {
        (sorted[middle - 1] + sorted[middle]) / 2.0
    }
}

private fun <T> List<T>.longestRun(predicate: (T) -> Boolean): Int {
    var best = 0
    var current = 0
    forEach { item ->
        if (predicate(item)) {
            current += 1
            best = max(best, current)
        } else {
            current = 0
        }
    }
    return best
}

@Suppress("unused")
private fun List<Long>.percentileLong(percentile: Double): Long {
    if (isEmpty()) return 0
    val sorted = sorted()
    return sorted[((sorted.size - 1) * percentile).roundToInt().coerceIn(0, sorted.lastIndex)]
}
