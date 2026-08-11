package com.ldp.reader.ui.fragment

import com.ldp.reader.media.ReaderMediaKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaShelfFilterPolicyTest {
    @Test
    fun comicFirstSavedPageCountsAsReadingButMissingProgressIsUnread() {
        assertTrue(MediaShelfFilterPolicy.hasReadingProgress(ReaderMediaKind.COMIC, 0, 0L))
        assertFalse(MediaShelfFilterPolicy.hasReadingProgress(ReaderMediaKind.COMIC, -1, 0L))
    }

    @Test
    fun audioRequiresPersistedPositivePositionToCountAsReading() {
        assertTrue(MediaShelfFilterPolicy.hasReadingProgress(ReaderMediaKind.AUDIO, -1, 1L))
        assertFalse(MediaShelfFilterPolicy.hasReadingProgress(ReaderMediaKind.AUDIO, -1, 0L))
    }

    @Test
    fun unreadAndReadingFiltersUseMediaProgress() {
        val now = 1_775_000_000_000L

        assertTrue(MediaShelfFilterPolicy.matches(BookShelfViewModel.FilterKey.UNREAD, now, false, now))
        assertFalse(MediaShelfFilterPolicy.matches(BookShelfViewModel.FilterKey.UNREAD, now, true, now))
        assertTrue(MediaShelfFilterPolicy.matches(BookShelfViewModel.FilterKey.READING, now, true, now))
        assertFalse(MediaShelfFilterPolicy.matches(BookShelfViewModel.FilterKey.READING, now, false, now))
    }

    @Test
    fun updateFiltersNeverTreatLocalMediaActivityAsPublicationTime() {
        val dayMs = 24L * 60L * 60L * 1000L
        val now = 1_775_000_000_000L

        assertFalse(MediaShelfFilterPolicy.matches(
            BookShelfViewModel.FilterKey.UPDATED_3_DAYS,
            now - 2L * dayMs,
            false,
            now
        ))
        assertFalse(MediaShelfFilterPolicy.matches(
            BookShelfViewModel.FilterKey.UPDATED_3_DAYS,
            now - 4L * dayMs,
            false,
            now
        ))
        assertFalse(MediaShelfFilterPolicy.matches(
            BookShelfViewModel.FilterKey.UPDATED_7_DAYS,
            now - 6L * dayMs,
            true,
            now
        ))
    }

    @Test
    fun unsupportedFinishedAndLocalFiltersDoNotMisclassifyMedia() {
        val now = 1_775_000_000_000L

        assertFalse(MediaShelfFilterPolicy.matches(BookShelfViewModel.FilterKey.FINISHED, now, true, now))
        assertFalse(MediaShelfFilterPolicy.matches(BookShelfViewModel.FilterKey.LOCAL, now, false, now))
    }
}
