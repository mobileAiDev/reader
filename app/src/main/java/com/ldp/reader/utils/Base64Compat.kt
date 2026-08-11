package com.ldp.reader.utils

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Base64 variants used by Android code without relying on platform classes introduced in API 26. */
@OptIn(ExperimentalEncodingApi::class)
internal object Base64Compat {
    private val basicWithOptionalPadding = Base64.Default.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)
    private val mimeWithOptionalPadding = Base64.Mime.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)
    private val urlSafeWithOptionalPadding = Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)
    private val urlSafeWithoutPadding = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    /** RFC 4648 basic alphabet, padded, and never line-wrapped. */
    fun encodeBasic(value: ByteArray): String = Base64.Default.encode(value)

    /** Strict RFC 4648 basic decoding; padding may be omitted. */
    fun decodeBasic(value: CharSequence): ByteArray = basicWithOptionalPadding.decode(value)

    /** MIME-style decoding that ignores whitespace and other non-alphabet separators. */
    fun decodeMime(value: CharSequence): ByteArray = mimeWithOptionalPadding.decode(value)

    /** RFC 4648 URL-safe alphabet, never line-wrapped or padded. */
    fun encodeUrlWithoutPadding(value: ByteArray): String = urlSafeWithoutPadding.encode(value)

    /** Strict RFC 4648 URL-safe decoding; padding may be omitted. */
    fun decodeUrl(value: CharSequence): ByteArray = urlSafeWithOptionalPadding.decode(value)
}
