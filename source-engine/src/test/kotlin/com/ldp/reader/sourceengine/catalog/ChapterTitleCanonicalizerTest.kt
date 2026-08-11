package com.ldp.reader.sourceengine.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

class ChapterTitleCanonicalizerTest {
    @Test
    fun removesKnownUpdateDecorationFromChapterTitle() {
        assertEquals(
            "第五百九十一章 融合成功！",
            ChapterTitleCanonicalizer.canonicalizeKnownUpdateDecoration(
                "第五百九十一章 融合成功！【第三更！】"
            )
        )
    }

    @Test
    fun removesOnlyWhitelistedUpdateMarkersAcrossMatchedBracketStyles() {
        val cases = mapOf(
            "第五百九十一章 融合成功！〖第三更！〗" to "第五百九十一章 融合成功！",
            "第五百九十一章 融合成功！[第三更!]" to "第五百九十一章 融合成功！",
            "第五百九十一章 融合成功！（加更）" to "第五百九十一章 融合成功！",
            "第五百九十一章 融合成功！(补更)" to "第五百九十一章 融合成功！",
            "第五百九十一章 融合成功！【已修订】" to "第五百九十一章 融合成功！",
            "第五百九十一章 融合成功！【校订】" to "第五百九十一章 融合成功！",
            "第五百九十一章 融合成功！【求月票！】" to "第五百九十一章 融合成功！"
        )

        cases.forEach { (raw, expected) ->
            assertEquals(raw, expected, ChapterTitleCanonicalizer.canonicalizeKnownUpdateDecoration(raw))
        }
    }

    @Test
    fun preservesStoryTitlesAndUnknownTrailingNotesExactly() {
        val preserved = listOf(
            "番外 萧炎与薰儿",
            "第五百九十一章 融合成功！【番外】",
            "第三卷 中州风云",
            "第五百九十一章 融合成功！（薰儿视角）",
            "第五百九十一章 融合成功！【作者有话说】",
            "第五百九十一章 融合成功！【今日更新】",
            "第五百九十一章 融合成功！【第三更！〗"
        )

        preserved.forEach { title ->
            assertEquals(title, ChapterTitleCanonicalizer.canonicalizeKnownUpdateDecoration(title))
        }
    }
}
