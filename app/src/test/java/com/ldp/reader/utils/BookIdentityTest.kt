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
    fun sourceEngineIdentityKeyIgnoresSymbolsAndWhitespace() {
        assertEquals(
            BookIdentity.sourceEngineIdentityKey("灵源仙路", "春雾煮茶"),
            BookIdentity.sourceEngineIdentityKey("《灵 源·仙 路》", "春 雾-煮 茶")
        )
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

    @Test
    fun anonymousAuthorIsCompatibleButNotUsedForDisplay() {
        listOf("佚名", "未知", "无名", "匿名", "不详", "佚名作者").forEach { anonymous ->
            assertTrue(BookIdentity.isAnonymousAuthor(anonymous))
            assertTrue(BookIdentity.authorsCompatible("忘语", anonymous))
            assertTrue(BookIdentity.authorsCompatible(anonymous, "忘语"))
        }
        assertEquals("忘语", BookIdentity.preferredDisplayAuthor("佚名", "忘语"))
    }

    @Test
    fun containedAuthorsAreCompatibleAndLongerAuthorWinsDisplay() {
        assertTrue(BookIdentity.authorsCompatible("忘语", "忘语著"))
        assertEquals("忘语著", BookIdentity.preferredDisplayAuthor("忘语", "忘语著"))
    }
}
