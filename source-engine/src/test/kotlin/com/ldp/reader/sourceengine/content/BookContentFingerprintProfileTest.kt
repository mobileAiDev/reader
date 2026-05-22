package com.ldp.reader.sourceengine.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookContentFingerprintProfileTest {
    @Test
    fun profileRecomputesFingerprintWhenTrustedChaptersArrive() {
        val profile = BookContentFingerprintProfile(maxTrustedContents = 8)
        profile.addTrustedContents(
            listOf(
                qinSangChapter("秦桑按住阵盘，蓝容彩在洞府石阶旁辨认阵法。"),
                qinSangChapter("秦桑让云游剑悬在阵眼上方，蓝容彩继续记录灵脉变化。")
            )
        )

        val initial = profile.snapshot

        assertFalse(initial.usable)
        assertEquals(2, initial.trustedChapterCount)

        profile.addTrustedContent(qinSangChapter("青衫道人来到洞府外，扶着阵旗查看灵脉余波。"))
        profile.addTrustedContent(qinSangChapter("青衫道人又回到石阶前，和秦桑一起稳住阵旗。"))
        profile.addTrustedContent(qinSangChapter("秦桑带着蓝容彩退回洞府门前，继续检查阵盘与灵脉。"))
        val updated = profile.snapshot

        assertEquals(5, updated.trustedChapterCount)
        assertTrue(updated.usable)
        assertTrue(updated.leadCharacterTerms.contains("秦桑"))
        assertTrue(updated.characterScore(TextFingerprintSignals.index("秦桑正在洞府中查看阵法。")) > 0)
    }

    @Test
    fun profileIgnoresDuplicateSamplesAndKeepsBoundedHistory() {
        val profile = BookContentFingerprintProfile(maxTrustedContents = 3)
        val first = qinSangChapter("秦桑和蓝容彩在洞府里修补阵法。")

        profile.addTrustedContent(first)
        profile.addTrustedContent(first)

        assertEquals(1, profile.snapshot.trustedChapterCount)

        profile.addTrustedContent(qinSangChapter("秦桑催动云游剑，灵脉重新汇入阵眼。"))
        profile.addTrustedContent(qinSangChapter("蓝容彩守着石阶，洞府外的灵气渐渐平复。"))
        profile.addTrustedContent(qinSangChapter("青衫道人立在山门前，宗门弟子搬来符阵。"))

        assertEquals(3, profile.snapshot.trustedChapterCount)
    }

    @Test
    fun personSignalsKeepThreeCharacterNamesWithMiddleFunctionCharacter() {
        val names = TextFingerprintSignals.personLikeNames("温以凡看向桑延，桑延把手链递给温以凡。")

        assertTrue(names.contains("温以凡"))
        assertTrue(names.contains("桑延"))
    }

    @Test
    fun repeatedMultiChapterTermsOutweighOccasionalTerms() {
        val fingerprint = BookContentFingerprinter().build(
            listOf(
                qingShanChapter("陈迹说道今日海风不对，陈迹看向老耳朵，陈迹又回头检查甲板。"),
                qingShanChapter("陈迹站在船舷旁，陈迹低声问老耳朵，镜城港为何还没靠岸。"),
                qingShanChapter("陈迹把桃木枝递给老耳朵，陈迹说道甲板上的船工都醒了。"),
                qingShanChapter("陈迹看向大海，陈迹让船工收起绳索，准备靠近镜城港。"),
                qingShanChapter("陈迹说道船舷外有灯火，陈迹把手按在凭栏上等船靠岸。")
            )
        )

        val chenJiWeight = fingerprint.characterWeights.getValue("陈迹")
        val laoErDuoWeight = fingerprint.characterWeights.getValue("老耳朵")

        assertTrue(fingerprint.usable)
        assertTrue(fingerprint.leadCharacterTerms.contains("陈迹"))
        assertTrue(chenJiWeight > laoErDuoWeight)
    }

    @Test
    fun singleChapterNoiseDoesNotBecomeFingerprintTerm() {
        val fingerprint = BookContentFingerprinter().build(
            listOf(
                qingShanChapter("陈迹说道甲板湿滑，陈迹一时间停住脚步，唐少也只在这里出现一次。"),
                qingShanChapter("陈迹看向老耳朵，陈迹说道镜城港的灯火已经近了。"),
                qingShanChapter("陈迹站在船舷旁，陈迹让船工把绳索重新收好。"),
                qingShanChapter("陈迹回到艉楼前，陈迹听见大海深处传来潮声。"),
                qingShanChapter("陈迹按住凭栏，陈迹说道靠岸以后还要去见陆氏。")
            )
        )

        assertTrue(fingerprint.usable)
        assertTrue(fingerprint.characterTerms.contains("陈迹"))
        assertFalse(fingerprint.characterTerms.contains("陈迹一"))
        assertFalse(fingerprint.characterTerms.contains("唐少"))
    }

    @Test
    fun environmentTermsAreClassifiedAfterCommonExtraction() {
        val fingerprint = BookContentFingerprinter().build(
            listOf(
                qingShanChapter("陈迹说道镜城港外海风渐冷，景朝旧事仍在，甲板和船舷都结着水汽。"),
                qingShanChapter("陈迹看向镜城港，景朝旧事未了，甲板上的船工沿着船舷收紧缆绳。"),
                qingShanChapter("陈迹站在甲板上听海风，景朝边境遥远，船舷外的镜城港灯火越来越近。"),
                qingShanChapter("陈迹说道白龙与景朝旧事仍在，镜城港的甲板却已经忙乱。"),
                qingShanChapter("陈迹回到船舷旁，景朝传闻仍绕不开黄山，甲板上的海风把镜城港雾气吹散。")
            )
        )

        val categories = fingerprint.environmentProfiles.associate { term -> term.term to term.category }

        assertTrue(fingerprint.usable)
        assertEquals(BookContentFingerprintTermCategory.LOCATION, categories["镜城港"])
        assertEquals(BookContentFingerprintTermCategory.VEHICLE, categories["甲板"])
        assertEquals(BookContentFingerprintTermCategory.WORLDVIEW, categories["景朝"])
    }

    @Test
    fun repeatedGenericWordsDoNotEnterFingerprintEvenAcrossChapters() {
        val fingerprint = BookContentFingerprinter().build(
            listOf(
                qingShanChapter("陈迹说道这个那个只是闲话，陈迹仍看向镜城港。"),
                qingShanChapter("陈迹说道这个那个只是旧闻，陈迹继续守在甲板。"),
                qingShanChapter("陈迹说道这个那个只是传言，陈迹让船工收绳。"),
                qingShanChapter("陈迹说道这个那个只是误会，陈迹回到船舷。"),
                qingShanChapter("陈迹说道这个那个只是插曲，陈迹等着船靠岸。")
            )
        )

        assertTrue(fingerprint.usable)
        assertFalse(fingerprint.characterTerms.contains("这个"))
        assertFalse(fingerprint.characterTerms.contains("那个"))
        assertFalse(fingerprint.environmentTerms.contains("这个"))
        assertFalse(fingerprint.environmentTerms.contains("那个"))
    }

    private fun qinSangChapter(seed: String): String {
        return buildString {
            repeat(8) {
                append(seed)
                append("秦桑、蓝容彩、云游剑、天目蝶、洞府、阵法、灵脉、宗门、山门、符阵都保持同一修仙语境。")
            }
        }
    }

    private fun qingShanChapter(seed: String): String {
        return buildString {
            repeat(8) {
                append(seed)
                append("陈迹、老耳朵、镜城港、甲板、船舷、大海、海风、船工、艉楼、靠岸都保持同一航海语境。")
            }
        }
    }
}
