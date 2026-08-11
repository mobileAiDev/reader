package com.ldp.reader.utils

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileUtilsCharsetTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun detectsUnicodeBomBeforeHeuristicDetection() {
        val cases = listOf(
            Triple("utf8.txt", byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()), Charset.UTF8),
            Triple("utf16le.txt", byteArrayOf(0xff.toByte(), 0xfe.toByte()), Charset.UTF16LE),
            Triple("utf16be.txt", byteArrayOf(0xfe.toByte(), 0xff.toByte()), Charset.UTF16BE)
        )

        cases.forEach { (name, bom, expected) ->
            val file = temporaryFolder.newFile(name)
            val body = when (expected) {
                Charset.UTF16LE -> "第一章 开始".toByteArray(Charsets.UTF_16LE)
                Charset.UTF16BE -> "第一章 开始".toByteArray(Charsets.UTF_16BE)
                else -> "第一章 开始".toByteArray(Charsets.UTF_8)
            }
            file.writeBytes(bom + body)

            assertEquals(name, expected, FileUtils.getCharset(file.absolutePath))
        }
    }

    @Test
    fun validatesCompleteUtf8IncludingFourByteCharacters() {
        val file = temporaryFolder.newFile("emoji.txt")
        file.writeText("第一章 𠮷野家 😀\n正文完整。", Charsets.UTF_8)

        assertEquals(Charset.UTF8, FileUtils.getCharset(file.absolutePath))
    }

    @Test
    fun malformedUtf8DoesNotBecomeUtf8BecauseAnEarlierSequenceWasValid() {
        val file = temporaryFolder.newFile("malformed.txt")
        file.writeBytes("第一章".toByteArray(Charsets.UTF_8) + byteArrayOf(0xc3.toByte(), 0x28))

        assertEquals(Charset.GBK, FileUtils.getCharset(file.absolutePath))
    }

    @Test
    fun strictlyValidTwoByteUtf8TextDoesNotFallBackToGbk() {
        listOf(
            "français: déjà vu, été, élève",
            "Ελληνικά χωρίς τόνους",
            "Привет мир"
        ).forEachIndexed { index, text ->
            val file = temporaryFolder.newFile("utf8-two-byte-$index.txt")
            file.writeText(text, Charsets.UTF_8)

            assertEquals(text, Charset.UTF8, FileUtils.getCharset(file.absolutePath))
        }
    }

    @Test
    fun strictlyValidShortUtf8TextDoesNotFallBackToGbk() {
        listOf("一", "😀", "é").forEachIndexed { index, text ->
            val file = temporaryFolder.newFile("utf8-short-$index.txt")
            file.writeText(text, Charsets.UTF_8)

            assertEquals(text, Charset.UTF8, FileUtils.getCharset(file.absolutePath))
        }
    }

    @Test
    fun dualValidUnmarkedBytesUseTheModernUtf8Default() {
        val file = temporaryFolder.newFile("dual-valid.txt")
        file.writeBytes(byteArrayOf(0xe4.toByte(), 0xbd.toByte(), 0xa0.toByte(), 0x40))

        assertEquals(Charset.UTF8, FileUtils.getCharset(file.absolutePath))
    }

    @Test
    fun ordinaryLegacyGbkStillFallsBackToGbk() {
        val file = temporaryFolder.newFile("legacy-gbk.txt")
        file.writeText(
            "第一章 山雨欲来\n这是一本传统编码的中文小说。",
            java.nio.charset.Charset.forName("GBK")
        )

        assertEquals(Charset.GBK, FileUtils.getCharset(file.absolutePath))
    }

    @Test
    fun mostlyUtf8WithSparseMalformedBytesKeepsItsValidChineseText() {
        val file = temporaryFolder.newFile("mostly-utf8.txt")
        file.writeBytes(
            "QA malformed UTF-8\n第1章 valid\nbody-before-invalid ".toByteArray(Charsets.UTF_8) +
                byteArrayOf(0xf0.toByte(), 0x28, 0x8c.toByte(), 0x28) +
                " 中文\n第2章 tail\n".toByteArray(Charsets.UTF_8) +
                byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte(), 0xff.toByte(), 0xfe.toByte())
        )

        assertEquals(Charset.UTF8, FileUtils.getCharset(file.absolutePath))
    }

    @Test
    fun sparseUtf8DamageThresholdIsInclusiveButNotDilutedPastTenPercent() {
        val exactlyTenPercent = temporaryFolder.newFile("utf8-damage-10-percent.txt")
        exactlyTenPercent.writeBytes(
            "abcde中文测试".toByteArray(Charsets.UTF_8) + byteArrayOf(0xff.toByte())
        )
        val overTenPercent = temporaryFolder.newFile("utf8-damage-over-10-percent.txt")
        overTenPercent.writeBytes(
            "abcd中文测试".toByteArray(Charsets.UTF_8) + byteArrayOf(0xff.toByte())
        )

        assertEquals(Charset.UTF8, FileUtils.getCharset(exactlyTenPercent.absolutePath))
        assertEquals(Charset.GBK, FileUtils.getCharset(overTenPercent.absolutePath))
    }
}
