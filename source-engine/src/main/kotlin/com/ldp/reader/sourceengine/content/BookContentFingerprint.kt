package com.ldp.reader.sourceengine.content

enum class BookContentFingerprintTermCategory {
    LEAD_CHARACTER,
    SUPPORTING_CHARACTER,
    CHARACTER,
    LOCATION,
    ORGANIZATION,
    WEAPON,
    ARTIFACT,
    TECHNIQUE,
    CULTIVATION_RANK,
    RESOURCE,
    SPECIES,
    VEHICLE,
    ROLE_TITLE,
    WORLDVIEW,
    ENVIRONMENT
}

data class BookContentFingerprintTerm(
    val term: String,
    val category: BookContentFingerprintTermCategory,
    val chapterHitCount: Int,
    val totalHitCount: Int,
    val weight: Int
)

data class BookContentFingerprint(
    val characterWeights: Map<String, Int>,
    val environmentWeights: Map<String, Int>,
    val trustedChapterCount: Int,
    val characterProfiles: List<BookContentFingerprintTerm> = emptyList(),
    val environmentProfiles: List<BookContentFingerprintTerm> = emptyList()
) {
    val characterTerms: Set<String>
        get() = characterWeights.keys

    val environmentTerms: Set<String>
        get() = environmentWeights.keys

    val leadCharacterTerms: Set<String>
        get() = characterProfiles
            .filter { term -> term.category == BookContentFingerprintTermCategory.LEAD_CHARACTER }
            .mapTo(LinkedHashSet()) { term -> term.term }

    val usable: Boolean
        get() = trustedChapterCount >= MIN_TRUSTED_CHAPTERS &&
            (characterWeights.size >= MIN_CHARACTER_TERMS || environmentWeights.size >= MIN_ENVIRONMENT_TERMS)

    fun characterScore(index: TextFingerprintIndex): Int {
        return weightedHitScore(index.personNames, characterWeights)
    }

    fun environmentScore(index: TextFingerprintIndex): Int {
        return weightedHitScore(index.environmentTerms, environmentWeights)
    }

    fun match(index: TextFingerprintIndex): BookContentFingerprintMatch {
        val characterHits = weightedHits(index.personNames, characterWeights)
        val environmentHits = weightedHits(index.environmentTerms, environmentWeights)
        val characterScore = characterHits.sumOf { hit -> hit.weight }
        val environmentScore = environmentHits.sumOf { hit -> hit.weight }
        val primaryCharacterHit = characterHits.any { hit -> hit.weight >= PRIMARY_CHARACTER_WEIGHT }
        val strongCharacterMatch =
            (primaryCharacterHit && characterScore >= MIN_PRIMARY_CHARACTER_SCORE) ||
                (characterHits.size >= MIN_STRONG_CHARACTER_HITS && characterScore >= MIN_STRONG_CHARACTER_SCORE)
        val balancedMatch =
            characterScore >= MIN_BALANCED_CHARACTER_SCORE &&
                environmentScore >= MIN_BALANCED_ENVIRONMENT_SCORE &&
                environmentHits.size >= MIN_BALANCED_ENVIRONMENT_HITS
        val strongEnvironmentMatch =
            environmentScore >= MIN_STRONG_ENVIRONMENT_SCORE &&
                environmentHits.size >= MIN_STRONG_ENVIRONMENT_HITS
        return BookContentFingerprintMatch(
            matches = strongCharacterMatch || balancedMatch || strongEnvironmentMatch,
            characterScore = characterScore,
            characterHitCount = characterHits.size,
            environmentScore = environmentScore,
            environmentHitCount = environmentHits.size,
            primaryCharacterHit = primaryCharacterHit,
            strongCharacterMatch = strongCharacterMatch,
            balancedMatch = balancedMatch,
            strongEnvironmentMatch = strongEnvironmentMatch
        )
    }

    private fun weightedHitScore(tokens: Set<String>, weights: Map<String, Int>): Int {
        if (tokens.isEmpty() || weights.isEmpty()) return 0
        return tokens.sumOf { token -> weights[token] ?: 0 }
    }

    private fun weightedHits(tokens: Set<String>, weights: Map<String, Int>): List<WeightedFingerprintHit> {
        if (tokens.isEmpty() || weights.isEmpty()) return emptyList()
        return tokens.mapNotNull { token ->
            val weight = weights[token] ?: return@mapNotNull null
            WeightedFingerprintHit(token, weight)
        }
    }

    companion object {
        private const val MIN_TRUSTED_CHAPTERS = 5
        private const val MIN_CHARACTER_TERMS = 2
        private const val MIN_ENVIRONMENT_TERMS = 8
        private const val PRIMARY_CHARACTER_WEIGHT = 24
        private const val MIN_PRIMARY_CHARACTER_SCORE = 24
        private const val MIN_STRONG_CHARACTER_HITS = 2
        private const val MIN_STRONG_CHARACTER_SCORE = 36
        private const val MIN_BALANCED_CHARACTER_SCORE = 18
        private const val MIN_BALANCED_ENVIRONMENT_SCORE = 48
        private const val MIN_BALANCED_ENVIRONMENT_HITS = 4
        private const val MIN_STRONG_ENVIRONMENT_SCORE = 96
        private const val MIN_STRONG_ENVIRONMENT_HITS = 8
    }
}

