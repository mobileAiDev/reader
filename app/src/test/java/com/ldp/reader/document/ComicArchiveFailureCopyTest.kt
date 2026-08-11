package com.ldp.reader.document

import org.junit.Assert.assertEquals
import org.junit.Test

class ComicArchiveFailureCopyTest {

    @Test
    fun archiveStructureFailuresUseTheDamagedFileMessage() {
        listOf(
            ComicArchiveFailureReason.UNSAFE_ENTRY,
            ComicArchiveFailureReason.INVALID_ARCHIVE,
            ComicArchiveFailureReason.IO_ERROR
        ).forEach { reason ->
            assertEquals("漫画文件已损坏", ComicArchiveFailureCopy.messageFor(reason))
        }
    }

    @Test
    fun imageCompatibilityFailuresUseTheUnsupportedImageMessage() {
        listOf(
            ComicArchiveFailureReason.NO_IMAGES,
            ComicArchiveFailureReason.INVALID_IMAGE
        ).forEach { reason ->
            assertEquals("包含不支持的图片", ComicArchiveFailureCopy.messageFor(reason))
        }
    }

    @Test
    fun resourceFailuresUseActionableMessages() {
        assertEquals(
            "文件过大，暂时无法打开",
            ComicArchiveFailureCopy.messageFor(ComicArchiveFailureReason.LIMIT_EXCEEDED)
        )
        assertEquals(
            "存储空间不足",
            ComicArchiveFailureCopy.messageFor(ComicArchiveFailureReason.INSUFFICIENT_STORAGE)
        )
    }
}
