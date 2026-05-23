package com.ldp.reader.algorithmtest.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterQualityGateTest {
    private val gate = ChapterQualityGate()

    @Test
    fun trimsRealXiaoxiangPageChromeWithoutDroppingStoryBody() {
        val result = gate.inspect(
            ChapterInput(
                index = 2923,
                title = "第2868章_第二计划启动",
                content = realXiaoxiangPageChromeSnippet()
            )
        )

        assertTrue(
            result.toString(),
            result.type == ChapterQualityType.CLEAN_WITH_TRIM
        )
        assertTrue(result.cleanText.contains("贺灵川心知肚明"))
        assertTrue(result.cleanText.contains("千幻真人的幻术"))
        assertFalse(result.cleanText.contains("xhr.send"))
        assertFalse(result.cleanText.contains("JSON.stringify"))
        assertFalse(result.cleanText.contains("潇湘书院"))
        assertFalse(result.cleanText.contains("全文免费阅读"))
        assertFalse(result.cleanText.contains("订阅本章"))
    }

    @Test
    fun classifiesRealEndingPostscriptAsNonStory() {
        val result = gate.inspect(
            ChapterInput(
                index = 1218,
                title = "完结感言",
                content = realEndingPostscriptSnippet()
            )
        )

        assertEquals(ChapterQualityType.NON_STORY, result.type)
        assertFalse(result.usableForStory)
        assertTrue(result.cleanText.contains("完结感言"))
        assertTrue(result.reasons.any { reason -> reason.contains("non-story") })
    }

    @Test
    fun classifiesAuthorLetterToReadersAsNonStory() {
        val result = gate.inspect(
            ChapterInput(
                index = 1221,
                title = "【新年写给书友的一封信】 仙人茶话会11",
                content = """
                    感谢各位书友这一年以来的陪伴。
                    这本书写到这里，很多设定和人物都有不少想和大家聊的地方。
                    新的一年祝大家身体健康，万事顺遂。
                    后面剧情我会继续认真打磨，也希望大家继续支持正版阅读。
                """.trimIndent()
            )
        )

        assertEquals(ChapterQualityType.NON_STORY, result.type)
        assertFalse(result.usableForStory)
    }

    @Test
    fun removesInlineSiteAdWithoutCuttingFollowingStory() {
        val result = gate.inspect(
            ChapterInput(
                index = 88,
                title = "第八十八章 归山",
                content = buildString {
                    repeat(4) {
                        appendLine("陆长安回到巫祁山，玄水龟伏在潭边，夏文月正在整理阵旗。")
                    }
                    appendLine("最新网址：www.example.com 请收藏本站，方便下次阅读。")
                    repeat(4) {
                        appendLine("陆长安没有停步，继续与关巧芝商议青冥秘境的后续安排。")
                    }
                }
            )
        )

        assertTrue(
            result.toString(),
            result.type == ChapterQualityType.CLEAN_WITH_TRIM
        )
        assertTrue(result.cleanText.contains("陆长安回到巫祁山"))
        assertTrue(result.cleanText.contains("继续与关巧芝商议"))
        assertFalse(result.cleanText.contains("www.example.com"))
        assertFalse(result.cleanText.contains("请收藏本站"))
    }

    @Test
    fun analyzerDoesNotLetBadExtractionEnterBookMemory() {
        val normal = (0 until 6).map { index ->
            ChapterInput(
                index = index,
                title = "第${index + 1}章",
                content = buildString {
                    repeat(25) {
                        append("贺灵川进入盘龙城，白笠客守在蛇口峰，千幻真人留下幻术线索。")
                        append("贺灵川与朱大娘继续追查第二计划。")
                    }
                }
            )
        }
        val bad = ChapterInput(
            index = 6,
            title = "第七章 页面壳",
            content = pureBadExtractionSnippet()
        )

        val report = NovelPollutionAnalyzer().analyze("仙人消失之后", "风行水云间", normal + bad)
        val quality = report.qualityResults.first { result -> result.chapterIndex == 6 }
        val featureTexts = report.fingerprint.coreFeatures.map { feature -> feature.text }.toSet()

        assertEquals(ChapterQualityType.BAD_EXTRACTION, quality.type)
        assertFalse(report.chunkScores.any { score -> score.chunk.chapterIndex == 6 })
        assertFalse(featureTexts.contains("潇湘书院"))
        assertFalse(featureTexts.contains("全文免费"))
    }

    @Test
    fun analyzerAbortsPollutionJudgmentWhenCleanStoryMemoryIsInsufficient() {
        val chapters = listOf(
            ChapterInput(0, "第1章 正文", repeatedStory("苏午进入灶神庙，云霓在火塘旁等待。")),
            ChapterInput(1, "第2章 正文", repeatedStory("苏午继续推演过去人生，李岳山提起阴喜脉。")),
        ) + (2 until 10).map { index ->
            ChapterInput(index, "第${index + 1}章 页面壳", pureBadExtractionSnippet())
        }

        val report = NovelPollutionAnalyzer().analyze("旧域怪诞", "狐尾的笔", chapters)

        assertEquals(0, report.chunkCount)
        assertTrue(report.suggestions.isEmpty())
        assertTrue(report.logs.any { line -> line.contains("quality.abort") })
        assertTrue(report.qualityResults.count { result -> result.type == ChapterQualityType.BAD_EXTRACTION } >= 8)
    }

    private fun realXiaoxiangPageChromeSnippet(): String {
        return """
            (function() {
            function getQueryVariable(variable) {
            var query = window.location.search.substring(1);
            var vars = query.split("&");
            for (var i=0;i -1 && (getQueryVariable('s_wd') || getQueryVariable('tag'))) {
            redirect = '/search/' + window.location.search
            }
            if (window.outerWidth)
            第2868章第二计划启动_仙人消失之后全文免费阅读 – 潇湘书院
            yep('set', {
            appid: 10111,
            })
            登录
            首页
            仙人消失之后
            第2868章第二计划启动
            但贺灵川心知肚明：
            高怀远把替身放在主帐里，自己换了脸，蹲在蛇口峰的帐篷里发号施令！
            而能够瞒过白笠客的透视眼，还能蒙蔽盘龙城的元力探视，他能想到的，只有千幻真人的幻术！
            在现实里，贺灵川就常用千幻真人的幻术，因而对它的妙用了然于心。
            订阅本章：10币
            推荐
            他的倾城意大明英华君九龄我在诡异世界继承神位后楚后暴君如此多娇九重紫谜案追凶大帝姬穿成继母后，我改造全家种田忙为什么它永无止境
            举报指南
            目录
            书详情
            夜间
            设置
            手机
            //百度统计
            var _hmt = _hmt || [];
            (function () {
            var hm = document.createElement("script");
            hm.async = 1;
            xhr.send(JSON.stringify(pramas))
            })();
        """.trimIndent()
    }

    private fun realEndingPostscriptSnippet(): String {
        return """
            码字码到没什么灵感，想了想，就给完本的太一道果写一下完结感言。
            从23年五月到今年四月，近两年的时间，太一道果总算是完本了。
            从一开始的有精品之姿到中期一路下滑，再到后期老夫更新疲软，能够完结也是挺不容易的，现在写完了，心情相当复杂。
            感谢一路过来陪伴的书友，谢谢你们的支持。
            也感谢前编辑透明和现任编辑水墨。
            还有，感谢群友们的惊世智慧，伴我度过了熬夜码字的每一个晚上。
            谢谢大家！
            最后，就是推一推新书《人在高武，言出法随》了。
            总之就是求收藏，求追读。
            《太一道果》完结感言
            正在手打中，请稍等片刻，内容更新后，请重新刷新页面，即可获取最新更新！
        """.trimIndent()
    }

    private fun pureBadExtractionSnippet(): String {
        return """
            第2868章第二计划启动_仙人消失之后全文免费阅读 – 潇湘书院
            <script>
            function ajaxLog(pramas){
            var xhr = new XMLHttpRequest();
            xhr.open("POST", '/api/user/guest/log', true);
            xhr.setRequestHeader('Content-type', 'application/json');
            xhr.send(JSON.stringify(pramas))
            }
            document.querySelector('#reader').appendChild(script);
            window.localStorage.setItem('source', 'pc_jump');
            </script>
            登录
            首页
            上一章
            下一章
            返回目录
            相关推荐
            手机阅读
        """.trimIndent()
    }

    private fun repeatedStory(sentence: String): String {
        return buildString {
            repeat(30) {
                append(sentence)
                append("众人低声商议，随后继续沿着旧域边缘前行。")
            }
        }
    }
}