data class BookContentFingerprintMatch(
    val matches: Boolean,
    val characterScore: Int,
    val characterHitCount: Int,
    val environmentScore: Int,
    val environmentHitCount: Int,
    val primaryCharacterHit: Boolean,
    val strongCharacterMatch: Boolean,
    val balancedMatch: Boolean,
    val strongEnvironmentMatch: Boolean
)

private data class WeightedFingerprintHit(
    val term: String,
    val weight: Int
)

class BookContentFingerprintProfile(
    private val fingerprinter: BookContentFingerprinter = BookContentFingerprinter(),
    private val maxTrustedContents: Int = DEFAULT_MAX_TRUSTED_CONTENTS
) {
    private val samples = ArrayList<TrustedFingerprintSample>()
    private val sampleKeys = LinkedHashSet<String>()

    @Volatile
    private var currentFingerprint: BookContentFingerprint = fingerprinter.build(emptyList())

    val snapshot: BookContentFingerprint
        get() = currentFingerprint

    @Synchronized
    fun addTrustedContent(content: String): BookContentFingerprint {
        return addTrustedContents(listOf(content))
    }

    @Synchronized
    fun addTrustedContents(contents: List<String>): BookContentFingerprint {
        var changed = false
        contents.forEach { content ->
            if (addTrustedSample(content)) {
                changed = true
            }
        }
        if (changed) {
            currentFingerprint = fingerprinter.build(samples.map { sample -> sample.content })
        }
        return currentFingerprint
    }

    private fun addTrustedSample(content: String): Boolean {
        val normalized = TextFingerprintSignals.normalizeForComparison(content)
        if (normalized.length < MIN_PROFILE_CONTENT_CHARS) return false
        val key = normalized.take(PROFILE_SAMPLE_KEY_CHARS)
        if (!sampleKeys.add(key)) return false
        samples.add(TrustedFingerprintSample(key, content))
        while (samples.size > maxTrustedContents.coerceAtLeast(1)) {
            val removed = samples.removeAt(0)
            sampleKeys.remove(removed.key)
        }
        return true
    }

    private data class TrustedFingerprintSample(
        val key: String,
        val content: String
    )

    private companion object {
        private const val DEFAULT_MAX_TRUSTED_CONTENTS = 64
        private const val MIN_PROFILE_CONTENT_CHARS = 120
        private const val PROFILE_SAMPLE_KEY_CHARS = 1_024
    }
}

class BookContentFingerprinter {
    private val extractor = MultiChapterTermExtractor()

    fun build(trustedContents: List<String>): BookContentFingerprint {
        val normalizedContents = trustedContents
            .map { content -> content.trim() }
            .filter { content -> content.length >= MIN_TRUSTED_CONTENT_CHARS }
        if (normalizedContents.isEmpty()) {
            return BookContentFingerprint(emptyMap(), emptyMap(), 0)
        }

        val commonTerms = extractor.extract(normalizedContents)
        val minCharacterChapterCount = requiredCharacterChapterHitCount(normalizedContents.size)
        val characterProfiles = commonTerms
            .asSequence()
            .filter { term -> term.chapterHitCount >= minCharacterChapterCount }
            .mapNotNull { term -> term.toCharacterProfile(normalizedContents.size) }
            .sortedWith(fingerprintTermOrdering())
            .take(MAX_CHARACTER_TERMS)
            .toList()
        val characters = characterProfiles.associate { profile -> profile.term to profile.weight }

        val minEnvironmentChapterCount = requiredEnvironmentChapterHitCount(normalizedContents.size)
        val environmentProfiles = commonTerms
            .asSequence()
            .filter { term -> term.term !in characters }
            .filter { term -> term.chapterHitCount >= minEnvironmentChapterCount }
            .mapNotNull { term -> term.toEnvironmentProfile() }
            .sortedWith(fingerprintTermOrdering())
            .take(MAX_ENVIRONMENT_TERMS)
            .toList()
        val environments = environmentProfiles.associate { profile -> profile.term to profile.weight }

        return BookContentFingerprint(
            characterWeights = characters,
            environmentWeights = environments,
            trustedChapterCount = normalizedContents.size,
            characterProfiles = characterProfiles,
            environmentProfiles = environmentProfiles
        )
    }

