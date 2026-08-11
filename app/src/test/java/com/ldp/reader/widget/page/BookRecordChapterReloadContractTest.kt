package com.ldp.reader.widget.page

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BookRecordChapterReloadContractTest {
    @Test
    fun `anchor relocation invalidates opened pages before reloading`() {
        val source = File("src/main/java/com/ldp/reader/widget/page/PageLoader.kt").readText()
        val start = source.indexOf("protected fun restoreCurrentChapterFromAnchor()")
        val end = source.indexOf("protected fun clampCurrentChapterToAvailableCatalog()", start)
        val relocation = source.substring(start, end)

        assertTrue(relocation.contains("invalidateCurrentChapterLoad()"))
        assertTrue(relocation.contains("mCurPageList = null"))
        assertTrue(relocation.contains("mCurPage = null"))
        assertTrue(relocation.contains("isChapterOpen = false"))
        assertTrue(relocation.contains("mStatus = STATUS_LOADING"))
    }

    @Test
    fun `network hot refresh immediately opens the relocated chapter`() {
        val source = File("src/main/java/com/ldp/reader/widget/page/NetPageLoader.kt").readText()

        assertTrue(source.contains("if (!isChapterOpen() || clampedChapter || restoredByAnchor)"))
        assertTrue(source.indexOf("restoreCurrentChapterFromAnchor()") < source.indexOf("openChapter()"))
    }

    @Test
    fun `local hot refresh restores anchor before its unconditional open`() {
        val source = File("src/main/java/com/ldp/reader/widget/page/LocalPageLoader.kt").readText()
        val restore = source.indexOf("restoreCurrentChapterFromAnchor()")
        val open = source.indexOf("openChapter()", restore)

        assertTrue(restore >= 0)
        assertTrue(open > restore)
    }
}
