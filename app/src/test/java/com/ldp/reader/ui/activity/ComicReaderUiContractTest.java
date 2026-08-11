package com.ldp.reader.ui.activity;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

public class ComicReaderUiContractTest {
    @Test
    public void directionSwitchRetainsDominantPageAndRefreshesCounter() throws Exception {
        String source = readFile("src/main/java/com/ldp/reader/ui/activity/ComicReadActivity.kt");

        assertTrue(source.contains("val retainedPage = dominantVisiblePosition(binding.comicReadPages)"));
        assertTrue(source.contains("applyPageOrientation(retainedPage)"));
        assertTrue(source.contains("manager.scrollToPositionWithOffset(page, 0)"));
        assertTrue(source.contains("updatePageState(page)"));
    }

    @Test
    public void detailRefreshesShelfStateAndDoesNotReAddExistingComic() throws Exception {
        String source = readFile("src/main/java/com/ldp/reader/ui/activity/ComicDetailActivity.kt");

        assertTrue(source.contains("override fun onResume()"));
        assertTrue(source.contains("readingChapter()?.let"));
        assertTrue(source.contains("if (MediaShelfStore.isAdded(this, routeId))"));
        assertTrue(source.contains("ComicReadingResume.hasSavedChapter"));
    }

    @Test
    public void catalogSelectionReusesExistingReaderInsteadOfLeavingAStaleReaderBehind() throws Exception {
        String source = readFile("src/main/java/com/ldp/reader/ui/activity/ComicReadActivity.kt");

        assertTrue(source.contains(".addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)"));
        assertTrue(source.contains("override fun onNewIntent(intent: Intent?)"));
        assertTrue(source.contains("saveCurrentPage()"));
    }

    @Test
    public void detailCatalogAndReaderUseTheSameChronologicalComicOrder() throws Exception {
        String detail = readFile("src/main/java/com/ldp/reader/ui/activity/ComicDetailActivity.kt");
        String catalog = readFile("src/main/java/com/ldp/reader/ui/activity/MediaCatalogActivity.kt");
        String reader = readFile("src/main/java/com/ldp/reader/ui/activity/ComicReadActivity.kt");

        assertTrue(detail.contains("ComicChapterOrder.resolve(sourceChapters, detail.latest)"));
        assertTrue(detail.contains("firstChapter = chapterOrder.first"));
        assertTrue(detail.contains("latestChapter = chapterOrder.latest"));
        assertTrue(catalog.contains("ComicChapterOrder.resolve(sourceChapters, detail?.latest).chronological"));
        assertTrue(reader.contains("ComicChapterOrder.resolve(sourceChapters, detail?.latest).chronological"));
        assertTrue(reader.contains("val currentPosition = currentChapterPosition() ?: return"));
    }

    @Test
    public void oneShotPageResetIsConsumedAndReaderCatalogUsesBookTitle() throws Exception {
        String reader = readFile("src/main/java/com/ldp/reader/ui/activity/ComicReadActivity.kt");

        assertTrue(reader.contains("resetPageOnOpen = intent.getBooleanExtra(EXTRA_RESET_PAGE, false)"));
        assertTrue(reader.contains("intent.removeExtra(EXTRA_RESET_PAGE)"));
        assertTrue(reader.contains("MediaCatalogActivity.start(this, route, title = \"\", selectedIndex = chapterIndex)"));
    }

    @Test
    public void readingPreviewOnlyUpdatesAnExistingShelfItem() throws Exception {
        String reader = readFile("src/main/java/com/ldp/reader/ui/activity/ComicReadActivity.kt");
        String store = readFile("src/main/java/com/ldp/reader/media/MediaShelfStore.kt");

        assertTrue(reader.contains("MediaShelfStore.updateComicChapter("));
        assertTrue(!reader.contains("MediaShelfStore.addOrUpdateForChapter("));
        assertTrue(store.contains("fun updateComicChapter(context: Context, chapterRouteId: String, title: String)"));
        assertTrue(store.contains("updateChapter(context, ReaderMediaKind.COMIC, chapterRouteId, title)"));
    }