    private fun WeightedCommonTerm.toCharacterProfile(sampleCount: Int): BookContentFingerprintTerm? {
        if (!TextFingerprintSignals.isUsefulPersonNameTerm(term)) return null
        val coverage = chapterHitCount.toDouble() / sampleCount.toDouble()
        val category = when {
            coverage >= LEAD_CHARACTER_MIN_COVERAGE &&
                totalHitCount >= sampleCount * LEAD_CHARACTER_MIN_REPEAT_PER_CHAPTER -> {
                BookContentFingerprintTermCategory.LEAD_CHARACTER
            }
            coverage >= SUPPORTING_CHARACTER_MIN_COVERAGE -> {
                BookContentFingerprintTermCategory.SUPPORTING_CHARACTER
            }
            else -> BookContentFingerprintTermCategory.CHARACTER
        }
        return BookContentFingerprintTerm(term, category, chapterHitCount, totalHitCount, weight)
    }

    private fun WeightedCommonTerm.toEnvironmentProfile(): BookContentFingerprintTerm? {
        if (!TextFingerprintSignals.isUsefulEnvironmentTerm(term)) return null
        val category = TextFingerprintSignals.environmentCategory(term)
        if (category == BookContentFingerprintTermCategory.ENVIRONMENT &&
            !TextFingerprintSignals.isExplicitEnvironmentTerm(term)
        ) {
            return null
        }
        return BookContentFingerprintTerm(
            term = term,
            category = category,
            chapterHitCount = chapterHitCount,
            totalHitCount = totalHitCount,
            weight = weight
        )
    }

    private fun requiredCharacterChapterHitCount(sampleCount: Int): Int {
        return when {
            sampleCount >= 10 -> 5
            sampleCount >= 7 -> 4
            else -> 3
        }
    }

    private fun requiredEnvironmentChapterHitCount(sampleCount: Int): Int {
        return when {
            sampleCount >= 10 -> 6
            sampleCount >= 7 -> 5
            else -> 4
        }
    }

    private fun fingerprintTermOrdering(): Comparator<BookContentFingerprintTerm> {
        return compareByDescending<BookContentFingerprintTerm> { term -> term.weight }
            .thenByDescending { term -> term.chapterHitCount }
            .thenByDescending { term -> term.totalHitCount }
            .thenBy { term -> term.term }
    }

    private companion object {
        private const val MIN_TRUSTED_CONTENT_CHARS = 80
        private const val MAX_CHARACTER_TERMS = 40
        private const val MAX_ENVIRONMENT_TERMS = 80
        private const val LEAD_CHARACTER_MIN_COVERAGE = 0.70
        private const val SUPPORTING_CHARACTER_MIN_COVERAGE = 0.45
        private const val LEAD_CHARACTER_MIN_REPEAT_PER_CHAPTER = 2
    }
}

internal class MultiChapterTermExtractor {
    fun extract(chapterTexts: List<String>): List<WeightedCommonTerm> {
        val stats = LinkedHashMap<String, MutableCommonTermStats>()
        chapterTexts.forEach { content ->
            val chapterCounts = LinkedHashMap<String, Int>()
            TextFingerprintSignals.commonTermCandidates(content).forEach { term ->
                chapterCounts[term] = (chapterCounts[term] ?: 0) + 1
            }
            chapterCounts.forEach { (term, count) ->
                stats.getOrPut(term) { MutableCommonTermStats(term) }
                    .addChapter(count)
            }
        }

        return stats.values
            .asSequence()
            .map { term -> term.toWeightedCommonTerm() }
            .filter { term -> term.chapterHitCount >= MIN_COMMON_TERM_CHAPTERS }
            .filter { term -> term.totalHitCount >= MIN_COMMON_TERM_TOTAL_HITS }
            .sortedWith(
                compareByDescending<WeightedCommonTerm> { term -> term.weight }
                    .thenByDescending { term -> term.chapterHitCount }
                    .thenByDescending { term -> term.totalHitCount }
                    .thenBy { term -> term.term }
            )
            .toList()
    }

