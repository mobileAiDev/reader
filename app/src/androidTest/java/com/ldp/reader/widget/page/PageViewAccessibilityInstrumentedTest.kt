package com.ldp.reader.widget.page

import android.content.Context
import android.graphics.Rect
import android.view.View
import androidx.core.view.ViewCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PageViewAccessibilityInstrumentedTest {

    @Test
    fun accessibilityProviderPublishesSeparateHeadingAndParagraphNodes() {
        val pageView = PageView(ApplicationProvider.getApplicationContext<Context>())
        pageView.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
        )
        pageView.layout(0, 0, 1080, 1920)
        pageView.publishAccessibilitySnapshot(
            ReaderPageSemanticSnapshot(
                listOf(
                    ReaderPageSemanticNode(
                        virtualId = 1,
                        role = ReaderPageSemanticRole.HEADING,
                        text = "第五十一章 边界验证",
                        visualLineCount = 2
                    ),
                    ReaderPageSemanticNode(
                        virtualId = 2,
                        role = ReaderPageSemanticRole.PARAGRAPH,
                        text = "第一段正文",
                        visualLineCount = 4
                    )
                )
            )
        )

        val provider = ViewCompat.getAccessibilityNodeProvider(pageView)
        assertNotNull(provider)
        val host = requireNotNull(provider?.createAccessibilityNodeInfo(View.NO_ID))
        val heading = requireNotNull(provider?.createAccessibilityNodeInfo(1))
        val paragraph = requireNotNull(provider.createAccessibilityNodeInfo(2))
        val headingBounds = Rect()
        val paragraphBounds = Rect()
        heading.getBoundsInParent(headingBounds)
        paragraph.getBoundsInParent(paragraphBounds)

        assertEquals(2, host.childCount)
        assertFalse(host.isClickable)
        assertFalse(host.isFocusable)
        assertEquals("第五十一章 边界验证", heading.text.toString())
        assertTrue(heading.isHeading)
        assertEquals("第一段正文", paragraph.text.toString())
        assertTrue(headingBounds.bottom <= paragraphBounds.top)
    }
}
