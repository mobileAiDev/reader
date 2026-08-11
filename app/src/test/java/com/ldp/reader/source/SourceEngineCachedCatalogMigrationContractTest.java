package com.ldp.reader.source;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

public class SourceEngineCachedCatalogMigrationContractTest {
    @Test
    public void readActivityMigratesRecordAndLiveLoaderBeforeFirstPersistedCatalogDisplay() throws IOException {
        String activity = readFile("src/main/java/com/ldp/reader/ui/activity/ReadActivity.kt");

        assertInOrder(activity,
                "val storedBookChapters = BookRepository.getInstance().getBookChapters(mBookId)",
                "val cachedCatalogMigration = migratePersistedSourceEngineCatalog(storedBookChapters)",
                "val bookChapterBeen = cachedCatalogMigration?.chapters ?: storedBookChapters",
                "cachedCatalogMigration.remapRecord(persistedRecord)",
                "persistSourceEngineCatalogMigration(",
                "mPageLoader!!.applyMigratedBookRecord(persistedRecord)",
                "mPageLoader!!.collBook.bookChapters = bookChapterBeen",
                "mPageLoader!!.refreshChapterList()");
        assertFalse(activity.contains("saveBookRecord(persistedRecord)"));
    }

    @Test
    public void pageLoaderSynchronizesLiveRecordAndForcesContentToReopen() throws IOException {
        String loader = readFile("src/main/java/com/ldp/reader/widget/page/PageLoader.kt");

        assertInOrder(loader,
                "fun applyMigratedBookRecord(record: BookRecordBean)",
                "mBookRecord.chapter = record.chapter",
                "mBookRecord.pagePos = record.pagePos",
                "mBookRecord.chapterLink = record.chapterLink",
                "mBookRecord.chapterTitle = record.chapterTitle",
                "mCurChapterPos = record.chapter",
                "mLastChapterPos = record.chapter",
                "invalidateCurrentChapterLoad()",
                "mPrePageList = null",
                "mCurPageList = null",
                "mNextPageList = null",
                "mCurPage = null",
                "mStatus = STATUS_LOADING",
                "isChapterOpen = false");
    }

    @Test
    public void viewModelSanitizesEveryCachedAndRefreshCatalogBoundary() throws IOException {
        String viewModel = readFile("src/main/java/com/ldp/reader/ui/activity/ReadViewModel.kt");

        assertTrue(viewModel.contains("boundary = \"attached-existing\""));
        assertTrue(viewModel.contains("boundary = \"incoming\""));
        assertTrue(viewModel.contains("boundary = \"session-cache\""));
        assertTrue(viewModel.contains("boundary = \"bootstrap\""));
        assertTrue(viewModel.contains("boundary = \"tier-refresh\""));
        assertInOrder(viewModel,
                "val existingBookChapters = sanitizeSourceEngineCatalogBoundary",
                "val incomingBookChapters = BookContentProviderRouter.getBookFolder",
                "val bookChapterBeans = sanitizeSourceEngineCatalogBoundary",
                "shouldRetainExistingSourceEngineCatalog");
    }

    @Test
    public void repositorySanitizesStoredAndIncomingCatalogsBeforeSizeRetention() throws IOException {
        String repository = readFile("src/main/java/com/ldp/reader/model/local/BookRepository.kt");

        assertInOrder(repository,
                "normalizeSourceEngineShelfIdentity(bean)",
                "sanitizeSourceEngineCatalogForSave(bean)",
                "val existing = mBookStore.getCollBook(bean.get_id())");
        assertInOrder(repository,
                "private fun mergeStoredBookIntoIncoming",
                "sanitizeSourceEngineCatalogForSave(stored)",
                "sanitizeSourceEngineCatalogForSave(incoming)",
                "shouldKeepStoredSourceEngineCatalog(stored, incoming)");
        assertInOrder(repository,
                "fun persistSourceEngineCatalogMigration",
                "mBookStore.runInTx",
                "replaceBookChaptersInTx(bookId, chapters)",
                "mBookRecordStore.saveBookRecord(record)");
        assertInOrder(repository,
                "private fun replaceBookChaptersInTx",
                "SourceEngineCachedCatalogMigration.sanitize(beans).chapters",
                "mBookStore.replaceBookChapters(bookId, preparedChapters)",
                "storedBook.chaptersCount = preparedChapters.size",
                "storedBook.lastChapter = preparedChapters.lastOrNull()?.title",
                "mBookStore.saveCollBook(storedBook)");
    }

    @Test
    public void appMigrationReusesSourceEngineRuleInsteadOfCopyingPatterns() throws IOException {
        String fusion = readFile("../source-engine/src/main/kotlin/com/ldp/reader/sourceengine/catalog/ChapterListFusion.kt");
        String migration = readFile("src/main/java/com/ldp/reader/source/SourceEngineCachedCatalogMigration.kt");

        assertTrue(fusion.contains("CatalogNavigationPollutionFilter"));
        assertTrue(migration.contains("CatalogNavigationPollutionFilter"));
        assertFalse(migration.contains("NAVIGATION_ONLY_TITLE_PATTERNS"));
        assertFalse(migration.contains("VIP. 第381章"));
    }

    private static void assertInOrder(String source, String... tokens) {
        int cursor = -1;
        for (String token : tokens) {
            int found = source.indexOf(token, cursor + 1);
            assertTrue("Missing or out-of-order token: " + token, found > cursor);
            cursor = found;
        }
    }

    private static String readFile(String path) throws IOException {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }
}
