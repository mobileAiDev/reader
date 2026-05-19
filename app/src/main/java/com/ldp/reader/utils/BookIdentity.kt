package com.ldp.reader.utils

import java.util.Locale

object BookIdentity {
    private const val SOURCE_ENGINE_SHELF_PREFIX = "source_engine_shelf_"
    private const val MIN_MEANINGFUL_CHARS = 2

    @JvmStatic
    fun sourceEngineShelfId(title: String?, author: String?): String {
        val titleKey = canonicalTitleKey(title, author)
        val stableKey = titleKey.ifBlank { canonicalAuthorKey(author).ifBlank { "unknown" } }
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
    fun canonicalAuthorKey(author: String?): String {
        return normalizeToken(author)
            .removePrefix("作者")
            .removePrefix("作家")
    }

    private fun normalizeToken(value: String?): String {
        return value.orEmpty()
            .lowercase(Locale.ROOT)
            .replace(Regex("""作者[:：]\s*"""), "")
            .replace(Regex("""[\s\p{Punct}，。！？、；：“”‘’（）【】《》〈〉「」『』]+"""), "")
            .trim()
    }
}
