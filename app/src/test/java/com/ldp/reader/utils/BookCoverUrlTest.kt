package com.ldp.reader.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookCoverUrlTest {
    @Test
    fun rejectsKnownSiteLogoCoversThatLookLikeDownloadableImages() {
        assertFalse(
            BookCoverUrl.isLikelyImage(
                "https://www.xbiquge.la/files/article/image/7/7877/7877s.jpg"
            )
        )
        assertFalse(
            BookCoverUrl.isLikelyImage(
                "https://wap2.xinbiquge.org/images/ttkg.jpg"
            )
        )
    }

    @Test
    fun keepsNormalBookCoverUrlsUsable() {
        assertTrue(BookCoverUrl.isLikelyImage("https://example.com/bookimages/doupo-cover.jpg"))
    }

    @Test
    fun keepsBaiduGimgRedirectCoversLikelyWhenProbeAlreadyVerifiedThem() {
        assertTrue(
            BookCoverUrl.isLikelyImage(
                "https://gimg0.baidu.com/gimg/src=http%3A%2F%2Fexample.com%2Fcover&id=abc"
            )
        )
    }
}
