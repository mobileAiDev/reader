package com.ldp.reader.ui.activity

import com.ldp.reader.model.bean.BookChapterBean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderCatalogRefreshPromotionPolicyTest {
    @Test
    fun retainsCurrentCatalogWhenMainRefreshHasAnUnsafeSameSizeRewrite() {
        val current = catalog(
            "第五百九十章 前一章",
            "第五百九十一章 融合...",
            "第五百九十二章 后一章"
        )
        val refreshed = catalog(
            "第五百九十章 前一章",
            "第五百九十一章 融合...",
            "第五百九十二章 完全无关的改名"
        )

        assertTrue(
            ReaderCatalogRefreshPromotionPolicy.shouldRetainCurrentCatalog(current, refreshed)
        )
    }

    @Test
    fun acceptsAValidFirstCatalogButRetainsEmptyBaselineForInvalidRoutes() {
        val valid = catalog("第一章", "第二章")
        val blankRoute = catalog("第一章", "第二章").also { chapters ->
            chapters[1].link = ""
        }
        val duplicateRoute = catalog("第一章", "第二章").also { chapters ->
            chapters[1].link = chapters[0].link
        }

        assertFalse(
            ReaderCatalogRefreshPromotionPolicy.shouldRetainCurrentCatalog(emptyList(), valid)
        )
        assertTrue(
            ReaderCatalogRefreshPromotionPolicy.shouldRetainCurrentCatalog(emptyList(), blankRoute)
        )
        assertTrue(
            ReaderCatalogRefreshPromotionPolicy.shouldRetainCurrentCatalog(emptyList(), duplicateRoute)
        )
    }

    @Test
    fun acceptsAValidCatalogAsRepairForAnInvalidLegacyBaseline() {
        val legacy = catalog("第一章 旧", "第二章 旧").also { chapters ->
            chapters[1].link = chapters[0].link
        }
        val repaired = catalog("第一章 正文", "第二章 正文")

        assertFalse(
            ReaderCatalogRefreshPromotionPolicy.shouldRetainCurrentCatalog(legacy, repaired)
        )
    }

    @Test
    fun promotesSameSizeCatalogWhenAMiddleTitleSafelyCompletesOnTheSameRoute() {
        val current = catalog(
            "第五百九十章 前一章",
            "第五百九十一章 融合...",
            "第五百九十二章 后一章"
        )
        val refreshed = catalog(
            "第五百九十章 前一章",
            "第五百九十一章 融合成功！",
            "第五百九十二章 后一章"
        )

        assertTrue(ReaderCatalogRefreshPromotionPolicy.shouldPromote(current, refreshed))
    }

    @Test
    fun doesNotPromoteAMiddleTitleChangeWhenTheRouteChanged() {
        val current = catalog(
            "第五百九十章 前一章",
            "第五百九十一章 融合...",
            "第五百九十二章 后一章"
        )
        val refreshed = catalog(
            "第五百九十章 前一章",
            "第五百九十一章 融合成功！",
            "第五百九十二章 后一章"
        ).also { chapters ->
            chapters[1].link = "source-engine://different-source/chapter-591"
        }

        assertFalse(ReaderCatalogRefreshPromotionPolicy.shouldPromote(current, refreshed))
    }

    @Test
    fun doesNotPromoteAnUnrelatedMiddleTitleRewrite() {
        val current = catalog(
            "第五百九十章 前一章",
            "第五百九十一章 融合...",
            "第五百九十二章 后一章"
        )
        val refreshed = catalog(
            "第五百九十章 前一章",
            "第五百九十一章 灵魂融合成功！",
            "第五百九十二章 后一章"
        )

        assertFalse(ReaderCatalogRefreshPromotionPolicy.shouldPromote(current, refreshed))
    }

    @Test
    fun doesNotPromoteAnUnrelatedLastTitleRewriteAtTheSameSize() {
        val current = catalog(
            "第五百九十章 前一章",
            "第五百九十一章 融合...",
            "第五百九十二章 后一章"
        )
        val refreshed = catalog(
            "第五百九十章 前一章",
            "第五百九十一章 融合...",
            "第五百九十二章 完全无关的改名"
        )

        assertFalse(ReaderCatalogRefreshPromotionPolicy.shouldPromote(current, refreshed))
    }

    @Test
    fun doesNotLetOneSafeCompletionCarryAnUnrelatedRewrite() {
        val current = catalog(
            "第五百九十章 前一章",
            "第五百九十一章 融合...",
            "第五百九十二章 后一章",
            "第五百九十三章 末章"
        )
        val refreshed = catalog(
            "第五百九十章 前一章",
            "第五百九十一章 融合成功！",
            "第五百九十二章 完全无关的改名",
            "第五百九十三章 末章"
        )

        assertFalse(ReaderCatalogRefreshPromotionPolicy.shouldPromote(current, refreshed))
    }

    @Test
    fun doesNotLetAnAppendedChapterCarryAnUnrelatedRewrite() {
        val current = catalog(
            "第五百九十章 前一章",
            "第五百九十一章 融合...",
            "第五百九十二章 后一章"
        )
        val refreshed = catalog(
            "第五百九十章 前一章",
            "第五百九十一章 完全无关的改名",
            "第五百九十二章 后一章",
            "第五百九十三章 新章"
        )

        assertFalse(ReaderCatalogRefreshPromotionPolicy.shouldPromote(current, refreshed))
    }

    @Test
    fun promotesAnAppendedChapterWhenTheExistingRoutesAndTitlesAreUnchanged() {
        val current = catalog(
            "第五百九十章 前一章",
            "第五百九十一章 融合...",
            "第五百九十二章 后一章"
        )
        val refreshed = catalog(
            "第五百九十章 前一章",
            "第五百九十一章 融合...",
            "第五百九十二章 后一章",
            "第五百九十三章 新章"
        )

        assertTrue(ReaderCatalogRefreshPromotionPolicy.shouldPromote(current, refreshed))
    }

    @Test
    fun doesNotPromoteACompletionUnderADifferentOrdinal() {
        val current = catalog(
            "第五百九十章 前一章",
            "第五百九十一章 融合...",
            "第五百九十二章 后一章"
        )
        val refreshed = catalog(
            "第五百九十章 前一章",
            "第五百九十二章 融合成功！",
            "第五百九十二章 后一章"
        )

        assertFalse(ReaderCatalogRefreshPromotionPolicy.shouldPromote(current, refreshed))
    }

    @Test
    fun doesNotPromoteAShorterCatalog() {
        val current = catalog(
            "第五百九十章 前一章",
            "第五百九十一章 融合...",
            "第五百九十二章 后一章"
        )
        val refreshed = current.dropLast(1)

        assertFalse(ReaderCatalogRefreshPromotionPolicy.shouldPromote(current, refreshed))
    }

    private fun catalog(vararg titles: String): List<BookChapterBean> {
        return titles.mapIndexed { index, title ->
            BookChapterBean().apply {
                link = "source-engine://chapter-${index + 590}"
                this.title = title
            }
        }
    }
}
