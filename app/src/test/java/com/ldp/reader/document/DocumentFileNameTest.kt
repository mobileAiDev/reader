package com.ldp.reader.document

import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentFileNameTest {
    @Test
    fun fileSchemeUsesTheRealPathBasename() {
        assertEquals(
            "normal-book.epub",
            DocumentFileName.trustedFilePathDisplayName(
                scheme = "file",
                path = "/storage/emulated/0/Download/normal-book.epub"
            )
        )
    }

    @Test
    fun contentSchemeNeverUsesAPathSegmentThatLooksLikeASupportedFile() {
        listOf("forged.epub", "forged.pdf", "forged.cbz").forEach { forgedName ->
            assertEquals(
                "",
                DocumentFileName.trustedFilePathDisplayName(
                    scheme = "content",
                    path = "/untrusted/provider/path/$forgedName"
                )
            )
        }
    }
}