    private class MutableCommonTermStats(
        private val term: String,
        private var chapterHitCount: Int = 0,
        private var totalHitCount: Int = 0
    ) {
        fun addChapter(count: Int) {
            chapterHitCount += 1
            totalHitCount += count
        }

        fun toWeightedCommonTerm(): WeightedCommonTerm {
            val repeatScore = totalHitCount.coerceAtMost(chapterHitCount * MAX_REPEAT_SCORE_PER_CHAPTER)
            return WeightedCommonTerm(
                term = term,
                chapterHitCount = chapterHitCount,
                totalHitCount = totalHitCount,
                weight = chapterHitCount * CHAPTER_SPREAD_WEIGHT + repeatScore
            )
        }
    }

    private companion object {
        private const val MIN_COMMON_TERM_CHAPTERS = 2
        private const val MIN_COMMON_TERM_TOTAL_HITS = 2
        private const val CHAPTER_SPREAD_WEIGHT = 100
        private const val MAX_REPEAT_SCORE_PER_CHAPTER = 40
    }
}

internal data class WeightedCommonTerm(
    val term: String,
    val chapterHitCount: Int,
    val totalHitCount: Int,
    val weight: Int
)

data class TextFingerprintIndex(
    val normalized: String,
    val personNames: Set<String>,
    val environmentTerms: Set<String>
)

internal object TextFingerprintSignals {
    fun index(value: String): TextFingerprintIndex {
        return TextFingerprintIndex(
            normalized = normalizeForComparison(value),
            personNames = personLikeNames(value).toSet(),
            environmentTerms = environmentTerms(value).toSet()
        )
    }

    fun normalizeForComparison(value: String): String {
        return value
            .lowercase()
            .filter { char -> char.isLetterOrDigit() || char in '\u4e00'..'\u9fff' }
    }

    fun personLikeNames(value: String): List<String> {
        return commonTermCandidates(value)
            .asSequence()
            .filter { term -> isUsefulPersonNameTerm(term) }
            .distinct()
            .toList()
    }

    fun environmentTerms(value: String): List<String> {
        return commonTermCandidates(value)
            .asSequence()
            .filter { term -> isUsefulEnvironmentTerm(term) }
            .filter { term ->
                environmentCategory(term) != BookContentFingerprintTermCategory.ENVIRONMENT ||
                    isExplicitEnvironmentTerm(term)
            }
            .distinct()
            .toList()
    }

    fun commonTermCandidates(value: String): List<String> {
        val normalized = normalizeForComparison(value)
        if (normalized.length < MIN_COMMON_TERM_SIZE) return emptyList()
        val candidates = ArrayList<String>()
        for (size in MIN_COMMON_TERM_SIZE..MAX_TERM_SIZE) {
            if (normalized.length < size) continue
            for (index in 0..normalized.length - size) {
                candidates.add(normalized.substring(index, index + size))
            }
        }
        return candidates
    }

    fun isExplicitEnvironmentTerm(term: String): Boolean {
        return ENVIRONMENT_SIGNAL_TERMS
            .asSequence()
            .map { signal -> normalizeForComparison(signal) }
            .any { signal -> signal == term || signal.contains(term) || term.contains(signal) }
    }

    fun isUsefulPersonNameTerm(value: String): Boolean {
        if (value.length !in 2..4) return false
        if (!value.all { it in '\u4e00'..'\u9fff' }) return false
        if (value in COMMON_STOP_TERMS) return false
        if (value in NON_PERSON_NAME_TERMS) return false
        val startsWithNamePrefix = value.first() in PERSON_NAME_PREFIX_CHARS
        if (!startsWithNamePrefix && value.first() !in PERSON_NAME_START_CHARS) return false
        if (value.last() in NON_PERSON_NAME_SUFFIX_CHARS) return false
        if (value.drop(1).any { it in PERSON_NAME_STOP_CHARS } && !hasAllowedMiddleNameFunctionChar(value)) {
            return false
        }
        return true
    }

