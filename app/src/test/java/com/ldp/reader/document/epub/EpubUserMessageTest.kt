package com.ldp.reader.document.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EpubUserMessageTest {
    @Test
    fun everyParserFailureMapsToAShortUserFacingMessage() {
        val messages = EpubParseError.entries.associateWith(EpubUserMessage::forParseError)

        assertEquals(EpubParseError.entries.toSet(), messages.keys)
        assertEquals("文件中没有可阅读内容", messages[EpubParseError.SPINE_EMPTY])
        assertEquals(
            "文件过大或内容过于复杂，暂时无法打开",
            messages[EpubParseError.ENTRY_TOO_LARGE]
        )
        assertEquals(
            "文件包含暂不支持的内容",
            messages[EpubParseError.INLINE_DATA_IMAGE_NOT_ALLOWED]
        )
        messages.values.forEach { message ->
            listOf("META-INF", "container", "OPF", "manifest", "spine", "id", "XML", "安全上限").forEach { token ->
                assertFalse("message=$message", message.contains(token, ignoreCase = true))
            }
        }
    }
}
