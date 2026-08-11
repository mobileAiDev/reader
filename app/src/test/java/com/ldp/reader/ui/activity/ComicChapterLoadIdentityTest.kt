package com.ldp.reader.ui.activity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComicChapterLoadIdentityTest {
    @Test
    fun `older completion cannot publish after a newer cross-book request`() {
        val oldRequest = ComicChapterLoadIdentity(1, "book-a", "chapter-a")
        val newRequest = ComicChapterLoadIdentity(2, "book-b", "chapter-b")

        assertFalse(oldRequest.isCurrent(2, "book-b", "chapter-b"))
        assertTrue(newRequest.isCurrent(2, "book-b", "chapter-b"))
    }

    @Test
    fun `route mismatch rejects publication even when token matches`() {
        val request = ComicChapterLoadIdentity(7, "book-a", "chapter-a")

        assertFalse(request.isCurrent(7, "book-a", "chapter-b"))
        assertFalse(request.isCurrent(7, "book-b", "chapter-a"))
    }
}
