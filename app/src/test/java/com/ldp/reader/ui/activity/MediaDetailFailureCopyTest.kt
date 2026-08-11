package com.ldp.reader.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaDetailFailureCopyTest {
    @Test
    fun detailFailuresDescribeTheWorkThatActuallyFailed() {
        assertEquals(
            "暂时无法加载作品信息，点击重试",
            MediaDetailFailureCopy.messageFor(MediaDetailFailureStage.DETAIL)
        )
    }

    @Test
    fun catalogFailuresKeepTheExistingCatalogSpecificAction() {
        assertEquals(
            "暂时无法加载目录，点击重试",
            MediaDetailFailureCopy.messageFor(MediaDetailFailureStage.CATALOG)
        )
    }
}
