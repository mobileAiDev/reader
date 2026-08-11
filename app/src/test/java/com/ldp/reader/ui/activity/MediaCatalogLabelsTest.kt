package com.ldp.reader.ui.activity

import com.ldp.reader.media.ReaderMediaKind
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaCatalogLabelsTest {
    @Test
    fun usesMediaSpecificCatalogTitlesAndUnits() {
        assertEquals("漫画目录", MediaCatalogLabels.title(ReaderMediaKind.COMIC))
        assertEquals("话", MediaCatalogLabels.unit(ReaderMediaKind.COMIC))
        assertEquals("剧集目录", MediaCatalogLabels.title(ReaderMediaKind.AUDIO))
        assertEquals("集", MediaCatalogLabels.unit(ReaderMediaKind.AUDIO))
        assertEquals("书籍目录", MediaCatalogLabels.title(ReaderMediaKind.NOVEL))
        assertEquals("章", MediaCatalogLabels.unit(ReaderMediaKind.NOVEL))
        assertEquals("目录", MediaCatalogLabels.title(null))
    }
}
