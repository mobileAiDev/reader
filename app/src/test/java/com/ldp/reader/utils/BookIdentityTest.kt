package com.ldp.reader.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookIdentityTest {
    @Test
    fun titleKeyIgnoresBookTitleMarksAndCommonNoise() {
        assertEquals("诡秘之主", BookIdentity.canonicalTitleKey("《诡秘之主》", "爱潜水的乌贼"))
        assertEquals("诡秘之主", BookIdentity.canonicalTitleKey("诡秘之主 最新章节", "爱潜水的乌贼"))
        assertEquals("斗破苍穹", BookIdentity.canonicalTitleKey("斗破苍穹-全文阅读", "天蚕土豆"))
    }

    @Test
    fun sourceEngineShelfIdIsStableAcrossTitleMarksAndAuthorSuffix() {
        val plain = BookIdentity.sourceEngineShelfId("诡秘之主", "爱潜水的乌贼")
        val marked = BookIdentity.sourceEngineShelfId("《诡秘之主》", "爱潜水的乌贼")
        val withAuthor = BookIdentity.sourceEngineShelfId("诡秘之主 爱潜水的乌贼", "爱潜水的乌贼")

        assertEquals(plain, marked)
        assertEquals(plain, withAuthor)
        assertTrue(BookIdentity.isSourceEngineShelfId(plain))
    }

    @Test
    fun differentCanonicalBooksKeepDifferentShelfIds() {
        assertNotEquals(
            BookIdentity.sourceEngineShelfId("斗破苍穹", "天蚕土豆"),
            BookIdentity.sourceEngineShelfId("诡秘之主", "爱潜水的乌贼")
        )
    }

    @Test
    fun sameTitleDifferentAuthorsKeepDifferentSourceEngineShelfIds() {
        assertNotEquals(
            BookIdentity.sourceEngineShelfId("难哄", "竹已"),
            BookIdentity.sourceEngineShelfId("难哄", "糖不甜")
        )
    }
}
