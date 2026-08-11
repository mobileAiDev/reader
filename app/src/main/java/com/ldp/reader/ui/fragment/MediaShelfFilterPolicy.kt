package com.ldp.reader.ui.fragment

import com.ldp.reader.media.ReaderMediaKind

/** Maps persisted media progress onto bookshelf filters without inventing source metadata. */
object MediaShelfFilterPolicy {
    @JvmStatic
    fun hasReadingProgress(
        kind: ReaderMediaKind?,
        comicPageIndex: Int,
        audioPositionMs: Long
    ): Boolean {
        return when (kind) {
            ReaderMediaKind.COMIC -> comicPageIndex >= 0
            ReaderMediaKind.AUDIO -> audioPositionMs > 0L
            else -> false
        }
    }

    @JvmStatic
    fun matches(
        key: BookShelfViewModel.FilterKey?,
        @Suppress("UNUSED_PARAMETER") itemUpdatedAtMs: Long,
        hasReadingProgress: Boolean,
        @Suppress("UNUSED_PARAMETER") nowMillis: Long
    ): Boolean {
        return when (key) {
            null,
            BookShelfViewModel.FilterKey.ALL -> true
            // updatedAtMs currently records local shelf/read activity, not a work publication
            // time. Treating it as “3/7 日内更新” makes an old comic look newly published merely
            // because it was opened, so media stays out until a real source timestamp exists.
            BookShelfViewModel.FilterKey.UPDATED_3_DAYS,
            BookShelfViewModel.FilterKey.UPDATED_7_DAYS -> false
            BookShelfViewModel.FilterKey.UNREAD -> !hasReadingProgress
            BookShelfViewModel.FilterKey.READING -> hasReadingProgress
            BookShelfViewModel.FilterKey.FINISHED,
            BookShelfViewModel.FilterKey.LOCAL -> false
        }
    }

}