    fun isUsefulEnvironmentTerm(term: String): Boolean {
        if (term.length !in MIN_EXPLICIT_TERM_SIZE..MAX_TERM_SIZE) return false
        if (term.any { it !in '\u4e00'..'\u9fff' }) return false
        if (term in COMMON_STOP_TERMS) return false
        if (term in NON_ENVIRONMENT_TERMS) return false
        if (term.any { it in TERM_STOP_CHARS }) return false
        if (term.length > 2 && !isExplicitEnvironmentTerm(term)) return false
        if (term.last() in GENERIC_ENVIRONMENT_SUFFIX_CHARS) return false
        return isEnvironmentLikeTerm(term)
    }

    fun environmentCategory(term: String): BookContentFingerprintTermCategory {
        return when {
            term.any { char -> char in VEHICLE_CHARS } ||
                VEHICLE_SIGNAL_TERMS.any { signal -> term.contains(signal) } -> {
                BookContentFingerprintTermCategory.VEHICLE
            }
            term.any { char -> char in LOCATION_CHARS } -> {
                BookContentFingerprintTermCategory.LOCATION
            }
            term.any { char -> char in ORGANIZATION_CHARS } -> {
                BookContentFingerprintTermCategory.ORGANIZATION
            }
            term.any { char -> char in CULTIVATION_RANK_CHARS } ||
                CULTIVATION_RANK_SIGNAL_TERMS.any { signal -> term.contains(signal) } -> {
                BookContentFingerprintTermCategory.CULTIVATION_RANK
            }
            term.any { char -> char in TECHNIQUE_CHARS } ||
                TECHNIQUE_SIGNAL_TERMS.any { signal -> term.contains(signal) } -> {
                BookContentFingerprintTermCategory.TECHNIQUE
            }
            term.any { char -> char in ARTIFACT_CHARS } ||
                ARTIFACT_SIGNAL_TERMS.any { signal -> term.contains(signal) } -> {
                BookContentFingerprintTermCategory.ARTIFACT
            }
            term.any { char -> char in WEAPON_CHARS } ||
                WEAPON_SIGNAL_TERMS.any { signal -> term.contains(signal) } -> {
                BookContentFingerprintTermCategory.WEAPON
            }
            term.any { char -> char in RESOURCE_CHARS } ||
                RESOURCE_SIGNAL_TERMS.any { signal -> term.contains(signal) } -> {
                BookContentFingerprintTermCategory.RESOURCE
            }
            term.any { char -> char in SPECIES_CHARS } ||
                SPECIES_SIGNAL_TERMS.any { signal -> term.contains(signal) } -> {
                BookContentFingerprintTermCategory.SPECIES
            }
            term.any { char -> char in ROLE_TITLE_CHARS } ||
                ROLE_TITLE_SIGNAL_TERMS.any { signal -> term.contains(signal) } -> {
                BookContentFingerprintTermCategory.ROLE_TITLE
            }
            term.any { char -> char in WORLDVIEW_CHARS } ||
                WORLDVIEW_SIGNAL_TERMS.any { signal -> term.contains(signal) } -> {
                BookContentFingerprintTermCategory.WORLDVIEW
            }
            else -> BookContentFingerprintTermCategory.ENVIRONMENT
        }
    }

    private fun isEnvironmentLikeTerm(term: String): Boolean {
        return term.any { char -> char in ENVIRONMENT_SIGNAL_CHARS } ||
            ENVIRONMENT_SIGNAL_TERMS.any { signal -> term.contains(normalizeForComparison(signal)) }
    }

    private fun hasAllowedMiddleNameFunctionChar(value: String): Boolean {
        return value.length == 3 &&
            value[1] in PERSON_NAME_MIDDLE_FUNCTION_CHARS &&
            value.first() in PERSON_NAME_START_CHARS &&
            value.last() !in PERSON_NAME_STOP_CHARS
    }

    private const val MIN_COMMON_TERM_SIZE = 2
    private const val MIN_EXPLICIT_TERM_SIZE = 2
    private const val MAX_TERM_SIZE = 4

