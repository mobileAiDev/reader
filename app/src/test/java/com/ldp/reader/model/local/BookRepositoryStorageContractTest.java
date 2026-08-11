package com.ldp.reader.model.local;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class BookRepositoryStorageContractTest {

    @Test
    public void repositoryKeepsBookshelfChapterAndRecordStorageSemantics() throws IOException {
        String repository = readFile("src/main/java/com/ldp/reader/model/local/BookRepository.kt");

        assertInOrder(repository,
                "val collBooks: List<CollBookBean>",
                "get() = mergeDuplicateSourceEngineBooks(mBookStore.getCollBooks())");

        assertInOrder(repository,
                "fun getBookChapters(bookId: String?): List<BookChapterBean>",
                "return mBookStore.getBookChapters(bookId)");

        assertInOrder(repository,
                "fun deleteCollBookWithFiles(bean: CollBookBean)",
                "val bookId = bean.get_id()",
                "deleteBook(bookId)",
                "deleteBookChapter(bookId)",
                "mBookRecordStore.deleteBookRecord(bookId)",
                "readingProgressClearer(bookId)",
                "mBookStore.deleteCollBook(bean)");

        assertInOrder(repository,
                "fun saveBookRecord(bean: BookRecordBean)",
                "mBookRecordStore.saveBookRecord(bean)");

        assertInOrder(repository,
                "fun getBookRecord(bookId: String?): BookRecordBean?",
                "return mBookRecordStore.getBookRecord(bookId)");

        assertInOrder(repository,
                "fun deleteBookRecord(id: String?)",
                "mBookRecordStore.deleteBookRecord(id)");

        assertInOrder(repository,
                "private fun replaceBookChaptersInTx(bookId: String?, beans: List<BookChapterBean>?)",
                "SourceEngineCachedCatalogMigration.sanitize(beans).chapters",
                "mBookStore.replaceBookChapters(bookId, preparedChapters)");

        assertInOrder(repository,
                "private fun normalizeSourceEngineShelfIdentity(bean: CollBookBean)",
                "BookIdentity.sourceEngineShelfId(bean.title, bean.author)",
                "bean.bookIdInBiquge = routeId");

        assertInOrder(repository,
                "private fun mergeDuplicateSourceEngineBooks(books: List<CollBookBean>)",
                "sourceEngineIdentityKey(book)",
                "mergeVisibleSourceEngineBook(existing, book)");

        assertInOrder(repository,
                "private fun shouldKeepStoredSourceEngineCatalog",
                "isSourceEngineBook(stored)",
                "SourceEngineCatalogPersistencePolicy.canReplacePersistedCatalog");
        assertInOrder(repository,
                "private fun mergeStoredBookIntoIncoming",
                "sanitizeSourceEngineCatalogForSave(stored)",
                "sanitizeSourceEngineCatalogForSave(incoming)",
                "shouldKeepStoredSourceEngineCatalog(stored, incoming)");
        assertInOrder(repository,
                "private fun replaceBookChaptersInTx",
                "SourceEngineCachedCatalogMigration.sanitize(beans).chapters",
                "mBookStore.replaceBookChapters(bookId, preparedChapters)");

        String readViewModel = readFile("src/main/java/com/ldp/reader/ui/activity/ReadViewModel.kt");
        assertInOrder(readViewModel,
                "private fun shouldRetainExistingSourceEngineCatalog",
                "isSourceEngineBookRequest(bookId, collBookBean)",
                "ReaderCatalogRefreshPromotionPolicy.shouldRetainCurrentCatalog(");
        assertInOrder(readViewModel,
                "promoteCatalogAfterTierAttempt(",
                "session",
                "if (tierResult.isReady) {");
        assertInOrder(readViewModel,
                "var publicationOutcome = if (publicationAttempt != null)",
                "catalogPublicationGate.publishMainResult(",
                "if (publicationOutcome == ReaderCatalogPublicationOutcome.STALE)",
                "catalogPublicationGate.retryStaleMainResult(",
                "if (publicationOutcome == ReaderCatalogPublicationOutcome.STALE)",
                "return@launch");
        assertInOrder(readViewModel,
                "SourceContentTierPrepareResult.RETRY_LATER",
                "source_read_tier_exhausted",
                "SOURCE_ENGINE_READING_TIER_MAX_ATTEMPTS",
                "source_read_tier_deferred",
                "source_read_tier_retry");
        assertInOrder(readViewModel,
                "private suspend fun promoteCatalogAfterTierAttempt",
                "val rawCurrentChapters = collBookBean.getBookChapters().orEmpty()",
                "boundary = \"tier-current\"",
                "catalogPublicationGate.capture(catalogSession, currentChapters)",
                "boundary = \"tier-refresh\"",
                "boundary = \"tier-publish-current\"",
                "catalogPublicationGate.promoteTierResult(",
                "if (publicationOutcome == ReaderCatalogPublicationOutcome.STALE)",
                "catalogPublicationGate.retryStaleTierResult(",
                "if (publicationOutcome != ReaderCatalogPublicationOutcome.APPLIED_CHANGED)");
        assertInOrder(readViewModel,
                "val catalogPublicationOutcome = promoteCatalogAfterTierAttempt(",
                "if (tierResult.isReady)",
                "\"catalogPublication\" to catalogPublicationOutcome.name.lowercase()");

        String detailActivity = readFile("src/main/java/com/ldp/reader/ui/activity/BookDetailActivity.kt");
        assertInOrder(detailActivity,
                "BookDetailShelfAction.forPresence(isCollected)",
                "BookDetailShelfTapAction.ADD_TO_SHELF",
                "viewModel.addToBookShelf(collBook)",
                "BookDetailShelfTapAction.KEEP_ON_SHELF");
        assertTrue(!detailActivity.contains("deleteCollBookWithFiles(collBook)"));

        String bookShelfFragment = readFile("src/main/java/com/ldp/reader/ui/fragment/BookShelfFragment.kt");
        assertTrue(bookShelfFragment.contains("val removableBooks = selectedBooks.filterNot"));
        assertTrue(bookShelfFragment.contains("removableBooks.forEach { book ->"));
        assertTrue(bookShelfFragment.contains("deleteCollBookWithFiles(book)"));
        assertTrue(bookShelfFragment.contains("removeShelfItems(listOf(collBook), emptyList())"));
        assertTrue(bookShelfFragment.contains("deleteManagedLocalCopy(book, appContext.filesDir)"));
        assertTrue(!bookShelfFragment.contains("private fun deleteBook(collBook"));
        assertTrue(!bookShelfFragment.contains("deleteBookRecord(collBook._id)"));

        String detailViewModel = readFile("src/main/java/com/ldp/reader/ui/activity/BookDetailViewModel.kt");
        assertInOrder(detailViewModel,
                "val pendingAdd = shelfAddGeneration.beginAdd()",
                "BookContentProviderRouter.getBookFolder(routeId, collBookBean)",
                "if (!shelfAddGeneration.isShelfAddCurrent(pendingAdd))",
                "saveCollBookWithAsync(",
                "shouldApply = { shelfAddGeneration.isCurrent(pendingAdd) }");
    }

    @Test
    public void persistedEntitiesKeepCurrentBusinessIdsAndRelationFields() throws IOException {
        String collBook = readFile("src/main/java/com/ldp/reader/model/bean/CollBookBean.kt");
        String chapter = readFile("src/main/java/com/ldp/reader/model/bean/BookChapterBean.kt");
        String record = readFile("src/main/java/com/ldp/reader/model/bean/BookRecordBean.kt");

        assertTrue("CollBookBean should no longer be a GreenDAO entity",
                !collBook.contains("org.greenrobot.greendao.annotation"));
        assertInOrder(collBook,
                "private var idValue",
                "private var bookChapterList");
        assertInOrder(collBook,
                "bookIdInBiquge = parcel.readString()",
                "dest.writeString(bookIdInBiquge)");
        assertInOrder(collBook,
                "fun setBookChapters(beans: List<BookChapterBean>?)",
                "if (bookChapterList == null)",
                "return");

        assertTrue("BookChapterBean should no longer be a GreenDAO entity",
                !chapter.contains("org.greenrobot.greendao.annotation"));
        assertInOrder(chapter,
                "var id",
                "var bookId",
                "var start",
                "var end");

        assertTrue("BookRecordBean should no longer be a GreenDAO entity",
                !record.contains("org.greenrobot.greendao.annotation"));
        assertInOrder(record,
                "var bookId",
                "var chapter",
                "var pagePos");
    }

    @Test
    public void greenDaoBuildAndGeneratedSourcesAreRemoved() throws IOException {
        String appGradle = readFile("build.gradle");
        String rootGradle = readFile("../build.gradle");
        String gradleProperties = readFile("../gradle.properties");

        assertTrue(!appGradle.contains("org.greenrobot.greendao"));
        assertTrue(!appGradle.contains("greendao {"));
        assertTrue(!rootGradle.contains("greendao-gradle-plugin"));
        assertTrue(!gradleProperties.contains("greendaoVersion"));
        assertTrue(!new File("src/main/java/com/ldp/reader/model/gen").exists());
        assertTrue(!new File("src/main/java/com/ldp/reader/model/local/DaoDbHelper.java").exists());
    }

    private static void assertInOrder(String source, String... parts) {
        int cursor = 0;
        for (String part : parts) {
            int index = source.indexOf(part, cursor);
            assertTrue("Missing or out of order: " + part, index >= 0);
            cursor = index + part.length();
        }
    }

    private static String readFile(String path) throws IOException {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }
}
