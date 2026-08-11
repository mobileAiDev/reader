package com.ldp.reader.ui.activity;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class MediaCatalogActivityContractTest {
    @Test
    public void catalogStateAndOrderResourcesAreWiredToRetrySafeStates() throws IOException {
        String layout = readFile("src/main/res/layout/activity_media_catalog.xml");
        String activity = readActivity();

        assertTrue(layout.contains("@+id/media_catalog_state"));
        assertTrue(layout.contains("@+id/media_catalog_order"));
        assertTrue(activity.contains("mediaCatalogState.setOnClickListener"));
        assertTrue(activity.contains(
                "if (binding.mediaCatalogState.isClickable) {\n"
                        + "                loadCatalog()"));
        assertTrue(activity.contains("applyCatalogState(MediaCatalogStatePolicy.loading())"));
        assertTrue(activity.contains("if (!binding.mediaCatalogOrder.isEnabled || chapters.isEmpty())"));
        assertTrue(activity.contains("binding.mediaCatalogState.isClickable = state.retryEnabled"));
        assertTrue(activity.contains("binding.mediaCatalogOrder.isEnabled = state.orderEnabled"));
    }

    @Test
    public void catalogSelectionFollowsShelfProgressAndTheLastClickedChapter() throws IOException {
        String activity = readActivity();

        assertTrue(activity.contains("MediaShelfStore.restoreForBook(this, routeId)"));
        assertTrue(activity.contains("val shelfItem = MediaShelfStore.itemForBook(this, routeId)"));
        assertTrue(activity.contains("ComicChapterRouteResolver.resolve("));
        assertTrue(activity.contains("savedRouteId = comicShelfItem.currentChapterRouteId"));
        assertTrue(activity.contains("savedTitle = comicShelfItem.currentChapterTitle"));
        assertTrue(activity.contains("savedSourceIndex = comicShelfItem.currentChapterIndex"));
        assertTrue(activity.contains("shelfItem?.currentChapterIndex ?: -1"));
        assertTrue(activity.contains("selectedIndex = if (comicShelfResolutionAvailable)"));
        assertTrue(activity.contains("override fun onResume()"));
        assertTrue(activity.contains("if (refreshSelectionFromShelf())"));
        assertTrue(activity.contains("selectedIndex = chapter.index"));
        assertTrue(activity.contains("chapterAdapter.selectedIndex = selectedIndex"));
        assertTrue(activity.contains("scrollToSelectedChapter()"));
    }

    @Test
    public void audioCatalogUsesTheSameChronologicalOrderAndDisplayPositionsAsPlayback() throws IOException {
        String activity = readActivity();
        String detail = readFile("src/main/java/com/ldp/reader/ui/activity/AudioDetailActivity.kt");
        String player = readFile("src/main/java/com/ldp/reader/ui/activity/AudioPlayerActivity.kt");
        String navigation = readFile("src/main/java/com/ldp/reader/audio/AudioPlaybackTransitionPolicy.kt");
        String autoAdvance = readFile("src/main/java/com/ldp/reader/audio/AudioAutoAdvance.kt");

        assertTrue(activity.contains("ReaderMediaKind.AUDIO -> AudioChapterOrder.chronological(sourceChapters)"));
        assertTrue(activity.contains("chapterAdapter.useChronologicalDisplayOrder(chapters)"));
        assertTrue(detail.contains("episodes = AudioChapterOrder.chronological(result.episodes)"));
        assertTrue(detail.contains("episodeAdapter.useChronologicalDisplayOrder(episodes)"));
        assertTrue(player.contains("episodes = AudioChapterOrder.chronological("));
        assertTrue(player.contains("episodeAdapter.useChronologicalDisplayOrder(episodes)"));
        assertTrue(navigation.contains("AudioChapterOrder.chronological(episodes)"));
        assertTrue(autoAdvance.contains("AudioChapterOrder.chronologicalSnapshots(chapters)"));
    }

    @Test
    public void comicCatalogAndDetailNumberTheNormalizedOrderWithoutReplacingSourceIdentity() throws IOException {
        String activity = readActivity();
        String detail = readFile("src/main/java/com/ldp/reader/ui/activity/ComicDetailActivity.kt");
        String adapter = readFile("src/main/java/com/ldp/reader/ui/adapter/MediaChapterAdapter.kt");

        assertTrue(activity.contains("ReaderMediaKind.COMIC -> ComicChapterOrder.resolve(sourceChapters, detail?.latest).chronological"));
        assertTrue(activity.contains("ReaderMediaKind.COMIC -> chapterAdapter.useChronologicalDisplayOrder(chapters)"));
        assertTrue(detail.contains("chapters = chapterOrder.chronological"));
        assertTrue(detail.contains("chapterAdapter.useChronologicalDisplayOrder(chapters)"));
        assertTrue(adapter.contains("displayOrdinalProvider = { chapter -> displayOrdinals.forChapter(chapter) }"));
    }

    @Test
    public void orderSelectionAndRouteAnchoredScrollSurviveConfigurationRecreation() throws IOException {
        String activity = readActivity();

        assertTrue(activity.contains("descending = savedInstanceState?.getBoolean(STATE_DESCENDING, false)"));
        assertTrue(activity.contains("selectedIndex = savedInstanceState.getInt(STATE_SELECTED_INDEX, selectedIndex)"));
        assertTrue(activity.contains("override fun onSaveInstanceState(outState: Bundle)"));
        assertTrue(activity.contains("outState.putBoolean(STATE_DESCENDING, descending)"));
        assertTrue(activity.contains("outState.putInt(STATE_SELECTED_INDEX, selectedIndex)"));
        assertTrue(activity.contains("outState.putString(STATE_SCROLL_CHAPTER_ROUTE_ID, scrollChapterRouteId)"));
        assertTrue(activity.contains(".ifBlank { pendingScrollChapterRouteId }"));
        assertTrue(activity.contains("manager.scrollToPositionWithOffset(visibleIndex, pendingScrollOffset)"));
        assertTrue(activity.contains("if (!restorePendingScrollPosition())"));
    }

    private static String readActivity() throws IOException {
        return readFile("src/main/java/com/ldp/reader/ui/activity/MediaCatalogActivity.kt");
    }

    private static String readFile(String path) throws IOException {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }
}
