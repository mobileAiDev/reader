package com.ldp.reader.sourceengine;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

public class SourceEngineIsolationContractTest {

    @Test
    public void sourceEngineModuleIsAVisibleButExplicitAppEntry() throws Exception {
        String appGradle = readFile("build.gradle");

        assertTrue(appGradle.contains("implementation project(':source-engine')"));
        assertFalse(appGradle.contains("debugImplementation project(':source-engine')"));
        assertFalse(appGradle.contains("releaseImplementation project(':source-engine')"));
    }

    @Test
    public void sourceEngineEntryIsRegisteredInMainManifestAndHomePage() throws Exception {
        String mainManifest = readFile("src/main/AndroidManifest.xml");
        String mainActivity = readFile("src/main/java/com/ldp/reader/ui/activity/SourceEngineActivity.kt");
        String mainLayout = readFile("src/main/res/layout/activity_source_engine.xml");
        String bookShelfFragment = readFile("src/main/java/com/ldp/reader/ui/fragment/BookShelfFragment.kt");
        String bookShelfLayout = readFile("src/main/res/layout/fragment_bookshelf.xml");

        assertTrue(mainManifest.contains(".ui.activity.SourceEngineActivity"));
        assertTrue(mainManifest.contains("android:exported=\"false\""));
        assertTrue(mainManifest.contains("io.legado.READ_WRITE"));
        assertTrue(mainActivity.contains("source-engine"));
        assertTrue(mainActivity.contains("book-sources.json"));
        assertTrue(mainActivity.contains("getCanonicalChapterList"));
        assertTrue(mainActivity.contains("getCleanContent"));
        assertTrue(mainActivity.contains("当前阅读链路：书源引擎"));
        assertFalse(mainActivity.contains("回到后端"));
        assertTrue(mainLayout.contains("source_engine_storage_import_button"));
        assertFalse(mainLayout.contains("source_engine_enable_reader_button"));
        assertFalse(mainLayout.contains("source_engine_disable_reader_button"));
        assertTrue(mainLayout.contains("source_engine_storage_path"));
        assertTrue(bookShelfLayout.contains("home_bookshelf_source_engine"));
        assertTrue(bookShelfLayout.contains("android:text=\"书源\""));
        assertTrue(bookShelfFragment.contains("SourceEngineActivity.start(requireContext())"));
    }

