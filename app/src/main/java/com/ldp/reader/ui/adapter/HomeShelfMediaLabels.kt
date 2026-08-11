package com.ldp.reader.ui.adapter

object HomeShelfMediaLabels {
    @JvmStatic
    fun comicProgressText(
        currentChapterTitle: String?,
        currentChapterIndex: Int,
        savedPageIndex: Int
    ): String {
        // Source indexes describe fetch order and may be descending (for example index 995 can
        // still represent the first chronological chapter). Prefer the actual chapter label.
        val chapter = currentChapterTitle?.trim().takeUnless { it.isNullOrEmpty() }
            ?: currentChapterIndex.takeIf { it >= 0 }?.let { "第${it + 1}话" }
            ?: "当前话"
        val page = savedPageIndex.takeIf { it >= 0 }?.plus(1) ?: 1
        return "$chapter · 第${page.coerceAtLeast(1)}页"
    }
}
