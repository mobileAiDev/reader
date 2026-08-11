package com.ldp.reader.widget.page

import com.tencent.mmkv.MMKV
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest

internal object LocalTxtParserCacheVersionStore {
    private const val STORE_NAME = "local_txt_parser_cache"
    private const val CURRENT_VERSION = 6

    fun fingerprint(
        file: File,
        cancellationCheck: () -> Unit = {}
    ): String {
        repeat(FINGERPRINT_ATTEMPTS) {
            cancellationCheck()
            val length = file.length()
            val lastModified = file.lastModified()
            val contentDigest = FileInputStream(file).use { input ->
                fullContentDigest(input, cancellationCheck)
            }
            cancellationCheck()
            if (length == file.length() && lastModified == file.lastModified()) {
                return "$CURRENT_VERSION:$length:$lastModified:$contentDigest"
            }
        }
        throw LocalTxtParseException("TXT file changed while its content fingerprint was calculated")
    }

    fun fingerprint(
        snapshot: LocalTxtFileSnapshot,
        cancellationCheck: () -> Unit = {}
    ): String {
        repeat(FINGERPRINT_ATTEMPTS) {
            cancellationCheck()
            if (snapshot.currentLength() != snapshot.length) {
                return@repeat
            }
            val contentDigest = snapshot.openStream().use { input ->
                fullContentDigest(input, cancellationCheck)
            }
            cancellationCheck()
            if (snapshot.currentLength() == snapshot.length) {
                return "$CURRENT_VERSION:${snapshot.length}:${snapshot.lastModified}:$contentDigest"
            }
        }
        throw LocalTxtParseException("TXT file changed while its content fingerprint was calculated")
    }

    fun isCurrent(
        bookId: String?,
        fingerprint: String,
        encodingPreference: LocalTxtEncodingPreference = LocalTxtEncodingPreference.AUTO
    ): Boolean {
        if (bookId.isNullOrBlank()) return false
        return store().decodeString(bookId) == cacheIdentity(fingerprint, encodingPreference)
    }

    fun markCurrent(
        bookId: String?,
        fingerprint: String,
        encodingPreference: LocalTxtEncodingPreference = LocalTxtEncodingPreference.AUTO
    ) {
        if (bookId.isNullOrBlank()) return
        store().encode(bookId, cacheIdentity(fingerprint, encodingPreference))
    }

    fun cacheIdentity(
        fingerprint: String,
        encodingPreference: LocalTxtEncodingPreference
    ): String = "$fingerprint:encoding=${encodingPreference.persistedValue}"

    private fun store(): MMKV = MMKV.mmkvWithID(STORE_NAME)

    private fun fullContentDigest(
        inputStream: InputStream,
        cancellationCheck: () -> Unit
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(inputStream, DIGEST_BUFFER_BYTES).use { input ->
            val buffer = ByteArray(DIGEST_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) {
                    digest.update(buffer, 0, count)
                    cancellationCheck()
                }
            }
        }
        cancellationCheck()
        return digest.digest().joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    private const val FINGERPRINT_ATTEMPTS = 2
    private const val DIGEST_BUFFER_BYTES = 64 * 1024
}