    @Test
    public void readerFlowUsesSwitchableProviderWithBackendRollback() throws Exception {
        String readViewModel = readFile("src/main/java/com/ldp/reader/ui/activity/ReadViewModel.kt");
        String searchViewModel = readFile("src/main/java/com/ldp/reader/ui/activity/SearchViewModel.kt");
        String detailViewModel = readFile("src/main/java/com/ldp/reader/ui/activity/BookDetailViewModel.kt");
        String router = readFile("src/main/java/com/ldp/reader/source/BookContentProviderRouter.kt");
        String backend = readFile("src/main/java/com/ldp/reader/source/BackendReaderContentProvider.kt");
        String sourceEngine = readFile("src/main/java/com/ldp/reader/source/SourceEngineReaderContentProvider.kt");
        String tailLocator = readFile("src/main/java/com/ldp/reader/source/CatalogTailBoundaryLocator.kt");
        String tailLocatorTest = readFile("src/test/java/com/ldp/reader/source/CatalogTailBoundaryLocatorTest.kt");
        String sourceSwitch = readFile("src/main/java/com/ldp/reader/source/SourceEngineSwitch.kt");
        String route = readFile("src/main/java/com/ldp/reader/source/SourceEngineBookRoute.kt");
        String readActivity = readFile("src/main/java/com/ldp/reader/ui/activity/ReadActivity.kt");
        String searchActivity = readFile("src/main/java/com/ldp/reader/ui/activity/SearchActivity.kt");
        String netPageLoader = readFile("src/main/java/com/ldp/reader/widget/page/NetPageLoader.kt");
        String pageLoader = readFile("src/main/java/com/ldp/reader/widget/page/PageLoader.kt");

        assertTrue(readViewModel.contains("BookContentProviderRouter.getBookFolder"));
        assertTrue(readViewModel.contains("BookContentProviderRouter.getBookContent"));
        assertTrue(searchViewModel.contains("BookContentProviderRouter.searchHotWords"));
        assertTrue(searchViewModel.contains("BookContentProviderRouter.searchKeyWords"));
        assertTrue(searchViewModel.contains("BookContentProviderRouter.searchBooks"));
        assertTrue(detailViewModel.contains("BookContentProviderRouter.getBookInfo"));
        assertTrue(detailViewModel.contains("BookContentProviderRouter.getBookFolder"));
        assertTrue(router.contains("backendProvider"));
        assertTrue(router.contains("sourceEngineProvider"));
        assertTrue(router.contains("SourceEngineSwitch.isEnabled()"));
        assertTrue(router.contains("Log.i(TAG"));
        assertTrue(backend.contains("RemoteRepository.getInstance()"));
        assertTrue(backend.contains("getHotWords()"));
        assertTrue(backend.contains("getKeyWords(query)"));
        assertTrue(backend.contains("getSearchResult(query)"));
        assertTrue(backend.contains("getBookFolder(bookId)"));
        assertTrue(backend.contains("getBookContent"));
        assertTrue(sourceEngine.contains("LegadoSourceEngine"));
        assertTrue(sourceEngine.contains("searchHotWords()"));
        assertTrue(sourceEngine.contains("searchKeyWords(query: String?)"));
        assertTrue(sourceEngine.contains("BookSearchRanker"));
        assertTrue(sourceEngine.contains("titleAliasQueries(keyword)"));
        assertTrue(sourceEngine.contains("KNOWN_TITLE_ALIAS_SEARCHES"));
        assertTrue(sourceEngine.contains("\"灵源仙路\" to listOf("));
        assertTrue(sourceEngine.contains("\"灵源仙途：我养的灵兽太懂感恩了\""));
        assertTrue(sourceEngine.contains("getCanonicalChapterList"));
        assertTrue(sourceEngine.contains("getCleanContent"));
        assertTrue(sourceEngine.contains("coherenceScore"));
        assertTrue(sourceEngine.contains("findReadableContentFallback"));
        assertTrue(sourceEngine.contains("MAX_CONTENT_FALLBACK_CANDIDATES"));
        assertTrue(sourceEngine.contains("contentFallbackResolved"));
        assertTrue(sourceEngine.contains("CatalogTailBoundaryLocator"));
        assertTrue(sourceEngine.contains("locateCatalogTailBoundary"));
        assertTrue(sourceEngine.contains("MAX_CATALOG_TAIL_BACKTRACK_CHAPTERS"));
        assertTrue(tailLocator.contains("exponential-binary"));
        assertTrue(tailLocator.contains("exponential-no-readable-anchor"));
        assertTrue(tailLocatorTest.contains("locatesDozensOfBadTailChaptersWithoutLinearScan"));
        assertTrue(tailLocatorTest.contains("locatesHundredsOfBadTailChaptersWithoutLinearScan"));
        assertFalse(sourceEngine.contains("collBookBean == null"));
        assertTrue(sourceSwitch.contains("return true"));
        assertTrue(route.contains("source_engine_book_"));
        assertTrue(route.contains("source_engine_chapter_"));
        assertTrue(readActivity.contains("adapter.count == 0"));
        assertTrue(readActivity.contains("chapterPos !in 0 until adapter.count"));
        assertTrue(searchActivity.contains("setSearchPanelsVisible(false)"));
        assertTrue(searchActivity.contains("binding?.searchHotPanel?.visibility"));
        assertTrue(readViewModel.contains("collBookBean.lastChapter = bookChapterBeans.lastOrNull()?.title"));
        assertTrue(netPageLoader.contains("requestOrder.add(mCurChapterPos)"));
        assertTrue(netPageLoader.contains("当前阅读章节优先"));
        assertTrue(pageLoader.contains("clampCurrentChapterToAvailableCatalog"));
        assertTrue(pageLoader.contains("chapterPositionClamped"));
    }

    @Test
    public void contentBelongingCheckCatchesForeignTailAndRemainsReplaceable() throws Exception {
        String cleaner = readSourceEngineFile("content/ContentCleaner.kt");
        String checker = readSourceEngineFile("content/DeterministicContentBelongingChecker.kt");
        String contract = readSourceEngineFile("content/ContentBelongingChecker.kt");
        String test = readSourceEngineTestFile("content/ContentCleanerTest.kt");

        assertTrue(contract.contains("interface ContentBelongingChecker"));
        assertTrue(contract.contains("referenceContents: List<String> = emptyList()"));
        assertTrue(cleaner.contains("belongingChecker: ContentBelongingChecker"));
        assertTrue(cleaner.contains("referenceContents = referenceContents"));
        assertTrue(checker.contains("fragmented-tail-after-valid-prefix"));
        assertTrue(checker.contains("cross-source-tail-divergence"));
        assertTrue(checker.contains("foreign-domain-tail-marker"));
        assertTrue(test.contains("detectsFragmentedForeignTailWithoutEmbeddedChapterHeading"));
        assertTrue(test.contains("keepsCoherentXianxiaTailWithManyNamesAndSceneChanges"));
        assertTrue(test.contains("detectsReferenceTailDivergenceAfterMatchingPrefix"));
        assertTrue(test.contains("contentBelongingCheckerCanBeReplacedByModelBackedImplementation"));
    }

    private static String readFile(String relativePath) throws Exception {
        return new String(
                Files.readAllBytes(new File(relativePath).toPath()),
                StandardCharsets.UTF_8
        );
    }

    private static String readSourceEngineFile(String relativePath) throws Exception {
        return new String(
                Files.readAllBytes(new File("../source-engine/src/main/kotlin/com/ldp/reader/sourceengine/" + relativePath).toPath()),
                StandardCharsets.UTF_8
        );
    }

    private static String readSourceEngineTestFile(String relativePath) throws Exception {
        return new String(
                Files.readAllBytes(new File("../source-engine/src/test/kotlin/com/ldp/reader/sourceengine/" + relativePath).toPath()),
                StandardCharsets.UTF_8
        );
    }
}
