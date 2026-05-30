package com.ldp.reader.sourceengine.content.v8

import com.ldp.reader.sourceengine.content.v8.V8ChapterInput
import com.ldp.reader.sourceengine.content.v8.V8CleanAction
import com.ldp.reader.sourceengine.content.v8.V8NovelStateOutputType
import com.ldp.reader.sourceengine.content.v8.V8ChapterMarkResult
import com.ldp.reader.sourceengine.content.v8.V8ChapterMarkState
import com.ldp.reader.sourceengine.content.v8.V8DiagnosticSink
import com.ldp.reader.sourceengine.content.v8.V8SparseSemanticModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V8SourceChapterValidatorTest {
    private val validator = V8SourceChapterValidator(
        semanticModel = V8SparseSemanticModel(),
        config = V8PsbmtConfig(
            minLowThreshold = 0.015,
            maxLowThreshold = 0.055,
            lowThresholdMinDrop = 0.02,
            normalThresholdMinDrop = 0.01,
            minNormalThresholdGap = 0.01,
            futureRescueThreshold = 0.08,
            tailClusterFutureThreshold = 0.12
        )
    )

    @Test
    fun mapsSparseSuspectPollutionToInconclusive() {
        val book = bookChapters()
        val current = storyText(book[3]).take(136) + storyText(foreignChapter()).drop(30)
        val diagnostics = ArrayList<String>()

        val result = validator.validate(
            V8SourceRunRequest(
                title = "武馆旧案",
                author = "test",
                sourceKey = "source-a",
                chapters = listOf(
                    V8ChapterInput(0, "第一章", storyText(book[0])),
                    V8ChapterInput(1, "第二章", storyText(book[1])),
                    V8ChapterInput(2, "第三章", storyText(book[2])),
                    V8ChapterInput(3, "第四章", current),
                    V8ChapterInput(4, "第五章", storyText(book[4]))
                ),
                markableChapterIndexes = setOf(3),
                contextChapterIndexes = setOf(0, 1, 2),
                diagnosticSink = V8DiagnosticSink { line -> diagnostics.add(line) }
            )
        )

        assertEquals(1, result.marks.size)
        assertEquals(V8ChapterMarkState.INCONCLUSIVE, result.marks.single().state)
        assertEquals(4, result.latestObservedOrdinal)
        assertEquals(null, result.firstBadTailOrdinal)
        assertTrue(diagnostics.any { line -> line.startsWith("v8.mark.finish ") })
    }

    @Test
    fun keepsSameBookChapterNormal() {
        val book = bookChapters()

        val result = validator.validate(
            V8SourceRunRequest(
                title = "武馆旧案",
                author = "test",
                sourceKey = "source-a",
                chapters = listOf(
                    V8ChapterInput(0, "第一章", storyText(book[0])),
                    V8ChapterInput(1, "第二章", storyText(book[1])),
                    V8ChapterInput(2, "第三章", storyText(book[2])),
                    V8ChapterInput(3, "第四章", storyText(book[3])),
                    V8ChapterInput(4, "第五章", storyText(book[4]))
                ),
                markableChapterIndexes = setOf(3),
                contextChapterIndexes = setOf(0, 1, 2)
            )
        )

        assertEquals(V8ChapterMarkState.NORMAL, result.marks.single().state)
        assertEquals(4, result.latestObservedOrdinal)
        assertEquals(4, result.latestNormalOrdinal)
        assertEquals(null, result.firstBadTailOrdinal)
    }

    @Test
    fun returnsInconclusiveWhenPreviousContextIsMissing() {
        val book = bookChapters()

        val result = validator.validate(
            V8SourceRunRequest(
                title = "武馆旧案",
                author = "test",
                sourceKey = "source-a",
                chapters = listOf(
                    V8ChapterInput(3, "第四章", storyText(book[3])),
                    V8ChapterInput(4, "第五章", storyText(book[4]))
                ),
                markableChapterIndexes = setOf(3)
            )
        )

        assertEquals(V8ChapterMarkState.INCONCLUSIVE, result.marks.single().state)
        assertEquals(4, result.latestObservedOrdinal)
        assertEquals(0, result.latestNormalOrdinal)
        assertEquals(null, result.firstBadTailOrdinal)
    }

    @Test
    fun reportsEarliestCredibleBadTailBoundary() {
        val result = V8SourceRunResult(
            title = "尾部探针",
            author = "test",
            sourceKey = "source-a",
            marks = (755..762).map { index -> mark(index, V8ChapterMarkState.NORMAL) } +
                listOf(
                    mark(763, V8ChapterMarkState.WRONG),
                    mark(764, V8ChapterMarkState.WRONG),
                    mark(765, V8ChapterMarkState.WRONG)
                ),
            planningMarks = emptyList()
        )

        assertEquals(764, result.firstBadTailOrdinal)
    }

    @Test
    fun isolatedBadChapterDoesNotPoisonLaterNormalChapter() {
        val book = bookChapters()
        val badTail = "今天请假，明天恢复更新。感谢大家，求月票。"

        val result = validator.validate(
            V8SourceRunRequest(
                title = "武馆旧案",
                author = "test",
                sourceKey = "source-a",
                chapters = listOf(
                    V8ChapterInput(0, "第一章", storyText(book[0])),
                    V8ChapterInput(1, "第二章", storyText(book[1])),
                    V8ChapterInput(2, "第三章", storyText(book[2])),
                    V8ChapterInput(3, "请假一天", badTail),
                    V8ChapterInput(4, "第五章", storyText(book[3])),
                    V8ChapterInput(5, "第六章", storyText(book[4]))
                ),
                markableChapterIndexes = setOf(3, 4),
                contextChapterIndexes = setOf(0, 1, 2)
            )
        )

        val brief = result.marks.joinToString("\n") { mark -> "${mark.chapterIndex} ${mark.state} ${mark.reasons}" }
        assertEquals(brief, V8ChapterMarkState.NON_STORY, result.marks[0].state)
        assertEquals(brief, V8ChapterMarkState.NORMAL, result.marks[1].state)
    }

    @Test
    fun contentQualityForeignWarningDoesNotBypassPsbmt() {
        val book = bookChapters()
        val current = storyText(book[3])

        val result = validator.validate(
            V8SourceRunRequest(
                title = "武馆旧案",
                author = "test",
                sourceKey = "source-a",
                chapters = listOf(
                    V8ChapterInput(0, "第一章", storyText(book[0])),
                    V8ChapterInput(1, "第二章", storyText(book[1])),
                    V8ChapterInput(2, "第三章", storyText(book[2])),
                    V8ChapterInput(
                        3,
                        "第四章",
                        current,
                        V8ContentQualitySignal(
                            qualityScore = 30,
                            coherenceScore = 0,
                            cleanedLength = current.length,
                            warnings = listOf("content-may-belong-to-other-book")
                        )
                    )
                ),
                markableChapterIndexes = setOf(3),
                contextChapterIndexes = setOf(0, 1, 2)
            )
        )

        assertEquals(V8ChapterMarkState.NORMAL, result.marks.single().state)
    }

    @Test
    fun contentQualityCoherenceWarningDoesNotBypassPsbmt() {
        val book = bookChapters()
        val current = storyText(book[3])

        val result = validator.validate(
            V8SourceRunRequest(
                title = "武馆旧案",
                author = "test",
                sourceKey = "source-a",
                chapters = listOf(
                    V8ChapterInput(0, "第一章", storyText(book[0])),
                    V8ChapterInput(1, "第二章", storyText(book[1])),
                    V8ChapterInput(2, "第三章", storyText(book[2])),
                    V8ChapterInput(
                        3,
                        "第四章",
                        current,
                        V8ContentQualitySignal(
                            qualityScore = 80,
                            coherenceScore = 80,
                            cleanedLength = current.length,
                            warnings = listOf("content-coherence-warning")
                        )
                    )
                ),
                markableChapterIndexes = setOf(3),
                contextChapterIndexes = setOf(0, 1, 2)
            )
        )

        assertEquals(V8ChapterMarkState.NORMAL, result.marks.single().state)
    }

    @Test
    fun targetChaptersDoNotBecomeReferenceForLaterTargetsInSameRun() {
        val book = bookChapters()
        val recorder = RecordingSemanticModel()
        val validator = V8SourceChapterValidator(
            semanticModel = recorder,
            config = V8PsbmtConfig(
                minLowThreshold = 0.015,
                maxLowThreshold = 0.055,
                lowThresholdMinDrop = 0.02,
                normalThresholdMinDrop = 0.01,
                minNormalThresholdGap = 0.01,
                futureRescueThreshold = 0.08,
                tailClusterFutureThreshold = 0.12
            )
        )

        val result = validator.validate(
            V8SourceRunRequest(
                title = "武馆旧案",
                author = "test",
                sourceKey = "source-a",
                chapters = listOf(
                    V8ChapterInput(0, "第一章", storyText(book[0])),
                    V8ChapterInput(1, "第二章", storyText(book[1])),
                    V8ChapterInput(2, "第三章", storyText(book[2])),
                    V8ChapterInput(3, "第四章", storyText(book[3])),
                    V8ChapterInput(4, "第五章", storyText(book[4]))
                ),
                markableChapterIndexes = setOf(3, 4),
                contextChapterIndexes = setOf(0, 1, 2)
            )
        )

        val brief = result.marks.joinToString("\n") { mark -> "${mark.chapterIndex} ${mark.state}" }
        assertEquals(brief, V8ChapterMarkState.NORMAL, result.marks[0].state)
        assertEquals(2, recorder.calls.size)
        assertTrue(recorder.calls[1].referenceTexts.any { text -> text.contains("顾南衣推开武馆后门") })
        assertTrue(recorder.calls[1].referenceTexts.any { text -> text.contains("顾南衣收住呼吸") })
        assertTrue(recorder.calls[1].referenceTexts.any { text -> text.contains("三江盟客人坐在武馆正堂") })
        assertTrue(recorder.calls[1].referenceTexts.none { text -> text.contains("雨声忽然变急") })
    }

    private fun storyText(sentences: List<String>): String {
        return buildString {
            repeat(10) { round ->
                sentences.forEach { sentence ->
                    append(sentence).append("第").append(round).append("轮。")
                }
            }
        }
    }

    private fun bookChapters(): List<List<String>> {
        return listOf(
            listOf(
                "顾南衣推开武馆后门，雨水顺着檐角落下，演武场上的青砖已经被拳劲震出细纹。",
                "沈听澜抱着刀匣站在廊下，提醒他今日擂台不同往常，三江盟的客人都在暗处观望。",
                "老掌柜翻出一卷泛黄拳谱，上面记着伏虎劲、碎玉步和当年北境一战的残缺注解。"
            ),
            listOf(
                "顾南衣收住呼吸，把气血压回丹田，等铜钟第三声响起才缓缓踏入雨幕。",
                "城西镖局送来的密信还带着火漆，信中只说黑水寨又劫了一批药材。",
                "沈听澜把刀匣扣紧，三江盟的暗号在窗纸上留下两道淡淡划痕。"
            ),
            listOf(
                "三江盟客人坐在武馆正堂，茶盏旁压着一枚黑水寨的旧铜钱。",
                "顾南衣看出铜钱边缘有伏虎劲留下的震纹，心里已经猜到来人目的。",
                "沈听澜低声提醒他，北境旧案和这枚铜钱之间恐怕还有一层牵连。"
            ),
            listOf(
                "雨声忽然变急，顾南衣听见后巷传来短促铜哨，正是三江盟约定的求援信号。",
                "沈听澜先一步推开窗，刀匣中寒光一闪，黑水寨的探子已经越过墙头。",
                "老掌柜没有出声，只把那卷伏虎劲残谱塞进顾南衣怀里。"
            ),
            listOf(
                "天亮以后，顾南衣和沈听澜在城外破庙会合，三江盟的人已经撤得干干净净。",
                "残谱上多出一行细字，指向北境雨夜里失踪的第二批药材。",
                "顾南衣知道黑水寨不会善罢甘休，便决定先回武馆稳住弟子。"
            )
        )
    }

    private fun foreignChapter(): List<String> {
        return listOf(
            "韩砚站在太空港候机厅，透明穹顶外的货运飞船缓慢升空，机械警卫扫描着每一张通行证。",
            "阿诺把芯片藏进袖口，低声说第九殖民区已经失联，星网只剩下一段被截断的求救信号。",
            "指挥官在全息沙盘上标出跃迁坐标，蓝色光点穿过小行星带，最后停在废弃矿站旁边。"
        )
    }

    private fun mark(index: Int, state: V8ChapterMarkState): V8ChapterMarkResult {
        return V8ChapterMarkResult(
            chapterIndex = index,
            chapterTitle = "第${index + 1}章",
            state = state,
            confidence = 0.9,
            qualityType = null,
            suggestionState = if (state == V8ChapterMarkState.WRONG) {
                V8NovelStateOutputType.POLLUTED_SUFFIX
            } else {
                V8NovelStateOutputType.NORMAL
            },
            action = if (state == V8ChapterMarkState.WRONG) V8CleanAction.MARK_ONLY else V8CleanAction.KEEP,
            reasons = emptyList()
        )
    }

    private class RecordingSemanticModel : V8SemanticModel {
        private val delegate = V8SparseSemanticModel()
        val calls = ArrayList<BuildCall>()

        override fun build(
            referenceTexts: List<String>,
            currentText: String,
            futureTexts: List<String>,
            config: V8PsbmtConfig
        ): V8SemanticSpace {
            calls.add(BuildCall(referenceTexts, currentText, futureTexts))
            return delegate.build(referenceTexts, currentText, futureTexts, config)
        }
    }

    private data class BuildCall(
        val referenceTexts: List<String>,
        val currentText: String,
        val futureTexts: List<String>
    )
}
