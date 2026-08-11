package com.ldp.reader.sourceengine.catalog

object ChapterTitleCanonicalizer {
    fun canonicalizeKnownUpdateDecoration(title: String): String {
        val decoration = TRAILING_UPDATE_DECORATION.find(title) ?: return title
        return title.removeRange(decoration.range).trimEnd()
    }

    private val TRAILING_UPDATE_DECORATION = Regex(
        """\s*(?:【\s*$KNOWN_UPDATE_MARKER\s*[！!。.]?\s*】|〖\s*$KNOWN_UPDATE_MARKER\s*[！!。.]?\s*〗|\[\s*$KNOWN_UPDATE_MARKER\s*[！!。.]?\s*]|（\s*$KNOWN_UPDATE_MARKER\s*[！!。.]?\s*）|\(\s*$KNOWN_UPDATE_MARKER\s*[！!。.]?\s*\))\s*$"""
    )

    private const val KNOWN_UPDATE_MARKER =
        """(?:第?[0-9０-９零〇一二两三四五六七八九十百千万]+更|加更|补更|已?修订|校订|求(?:月票|推荐票|订阅|收藏|打赏|支持))"""
}
