package com.ldp.reader.document

import java.io.File
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal object LocalTextImportIdentity {
    fun relativeTarget(displayName: String, sourceIdentity: String): String {
        return "imports/text/${stableSourceKey(sourceIdentity)}/${safeFileName(displayName)}"
    }

    /**
     * Target used before imports became URI-scoped. Keeping the exact old name normalization is
     * required so an app upgrade can find the shelf identity and progress created by that version.
     */
    fun legacyRelativeTarget(displayName: String): String {
        return "imports/text/${legacySafeFileName(displayName)}"
    }

    /**
     * Prefer the most precise existing identity, then the historical SAF identity, then the
     * physical-path identity. If this is a new import, a resolvable direct path remains canonical.
     */
    fun preferredBookId(
        uriScopedBookId: String,
        legacyUnscopedBookId: String,
        directPathBookId: String?,
        existingBookIds: Set<String>
    ): String {
        return sequenceOf(uriScopedBookId, legacyUnscopedBookId, directPathBookId)
            .filterNotNull()
            .firstOrNull(existingBookIds::contains)
            ?: directPathBookId
            ?: uriScopedBookId
    }

    /**
     * The old layout recorded only a display name, so two providers with the same name were
     * indistinguishable. Content equality is the only safe migration proof available after the
     * fact; without it, reusing the old ID could attach another book's reading progress.
     */
    fun sameFileContent(first: File, second: File): Boolean {
        if (!first.isFile || !second.isFile || first.length() != second.length()) return false
        val firstDigest = fileDigest(first) ?: return false
        val secondDigest = fileDigest(second) ?: return false
        return MessageDigest.isEqual(firstDigest, secondDigest)
    }

    /**
     * Resolves only identities whose mapping is unambiguous. Android's standard external-storage
     * document IDs map to the same absolute path used by the direct directory entry. Other
     * providers remain URI-scoped instead of guessing a path.
     */
    fun directFilePath(sourceIdentity: String, primaryStorageRoot: String): String? {
        val uri = runCatching { URI(sourceIdentity) }.getOrNull() ?: return null
        if (uri.scheme.equals("file", ignoreCase = true)) {
            return uri.path?.let(::canonicalPath)
        }
        if (
            !uri.scheme.equals("content", ignoreCase = true) ||
            uri.authority != EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY
        ) {
            return null
        }
        val rawDocumentId = uri.rawPath
            ?.substringAfterLast(DOCUMENT_MARKER, missingDelimiterValue = "")
            ?.takeIf(String::isNotBlank)
            ?: return null
        val documentId = percentDecodePath(rawDocumentId) ?: return null
        val volumeId = documentId.substringBefore(':', missingDelimiterValue = "")
        val relativePath = documentId.substringAfter(':', missingDelimiterValue = "")
        if (!SAFE_VOLUME_ID.matches(volumeId) || !isSafeRelativePath(relativePath)) return null

        val storageRoot = if (volumeId.equals(PRIMARY_VOLUME, ignoreCase = true)) {
            File(primaryStorageRoot)
        } else {
            File("/storage", volumeId)
        }
        val canonicalRoot = canonicalPath(storageRoot.path) ?: return null
        val candidate = canonicalPath(File(storageRoot, relativePath).path) ?: return null
        return candidate.takeIf { path ->
            path == canonicalRoot || path.startsWith("$canonicalRoot${File.separator}")
        }
    }

    private fun stableSourceKey(sourceIdentity: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(sourceIdentity.toByteArray(StandardCharsets.UTF_8))
        return digest.take(8).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun fileDigest(file: File): ByteArray? {
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) digest.update(buffer, 0, read)
                }
            }
            digest.digest()
        }.getOrNull()
    }

    private fun safeFileName(name: String): String {
        val cleaned = name
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .trimStart('.')
            .ifBlank { "本地书籍.txt" }
        return if (cleaned.endsWith(".txt", ignoreCase = true)) cleaned else "$cleaned.txt"
    }

    private fun legacySafeFileName(name: String): String {
        val cleaned = name
            .replace(Regex("""[\\/:*?"<>|]"""), "_")
            .ifBlank { "本地书籍.txt" }
        return if (cleaned.endsWith(".txt", ignoreCase = true)) cleaned else "$cleaned.txt"
    }

    /**
     * Document IDs are URI path data, not application/x-www-form-urlencoded data. URI decoding
     * therefore preserves a literal '+' while decoding percent escapes such as "%2B" to '+'.
     */
    private fun percentDecodePath(rawValue: String): String? {
        return runCatching {
            URI("reader://local/$rawValue").path?.removePrefix("/")
        }.getOrNull()?.takeIf(String::isNotBlank)
    }

    private fun isSafeRelativePath(path: String): Boolean {
        if (path.isBlank() || File(path).isAbsolute) return false
        return path.split('/', '\\').none { segment -> segment == "." || segment == ".." }
    }

    private fun canonicalPath(path: String): String? {
        return runCatching { File(path).canonicalPath }.getOrNull()
    }

    private const val EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY =
        "com.android.externalstorage.documents"
    private const val DOCUMENT_MARKER = "/document/"
    private const val PRIMARY_VOLUME = "primary"
    private val SAFE_VOLUME_ID = Regex("[A-Za-z0-9_-]+")
}