    @Test
    public void staleCatalogResultIsValidatedBeforePublishingSharedChapters() throws Exception {
        String reader = readFile("src/main/java/com/ldp/reader/ui/activity/ComicReadActivity.kt");
        int load = reader.indexOf("private fun loadChapter()");
        int nextMethod = reader.indexOf("override fun onDestroy()", load);
        String loadChapter = reader.substring(load, nextMethod);

        int resolved = loadChapter.indexOf("val resolvedChapters =");
        int validation = loadChapter.indexOf("if (!request.isCurrent(loadToken, bookRouteId, chapterRouteId))");
        int publication = loadChapter.indexOf("chapters = resolvedChapters");
        assertTrue(resolved >= 0);
        assertTrue(validation > resolved);
        assertTrue(publication > validation);
    }

    @Test
    public void coldResumePublishesSavedChapterPagesWithoutWaitingForTheCatalog() throws Exception {
        String reader = readFile("src/main/java/com/ldp/reader/ui/activity/ComicReadActivity.kt");
        int load = reader.indexOf("private fun loadChapter()");
        int nextMethod = reader.indexOf("private fun comicPagesLoadResult", load);
        String loadChapter = reader.substring(load, nextMethod);

        int fastStart = loadChapter.indexOf("val fastPagesDeferred = async(Dispatchers.IO)");
        int catalogStart = loadChapter.indexOf("val catalogDeferred = async(Dispatchers.IO)");
        int fastAwait = loadChapter.indexOf("val fastPages = fastPagesDeferred.await()");
        int fastPublish = loadChapter.indexOf("fastPagesPublished = publishComicPages(");
        int catalogAwait = loadChapter.indexOf("val resolvedChapters = catalogDeferred.await()");

        assertTrue(fastStart >= 0);
        assertTrue(catalogStart > fastStart);
        assertTrue(fastAwait > catalogStart);
        assertTrue(fastPublish > fastAwait);
        assertTrue(catalogAwait > fastPublish);
        assertTrue(loadChapter.contains("ComicColdResumePolicy.canPublishFastPages("));
        assertTrue(loadChapter.contains("request.isCurrent(loadToken, bookRouteId, chapterRouteId)"));
    }

    @Test
    public void readerModesAreSavedRestoredAndKeptAcrossNewIntents() throws Exception {
        String reader = readFile("src/main/java/com/ldp/reader/ui/activity/ComicReadActivity.kt");

        assertTrue(reader.contains("ComicReaderModePreferences.loadOnline(defaultLandscape = false)"));
        assertTrue(reader.contains("savedInstanceState.getBoolean("));
        assertTrue(reader.contains("outState.putBoolean(STATE_HORIZONTAL_MODE, horizontalMode)"));
        assertTrue(reader.contains("outState.putBoolean(STATE_LANDSCAPE_MODE, landscapeMode)"));
        assertTrue(reader.contains("outState.putInt(STATE_BRIGHTNESS_PERCENT, brightnessPercent)"));
        assertTrue(reader.contains("outState.putBoolean(STATE_BRIGHTNESS_OVERRIDDEN, brightnessOverridden)"));
        assertTrue(reader.contains("ComicReaderModePreferences.saveOnline("));
        assertTrue(reader.contains("applyReaderModeState()"));
        assertTrue(reader.contains("binding.comicReadLandscape.isSelected = landscapeMode"));

        int newIntent = reader.indexOf("override fun onNewIntent(intent: Intent?)");
        int nextMethod = reader.indexOf("override fun onSaveInstanceState", newIntent);
        String newIntentBody = reader.substring(newIntent, nextMethod);
        assertTrue(!newIntentBody.contains("horizontalMode = false"));
        assertTrue(!newIntentBody.contains("nightMode = false"));
        assertTrue(!newIntentBody.contains("eyeProtectionEnabled = false"));
        assertTrue(!newIntentBody.contains("landscapeMode = false"));
    }

    @Test
    public void comicReaderKeepsSystemBrightnessUntilARealUserAdjustment() throws Exception {
        String reader = readFile("src/main/java/com/ldp/reader/ui/activity/ComicReadActivity.kt");

        assertTrue(reader.contains("if (!fromUser) return"));
        assertTrue(reader.contains("brightnessOverridden = true"));
        assertTrue(reader.contains("WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE"));
        assertTrue(reader.contains("STATE_BRIGHTNESS_OVERRIDDEN"));
    }

    @Test
    public void comicDetailFailureOverlayHasAnExplicitRetryAction() throws Exception {
        String detail = readFile("src/main/java/com/ldp/reader/ui/activity/ComicDetailActivity.kt");
        String layout = readFile("src/main/res/layout/activity_comic_detail.xml");

        assertTrue(layout.contains("android:id=\"@+id/comic_detail_loading_retry\""));
        assertTrue(layout.contains("android:text=\"重新加载\""));
        assertTrue(detail.contains("binding.comicDetailLoadingRetry.setOnClickListener { loadDetail() }"));
        assertTrue(detail.contains("binding.comicDetailLoadingRetry.visibility = View.GONE"));
        assertTrue(detail.contains("binding.comicDetailLoadingRetry.visibility = View.VISIBLE"));
    }

