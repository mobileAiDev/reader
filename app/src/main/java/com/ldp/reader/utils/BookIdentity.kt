package com.ldp.reader.utils

import java.util.Locale

object BookIdentity {
    private const val SOURCE_ENGINE_SHELF_PREFIX = "source_engine_shelf_"
    private const val MIN_MEANINGFUL_CHARS = 2
    private val anonymousAuthorKeys = setOf("佚名", "未知", "无名", "匿名", "不详", "佚名作者")

    @JvmStatic
    fun sourceEngineShelfId(title: String?, author: String?): String {
        val stableKey = sourceEngineIdentityKey(title, author).ifBlank { "unknown" }
        val digest = MD5Utils.strToMd5By16(stableKey) ?: stableKey.hashCode().toString()
        return SOURCE_ENGINE_SHELF_PREFIX + digest
    }

    @JvmStatic
    fun isSourceEngineShelfId(value: String?): Boolean {
        return value?.startsWith(SOURCE_ENGINE_SHELF_PREFIX) == true
    }

    @JvmStatic
    fun canonicalTitleKey(title: String?, author: String? = null): String {
        var key = normalizeToken(title)
            .replace("最新章节", "")
            .replace("全文阅读", "")
            .replace("无弹窗", "")
            .replace("小说", "")
        val authorKey = canonicalAuthorKey(author)
        if (key.isNotBlank() && authorKey.isNotBlank() && key.endsWith(authorKey)) {
            val withoutAuthor = key.removeSuffix(authorKey)
            if (withoutAuthor.length >= MIN_MEANINGFUL_CHARS) {
                key = withoutAuthor
            }
        }
        return key
    }

    @JvmStatic
    fun sourceEngineIdentityKey(title: String?, author: String?): String {
        val titleKey = canonicalTitleKey(title, author)
        val authorKey = canonicalAuthorKey(author)
        return when {
            titleKey.isNotBlank() && authorKey.isNotBlank() -> "$titleKey\n$authorKey"
            titleKey.isNotBlank() -> titleKey
            authorKey.isNotBlank() -> authorKey
            else -> ""
        }
    }

    @JvmStatic
    fun canonicalAuthorKey(author: String?): String {
        val key = normalizeToken(author)
            .removePrefix("作者")
            .removePrefix("作家")
            .removeSuffix("著")
            .removeSuffix("作品")
        return key.takeUnless { isAnonymousAuthorKey(it) }.orEmpty()
    }

    @JvmStatic
    fun isAnonymousAuthor(author: String?): Boolean {
        return isAnonymousAuthorKey(normalizeToken(author))
    }

    @JvmStatic
    fun authorsCompatible(first: String?, second: String?): Boolean {
        val firstKey = canonicalAuthorKey(first)
        val secondKey = canonicalAuthorKey(second)
        if (firstKey.isBlank() || secondKey.isBlank()) return true
        return firstKey == secondKey || firstKey.contains(secondKey) || secondKey.contains(firstKey)
    }

    @JvmStatic
    fun preferredDisplayAuthor(first: String?, second: String?): String {
        return listOf(first, second)
            .map { author -> author.orEmpty().trim() }
            .filter { author -> author.isNotBlank() && !isAnonymousAuthor(author) }
            .maxByOrNull { author -> author.length }
            ?: listOf(first, second)
                .map { author -> author.orEmpty().trim() }
                .firstOrNull { author -> author.isNotBlank() }
                .orEmpty()
    }

    private fun isAnonymousAuthorKey(key: String): Boolean {
        return key in anonymousAuthorKeys
    }

    private fun normalizeToken(value: String?): String {
        return value.orEmpty()
            .lowercase(Locale.ROOT)
            .replace(Regex("""作者[:：]\s*"""), "")
            .replace(Regex("""[\s\p{P}\p{S}]+"""), "")
            .trim()
    }
}
