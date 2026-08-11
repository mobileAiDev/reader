package com.ldp.reader.utils

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Base64CompatTest {
    @Test
    fun basicEncodingIsPaddedAndNeverWrapped() {
        assertEquals("cmVhZGVy", Base64Compat.encodeBasic("reader".toByteArray()))
        assertEquals("YQ==", Base64Compat.encodeBasic("a".toByteArray()))

        val encoded = Base64Compat.encodeBasic(ByteArray(120) { it.toByte() })
        assertFalse(encoded.contains('\n'))
        assertFalse(encoded.contains('\r'))
    }

    @Test
    fun basicDecoderAcceptsMissingPaddingAndRoundTrips() {
        val value = "Android 7.1 / 阅读器"
        val encoded = Base64Compat.encodeBasic(value.toByteArray(Charsets.UTF_8))

        assertEquals(value, String(Base64Compat.decodeBasic(encoded), Charsets.UTF_8))
        assertEquals(value, String(Base64Compat.decodeBasic(encoded.trimEnd('=')), Charsets.UTF_8))
    }

    @Test
    fun urlSafeEncodingUsesUrlAlphabetWithoutPaddingAndRoundTrips() {
        val bytes = byteArrayOf(0xfb.toByte(), 0xff.toByte(), 0xef.toByte(), 0xfa.toByte())
        val encoded = Base64Compat.encodeUrlWithoutPadding(bytes)

        assertEquals("-__v-g", encoded)
        assertFalse(encoded.contains('+'))
        assertFalse(encoded.contains('/'))
        assertFalse(encoded.contains('='))
        assertArrayEquals(bytes, Base64Compat.decodeUrl(encoded))
    }

    @Test
    fun mimeDecoderIgnoresSpacesAndLineBreaksWithoutPadding() {
        val encoded = "Y Q\r\n\t"

        assertEquals("a", String(Base64Compat.decodeMime(encoded), Charsets.UTF_8))
    }

    @Test(expected = IllegalArgumentException::class)
    fun strictBasicDecoderRejectsWhitespace() {
        Base64Compat.decodeBasic("cm VhZGVy")
    }

    @Test(expected = IllegalArgumentException::class)
    fun strictUrlDecoderRejectsBasicAlphabet() {
        Base64Compat.decodeUrl("+//v+g")
    }
}