    @Test
    public void chapterAndImageFailuresExposeAClearClickableRetry() throws Exception {
        String reader = readFile("src/main/java/com/ldp/reader/ui/activity/ComicReadActivity.kt");
        String layout = readFile("src/main/res/layout/activity_comic_read.xml");

        assertTrue(layout.contains("android:id=\"@+id/comic_read_retry\""));
        assertTrue(layout.contains("android:clickable=\"true\""));
        assertTrue(reader.contains("binding.comicReadRetry.setOnClickListener"));
        assertTrue(reader.contains("comicReaderRetryState("));
        assertTrue(reader.contains("renderComicRetryState("));
    }

    @Test
    public void reverseOrderedSourceUsesCanonicalTitleAndChronologicalPositionInReaderMetadata() throws Exception {
        String reader = readFile("src/main/java/com/ldp/reader/ui/activity/ComicReadActivity.kt");

        int ordered = reader.indexOf("ComicChapterOrder.resolve(sourceChapters, detail?.latest).chronological");
        int presented = reader.indexOf("comicReaderChapterPresentation(", ordered);
        assertTrue(ordered >= 0);
        assertTrue(presented > ordered);
        assertTrue(reader.contains("binding.comicReadTitle.text = chapterPresentation.title"));
        assertTrue(reader.contains("chapterPresentation.pageStatus("));
    }

    @Test
    public void loadingAndRetryNeverPersistAPlaceholderFirstPage() throws Exception {
        String reader = readFile("src/main/java/com/ldp/reader/ui/activity/ComicReadActivity.kt");
        int load = reader.indexOf("private fun loadChapter()");
        int destroy = reader.indexOf("override fun onDestroy()", load);
        String loadChapter = reader.substring(load, destroy);
        int save = reader.indexOf("private fun saveCurrentPage()");
        int schedule = reader.indexOf("private fun scheduleComicProgressSave", save);
        String saveCurrentPage = reader.substring(save, schedule);

        assertTrue(loadChapter.contains("ComicReaderProgressPolicy.loadingVisiblePage("));
        assertTrue(loadChapter.contains("ComicReaderProgressPolicy.pageAfterSuccessfulRender("));
        assertTrue(loadChapter.contains("resetRequested = resetPageOnOpen"));
        assertTrue(reader.contains("ComicReaderProgressPolicy.confirmRenderedPage("));
        assertTrue(saveCurrentPage.contains("ComicReaderProgressPolicy.pageToPersist("));
        assertFalse(saveCurrentPage.contains("dominantVisiblePosition("));
    }

    @Test
    public void sameUrlRetryInvalidatesOldGlideCallbacksAndDelayedRetries() throws Exception {
        String reader = readFile("src/main/java/com/ldp/reader/ui/activity/ComicReadActivity.kt");
        String adapter = readFile("src/main/java/com/ldp/reader/ui/adapter/ComicPageAdapter.kt");
        String holder = readFile("src/main/java/com/ldp/reader/ui/adapter/view/ComicPageHolder.kt");

        assertTrue(reader.contains("pageAdapter.refreshItemsForGeneration(request.token, emptyList())"));
        assertTrue(reader.contains("isCurrentPageRequest(generation, position, request)"));
        assertTrue(reader.contains("requestGeneration = generation"));
        assertTrue(reader.contains("currentGeneration = loadToken"));
        assertTrue(reader.contains("pageAdapter.cancelPendingRetries()"));
        assertTrue(adapter.contains("fun refreshItemsForGeneration(generation: Int"));
        assertTrue(adapter.contains("pageHolders.toList().forEach(ComicPageHolder::cancelPendingRetries)"));
        assertTrue(holder.contains("pendingRetry?.let(image::removeCallbacks)"));
        assertTrue(holder.contains("Glide.with(image).clear(image)"));
        assertTrue(holder.contains("generationProvider() == generation"));
        assertTrue(holder.contains("requestKey(generation, pos, layoutMode)"));
        assertTrue(holder.contains("${layoutMode.name}"));
    }

    private static String readFile(String path) throws Exception {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }
}