    private val ENVIRONMENT_SIGNAL_CHARS = setOf(
        '灵', '仙', '魔', '妖', '神', '法', '术', '剑', '刀', '枪', '阵', '符', '丹', '宗', '门', '派',
        '阁', '殿', '峰', '山', '海', '湖', '河', '城', '府', '宫', '谷', '寺', '院', '界', '域', '洲', '国',
        '族', '朝', '气', '石', '雷', '火', '冰', '血', '魂', '警', '医', '校', '车', '枪', '案', '队', '局',
        '司', '厂', '店', '街', '巷', '船', '港', '码', '岸', '潮', '楼', '栏', '舷', '甲', '板', '舱',
        '弓', '箭', '戟', '盾', '甲', '舟', '舰', '马', '桥', '路', '殿', '塔', '坛', '井', '碑', '令'
    )

    private val LOCATION_CHARS = setOf(
        '城', '港', '山', '海', '湖', '河', '楼', '街', '巷', '府', '宫', '谷', '峰', '岛', '洲', '岸',
        '舱', '舷', '阁', '殿', '院', '寺', '庙', '塔', '桥', '路', '门', '关', '台', '坛', '井'
    )

    private val ORGANIZATION_CHARS = setOf(
        '宗', '门', '派', '族', '氏', '帮', '会', '队', '局', '司', '厂', '店', '军', '营', '府'
    )

    private val WEAPON_CHARS = setOf(
        '剑', '刀', '枪', '弓', '箭', '戟', '盾', '矛', '戈', '斧', '鞭', '锤', '刃', '镖'
    )

    private val ARTIFACT_CHARS = setOf(
        '鼎', '炉', '印', '令', '珠', '镜', '幡', '旗', '符', '书', '卷', '瓶', '塔', '钟', '铃', '甲'
    )

    private val TECHNIQUE_CHARS = setOf(
        '阵', '术', '法', '诀', '咒', '禁', '印', '纹', '符', '势', '招', '式'
    )

    private val CULTIVATION_RANK_CHARS = setOf(
        '境', '阶', '品', '级', '婴', '丹', '基'
    )

    private val RESOURCE_CHARS = setOf(
        '灵', '石', '丹', '药', '银', '钱', '金', '玉', '币', '晶', '矿', '气'
    )

    private val SPECIES_CHARS = setOf(
        '族', '妖', '魔', '鬼', '神', '龙', '蛟', '兽', '虫', '灵'
    )

    private val ROLE_TITLE_CHARS = setOf(
        '帝', '王', '侯', '将', '君', '主', '公', '妃', '师', '徒', '官', '吏', '工', '东', '叔', '姨', '爷', '娘'
    )

    private val VEHICLE_CHARS = setOf(
        '船', '舟', '舰', '车', '马', '轿', '梯'
    )

    private val WORLDVIEW_CHARS = setOf(
        '灵', '仙', '魔', '妖', '神', '法', '术', '丹', '魂', '界', '域', '朝', '国', '气', '雷', '火', '冰', '血'
    )

    private val ENVIRONMENT_SIGNAL_TERMS = listOf(
        "灵气", "灵石", "元婴", "金丹", "筑基", "炼气", "洞府", "宗门", "法宝", "神通", "剑气", "阵法",
        "警队", "医院", "学校", "公司", "集团", "汽车", "枪声", "现金", "美元", "手机", "电脑", "公寓",
        "船舷", "甲板", "码头", "镜城港", "大海", "海风", "海潮", "艉楼", "船东", "船工", "靠岸",
        "黄山", "道庭", "景朝", "陇右道", "白龙", "灯火", "陆氏", "凭姨", "桃木枝", "张黎", "无字天书"
    )

    private val WEAPON_SIGNAL_TERMS = listOf(
        "剑气", "飞剑", "长剑", "刀光", "枪声"
    )

    private val ARTIFACT_SIGNAL_TERMS = listOf(
        "法宝", "阵盘", "玉简", "无字天书", "桃木枝", "符箓", "符阵", "天目蝶"
    )

    private val TECHNIQUE_SIGNAL_TERMS = listOf(
        "阵法", "剑气", "神通", "禁制", "阵纹", "灵纹", "御剑术", "夜叉神咒"
    )

    private val CULTIVATION_RANK_SIGNAL_TERMS = listOf(
        "元婴", "金丹", "筑基", "炼气", "四阶", "高阶", "三星", "真君"
    )

    private val RESOURCE_SIGNAL_TERMS = listOf(
        "灵气", "灵石", "灵脉", "丹药", "现金", "美元", "银子", "人参"
    )

