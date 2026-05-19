package com.ldp.reader.sourceengine.content

import com.ldp.reader.sourceengine.catalog.ChapterNormalizer

class DeterministicContentBelongingChecker(
    private val normalizer: ChapterNormalizer = ChapterNormalizer()
) : ContentBelongingChecker {
    override fun inspect(input: ContentInspectionInput): ContentBelongingReport {
        val content = input.cleanedContent.trim()
        if (content.isBlank()) {
            return ContentBelongingReport(false, 0, listOf("blank-content"))
        }

        val markers = ArrayList<String>()
        markers.addAll(referenceDivergenceMarkers(content, input.referenceContents))
        markers.addAll(fragmentedTailMarkers(content))
        markers.addAll(coherentForeignTailMarkers(content))
        markers.addAll(shortPrefixForeignTailMarkers(content))
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

        val distinctMarkers = markers.distinct()
        var score = 100
        if ("embedded-chapter-heading" in distinctMarkers) score -= 65
        if ("foreign-content-after-valid-prefix" in distinctMarkers) score -= 20
        if ("embedded-book-metadata" in distinctMarkers) score -= 30
        if ("cross-source-tail-divergence" in distinctMarkers) score -= 80
        if ("fragmented-tail-after-valid-prefix" in distinctMarkers) score -= 75
        if ("coherent-foreign-tail-after-valid-prefix" in distinctMarkers) score -= 75
        if ("short-prefix-foreign-tail" in distinctMarkers) score -= 75

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

    private fun shortPrefixForeignTailMarkers(content: String): List<String> {
        val normalized = normalizeForComparison(content)
        if (normalized.length < MIN_SHORT_PREFIX_CONTENT_CHARS) return emptyList()

        val prefixText = content.take(SHORT_PREFIX_CHARS)
        val tailText = content.drop(SHORT_PREFIX_TAIL_START_CHARS)
        val normalizedTail = normalizeForComparison(tailText)
        if (normalizedTail.length < MIN_SHORT_PREFIX_TAIL_CHARS) return emptyList()

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

    private fun coherentForeignTailMarkers(content: String): List<String> {
        val normalized = normalizeForComparison(content)
        if (normalized.length < MIN_COHERENT_FOREIGN_CONTENT_CHARS) return emptyList()

        val prefixText = content.take(COHERENT_FOREIGN_PREFIX_CHARS)
        val tailText = content.drop(COHERENT_FOREIGN_TAIL_START_CHARS)
        val normalizedTail = normalizeForComparison(tailText)
        if (normalizedTail.length < MIN_COHERENT_FOREIGN_TAIL_CHARS) return emptyList()

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
        val normalized = normalizeForComparison(value)
        if (normalized.length < 2) return emptyList()
        val names = ArrayList<String>()
        normalized.forEachIndexed { index, char ->
            if (char !in PERSON_NAME_START_CHARS) return@forEachIndexed
            val two = normalized.substring(index, (index + 2).coerceAtMost(normalized.length))
            val three = normalized.substring(index, (index + 3).coerceAtMost(normalized.length))
            val candidate = if (three.length == 3 && !containsStopCharacter(three.drop(1))) {
                three
            } else {
                two
            }
            if (candidate.length >= 2 && !containsStopCharacter(candidate.drop(1))) {
                names.add(candidate)
            }
        }
        return names
    }

    private fun containsStopCharacter(value: String): Boolean {
        return value.any { it in PERSON_NAME_STOP_CHARS }
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

        private val CHAPTER_HEADING_PATTERNS = listOf(
            Regex("""^\s*第\s*[0-9０-９零〇一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+\s*[章节回话卷].{0,50}$"""),
            Regex("""^\s*[0-9０-９]{1,5}\s*[.、]\s*.{1,50}$""")
        )

        private val BOOK_METADATA_PATTERNS = listOf(
            Regex("""^\s*(书名|小说名|作者|作家)\s*[:：].{1,50}$"""),
            Regex("""^\s*《[^》]{1,30}》\s*(作者|简介|最新章节)?.{0,30}$""")
        )

        private val FOREIGN_TAIL_PATTERNS = listOf(
            Regex("""(出租屋|霓虹灯|飞碟|学生会|登山服|火车站|站牌|码头|面试|任务者|安德莉亚|半妖|蚊虫嗡嗡|道师的追杀)"""),
            Regex("""(暴虐的王爷|王府里|选择题|选项|冰窖|黎筱雨|薇娅|万丈巨剑插在沙漠)"""),
            Regex("""(芝加哥|GMC|俱乐部|影院|电视发行|网络播放|飞机播映|麻省理工|剑桥|哈佛|百校汇演)"""),
            Regex("""(国足|迈巴赫|异能者|胡八一|王胖子|黄毛|唐宁|桃式|艾利亚|聂茴|法庭|专家证人|罗宋汤|足球)""")
        )

        private val PERSON_NAME_START_CHARS = setOf(
            '赵', '钱', '孙', '李', '周', '吴', '郑', '王', '冯', '陈', '褚', '卫', '蒋', '沈', '韩', '杨',
            '朱', '秦', '尤', '许', '何', '吕', '施', '张', '孔', '曹', '严', '华', '金', '魏', '陶', '姜',
            '戚', '谢', '邹', '喻', '柏', '水', '窦', '章', '云', '苏', '潘', '葛', '奚', '范', '彭', '郎',
            '鲁', '韦', '昌', '马', '苗', '凤', '花', '方', '俞', '任', '袁', '柳', '鲍', '史', '唐', '费',
            '廉', '岑', '薛', '雷', '贺', '倪', '汤', '滕', '殷', '罗', '毕', '郝', '邬', '安', '常', '乐',
            '于', '时', '傅', '皮', '卞', '齐', '康', '伍', '余', '元', '卜', '顾', '孟', '平', '黄', '和',
            '穆', '萧', '尹', '姚', '邵', '湛', '汪', '祁', '毛', '禹', '狄', '米', '贝', '明', '臧', '计',
            '伏', '成', '戴', '宋', '庞', '熊', '纪', '舒', '屈', '项', '祝', '董', '梁', '杜', '阮', '蓝',
            '闵', '席', '季', '麻', '强', '贾', '路', '娄', '危', '江', '童', '颜', '郭', '梅', '盛', '林',
            '刁', '钟', '徐', '邱', '骆', '高', '夏', '蔡', '田', '胡', '凌', '霍', '虞', '万', '支', '柯',
            '昝', '管', '卢', '莫', '柯', '房', '裘', '缪', '干', '解', '应', '宗', '丁', '宣', '邓', '郁',
            '单', '杭', '洪', '包', '诸', '左', '石', '崔', '吉', '龚', '程', '邢', '滑', '裴', '陆', '荣',
            '翁', '荀', '羊', '於', '惠', '甄', '曲', '家', '封', '芮', '羿', '储', '靳', '汲', '邴', '糜',
            '松', '井', '段', '富', '巫', '乌', '焦', '巴', '弓', '牧', '隗', '山', '谷', '车', '侯', '宓',
            '蓬', '全', '郗', '班', '仰', '秋', '仲', '伊', '宫', '宁', '仇', '栾', '暴', '甘', '钭', '厉',
            '戎', '祖', '武', '符', '刘', '景', '詹', '束', '龙', '叶', '幸', '司', '韶', '郜', '黎', '蓟',
            '薄', '印', '宿', '白', '怀', '蒲', '邰', '从', '鄂', '索', '咸', '籍', '赖', '卓', '蔺', '屠',
            '蒙', '池', '乔', '阴', '欎', '胥', '能', '苍', '双', '闻', '莘', '党', '翟', '谭', '贡', '劳',
            '逄', '姬', '申', '扶', '堵', '冉', '宰', '郦', '雍', '郤', '璩', '桑', '桂', '濮', '牛', '寿',
            '通', '边', '扈', '燕', '冀', '郏', '浦', '尚', '农', '温', '别', '庄', '晏', '柴', '瞿', '阎',
            '充', '慕', '连', '茹', '习', '宦', '艾', '鱼', '容', '向', '古', '易', '慎', '戈', '廖', '庾',
            '终', '暨', '居', '衡', '步', '都', '耿', '满', '弘', '匡', '国', '文', '寇', '广', '禄', '阙',
            '东', '殴', '殳', '沃', '利', '蔚', '越', '夔', '隆', '师', '巩', '厍', '聂', '晁', '勾', '敖',
            '融', '冷', '訾', '辛', '阚', '那', '简', '饶', '空', '曾', '毋', '沙', '乜', '养', '鞠', '须',
            '丰', '巢', '关', '蒯', '相', '查', '后', '荆', '红', '游', '竺', '权', '逯', '盖', '益', '桓',
            '公', '仉', '督', '岳', '帅', '缑', '亢', '况', '后', '有', '琴', '归', '海', '晋', '楚', '闫',
            '法', '汝', '鄢', '涂', '钦', '商', '牟', '佘', '佴', '伯', '赏', '墨', '哈', '谯', '笪', '年',
            '爱', '阳', '佟', '第', '言', '福', '兰', '千', '紫'
        )

        private val PERSON_NAME_STOP_CHARS = setOf(
            '的', '了', '着', '过', '是', '在', '有', '和', '与', '及', '或', '也', '都', '就', '还', '又',
            '很', '更', '最', '这', '那', '哪', '个', '些', '么', '吗', '呢', '啊', '吧', '把', '被', '从',
            '对', '为', '以', '而', '并', '但', '却', '所', '其', '他', '她', '它', '你', '我', '谁', '不'
        )
    }
}
