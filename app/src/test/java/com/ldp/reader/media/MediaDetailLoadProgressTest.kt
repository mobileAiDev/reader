package com.ldp.reader.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MediaDetailLoadProgressTest {
    @Test
    fun loadingStepsUsePlainUserFacingCopy() {
        assertEquals("正在加载作品信息", MediaDetailLoadProgress.Step.STARTED.label)
        assertEquals("正在加载作品信息", MediaDetailLoadProgress.Step.DETAIL.label)
        assertEquals("正在加载目录", MediaDetailLoadProgress.Step.CATALOG.label)
        assertEquals("加载完成", MediaDetailLoadProgress.Step.READY.label)
        assertEquals("暂时无法加载目录，点击重试", MediaDetailLoadProgress.Step.FAILED.label)

        assertFalse(
            MediaDetailLoadProgress.Step.values().any { step ->
                step.label.contains("引擎") ||
                    step.label.contains("分析") ||
                    step.label.contains("解析")
            }
        )
    }
}
