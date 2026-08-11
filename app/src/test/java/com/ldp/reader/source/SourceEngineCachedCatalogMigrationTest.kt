package com.ldp.reader.source

import com.ldp.reader.model.bean.BookChapterBean
import com.ldp.reader.model.bean.BookRecordBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceEngineCachedCatalogMigrationTest {
    @Test
    fun `legacy numeric record is remapped from 377 to 375 without losing page position`() {
        val cached = pollutedCatalog()
        val oldCurrent = cached[377]
        val record = BookRecordBean("book", 377, 11)

        val migration = SourceEngineCachedCatalogMigration.sanitize(cached)
        val recordChanged = migration.remapRecord(record)

        assertTrue(migration.changed)
        assertEquals(2, migration.removedCount)
        assertEquals(375, migration.newIndexForOldIndex(377))
        assertTrue(recordChanged)
        assertEquals(375, record.chapter)
        assertEquals(oldCurrent.link, record.chapterLink)
        assertEquals(oldCurrent.title, record.chapterTitle)
        assertEquals(11, record.pagePos)
        assertSame(oldCurrent, migration.chapters[record.chapter])
        assertEquals(migration.chapters.indices.map(Int::toLong), migration.chapters.map { it.start })
    }

    @Test
    fun `existing record anchor and page position survive cached catalog migration`() {
        val cached = pollutedCatalog()
        val oldCurrent = cached[377]
        val record = BookRecordBean(
            "book",
            377,
            7,
            oldCurrent.link,
            oldCurrent.title
        )

        val migration = SourceEngineCachedCatalogMigration.sanitize(cached)
        val recordChanged = migration.remapRecord(record)

        assertTrue(recordChanged)
        assertEquals(375, record.chapter)
        assertEquals(oldCurrent.link, record.chapterLink)
        assertEquals(oldCurrent.title, record.chapterTitle)
        assertEquals(7, record.pagePos)
        assertEquals(
            listOf("一个普通人的日常（一）", "不是诈尸，不是遭了阿蒙"),
            migration.chapters.takeLast(2).map { it.title }
        )
    }

    @Test
    fun `record targeting a removed navigation row falls back to a real chapter at page zero`() {
        val cached = pollutedCatalog()
        val removed = cached[160]
        val record = BookRecordBean("book", 160, 9, removed.link, removed.title)

        val migration = SourceEngineCachedCatalogMigration.sanitize(cached)
        val recordChanged = migration.remapRecord(record)

        assertTrue(recordChanged)
        assertFalse(migration.isOldIndexRetained(160))
        assertEquals(160, record.chapter)
        assertEquals("第161章 正文161", record.chapterTitle)
        assertEquals("link-161", record.chapterLink)
        assertEquals(0, record.pagePos)
    }

    @Test
    fun `existing anchor wins when legacy numeric chapter points at a different entry`() {
        val cached = pollutedCatalog()
        val anchored = cached[377]
        val record = BookRecordBean("book", 10, 6, anchored.link, anchored.title)

        val migration = SourceEngineCachedCatalogMigration.sanitize(cached)
        val recordChanged = migration.remapRecord(record)

        assertTrue(recordChanged)
        assertEquals(375, record.chapter)
        assertEquals(anchored.link, record.chapterLink)
        assertEquals(anchored.title, record.chapterTitle)
        assertEquals(6, record.pagePos)
        assertSame(anchored, migration.chapters[record.chapter])
    }

    @Test
    fun `record and chapter instances are untouched when catalog has no proven pollution`() {
        val clean = (1..30).map { ordinal -> chapter(ordinal, "第${ordinal}章 正文$ordinal") }
        val record = BookRecordBean("book", 20, 3, "link-21", "第21章 正文21")

        val migration = SourceEngineCachedCatalogMigration.sanitize(clean)

        assertFalse(migration.changed)
        assertFalse(migration.remapRecord(record))
        assertSame(clean, migration.chapters)
        assertEquals(20, record.chapter)
        assertEquals(3, record.pagePos)
    }

    @Test
    fun `sanitizing and remapping an already migrated catalog is idempotent`() {
        val firstMigration = SourceEngineCachedCatalogMigration.sanitize(pollutedCatalog())
        val record = BookRecordBean("book", 377, 4)
        assertTrue(firstMigration.remapRecord(record))

        val secondMigration = SourceEngineCachedCatalogMigration.sanitize(firstMigration.chapters)

        assertFalse(secondMigration.changed)
        assertEquals(0, secondMigration.removedCount)
        assertFalse(secondMigration.remapRecord(record))
        assertEquals(375, record.chapter)
        assertEquals(4, record.pagePos)
    }

    @Test
    fun `device shaped volume catalog keeps current 377 while removing rows 378 and 379`() {
        val earlierVolumes = (1..218).map { index ->
            chapter(30_000 + index, "卷内旧章节$index")
        }
        val currentVolume = (1..160).map { ordinal ->
            chapter(40_000 + ordinal, "第${ordinal}章 卷内正文$ordinal")
        }
        val pollution = listOf(
            chapter(50_001, "378. 第381章"),
            chapter(50_002, "VIP. 第381章(最新)")
        )
        val remainingVolume = (161..300).map { ordinal ->
            chapter(40_000 + ordinal, "第${ordinal}章 卷内正文$ordinal")
        }
        val catalog = earlierVolumes + currentVolume + pollution + remainingVolume
        val record = BookRecordBean("book", 377, 12)

        val migration = SourceEngineCachedCatalogMigration.sanitize(catalog)
        val recordChanged = migration.remapRecord(record)

        assertEquals(2, migration.removedCount)
        assertEquals(377, record.chapter)
        assertEquals("第160章 卷内正文160", record.chapterTitle)
        assertEquals(12, record.pagePos)
        assertTrue(recordChanged)
        assertEquals("第161章 卷内正文161", migration.chapters[378].title)
    }

    private fun pollutedCatalog(): List<BookChapterBean> {
        return (1..160).map { ordinal -> chapter(ordinal, "第${ordinal}章 正文$ordinal") } +
            listOf(
                chapter(10_001, "378. 第381章"),
                chapter(10_002, "VIP. 第381章(最新)")
            ) +
            (161..390).map { ordinal -> chapter(ordinal, "第${ordinal}章 正文$ordinal") } +
            listOf(
                chapter(20_001, "一个普通人的日常（一)".replace(')', '）')),
                chapter(20_002, "不是诈尸，不是遭了阿蒙")
            )
    }

    private fun chapter(identity: Int, title: String): BookChapterBean {
        return BookChapterBean().apply {
            id = "chapter-$identity"
            bookId = "book"
            link = "link-$identity"
            this.title = title
            start = (identity - 1).toLong()
        }
    }
}