    private val SPECIES_SIGNAL_TERMS = listOf(
        "炎黄族", "夜叉", "天魔", "妖族", "魔虫", "蛟龙", "白龙", "雷兽"
    )

    private val ROLE_TITLE_SIGNAL_TERMS = listOf(
        "船东", "船工", "凭姨", "帝天子", "真君", "侯爷", "王爷", "官员", "掌柜", "道人"
    )

    private val VEHICLE_SIGNAL_TERMS = listOf(
        "船舷", "甲板", "艉楼", "船东", "船工", "码头", "靠岸"
    )

    private val WORLDVIEW_SIGNAL_TERMS = listOf(
        "灵气", "元婴", "金丹", "筑基", "炼气", "神通", "道庭", "景朝", "黄山"
    )

    private val COMMON_STOP_TERMS = setOf(
        "一个", "这个", "那个", "自己", "他们", "她们", "我们", "你们", "不是", "没有", "还是", "只是", "已经",
        "可以", "因为", "所以", "但是", "如果", "时候", "什么", "怎么", "这里", "那里", "起来", "下去",
        "一道", "说道", "问道", "知道", "声道", "口道", "道一", "解释道", "释道", "不能", "不会", "不用",
        "不知", "不再", "之后", "之前", "正在", "仍然", "依旧", "突然", "看着", "看向", "低声", "开口",
        "语境", "航海", "修仙"
    )

    private val NON_PERSON_NAME_TERMS = setOf(
        "不知", "知道", "说道", "问道", "解释", "依旧", "仍然", "突然", "低声", "陈迹一", "宁朝"
    )

    private val NON_ENVIRONMENT_TERMS = setOf(
        "说道", "问道", "知道", "解释", "低声", "不知", "依旧", "仍然", "突然", "陈迹一"
    )

    private val TERM_STOP_CHARS = setOf(
        '的', '了', '着', '过', '是', '在', '有', '和', '与', '及', '或', '也', '都', '就', '还', '又',
        '很', '更', '最', '这', '那', '哪', '个', '些', '么', '吗', '呢', '啊', '吧', '把', '被', '从',
        '对', '为', '以', '而', '并', '但', '却', '所', '其', '他', '她', '它', '你', '我', '谁', '不',
        '一', '二', '两', '三', '四', '五', '六', '七', '八', '九', '十', '百', '千', '万', '亿'
    )

    private val GENERIC_ENVIRONMENT_SUFFIX_CHARS = setOf(
        '道', '说', '问', '想', '看', '听', '走', '来', '去', '时', '间'
    )

    private val PERSON_NAME_START_CHARS = setOf(
        '赵', '钱', '孙', '李', '周', '吴', '郑', '王', '冯', '陈', '褚', '卫', '蒋', '沈', '韩', '杨',
        '朱', '秦', '许', '何', '吕', '张', '曹', '严', '华', '金', '魏', '姜', '谢', '苏', '潘', '葛',
        '范', '彭', '马', '方', '任', '袁', '柳', '唐', '薛', '雷', '贺', '罗', '毕', '郝', '安', '傅',
        '顾', '孟', '黄', '萧', '尹', '姚', '汪', '毛', '伏', '成', '戴', '宋', '庞', '梁', '杜', '蓝',
        '季', '钟', '徐', '高', '夏', '蔡', '田', '胡', '陆', '莫', '宁', '白', '蒲', '卓', '曾', '游',
        '权', '楚', '闫', '聂', '辛', '墨', '言', '兰', '千', '紫', '江', '林', '叶', '龙', '牧', '洛',
        '青', '程', '黎', '乔', '温', '桑'
    )

    private val PERSON_NAME_PREFIX_CHARS = setOf('老', '小', '阿')

    private val PERSON_NAME_MIDDLE_FUNCTION_CHARS = setOf('以', '之', '亦', '若', '如')

    private val NON_PERSON_NAME_SUFFIX_CHARS = setOf(
        '朝', '国', '城', '港', '府', '山', '海', '湖', '河', '楼', '船', '门', '寺', '院', '街', '巷',
        '氏', '钱', '银', '两', '斤', '章', '回', '卷', '时', '间',
        '说', '道', '问', '看', '听', '想', '站', '走', '来', '去', '让', '把', '给',
        '一', '二', '三', '四', '五', '六', '七', '八', '九', '十'
    )

    private val PERSON_NAME_STOP_CHARS = TERM_STOP_CHARS
}
