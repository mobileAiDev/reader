package com.ldp.reader.sourceengine.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentCleanerTest {
    @Test
    fun removesPollutionLinesDuplicateLinesAndTitleLine() {
        val raw = """
            <h1>第一章 陨落的天才</h1>
            <p>萧炎站在测验魔石碑之前，手掌轻轻贴了上去。</p>
            <p>萧炎站在测验魔石碑之前，手掌轻轻贴了上去。</p>
            <p>请收藏本站，方便下次阅读。</p>
            <p>斗之气，三段！几个大字让广场安静下来。</p>
            <p>最新网址：www.example.com</p>
        """.trimIndent()

        val result = ContentCleaner().clean(raw, "第一章 陨落的天才")

        assertEquals(
            "萧炎站在测验魔石碑之前，手掌轻轻贴了上去。\n斗之气，三段！几个大字让广场安静下来。",
            result.cleanedContent
        )
        assertEquals(4, result.report.removedLineCount)
        assertEquals(1, result.report.duplicateLineCount)
        assertTrue(result.report.pollutionMarkers.contains("chapter-title"))
        assertTrue(result.report.pollutionMarkers.contains("bookmark"))
        assertTrue(result.report.pollutionMarkers.contains("url"))
        assertFalse(result.cleanedContent.contains("www.example.com"))
    }

    @Test
    fun scoresShortPollutedContentLowerThanCleanContent() {
        val polluted = ContentCleaner().clean("广告\n正文")
        val clean = ContentCleaner().clean(
            """
                第一段正文内容足够长，描述人物动作和场景变化，形成完整可读的段落，并且保留连续的叙事信息。
                第二段正文继续推进情节，保留人物对话和动作变化，读者可以直接从上一段接着阅读下去。
                第三段正文补充上下文，让章节质量评分达到正常阅读条件，也给后续多来源对比留下足够文本。
                第四段正文保持连贯，便于后续与其他来源做交叉验证，确认抓取到的是同一本书同一章节。
                第五段正文收束这个片段，保持普通小说正文的段落密度和长度，让这一章具备完整阅读价值。
            """.trimIndent()
        )

        assertTrue(polluted.report.qualityScore < clean.report.qualityScore)
        assertTrue(polluted.report.warnings.contains("content-unusable"))
        assertTrue(clean.report.qualityScore >= 80)
    }

    @Test
    fun rejectsMostlyRemovedContentAsUnusable() {
        val raw = buildString {
            appendLine("最新网址：www.example.com")
            repeat(40) { appendLine("请收藏本站，方便下次阅读。") }
            appendLine("正文残片")
        }

        val result = ContentCleaner().clean(raw)

        assertEquals("正文残片", result.cleanedContent)
        assertTrue(result.report.qualityScore <= 20)
        assertTrue(result.report.warnings.contains("content-unusable"))
        assertTrue(result.report.warnings.contains("cleanup-ratio-unusable"))
    }

    @Test
    fun detectsContentThatSwitchesToAnotherChapterAfterValidPrefix() {
        val raw = buildString {
            appendLine("萧炎站在测验魔石碑之前，手掌轻轻贴了上去，广场上的目光都落在他身上。")
            appendLine("石碑光芒微弱，少年沉默着收回手掌，听见四周压低的议论声。")
            appendLine("族人们的反应让他握紧拳头，也让这个清晨显得格外漫长。")
            appendLine("远处的风掠过训练场，他没有立刻开口，只把所有嘲笑都压进心底。")
            appendLine("这是属于当前章节的开头，人物、场景和情绪都还保持着同一本书的连续性。")
            appendLine("他抬头看向高台，听见长老继续宣布测试结果，所有上下文仍然围绕萧家、测验和少年的处境展开。")
            appendLine("第九章 陌生的城市")
            appendLine("李明推开出租屋的门，看到窗外完全陌生的街道和霓虹灯。")
            appendLine("他不知道自己为什么会出现在这里，只能沿着街边慢慢寻找线索。")
        }

        val result = ContentCleaner().clean(
            rawContent = raw,
            chapterTitle = "第一章 陨落的天才",
            bookName = "斗破苍穹",
            author = "天蚕土豆"
        )

        assertTrue(result.report.coherenceScore < 70)
        assertTrue(result.report.coherenceMarkers.contains("embedded-chapter-heading"))
        assertTrue(result.report.coherenceMarkers.contains("foreign-content-after-valid-prefix"))
        assertTrue(result.report.warnings.contains("content-may-belong-to-other-book"))
    }

    @Test
    fun detectsFragmentedForeignTailWithoutEmbeddedChapterHeading() {
        val raw = buildString {
            appendLine("陆长安敏锐捕捉到关键字眼，中州智者预言，不远的将来，此界将迎来一场浩劫。")
            appendLine("覆海真君道出消息来源，陆长安深思，心底隐隐有些猜测。")
            appendLine("在天珩大陆中央，能被称为智者的存在，其卜卦之术至少达到四阶后期。")
            appendLine("当年离开大渊时，他便嗅到某些异常，说不清道不明，只能继续追问前因后果。")
            appendLine("陆长安继续询问中州各派的动向，覆海真君将几处灵脉异象一一说明。")
            appendLine("这些消息仍然围绕天珩大陆、大渊旧事和即将到来的浩劫展开，语气始终凝重。")
            appendLine("他把玉简中的旧年记录和眼前预言互相印证，确认覆海真君并非危言耸听。")
            appendLine("洞府外的灵潮仍在起伏，陆长安暂时压下杂念，只准备继续追问中州那位智者的来历。")
            appendLine("可她却好似被打开了什么机关一样，一口口往外咳着鲜血。")
            appendLine("安德莉亚手上的动作缓缓停住，身子一抖，情不自禁地打了个寒颤。")
            appendLine("李宓看了一眼沈幼清，见沈幼清答应以后，也就带着沈幼清跟着那位管事离开。")
            appendLine("林闲看到一个比鸡还鸡的飞碟，觉得这玩意好像也不算飞碟。")
            appendLine("李松吓得不敢出声，连连点头，一个劲儿地道歉作保证。")
            appendLine("紫灵听到后心中一颤，随着挽起手臂一道主仆印出现，紫炎剑掉在地面。")
            appendLine("白芷忽然想起山下的商队，催着韩叔把破损的灯笼重新挂回门口。")
            appendLine("乔峰皱眉看着远处的雪线，完全不明白这座城为何会在一夜之间封门。")
            appendLine("陈墨推开书院旧门，发现案几上摆着一封没有署名的信，墨迹还没有完全干透。")
            appendLine("王婉拉住赵衡的衣袖，低声说码头那边已经换了旗号，今晚最好不要再出城。")
            appendLine("方晴在火车站外停住脚步，望着陌生站牌发怔，身旁的孩子却一直哭闹不止。")
        }

        val result = ContentCleaner().clean(
            rawContent = raw,
            chapterTitle = "第521章 诚不我欺，翻手为云",
            bookName = "我在修仙界万古长青",
            author = "快餐店"
        )

        assertTrue(result.report.toString(), result.report.coherenceScore < 70)
        assertTrue(result.report.toString(), result.report.coherenceMarkers.contains("fragmented-tail-after-valid-prefix"))
        assertTrue(result.report.toString(), result.report.coherenceMarkers.contains("foreign-content-after-valid-prefix"))
        assertTrue(result.report.toString(), result.report.warnings.contains("content-may-belong-to-other-book"))
    }

    @Test
    fun keepsCoherentXianxiaTailWithManyNamesAndSceneChanges() {
        val raw = buildString {
            appendLine("秦桑一只手捏着透明蜈蚣，另一只手按住玉屏风边缘，任由小洞天中的灵机缓缓沉降。")
            appendLine("青衫男子站在阵外，目光落向石台深处，提醒他此地禁制尚未完全平复，不宜贸然催动法宝。")
            appendLine("秦桑没有立刻回应，只让云游剑悬在身侧，又命雷兽战卫护住阵脚，等待天目蝶传回更清晰的感应。")
            appendLine("小洞天仍处于动荡之中，虚空裂隙在远处忽明忽暗，像是一道随时可能合拢的门户。")
            appendLine("玉屏风上的灵纹一层层亮起，先前散乱的气息被重新梳理，终于显露出通往浊河源头的细小脉络。")
            appendLine("青衫男子见状收起笑意，低声说这条脉络与旧日洞府相连，若能顺势追索，或许可以找到失落的阵眼。")
            appendLine("秦桑神色不变，先将透明蜈蚣送入玉屏风，又以剑气封住左右两侧，防止浊气反卷伤及众人。")
            appendLine("雷兽战卫踏前一步，周身电光在石阶上铺开，替天目蝶争取到片刻安稳，蝶影随即没入灵纹深处。")
            appendLine("片刻之后，玉屏风背面浮现出一条淡淡水线，水线尽头正对应着浊河的支流，也印证了青衫男子的推断。")
            appendLine("秦桑确认方向后，才缓缓收回手掌，让云游剑先行开路，自己则压住阵心，准备进入下一处禁制。")
        }

        val result = ContentCleaner().clean(
            rawContent = raw,
            chapterTitle = "第二千二百章 浊河",
            bookName = "叩问仙道",
            author = "雨打青石"
        )

        assertTrue(result.report.toString(), result.report.coherenceScore >= 70)
        assertFalse(result.report.toString(), result.report.coherenceMarkers.contains("fragmented-tail-after-valid-prefix"))
        assertFalse(result.report.toString(), result.report.coherenceMarkers.contains("foreign-content-after-valid-prefix"))
    }

    @Test
    fun detectsReferenceTailDivergenceAfterMatchingPrefix() {
        val correctPrefix = """
            悬粟洞天可能是帝天子一脉的所有洞天里，唯一没有被天魔占据的洞天。
            洞天之中草木茂盛，青山绿水，拥有和魔域截然不同的景象。
            不过悬粟洞天的阡陌塔附近，应是当年地极魔宫修士和天魔大战的战场。
            多年来不曾有人涉足，那里还维持着古战场的原貌，残存禁制在石壁间若隐若现。
            秦桑凝神感应魔气流向，蓝容彩则在旁边辨认旧日阵纹，二人都没有贸然深入。
            他将神识铺向阡陌塔周围，发现帝天子一脉留下的几处封印仍在缓慢运转。
            蓝容彩提醒此地禁制牵连魔域旧阵，若是强行破开，很可能惊动潜伏深处的天魔。
        """.trimIndent()
        val correctTail = """
            秦桑凝视着阡陌塔深处的裂隙，感应到残存魔气仍在缓慢流转。
            他以剑阵护住众人，沿着古战场的边缘前行，寻找帝天子一脉留下的线索。
            魔火在石壁上明灭不定，悬粟洞天的禁制随之震荡，像是在回应他的神识。
            等到蓝容彩传音示警，秦桑已经看清阵眼所在，立刻催动法宝镇住魔影。
            阡陌塔外的草木被余波压低，众人依照秦桑吩咐后撤，只留下几道阵旗继续稳住入口。
            等魔气暂时平息，秦桑才把帝天子一脉的旧令取出，尝试唤醒洞天深处的残存灵机。
        """.trimIndent()
        val pollutedTail = """
            吕二娘不知道自己百年之后，如何去面对吕泰、封三娘。
            李玉莹抓起筷子夹起一条野鸡腿，大口咀嚼起来。
            留无邪的面孔不断扭曲，一会儿是蓝魈，一会儿是红箫。
            兰神医翻了翻烟香的眼睑，号了烟香的脉。
            千叶看来，韶华来江南照顾她已是难得。
            白芷忽然想起山下的商队，催着韩叔把破损的灯笼重新挂回门口。
            乔峰皱眉看着远处的雪线，完全不明白这座城为何会在一夜之间封门。
            陈墨推开书院旧门，发现案几上摆着一封没有署名的信，墨迹还没有完全干透。
            王婉拉住赵衡的衣袖，低声说码头那边已经换了旗号，今晚最好不要再出城。
            方晴在火车站外停住脚步，望着陌生站牌发怔，身旁的孩子却一直哭闹不止。
        """.trimIndent()

        val result = ContentCleaner().clean(
            rawContent = "$correctPrefix\n$pollutedTail",
            chapterTitle = "第二千六百九十一章 不同的选择",
            bookName = "叩问仙道",
            author = "雨打青石",
            referenceContents = listOf("$correctPrefix\n$correctTail")
        )

        assertTrue(result.report.toString(), result.report.coherenceScore < 70)
        assertTrue(result.report.toString(), result.report.coherenceMarkers.contains("cross-source-tail-divergence"))
        assertTrue(result.report.toString(), result.report.coherenceMarkers.contains("foreign-content-after-valid-prefix"))
    }

    @Test
    fun keepsContentWhenReferenceMatchesPrefixAndTail() {
        val content = """
            陆长安敏锐捕捉到关键字眼，中州智者预言，不远的将来，此界将迎来一场浩劫。
            覆海真君道出消息来源，陆长安深思，心底隐隐有些猜测。
            在天珩大陆中央，能被称为智者的存在，其卜卦之术至少达到四阶后期。
            陆长安没有立刻回答，只在心中推演这场大劫会牵连哪些宗门和故人。
            覆海真君继续解释中州各派的动向，提醒陆长安尽早返回洞府布置后手。
            两人谈到天珩大陆的灵脉变化，又提及大渊旧事，语气都变得凝重。
            陆长安收起玉简，决定先稳住修为，再借傀儡探查几处关键节点。
            覆海真君点头认可，约定三日后再交换消息，共同确认中州预言的真伪。
        """.trimIndent()

        val result = ContentCleaner().clean(
            rawContent = content,
            chapterTitle = "第521章 诚不我欺，翻手为云",
            bookName = "我在修仙界万古长青",
            author = "快餐店",
            referenceContents = listOf(content.replace("关键字眼", "关键字"))
        )

        assertTrue(result.report.toString(), result.report.coherenceScore >= 70)
        assertFalse(result.report.coherenceMarkers.contains("cross-source-tail-divergence"))
        assertFalse(result.report.coherenceMarkers.contains("fragmented-tail-after-valid-prefix"))
    }

    @Test
    fun contentBelongingCheckerCanBeReplacedByModelBackedImplementation() {
        val cleaner = ContentCleaner(
            belongingChecker = object : ContentBelongingChecker {
                override fun inspect(input: ContentInspectionInput): ContentBelongingReport {
                    return ContentBelongingReport(
                        belongsToChapter = false,
                        score = 12,
                        markers = listOf("model-rejected")
                    )
                }
            }
        )

        val result = cleaner.clean(
            rawContent = """
                第一段正文内容足够长，描述人物动作和场景变化，形成完整可读的段落。
                第二段正文继续推进情节，保留人物对话和动作变化。
                第三段正文补充上下文，让章节质量评分达到正常阅读条件。
            """.trimIndent(),
            chapterTitle = "第一章"
        )

        assertEquals(12, result.report.coherenceScore)
        assertTrue(result.report.coherenceMarkers.contains("model-rejected"))
        assertTrue(result.report.warnings.contains("content-may-belong-to-other-book"))
    }
}
