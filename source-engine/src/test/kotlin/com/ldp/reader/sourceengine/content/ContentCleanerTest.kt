package com.ldp.reader.sourceengine.content

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentCleanerTest {
    @Test
    fun removesLeadingEntityEqualsArtifacts() {
        val raw = "&nbsp;=&nbsp;=&nbsp;=&nbsp;正文第一段<br />&nbsp;=&nbsp;=&nbsp;第二段"

        val result = ContentCleaner().clean(raw)

        assertEquals("正文第一段\n第二段", result.cleanedContent)
    }

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
    fun removesMobileSiteAddressLine() {
        val raw = """
            四阶巅峰的殿主龙王道：“敖通，你拥有金蛟血脉。”
            记住手机版网址：m.xinbqg.info
            陆长安远离事发海域，中途多次变幻身份气息。
        """.trimIndent()

        val result = ContentCleaner().clean(raw, "第509章 金昌之死，龙宫震动")

        assertEquals(
            "四阶巅峰的殿主龙王道：“敖通，你拥有金蛟血脉。”\n陆长安远离事发海域，中途多次变幻身份气息。",
            result.cleanedContent
        )
        assertTrue(result.report.pollutionMarkers.contains("url"))
        assertFalse(result.cleanedContent.contains("xinbqg"))
        assertFalse(result.cleanedContent.contains("手机版网址"))
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
    fun embeddedChapterHeadingWithoutBodyFingerprintMismatchOnlyWarns() {
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

        assertTrue(result.report.toString(), result.report.coherenceScore >= 70)
        assertTrue(result.report.coherenceMarkers.contains("embedded-chapter-heading"))
        assertTrue(result.report.coherenceMarkers.contains("foreign-content-after-valid-prefix"))
        assertFalse(result.report.toString(), result.report.warnings.contains("content-may-belong-to-other-book"))
    }

    @Test
    fun fragmentedForeignTailWithoutBodyFingerprintMismatchOnlyWarns() {
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

        assertTrue(result.report.toString(), result.report.coherenceScore >= 70)
        assertTrue(result.report.toString(), result.report.coherenceMarkers.contains("fragmented-tail-after-valid-prefix"))
        assertTrue(result.report.toString(), result.report.coherenceMarkers.contains("foreign-content-after-valid-prefix"))
        assertFalse(result.report.toString(), result.report.warnings.contains("content-may-belong-to-other-book"))
    }

    @Test
    fun keepsModernCrimeChapterWithUrbanSceneTerms() {
        val raw = buildString {
            appendLine("爆炸后的街区还在冒烟，警戒线外的霓虹灯被雨水冲得忽明忽暗。")
            appendLine("江停站在救护车旁，听见对讲机里反复传来支援请求，眉眼却始终冷静。")
            appendLine("严峫把现场照片摊在车盖上，指着其中一处烧焦痕迹，低声问杨媚是否见过这个标记。")
            appendLine("杨媚摇头，指尖攥紧包带，KTV 后门的灯箱在风里晃动，映得她脸色发白。")
            appendLine("他们沿着巷口慢慢往里走，墙面上残留的黑灰、碎玻璃和脚印都指向同一个方向。")
            appendLine("刑侦队员陆续赶到，封锁线重新外扩，围观的人群被民警劝到马路对面。")
            appendLine("严峫没有急着下结论，只把时间线重新排了一遍，确认爆炸前最后一个电话来自医院。")
            appendLine("江停沉默片刻，说那不是偶然，对方留下的线索太刻意，像是在逼他们追过去。")
            appendLine("雨越下越大，警灯和店招在水洼里碎成一片片红蓝光影，整条街只剩电流声。")
            appendLine("杨媚终于开口，声音很轻，却把她刚才隐瞒的那段见面经过完整说了出来。")
        }

        val result = ContentCleaner().clean(
            rawContent = raw,
            chapterTitle = "第1章 chapter 1",
            bookName = "破云",
            author = "淮上"
        )

        assertTrue(result.report.toString(), result.report.coherenceScore >= 70)
        assertFalse(result.report.toString(), result.report.warnings.contains("content-may-belong-to-other-book"))
    }

    @Test
    fun keepsNanhongBarChapterWithContinuingLeadNames() {
        val raw = buildString {
            appendLine("好些年没见，距离最后一次见面至今，没有任何联系。淡薄到让温以凡几乎要忘了这个人的存在。")
            appendLine("两人的最后一次对话，并不太愉快，温以凡的头一个反应就是，对方认错人了。")
            appendLine("桑延没接，目光从她手上略过。而后，他淡声说：“我是这家酒吧的老板。”")
            appendLine("温以凡的手定在半空中，一时间也不太清楚他这话是在自我介绍，还是在炫耀。")
            appendLine("堕落街的俱乐部里灯光昏暗，钟思乔朝她眨眼，问她到底是怎么回事。")
            appendLine("温以凡只是把外套递给服务员，又看到桑延站在吧台旁，神色依旧懒散。")
            appendLine("她不想再跟桑延多说什么，转身和钟思乔一起往外走。")
            appendLine("可到了门口，冷风一吹，温以凡又想起少年时桑延最后喊她名字的样子。")
            appendLine("桑延声音沙哑，最后喊了她一声：“温以凡。”")
            appendLine("她记得那晚没有月亮，细雨落在窄巷里，也记得他说不会再缠着她。")
            appendLine("余卓收拾桌子时捡到手链，何明博让他上楼去找桑延。")
            appendLine("桑延坐在卡座最里侧，听完余卓的话，只慢慢抬了下眼。")
        }

        val result = ContentCleaner().clean(
            rawContent = raw,
            chapterTitle = "2 难哄",
            bookName = "难哄",
            author = "竹已"
        )

        assertTrue(result.report.toString(), result.report.coherenceScore >= 70)
        assertFalse(result.report.toString(), result.report.coherenceMarkers.contains("foreign-content-after-valid-prefix"))
    }

    @Test
    fun fingerprintKeepsNanhongChapterAcrossLegitimateSceneShift() {
        val raw = buildString {
            appendLine("温以凡还隐约记得，当时自己若无其事地把名字报出后，桑延只是拖着腔调啊了声。")
            appendLine("现在想起，她莫名还能脑补出他当时的心路历程，最后到温以凡也不过如此。")
            appendLine("那高傲到不可一世的模样，跟现在几乎相差无二。")
            appendLine("但也许是因为年纪增长，他不像少年时那般喜形于色")
            appendLine("恰好到了地铁站，温以凡边从包里翻着地铁卡，边拿出手机。")
            appendLine("回到家后，她先给房东打了电话，商量退租的事情。")
            appendLine("温以凡把邻居骚扰的事情告诉钟思乔，又开始重新看租房网站。")
            appendLine("没过多久，她跟台里申请了采访车，准备去东九广场做烟火秀直播。")
            appendLine("钱卫华开始调试设备，付壮也跟着她一块过去，甄玉则负责出镜。")
            appendLine("海风染上低温，温以凡戴上口罩，绕到公共厕所门口排队。")
            appendLine("她在凉亭旁再次看到桑延，他正跟中年女人说话，神色懒散。")
            appendLine("温以凡收回目光，手机里钟思乔发来桑延群发的新年快乐截图。")
        }

        val result = ContentCleaner().clean(
            rawContent = raw,
            chapterTitle = "5 难哄",
            bookName = "难哄",
            author = "竹已",
            bookFingerprint = nanhongFingerprint()
        )

        assertTrue(result.report.toString(), result.report.coherenceScore >= 70)
        assertFalse(result.report.toString(), result.report.coherenceMarkers.contains("fingerprint-body-divergence"))
    }

    @Test
    fun coherentForeignStoryTailWithoutBodyFingerprintMismatchOnlyWarns() {
        val raw = buildString {
            appendLine("眼看帝天子的身躯腐朽成沙，秦桑顿时惊悚，难道帝天子早已陨落！")
            appendLine("那头自在天魔又在何处，已经和帝天子同归于尽，还是仍然潜伏在混沌星辰深处。")
            appendLine("倘若那头自在天魔还活着，秦桑的下场可想而知。")
            appendLine("随着帝天子身化飞沙，他身下的宝盖，以及面前那柄本应由秦桑拔起的灵剑，竟也和他的肉身一起腐朽了。")
            appendLine("剑身表面剑光消散，只剩一点灵机在混沌中明灭。")
            appendLine("在王府里也许是最好的，虽然要面对那个暴虐的王爷，可至少短时间内安全可以得到保证。")
            appendLine("“黎筱雨，你这是选择题？我不可能选择第一个，你这不就相当于只给了我一个选项吗？”")
            appendLine("我苦逼的抱怨道。")
            appendLine("山就如同一把万丈巨剑插在沙漠，内部不仅仅是空心的，而且还充斥着大量的金色气雾。")
            appendLine("这个男人的声音仿佛是从地狱里传来的一般，让薇娅的整个身体顿时就如同陷入了冰窖窟里一般。")
            appendLine("倒是秦子皓瞥了二人一眼，没有太多的动作，手中的光球还在聚集。")
            appendLine("黎筱雨低头看着那张泛黄的契约，指尖停在王府印章旁边，迟迟没有再往下按。")
            appendLine("薇娅想要开口，却被身旁侍卫的目光逼了回去，只能跟着人群退到长廊尽头。")
            appendLine("那位王爷站在阶前，像是在审视一件无关紧要的物品，语气里没有半分修士斗法的紧迫。")
            appendLine("金色气雾从沙漠深处涌来，卷过巨剑的剑柄，又把王府里的灯火映得忽明忽暗。")
        }

        val result = ContentCleaner().clean(
            rawContent = raw,
            chapterTitle = "第二千六百九十章 宇宙洪荒，混沌星辰",
            bookName = "叩问仙道",
            author = "雨打青石"
        )

        assertTrue(result.report.toString(), result.report.coherenceScore >= 70)
        assertTrue(
            result.report.toString(),
            result.report.coherenceMarkers.contains("coherent-foreign-tail-after-valid-prefix") ||
                result.report.coherenceMarkers.contains("short-prefix-foreign-tail")
        )
        assertTrue(result.report.toString(), result.report.coherenceMarkers.contains("foreign-content-after-valid-prefix"))
        assertFalse(result.report.toString(), result.report.warnings.contains("content-may-belong-to-other-book"))
    }

    @Test
    fun multiBookTailAfterShortCorrectPrefixWithoutBodyFingerprintMismatchOnlyWarns() {
        val raw = buildString {
            appendLine("虫群涌入元载天。")
            appendLine("入侵元载天的不仅是魔虫，须知他们每一只都是蠹宙魔国的产物，相当于蠹宙魔国的道则附着在它们身上。")
            appendLine("顷刻之间，这座牢笼便挤满了黑压压的虫群。")
            appendLine("不计其数的魔虫拱卫着巨虾魔虫，它们形态各异，有的就像普通的飞蝗，有的长相凶恶。")
            appendLine("“既然他忍不住先出手了，那么我便就不用客气了……”龙展颜看着身边上官凌渊轻轻说道。")
            appendLine("“谢谢大哥提醒，其实现在我最担心的就是，这次能不能给老赵生下一个儿子。”井上枝子脸上有些发愁道。")
            appendLine("这种情况再劝就没意思了，万一国足真的胜了，别人就该埋怨你拦住他发财了。")
            appendLine("他此时此刻，已经只剩下一颗头颅，且头颅被叶鲲封住，无法继续使用再生的能力。")
            appendLine("当他们的眼神刚接触迈巴赫的时候，嘴里也情不自禁的发出了感叹的声音。")
            appendLine("刚打开就有一位高阶三星异能者冲到面前端起那盘龙虾，如翡翠白玉般的青椒葱蒜点缀着油光通亮的龙虾。")
            appendLine("胡八一看向自己，王胖子连忙说道。")
            appendLine("黄毛对其他人的吐槽加鄙视，都被楚枫偷偷记录下来。")
        }

        val result = ContentCleaner().clean(
            rawContent = raw,
            chapterTitle = "第二千六百八十八章 虫魔噬界",
            bookName = "叩问仙道",
            author = "雨打青石"
        )

        assertTrue(result.report.toString(), result.report.coherenceScore >= 70)
        assertTrue(result.report.toString(), result.report.coherenceMarkers.contains("foreign-content-after-valid-prefix"))
        assertFalse(result.report.toString(), result.report.warnings.contains("content-may-belong-to-other-book"))
    }

    @Test
    fun xuanjianTailAfterShortCorrectPrefixWithoutBodyFingerprintMismatchOnlyWarns() {
        val raw = buildString {
            appendLine("玄天之中白雪皑皑，白衣男子缓缓睁开双眼，眼中并不意外，甚至有几分了然。")
            appendLine("空衡的事情，净海既然想到了，陆江仙决不可能不知。")
            appendLine("从前往芝加哥考察到现在，李哲已经做出很多努力，换做其他人可能已经心灰意冷。")
            appendLine("“难道就没有不这样的俱乐部？”他还是不禁问道，好像还抱着一丝期望。")
            appendLine("马修是这家GMC旗下影院的经理，和大多数人不同，每到周末就是他最忙碌的时候。")
            appendLine("这还没有包括电视发行、网络播放以及飞机播映等几乎不需要多少成本的收入。")
            appendLine("柳鹰风见萧凌不肯承认错误，但也不去说他，只要他能交出御剑术，那就行了。")
            appendLine("韩冲和周钊二人都怒了，原来的计划应该是他们三国结盟，去镇压出现的起义军。")
        }

        val result = ContentCleaner().clean(
            rawContent = raw,
            chapterTitle = "第1495章 玄体",
            bookName = "玄鉴仙族",
            author = "季越人"
        )

        assertTrue(result.report.toString(), result.report.coherenceScore >= 70)
        assertTrue(result.report.toString(), result.report.coherenceMarkers.contains("foreign-content-after-valid-prefix"))
        assertFalse(result.report.toString(), result.report.warnings.contains("content-may-belong-to-other-book"))
    }

    @Test
    fun modernAdministrativeTailWithoutBodyFingerprintMismatchOnlyWarns() {
        val raw = buildString {
            appendLine("法界之中玄池清澈，左右摩诃翘首望着，江头首低眉不语。")
            appendLine("李周巍离去之后，梁川平静无声，诸释却仍不敢贸然向南。")
            appendLine("高处彩光映在莲座上，庙中的气息沉凝，仍然保持玄鉴仙族的修行语境。")
            appendLine("即便是在老大哥国内，也只有最高级的领导才能有这样的配额。")
            appendLine("14寸电视机可是最新款，没有身份和地位根本拿不到这样的配额。")
            appendLine("九人制委员会保持不变，又设立副委员长兼任政务司司长。")
            appendLine("工业局、商业局、农业局、教育局、卫生局全部划归政务司领导。")
            appendLine("一队士兵挎着枪迎面走过来，旁边的高管讨论股市跌停之后如何保存实力。")
        }

        val result = ContentCleaner().clean(
            rawContent = raw,
            chapterTitle = "第1497章 庙语",
            bookName = "玄鉴仙族",
            author = "季越人"
        )

        assertTrue(result.report.toString(), result.report.coherenceScore >= 70)
        assertTrue(
            result.report.toString(),
            result.report.coherenceMarkers.contains("strong-domain-shift-tail-after-valid-prefix")
        )
        assertTrue(result.report.toString(), result.report.coherenceMarkers.contains("foreign-content-after-valid-prefix"))
        assertFalse(result.report.toString(), result.report.warnings.contains("content-may-belong-to-other-book"))
    }

    @Test
    fun modernApartmentTailWithoutBodyFingerprintMismatchOnlyWarns() {
        val raw = buildString {
            appendLine("陆长安敏锐捕捉到关键字眼，中州智者预言，不远的将来此界将迎来一场浩劫。")
            appendLine("覆海真君道出消息来源，陆长安深思，心底隐隐有些猜测。")
            appendLine("两人仍在讨论天珩大陆、大渊旧事和中州卜卦之术，语境保持在修仙界。")
            appendLine("下一处地方却是一间公寓的房间，窗外是日常居民楼。")
            appendLine("高中同学站在门口，讨论森林里的毛毛虫和登山服上的污渍。")
            appendLine("桌上摆着橙白相间的黑盒，旁边还有印章和白色模板。")
            appendLine("这些现代生活细节连续出现，已经不再像当前章节的修仙正文。")
        }

        val result = ContentCleaner().clean(
            rawContent = raw,
            chapterTitle = "第521章 诚不我欺，翻手为云",
            bookName = "我在修仙界万古长青",
            author = "快餐店"
        )

        assertTrue(result.report.toString(), result.report.coherenceScore >= 70)
        assertTrue(
            result.report.toString(),
            result.report.coherenceMarkers.contains("strong-domain-shift-tail-after-valid-prefix")
        )
        assertTrue(result.report.toString(), result.report.coherenceMarkers.contains("foreign-content-after-valid-prefix"))
        assertFalse(result.report.toString(), result.report.warnings.contains("content-may-belong-to-other-book"))
    }

    @Test
    fun fingerprintRejectsForeignBodyAfterDroppedPrefix() {
        val fingerprint = qinSangFingerprint()
        val raw = buildString {
            appendLine("秦桑在洞府中停下脚步，听见阵法深处传来细微的震响。")
            appendLine("蓝容彩站在石阶旁辨认阵纹，提醒他灵脉尚未完全平复。秦桑点头后收起云游剑。")
            appendLine("乔峰皱眉看着远处雪线，完全不明白这座城为何会在一夜之间封门。")
            appendLine("陈墨推开书院旧门，发现案几上摆着一封没有署名的信。")
            appendLine("王婉拉住赵衡的衣袖，说码头那边已经换了旗号，今晚最好不要再出城。")
            appendLine("方晴在火车站外停住脚步，望着陌生站牌发怔，身旁的孩子却一直哭闹。")
            appendLine("韩叔把破损灯笼重新挂回门口，白芷却一直盯着山下商队不肯说话。")
            appendLine("唐少低声问今天是否还要继续，旁边的管事只让众人先去城门外等候。")
            appendLine("林闲推开旧仓库的门，看到墙角摆着一台生锈机器，电流声断断续续。")
            appendLine("沈幼清跟着管事穿过长廊，黎筱雨则把那张契约重新收进袖中。")
        }

        val result = ContentCleaner().clean(
            rawContent = raw,
            chapterTitle = "第二千二百章 浊河",
            bookName = "叩问仙道",
            author = "雨打青石",
            bookFingerprint = fingerprint
        )

        assertTrue(result.report.toString(), result.report.coherenceScore < 70)
        assertTrue(result.report.toString(), result.report.coherenceMarkers.contains("fingerprint-body-divergence"))
        assertTrue(result.report.toString(), result.report.coherenceMarkers.contains("character-fingerprint-divergence-tail"))
        assertTrue(result.report.toString(), result.report.coherenceMarkers.contains("environment-fingerprint-divergence-tail"))
        assertFalse(result.report.toString(), result.report.coherenceMarkers.contains("unpunctuated-line-break-after-valid-prefix"))
    }

    @Test
    fun formatBreakWithForeignBodyFingerprintMismatchRejects() {
        val fingerprint = qinSangFingerprint()
        val raw = buildString {
            appendLine("秦桑在洞府中停下脚步，听见阵法深处传来细微的震响。")
            appendLine("蓝容彩站在石阶旁辨认阵纹，提醒他灵脉尚未完全平复，秦桑抬手按住阵盘")
            appendLine("乔峰皱眉看着远处雪线，完全不明白这座城为何会在一夜之间封门。")
            appendLine("陈墨推开书院旧门，发现案几上摆着一封没有署名的信。")
            appendLine("王婉拉住赵衡的衣袖，说码头那边已经换了旗号，今晚最好不要再出城。")
            appendLine("方晴在火车站外停住脚步，望着陌生站牌发怔，身旁的孩子却一直哭闹。")
            appendLine("韩叔把破损灯笼重新挂回门口，白芷却一直盯着山下商队不肯说话。")
            appendLine("唐少低声问今天是否还要继续，旁边的管事只让众人先去城门外等候。")
            appendLine("林闲推开旧仓库的门，看到墙角摆着一台生锈机器，电流声断断续续。")
            appendLine("沈幼清跟着管事穿过长廊，黎筱雨则把那张契约重新收进袖中。")
        }

        val result = ContentCleaner().clean(
            rawContent = raw,
            chapterTitle = "第二千二百章 浊河",
            bookName = "叩问仙道",
            author = "雨打青石",
            bookFingerprint = fingerprint
        )

        assertTrue(result.report.toString(), result.report.coherenceScore < 70)
        assertTrue(result.report.toString(), result.report.coherenceMarkers.contains("fingerprint-body-divergence"))
        assertTrue(result.report.toString(), result.report.coherenceMarkers.contains("character-fingerprint-divergence-tail"))
        assertTrue(result.report.toString(), result.report.coherenceMarkers.contains("environment-fingerprint-divergence-tail"))
    }

    @Test
    fun fragmentedTailWithoutFingerprintOnlyWarns() {
        val raw = buildString {
            appendLine("陈迹从艉楼出来时，船工已聚在甲板上玩了骰子，但他们赌的不是钱，赌的是到了镜城港谁能下船见世面。")
            appendLine("老耳朵没有赌，独自站在船舷，胳膊撑在凭栏处默默地看着大海。")
            appendLine("陈迹走到他身边：“您喜欢看大海？”")
            appendLine("老耳朵依旧看着大海")
            appendLine("大家都不是傻子，之前还没有明白为什么炎黄族会花大力气去刺杀一些后勤官员。")
            appendLine("时有些语无伦次的少年，柳琴心不禁错愕，没想到这个木讷少年会向自己表白。")
            appendLine("韩铁方苦笑说：“我真的不知道应该怎么做，三哥，我是真的没有办法。”")
            appendLine("双方挥拳再拼，奇招迭出，招数精奇不说，招招都是五丁开山一般。")
            appendLine("在降服了夜摩合之后，江离的身躯散开，化为了无数泡影。")
            appendLine("方云抓到的，只是其中之一，比较强大的一个个体。")
            appendLine("在事实真相没弄清楚前，赵峰不能轻易离开金山宗侧灵峰。")
            appendLine("夜叉一族极其凶残，心狠手辣，夜叉神咒也非常厉害。")
            appendLine("这星辰，一面炽热的火红海洋，一面深蓝色冰寒海洋，且自然散发道的奥妙。")
            appendLine("唐少，对不起了，今天诗茵学妹似乎是有事情，我们得先走了。")
            appendLine("庞统想起了什么事情，脸涨得通红，突然连自己都有点受不了似的垂头丧气。")
            appendLine("那名备选队员举起了手中的手枪，对准自己的太阳穴按了下去。")
            appendLine("高手对决难免话语间旁敲侧击，攻人心智的口水战。")
            appendLine("唐军心说现在这样或许也很好，只是不知道这份安稳能持续到什么时候。")
        }

        val result = ContentCleaner().clean(
            rawContent = raw,
            chapterTitle = "672、取剑",
            bookName = "青山",
            author = "会说话的肘子"
        )

        assertTrue(result.report.toString(), result.report.coherenceScore >= 70)
        assertTrue(result.report.toString(), result.report.coherenceMarkers.contains("unpunctuated-line-break-after-valid-prefix"))
        assertTrue(result.report.toString(), result.report.coherenceMarkers.contains("fragmented-tail-after-unpunctuated-break"))
        assertTrue(result.report.toString(), result.report.coherenceMarkers.contains("foreign-content-after-valid-prefix"))
        assertFalse(result.report.toString(), result.report.warnings.contains("content-may-belong-to-other-book"))
    }

    @Test
    fun fingerprintDetectsQingShanPollutionAfterUnpunctuatedHardBreak() {
        val raw = buildString {
            appendLine("陈迹从艉楼出来时，船工已聚在甲板上玩了骰子，但他们赌的不是钱，赌的是到了镜城港谁能下船见世面。")
            appendLine("老耳朵没有赌，独自站在船舷，胳膊撑在凭栏处默默地看着大海。")
            appendLine("陈迹走到他身边：“您喜欢看大海？”")
            appendLine("老耳朵依旧看着大海")
            appendLine("大家都不是傻子，之前还没有明白为什么炎黄族会花大力气去刺杀一些后勤官员。")
            appendLine("时有些语无伦次的少年，柳琴心不禁错愕，没想到这个木讷少年会向自己表白。")
            appendLine("韩铁方苦笑说：“我真的不知道应该怎么做，三哥，我是真的没有办法。”")
            appendLine("在降服了夜摩合之后，江离的身躯散开，化为了无数泡影。")
            appendLine("方云抓到的，只是其中之一，比较强大的一个个体。")
            appendLine("在事实真相没弄清楚前，赵峰不能轻易离开金山宗侧灵峰。")
            appendLine("夜叉一族极其凶残，心狠手辣，夜叉神咒也非常厉害。")
            appendLine("唐少，对不起了，今天诗茵学妹似乎是有事情，我们得先走了。")
            appendLine("庞统想起了什么事情，脸涨得通红，突然连自己都有点受不了似的垂头丧气。")
            appendLine("那名备选队员举起了手中的手枪，对准自己的太阳穴按了下去。")
        }

        val fingerprint = qingShanFingerprint()
        val result = ContentCleaner().clean(
            rawContent = raw,
            chapterTitle = "672、取剑",
            bookName = "青山",
            author = "会说话的肘子",
            bookFingerprint = fingerprint
        )

        assertTrue(result.report.toString(), result.report.coherenceScore < 70)
        assertTrue(result.report.toString(), result.report.coherenceMarkers.contains("fingerprint-body-divergence"))
        assertTrue(result.report.toString(), result.report.coherenceMarkers.contains("character-fingerprint-divergence-tail"))
        assertTrue(result.report.toString(), result.report.coherenceMarkers.contains("environment-fingerprint-divergence-tail"))
    }

    @Test
    fun fingerprintOnlyModeDetectsQingShanBodyPollution() {
        val raw = buildString {
            appendLine("大家都不是傻子，之前还没有明白为什么炎黄族会花大力气去刺杀一些后勤官员。")
            appendLine("时有些语无伦次的少年，柳琴心不禁错愕，没想到这个木讷少年会向自己表白。")
            appendLine("韩铁方苦笑说：“我真的不知道应该怎么做，三哥，我是真的没有办法。”")
            appendLine("在降服了夜摩合之后，江离的身躯散开，化为了无数泡影。")
            appendLine("方云抓到的，只是其中之一，比较强大的一个个体。")
            appendLine("在事实真相没弄清楚前，赵峰不能轻易离开金山宗侧灵峰。")
            appendLine("夜叉一族极其凶残，心狠手辣，夜叉神咒也非常厉害。")
            appendLine("唐少，对不起了，今天诗茵学妹似乎是有事情，我们得先走了。")
            appendLine("庞统想起了什么事情，脸涨得通红，突然连自己都有点受不了似的垂头丧气。")
            appendLine("那名备选队员举起了手中的手枪，对准自己的太阳穴按了下去。")
        }
        val fingerprintOnlyCleaner = ContentCleaner(
            belongingChecker = DeterministicContentBelongingChecker(
                enabledDetectors = setOf(ContentBelongingDetector.FINGERPRINT)
            )
        )

        val result = fingerprintOnlyCleaner.clean(
            rawContent = raw,
            chapterTitle = "672、取剑",
            bookName = "青山",
            author = "会说话的肘子",
            bookFingerprint = qingShanFingerprint()
        )

        assertTrue(result.report.toString(), result.report.coherenceScore < 70)
        assertTrue(result.report.toString(), result.report.coherenceMarkers.contains("fingerprint-body-divergence"))
        assertTrue(result.report.toString(), result.report.coherenceMarkers.contains("character-fingerprint-divergence-tail"))
        assertTrue(result.report.toString(), result.report.coherenceMarkers.contains("environment-fingerprint-divergence-tail"))
        assertFalse(result.report.toString(), result.report.coherenceMarkers.contains("fragmented-tail-after-unpunctuated-break"))
        assertFalse(result.report.toString(), result.report.coherenceMarkers.contains("short-prefix-foreign-tail"))
    }

    @Test
    fun fingerprintRejectsAbsolutelyShortChapter() {
        val fingerprint = BookContentFingerprinter().build(
            (1..5).map { sampleIndex ->
                buildString {
                    repeat(18) {
                        appendLine(
                            "秦桑在洞府中催动阵法，蓝容彩守在石阶旁记录灵脉变化，" +
                                "云游剑与天目蝶围着阵眼巡查，宗门旧禁制第${sampleIndex}次重新闭合。"
                        )
                    }
                }
            }
        )
        val raw = buildString {
            appendLine("秦桑在洞府中催动阵法。")
            appendLine("蓝容彩守在石阶旁记录灵脉变化。")
        }
        val normalizedLength = TextFingerprintSignals.normalizeForComparison(raw).length

        assertTrue(fingerprint.usable)
        assertTrue(normalizedLength < 220)

        val result = ContentCleaner().clean(
            rawContent = raw,
            chapterTitle = "第二千二百章 浊河",
            bookName = "叩问仙道",
            author = "雨打青石",
            bookFingerprint = fingerprint
        )

        assertTrue(result.report.toString(), result.report.coherenceScore < 70)
        assertTrue(result.report.toString(), result.report.coherenceMarkers.contains("fingerprint-body-too-short"))
        assertTrue(result.report.toString(), result.report.coherenceMarkers.contains("fingerprint-body-divergence"))
    }

    @Test
    fun fingerprintDetectsForeignBodyEvenWhenFormatSignalsAreSparse() {
        val raw = buildString {
            appendLine("黑影沿着荒废楼道向前移动，墙皮在潮湿空气里整片脱落。")
            appendLine("远处的门牌已经生锈，灯管忽明忽暗，像是很久没有人维护。")
            appendLine("一辆旧车停在地下入口，车窗蒙着灰，仪表盘上跳着陌生的数字。")
            appendLine("后来传来广播声，要求所有人立刻离开大厅，不要回头。")
            appendLine("整条走廊只剩下电流声，玻璃门外的城市霓虹不断闪烁，和先前的场景没有联系。")
            appendLine("护士推开急诊室的玻璃门，警灯在窗外一闪一闪，值班医生低声确认病历。")
            appendLine("地铁广播再次响起，站台上的乘客开始后退，手机屏幕同时弹出撤离提醒。")
            appendLine("旧楼电梯停在负一层，保安拿着手电筒检查监控，走廊尽头只剩电流噪声。")
        }

        val fingerprint = qingShanFingerprint()
        val result = ContentCleaner().clean(
            rawContent = raw,
            chapterTitle = "672、取剑",
            bookName = "青山",
            author = "会说话的肘子",
            bookFingerprint = fingerprint
        )

        assertTrue(result.report.toString(), result.report.coherenceScore < 70)
        assertTrue(result.report.toString(), result.report.coherenceMarkers.contains("fingerprint-body-divergence"))
        assertTrue(result.report.toString(), result.report.coherenceMarkers.contains("character-fingerprint-divergence-tail"))
        assertTrue(result.report.toString(), result.report.coherenceMarkers.contains("environment-fingerprint-divergence-tail"))
    }

    @Test
    fun fingerprintRejectsEnvironmentOnlyCollisionAfterDroppedPrefix() {
        val raw = buildString {
            appendLine("陈迹从艉楼出来时，船工已聚在甲板上玩骰子，赌的是到了镜城港谁能下船见世面。")
            appendLine("老耳朵没有赌，独自站在船舷旁看海，头上的桃木枝拢着花白头发。")
            appendLine("陈迹走到他身边：“您喜欢看大海？”")
            appendLine("老耳朵依旧看着大海")
            appendLine("韩铁方苦笑说自己真的不知道应该怎么做，这是一道没有答案的僵局。")
            appendLine("旁人解释道，双方已经没有退路，只能继续消耗下去。")
            appendLine("方云抓到的只是其中之一，赵峰也不能轻易离开山门。")
            appendLine("唐少低声问道，今天的安排是否还要继续。")
            appendLine("庞统想起旧事，脸涨得通红，突然连自己都有点受不了。")
            appendLine("那名队员举起手枪，对准自己的太阳穴按了下去。")
            appendLine("唐军心说现在这样或许也很好，只是不知道这份安稳能持续到什么时候。")
        }

        val fingerprint = qingShanSpeechEnvironmentFingerprint()
        val result = ContentCleaner().clean(
            rawContent = raw,
            chapterTitle = "672、取剑",
            bookName = "青山",
            author = "会说话的肘子",
            bookFingerprint = fingerprint
        )

        assertTrue(result.report.toString(), result.report.coherenceScore < 70)
        assertTrue(result.report.toString(), result.report.coherenceMarkers.contains("fingerprint-body-divergence"))
        assertTrue(result.report.toString(), result.report.coherenceMarkers.contains("character-fingerprint-divergence-tail"))
        assertTrue(result.report.toString(), result.report.coherenceMarkers.contains("environment-fingerprint-divergence-tail"))
    }

    @Test
    fun domainShiftWithoutBodyFingerprintMismatchOnlyWarns() {
        val raw = buildString {
            appendLine("“大劫？”陆长安敏锐捕捉到关键字眼。")
            appendLine("中州智者预言，不远的将来，此界将迎来一场浩劫。")
            appendLine("覆海真君道出消息来源，陆长安心底隐隐有些猜测。")
            appendLine("他想到天珩大陆中央的卜卦之术，也想到大渊旧事和魔道暗流。")
            appendLine("这一段仍围绕陆长安、覆海真君和修仙界劫数展开。")
            appendLine("可她却好似没听见一般，姜悦兮坐在后面，目睹这个大型社死现场。")
            appendLine("程耀表情没有变化，学生会成员见到李松后连连道歉。")
            appendLine("森林里面蚊虫很多，他把登山服脱下，旁边又出现洛基和油纸伞。")
            appendLine("沈幼清跟着公公去见建安帝，半妖又被无良道师追杀。")
            appendLine("这些人物和生活场景连续变化，已经不是当前章节的修仙语境。")
        }

        val result = ContentCleaner().clean(
            rawContent = raw,
            chapterTitle = "第521章 诚不我欺，翻手为云",
            bookName = "我在修仙界万古长青",
            author = "快餐店"
        )

        assertTrue(result.report.toString(), result.report.coherenceScore >= 70)
        assertTrue(
            result.report.toString(),
            result.report.coherenceMarkers.contains("strong-domain-shift-tail-after-valid-prefix")
        )
        assertTrue(result.report.toString(), result.report.coherenceMarkers.contains("foreign-content-after-valid-prefix"))
        assertFalse(result.report.toString(), result.report.warnings.contains("content-may-belong-to-other-book"))
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
    fun keepsVerifiedQingShanTailChaptersAfterDroppingBodyPrefixForFingerprint() {
        val samples = qingShanVerifiedTailSamples()
        if (samples.isEmpty()) return
        val fingerprint = BookContentFingerprinter().build(samples.take(7))

        samples.forEachIndexed { index, content ->
            val result = ContentCleaner().clean(
                rawContent = content,
                chapterTitle = "青山 tail sample $index",
                bookName = "青山",
                author = "会说话的肘子",
                bookFingerprint = fingerprint
            )

            assertTrue(result.report.toString(), result.report.coherenceScore >= 70)
            assertFalse(result.report.toString(), result.report.coherenceMarkers.contains("fingerprint-body-divergence"))
            assertFalse(result.report.toString(), result.report.warnings.contains("content-may-belong-to-other-book"))
        }
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

    private fun qingShanVerifiedTailSamples(): List<String> {
        val dir = Paths.get("build", "tmp", "manual-tail-check")
        if (!Files.isDirectory(dir)) return emptyList()
        return (664..672).mapNotNull { chapter ->
            val path = dir.resolve("qingshan-xsw-$chapter.nb")
            if (Files.exists(path)) {
                Files.readString(path, StandardCharsets.UTF_8)
            } else {
                null
            }
        }
    }

    private fun nanhongFingerprint(): BookContentFingerprint {
        return BookContentFingerprinter().build(
            listOf(
                buildString {
                    appendLine("温以凡熬夜看了部恐怖电影，诡异感全靠背景音乐和尖叫声堆砌。")
                    appendLine("她强撑着眼皮看完，结束字幕出现时，温以凡甚至有了种解脱的感觉。")
                    appendLine("桑延的名字忽然从记忆里浮起来，像多年未见的人被旧事重新牵出。")
                    appendLine("钟思乔和温以凡聊起堕落街的酒吧，提到那个老板可以说是头牌。")
                },
                buildString {
                    appendLine("好些年没见，温以凡几乎要忘了桑延的存在，却也记得最后一次对话不太愉快。")
                    appendLine("桑延站在酒吧走廊里，淡声说自己是这家店的老板。")
                    appendLine("温以凡收回手里的外套，和钟思乔坐回卡座继续聊天。")
                    appendLine("离开之前，她又想起少年时桑延喊她名字的那个雨夜。")
                },
                buildString {
                    appendLine("温以凡回家之后给钟思乔发了消息，浴室里的水声让夜晚重新安静下来。")
                    appendLine("桑延是否真的认出了她，像个没有蛛丝马迹可寻的猜测。")
                    appendLine("第二天她去酒吧找手链，余卓和何明博都提到了桑延。")
                    appendLine("桑延抽走名片，垂眼看着温以凡，语气里带着熟悉的散漫。")
                },
                buildString {
                    appendLine("温以凡站在楼梯口，听着桑延慢条斯理地说话，只觉得这人还是从前那副模样。")
                    appendLine("钟思乔在微信里追问情况，温以凡没有立刻回复。")
                    appendLine("桑延和她之间隔着许多年没联系的空白，重逢后却仍旧让人难以忽视。")
                    appendLine("她回到家里开始看租房信息，想尽快从原来的住处搬走。")
                },
                buildString {
                    appendLine("温以凡去东九广场采访前，钟思乔又发来消息打听桑延的近况。")
                    appendLine("桑延靠在凉亭旁说话，语气仍旧懒散，像是完全不急着解释重逢。")
                    appendLine("温以凡收起手机，想到堕落街酒吧里的那个夜晚，又觉得有些不真实。")
                    appendLine("钱卫华和付壮在旁边调试设备，温以凡却还是被桑延两个字牵住心神。")
                }
            )
        )
    }

    private fun qinSangFingerprint(): BookContentFingerprint {
        return BookContentFingerprinter().build(
            listOf(
                buildString {
                    appendLine("秦桑在洞府中催动阵法，蓝容彩守在石阶旁，灵脉与剑气不断震荡。")
                    appendLine("秦桑收起云游剑，确认阵眼尚未完全稳定，又让蓝容彩继续辨认石壁上的阵纹。")
                    appendLine("洞府深处灵气翻涌，云游剑的剑气贴着灵脉游走，将躁动禁制一点点压回原位。")
                    appendLine("蓝容彩提醒秦桑，阵法与旧日洞府相连，若贸然破阵，很可能惊动更深处的灵机。")
                },
                buildString {
                    appendLine("秦桑站在阵法边缘，手中剑气凝而不散，蓝容彩则把玉简中的阵纹逐一比对。")
                    appendLine("洞府里的灵脉仍在震荡，石阶下方有细碎灵光浮起，像是在回应云游剑的牵引。")
                    appendLine("二人没有急着深入，只等禁制气息平复，再沿着灵脉寻找阵眼和失落的法宝。")
                    appendLine("秦桑确认方向后收束剑气，蓝容彩也把阵图重新合上，准备进入下一层洞府。")
                },
                buildString {
                    appendLine("阵法深处灵气翻涌，秦桑以云游剑护住蓝容彩，等待禁制自行平复。")
                    appendLine("蓝容彩辨认出洞府旧阵的缺口，提醒秦桑那处灵脉不能强行斩开。")
                    appendLine("秦桑点头后催动剑气绕行，沿石壁留下的阵纹慢慢探入，避免惊动沉睡的法宝。")
                    appendLine("洞府外层重新安静下来，阵法光华渐渐收敛，只余灵气在剑锋附近缓缓流转。")
                },
                buildString {
                    appendLine("秦桑让云游剑悬在阵眼之前，蓝容彩则守着洞府石阶继续查验阵纹。")
                    appendLine("灵气从灵脉深处涌来，阵法边缘的剑气随之震荡，却始终没有越过禁制。")
                    appendLine("蓝容彩提醒秦桑先稳住宗门旧阵，再寻找藏在洞府深处的法宝线索。")
                    appendLine("秦桑收回手掌，确认天目蝶传回的感应仍指向同一处阵眼。")
                },
                buildString {
                    appendLine("秦桑站在洞府门前按住阵盘，云游剑的剑气沿灵脉缓缓铺开。")
                    appendLine("蓝容彩看着石壁上的阵纹，低声说宗门留下的禁制正在重新闭合。")
                    appendLine("灵气被阵法牵引回阵眼，天目蝶也绕着洞府飞了一圈才落回肩头。")
                    appendLine("秦桑确认法宝没有异动，这才让蓝容彩继续记录灵脉变化。")
                }
            )
        )
    }

    private fun qingShanFingerprint(): BookContentFingerprint {
        return BookContentFingerprinter().build(
            listOf(
                buildString {
                    appendLine("陈迹从船舱出来时，老耳朵正靠在船舷边看着大海，海风吹动他头上的桃木枝。")
                    appendLine("陈迹低声问老耳朵为何总看着海面，船工们则在甲板上收拾绳索，准备靠岸。")
                    appendLine("老耳朵没有立刻回答，只让陈迹听船底水声，又看远处镜城港的灯火。")
                    appendLine("两人沿着船舷慢慢走，海潮拍打木板，凭栏处仍残留着清晨的水汽。")
                },
                buildString {
                    appendLine("陈迹站在甲板上望向大海，老耳朵把桃木枝重新别好，提醒他进港之后不可乱走。")
                    appendLine("船工在艉楼旁搬运木箱，绳索绷紧又松开，镜城港的影子从雾里慢慢显出来。")
                    appendLine("老耳朵看着陈迹，语气仍旧慢吞吞，却把码头上的规矩一条条说清楚。")
                    appendLine("海风穿过船舷，陈迹按住衣袖，心里还想着离岸前听见的那几句交代。")
                },
                buildString {
                    appendLine("陈迹回到船上时，老耳朵仍在船舷旁，远处海面泛着碎银一样的光。")
                    appendLine("船工们在甲板上低声说话，谁都知道靠近镜城港以后，东家会有新的安排。")
                    appendLine("老耳朵让陈迹记住大海的方向，若遇到麻烦就沿着船舷往艉楼退。")
                    appendLine("陈迹点头，听见潮水撞在木船上，像有人在黑暗里轻轻敲门。")
                },
                buildString {
                    appendLine("陈迹站在艉楼前看向镜城港，老耳朵把桃木枝压低，示意他不要惊动船工。")
                    appendLine("甲板上的绳索被海风吹得轻轻晃动，船舷外的大海已经能看见码头灯火。")
                    appendLine("老耳朵低声提醒陈迹，靠岸以后先跟着陆氏走，不要独自离开船东视线。")
                    appendLine("陈迹把这些话记下，又回头看了一眼凭栏处残留的水痕。")
                },
                buildString {
                    appendLine("陈迹沿着船舷往前走，老耳朵正和船工说镜城港的规矩。")
                    appendLine("大海上的雾气被海风吹散，甲板、艉楼和码头灯火都慢慢清晰起来。")
                    appendLine("老耳朵看向陈迹，说船东已经准备靠岸，陆氏稍后会来安排后续。")
                    appendLine("陈迹握紧衣袖，心里仍记着黄山、道庭和景朝旧事。")
                }
            )
        )
    }

    private fun qingShanSpeechEnvironmentFingerprint(): BookContentFingerprint {
        return BookContentFingerprinter().build(
            listOf(
                buildString {
                    appendLine("陈迹在甲板上问道，船东为何突然改道去镜城港。")
                    appendLine("老耳朵说道，船舷外的海风已经变冷，艉楼上的灯还没有熄。")
                    appendLine("陆氏解释道，灯火这趟出海带着一船货物，靠岸以后还有别的安排。")
                    appendLine("陈迹听完只点点头，心里仍想着藏在景朝陇右道的那柄剑。")
                },
                buildString {
                    appendLine("陈迹站在船舷旁看海，老耳朵随口道，镜城港的买卖比旅顺更稳妥。")
                    appendLine("船工从甲板搬着木箱经过，老李在艉楼前拦了一道。")
                    appendLine("陆氏给陈迹倒茶说道，这一船货物到了高丽能换不少银子。")
                    appendLine("陈迹低声问道，凭姨是不是早就知道他需要人参。")
                },
                buildString {
                    appendLine("陈迹回头看向甲板，老耳朵正剥着花生，像是什么都不知道。")
                    appendLine("老李在船东门前骂了一声，陆氏却只是摆手让船工散开。")
                    appendLine("海风掠过船舷，陈迹想起京城旧事，也想起白龙和灯火的交易。")
                    appendLine("陆氏解释道，去高丽只是权宜之计，靠岸之后仍可转去景朝。")
                },
                buildString {
                    appendLine("陈迹在艉楼旁低声问道，老耳朵为何一直盯着镜城港的灯火。")
                    appendLine("老耳朵说道，甲板上的船工越安静，靠岸之后的规矩就越多。")
                    appendLine("陆氏看向船舷外的大海，提醒陈迹别忘了黄山和道庭留下的旧账。")
                    appendLine("陈迹听见船东催促收绳，便把白龙、灯火和景朝的事暂时压下。")
                },
                buildString {
                    appendLine("陈迹站在甲板上说道，镜城港已经近在眼前，船工却没人敢大声说话。")
                    appendLine("老耳朵让陈迹靠近船舷，低声解释陆氏为何要先去见船东。")
                    appendLine("海风吹过艉楼，陈迹想起凭姨、白龙和灯火，也想起景朝陇右道的剑。")
                    appendLine("陆氏说道靠岸之后不能拖延，道庭那边未必会给他们太多时间。")
                }
            )
        )
    }
}
