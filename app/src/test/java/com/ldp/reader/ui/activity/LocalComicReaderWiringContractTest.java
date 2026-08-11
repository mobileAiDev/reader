package com.ldp.reader.ui.activity;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

public class LocalComicReaderWiringContractTest {

    @Test
    public void horizontalModeSnapsToOnePageAndVerticalModeDetachesWhileRetainingThePage()
            throws Exception {
        String reader = readFile("src/main/java/com/ldp/reader/ui/activity/LocalComicReadActivity.kt");

        assertTrue(reader.contains("import androidx.recyclerview.widget.PagerSnapHelper"));
        assertTrue(reader.contains("private val horizontalPageSnapHelper = PagerSnapHelper()"));
        assertTrue(reader.contains(
            "horizontalPageSnapHelper.attachToRecyclerView(\n" +
                "            binding.localComicReadPages.takeIf { horizontalMode }\n" +
                "        )"
        ));
        assertTrue(reader.contains("val retainedPage = dominantVisiblePage(binding.localComicReadPages)"));
        assertTrue(reader.contains("applyPageOrientation(retainedPage)"));
        assertTrue(reader.contains("manager.scrollToPositionWithOffset(page, 0)"));
    }

    @Test
    public void imageDecodeResultsAreGenerationBoundRetryableAndGateProgressPersistence()
            throws Exception {
        String reader = readFile("src/main/java/com/ldp/reader/ui/activity/LocalComicReadActivity.kt");
        String layout = readFile("src/main/res/layout/activity_local_comic_read.xml");

        assertTrue(reader.contains(
            "pageAdapter.onPageLoadResult = pageLoad@{ generation, position, request, success, detail ->"
        ));
        assertTrue(reader.contains("val updatedState = pageLoadState.record("));
        assertTrue(reader.contains("if (updatedState === pageLoadState) return@pageLoad"));
        assertTrue(reader.contains("pageLoadState = updatedState"));
        assertTrue(reader.contains("requestGeneration = generation"));
        assertTrue(reader.contains("currentGeneration = loadGeneration"));
        assertTrue(reader.contains("currentItems = pageAdapter.items"));
        assertTrue(reader.contains("pageAdapter.refreshItemsForGeneration(generation, pages)"));
        assertTrue(reader.contains("binding.localComicReadPageRetry.setOnClickListener"));
        assertTrue(reader.contains("private fun retryPageLoads()"));
        assertTrue(reader.contains(
            "if (!pageLoadState.canPersist(pageIndex, documentPageCount)) return"
        ));
        assertTrue(layout.contains("android:id=\"@+id/local_comic_read_page_retry\""));
        assertTrue(layout.contains("android:layout_height=\"48dp\""));
    }

    @Test
    public void directionChangesPropagatePageLayoutModeAndRebindVisiblePages()
            throws Exception {
        String reader = readFile("src/main/java/com/ldp/reader/ui/activity/LocalComicReadActivity.kt");
        String adapter = readFile("src/main/java/com/ldp/reader/ui/adapter/ComicPageAdapter.kt");
        String holder = readFile("src/main/java/com/ldp/reader/ui/adapter/view/ComicPageHolder.kt");

        assertTrue(reader.contains(
            "pageAdapter.updatePageLayoutMode(localComicPageLayoutMode(horizontalMode))"
        ));
        assertTrue(adapter.contains("fun updatePageLayoutMode(mode: ComicPageLayoutMode)"));
        assertTrue(adapter.contains("if (pageLayoutMode == mode) return"));
        assertTrue(adapter.contains("notifyItemRangeChanged(0, itemCount)"));
        assertTrue(adapter.contains("layoutModeProvider = { pageLayoutMode }"));
        assertTrue(holder.contains("val layoutMode = layoutModeProvider()"));
        assertTrue(holder.contains("applyPageLayout(comicPageLayoutSpec(layoutMode))"));
        assertTrue(holder.contains("requestKey(generation, pos, layoutMode)"));
    }

    private static String readFile(String relativePath) throws Exception {
        return new String(
            Files.readAllBytes(new File(relativePath).toPath()),
            StandardCharsets.UTF_8
        );
    }
}
