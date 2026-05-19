package com.ldp.reader.utils

import java.security.MessageDigest

object BookCacheKey {
    private const val MAX_SEGMENT_BYTES = 180
    private val illegalFileChars = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

    @JvmStatic
    fun folderSegment(bookId: String?): String? {
        return safeSegment(bookId, "book")
    }

    @JvmStatic
    fun fileSegment(chapterTitle: String?): String? {
        return safeSegment(chapterTitle, "chapter")
    }

    private fun safeSegment(value: String?, prefix: String): String? {
        if (value == null) {
            return null
        }
        if (value.isBlank()) {
            return value
        }
        if (isSafeSegment(value)) {
            return value
        }
        return prefix + "_" + sha256(value)
    }

    private fun isSafeSegment(value: String): Boolean {
        if (value.toByteArray(Charsets.UTF_8).size > MAX_SEGMENT_BYTES) {
            return false
        }
        return value.none { it.code < 32 || illegalFileChars.contains(it) }
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
