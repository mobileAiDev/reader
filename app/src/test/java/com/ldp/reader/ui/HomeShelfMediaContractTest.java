package com.ldp.reader.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class HomeShelfMediaContractTest {
    @Test
    public void mediaItemsUseTheSameShelfFiltersAndBatchEditingFlow() throws IOException {
        String fragment = readFile("src/main/java/com/ldp/reader/ui/fragment/BookShelfFragment.kt");
        String adapter = readFile("src/main/java/com/ldp/reader/ui/adapter/HomeShelfAdapter.kt");
        String itemLayout = readFile("src/main/res/layout/item_home_shelf_media.xml");

        assertTrue(fragment.contains("filterMediaShelfItems(mediaShelfItems(), nowMillis)"));
        assertTrue(fragment.contains("MediaShelfFilterPolicy.matches("));
        assertTrue(fragment.contains("mCollBookAdapter!!.toggleMediaSelection(item)"));
        assertTrue(fragment.contains("mCollBookAdapter?.selectMediaItem(item)"));
        assertTrue(fragment.contains("val selectedMediaItems = mCollBookAdapter!!.selectedMediaItems"));
        assertTrue(fragment.contains("MediaShelfStore.remove(appContext, item.id)"));
        assertTrue(fragment.contains("mCollBookAdapter?.removeMediaItems(result.removedMediaItems)"));

        assertTrue(adapter.contains("private val selectedMediaIds"));
        assertTrue(adapter.contains("val selectedMediaItems"));
        assertTrue(adapter.contains("is HomeShelfItem.Media -> selectedMediaIds.contains(item.item.id)"));
        assertTrue(adapter.contains("fun toggleMediaSelection"));
        assertTrue(adapter.contains("fun removeMediaItems"));

        assertTrue(itemLayout.contains("home_shelf_media_selected"));
        assertTrue(itemLayout.contains("@drawable/selector_bookshelf_edit_check"));
        assertFalse(itemLayout.contains("home_shelf_media_edit_mask"));
        assertFalse(itemLayout.contains("#66000000"));
    }

    @Test
    public void comicProgressAndMiniPlayerInsetRemainVisible() throws IOException {
        String fragment = readFile("src/main/java/com/ldp/reader/ui/fragment/BookShelfFragment.kt");
        String adapter = readFile("src/main/java/com/ldp/reader/ui/adapter/HomeShelfAdapter.kt");

        assertTrue(adapter.contains("HomeShelfMediaLabels.comicProgressText("));
        assertTrue(fragment.contains("AUDIO_FLOAT_LIST_BOTTOM_INSET_DP"));
        assertTrue(fragment.contains("updateShelfListBottomInset(showAudioFloat)"));
        assertTrue(fragment.contains("recyclerView.setPadding("));
    }

    @Test
    public void homeMiniPlayerRefreshesOnlyDuringTheVisibleLifecycle() throws IOException {
        String fragment = readFile("src/main/java/com/ldp/reader/ui/fragment/BookShelfFragment.kt");

        assertTrue(fragment.contains("audioFloatHandler.post(audioFloatTicker)"));
        assertTrue(fragment.contains("audioFloatHandler.removeCallbacks(audioFloatTicker)"));
        assertTrue(fragment.contains("AUDIO_FLOAT_REFRESH_MS"));
        assertTrue(fragment.contains("lastHomeAudioCoverUrl"));
    }

    @Test
    public void removalsReapplyTheActiveFilterEmptyState() throws IOException {
        String fragment = readFile("src/main/java/com/ldp/reader/ui/fragment/BookShelfFragment.kt");

        int batch = fragment.indexOf("private fun confirmRemoveSelectedShelfItems()");
        int nextMethod = fragment.indexOf("private fun updateEditUi()", batch);
        String batchBody = fragment.substring(batch, nextMethod);
        assertTrue(batchBody.contains("refreshShelfDisplay(BookRepository.getInstance().collBooks)"));
    }

    @Test
    public void shelfRemovalOffersIReaderLocalFileDeletionWithoutSilentlyClaimingFailure() throws IOException {
        String fragment = readFile("src/main/java/com/ldp/reader/ui/fragment/BookShelfFragment.kt");
        String deletion = readFile("src/main/java/com/ldp/reader/ui/fragment/BookshelfLocalFileDeleter.kt");
        String strings = readFile("src/main/res/values/strings.xml");

        assertTrue(fragment.contains("bookshelfRemoveDeleteLocalFile.visibility"));
        assertTrue(fragment.contains("sheetBinding.bookshelfRemoveDeleteLocalFile.isChecked"));
        assertTrue(fragment.contains("BookshelfLocalFileDeleter"));
        assertTrue(fragment.contains("BookshelfLocalFileRemovalExecutor"));
        assertTrue(deletion.contains("BookshelfLocalFileDeleteOutcome.FAILED"));
        assertTrue(deletion.contains("sourceReferences.clear(entry.bookId)"));
        assertTrue(fragment.contains("home_bookshelf_remove_file_failed"));
        assertTrue(fragment.contains("deleteManagedLocalCopy(book, appContext.filesDir)"));
        assertTrue(strings.contains("同时删除本地文件"));
        assertTrue(strings.contains("本地文件删除失败"));
        assertFalse(fragment.contains("DialogDeleteBinding"));
        assertFalse(fragment.contains("同时永久删除手机源文件"));
        assertFalse(fragment.contains("File(collBook.cover)\n                        if (file.exists()) file.delete()"));
    }

    @Test
    public void missingMediaRouteUsesAUserFacingRetryMessage() throws IOException {
        String fragment = readFile("src/main/java/com/ldp/reader/ui/fragment/BookShelfFragment.kt");

        assertTrue(fragment.contains("这部作品暂时无法打开，请稍后重试"));
        assertFalse(fragment.contains("ToastUtils.show(\"目录未准备好\")"));
    }

    private static String readFile(String path) throws IOException {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }
}
