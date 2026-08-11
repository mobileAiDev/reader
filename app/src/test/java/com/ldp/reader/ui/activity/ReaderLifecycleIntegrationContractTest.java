package com.ldp.reader.ui.activity;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

public class ReaderLifecycleIntegrationContractTest {
    @Test
    public void everyReaderUsesTheSharedForegroundSessionTracker() throws IOException {
        assertReaderTracksForeground("ReadActivity.kt", "mCollBook?._id");
        assertReaderTracksForeground("EpubReadActivity.kt", "\"local-document:epub:$uri\"");
        assertReaderTracksForeground("PdfReadActivity.kt", "\"local-document:pdf:$uri\"");
        assertReaderTracksForeground("LocalComicReadActivity.kt", "\"local-document:comic:$uri\"");
        assertReaderTracksForeground("ComicReadActivity.kt", "readingSessionIdentity()");
    }

    @Test
    public void pagerOnlyResumesTheVisibleHomeTab() throws IOException {
        String main = readFile("src/main/java/com/ldp/reader/ui/activity/MainActivity.kt");
        assertTrue(main.contains("FragmentPagerAdapter.BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT"));
        assertTrue(main.contains("override fun instantiateItem(container: ViewGroup, position: Int): Any"));
    }

    @Test
    public void shelfRemovalMatchesIReaderConfirmationHierarchyAndCopy() throws IOException {
        String shelf = readFile("src/main/java/com/ldp/reader/ui/fragment/BookShelfFragment.kt");
        String strings = readFile("src/main/res/values/strings.xml");
        String sheet = readFile("src/main/res/layout/dialog_bookshelf_remove_sheet.xml");
        String styles = readFile("src/main/res/values/styles.xml");
        int confirmationStart = shelf.indexOf("private fun confirmRemoveSelectedShelfItems()");
        int methodEnd = shelf.indexOf("private fun updateEditUi()", confirmationStart);
        assertTrue(confirmationStart >= 0);
        assertTrue(methodEnd > confirmationStart);
        String body = shelf.substring(confirmationStart, methodEnd);

        assertTrue(body.contains("R.style.ReaderBookshelfRemoveBottomSheetDialog"));
        assertTrue(styles.contains("<style name=\"ReaderBookshelfRemoveBottomSheet\""));
        assertTrue(styles.contains("<item name=\"backgroundTint\">@android:color/transparent</item>"));
        assertTrue(body.contains("DialogBookshelfRemoveSheetBinding.inflate(layoutInflater)"));
        assertTrue(body.contains("val hasLocalBooks = selectedBooks.any { it.isLocal() }"));
        assertTrue(body.contains("bookshelfRemoveDeleteLocalFile.visibility"));
        assertTrue(body.contains("if (hasLocalBooks) View.VISIBLE else View.GONE"));
        assertTrue(body.contains("sheetBinding.bookshelfRemoveDeleteLocalFile.isChecked"));
        assertTrue(body.contains("bookshelfRemoveConfirm.setOnClickListener"));
        assertTrue(body.contains("deleteLocalFiles ="));
        assertTrue(body.contains("bookshelfRemoveCancel.setOnClickListener"));
        assertTrue(body.contains("deleteCollBookWithFiles(book)"));
        assertTrue(body.contains("encodingOverrideClearer = LocalTxtEncodingPreferences::clear"));
        assertTrue(body.contains("synBook()"));
        int cancelStart = body.indexOf("bookshelfRemoveCancel.setOnClickListener");
        int confirmStart = body.indexOf("bookshelfRemoveConfirm.setOnClickListener", cancelStart);
        assertTrue(cancelStart >= 0);
        assertTrue(confirmStart > cancelStart);
        String cancelBody = body.substring(cancelStart, confirmStart);
        assertTrue(cancelBody.contains("dialog.dismiss()"));
        assertFalse(cancelBody.contains("removeShelfItems("));
        assertFalse(cancelBody.contains("LocalTxtEncodingPreferences"));
        assertTrue(strings.contains("确认删除"));
        assertTrue(strings.contains("书籍将从书架删除"));
        assertTrue(strings.contains("同时删除本地文件"));
        assertTrue(strings.contains(">确认<"));
        assertFalse(strings.contains("确认移出"));
        assertFalse(strings.contains("手机原文件会保留"));
        assertFalse(strings.contains("本机阅读记录和应用缓存会一并清理"));
        assertTrue(sheet.contains("bookshelf_remove_title"));
        assertTrue(sheet.contains("bookshelf_remove_message"));
        assertTrue(sheet.contains("bookshelf_remove_delete_local_file"));
        assertTrue(sheet.contains("bookshelf_remove_confirm"));
        assertTrue(sheet.contains("bookshelf_remove_cancel"));
        assertTrue(sheet.contains("@drawable/bg_bookshelf_remove_confirm"));
        assertTrue(sheet.contains("@drawable/bg_bookshelf_remove_cancel"));
        assertFalse(sheet.contains("@color/home_line"));
    }

    private static void assertReaderTracksForeground(String fileName, String identity)
        throws IOException {
        String reader = readFile("src/main/java/com/ldp/reader/ui/activity/" + fileName);
        assertTrue(fileName, reader.contains("ReadingSessionTracker"));
        assertTrue(fileName, reader.contains("readingSessionTracker.onResume(" + identity + ")"));
        assertTrue(fileName, reader.contains("readingSessionTracker.onPause()"));
        assertTrue(fileName, reader.contains("readingSessionTracker.onStop()"));
    }

    private static String readFile(String path) throws IOException {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }
}
