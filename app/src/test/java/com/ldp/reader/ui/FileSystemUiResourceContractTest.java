package com.ldp.reader.ui;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.ldp.reader.R;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class FileSystemUiResourceContractTest {

    @Test
    public void fileSystemPageKeepsReaderImportControls() throws IOException {
        assertNotEquals(0, R.layout.activity_file_system);
        assertNotEquals(0, R.id.file_system_title);
        assertNotEquals(0, R.id.file_system_search);
        assertNotEquals(0, R.id.file_system_filter);
        assertNotEquals(0, R.id.file_system_selected_count);
        assertNotEquals(0, R.id.file_system_cb_selected_all);
        assertNotEquals(0, R.id.file_system_btn_delete);
        assertNotEquals(0, R.id.file_system_btn_add_book);
        assertNotEquals(0, R.drawable.ic_ink_expand_16);
        assertNotEquals(0, R.drawable.ic_ink_filter);
        assertNotEquals(0, R.drawable.bg_file_system_bottom_bar);

        String layout = readFile("src/main/res/layout/activity_file_system.xml");
        assertTrue(layout.contains("android:text=\"智能导入\""));
        assertTrue(layout.contains("file_system_selected_count"));
        assertTrue(layout.contains("file_system_filter"));
        assertTrue(layout.contains("file_system_search"));
        assertTrue(layout.contains("android:text=\"加入书架\""));
        assertTrue(layout.contains("style=\"@style/Widget.Ink.Button\""));
        assertTrue(layout.contains("style=\"@style/Widget.Ink.Button.Tonal\""));
        assertTrue(layout.contains("android:layout_weight=\"2\""));
    }

    @Test
    public void bottomSelectionActionsExposeAtLeastFortyEightDpTouchTargets()
            throws IOException {
        String layout = readFile("src/main/res/layout/activity_file_system.xml");

        assertTrue(openingTagWithId(layout, "file_system_btn_delete")
                .contains("android:layout_height=\"48dp\""));
        assertTrue(openingTagWithId(layout, "file_system_btn_add_book")
                .contains("android:layout_height=\"48dp\""));
    }

    @Test
    public void localImportSearchClearActionUsesLocalizedAccessibleNameWithoutShrinkingInput()
            throws IOException {
        String activity = readFile("src/main/java/com/ldp/reader/ui/activity/FileSystemActivity.kt");
        String layout = readFile("src/main/res/layout/activity_file_system.xml");
        String simplified = readFile("src/main/res/values/strings.xml");
        String traditional = readFile("src/main/res/values-zh-rTW/strings.xml");
        String searchView = openingTagWithId(layout, "file_system_search_input");

        assertTrue(searchView.contains("android:layout_width=\"match_parent\""));
        assertTrue(searchView.contains("android:layout_height=\"match_parent\""));
        assertTrue(simplified.contains(
                "<string name=\"search_clear_input\">清除搜索内容</string>"
        ));
        assertTrue(traditional.contains(
                "<string name=\"search_clear_input\">清除搜尋內容</string>"
        ));
        assertTrue(simplified.contains(
                "<string name=\"abc_searchview_description_clear\">@string/search_clear_input</string>"
        ));
        assertFalse(activity.contains("findViewById"));
    }

    @Test
    public void localImportToolbarBackActionUsesReaderLocalizedAccessibleName()
            throws IOException {
        String activity = readFile("src/main/java/com/ldp/reader/ui/activity/FileSystemActivity.kt");
        String simplified = readFile("src/main/res/values/strings.xml");
        String traditional = readFile("src/main/res/values-zh-rTW/strings.xml");

        assertTrue(activity.contains(
                "toolbar?.setNavigationContentDescription(R.string.file_system_navigate_back)"
        ));
        assertTrue(simplified.contains(
                "<string name=\"file_system_navigate_back\">返回</string>"
        ));
        assertTrue(traditional.contains(
                "<string name=\"file_system_navigate_back\">返回</string>"
        ));
    }

    @Test
    public void fileRowsUseRightSideSelectionAndReaderMetadataStyle() throws IOException {
        assertNotEquals(0, R.layout.item_file);
        assertNotEquals(0, R.id.file_fl_icon);
        assertNotEquals(0, R.id.file_cb_select);
        assertNotEquals(0, R.id.file_tv_name);
        assertNotEquals(0, R.id.file_tv_tag);
        assertNotEquals(0, R.id.file_tv_size);
        assertNotEquals(0, R.id.file_tv_date);
        assertNotEquals(0, R.drawable.selector_file_reader_check);
        assertNotEquals(0, R.drawable.bg_file_row_tag);
        assertNotEquals(0, R.drawable.ic_file_row_loaded_32);
        assertNotEquals(0, R.drawable.ic_file_row_folder_32);

        String item = readFile("src/main/res/layout/item_file.xml");
        assertTrue(item.contains("android:minHeight=\"72dp\""));
        assertTrue(item.contains("android:id=\"@+id/file_fl_icon\"\n        android:layout_width=\"48dp\"\n        android:layout_height=\"48dp\""));
        assertTrue(item.indexOf("file_tv_name") < item.indexOf("file_fl_icon"));
        assertTrue(item.contains("@drawable/selector_file_reader_check"));
        assertTrue(item.contains("@drawable/bg_file_row_tag"));
    }

    @Test
    public void fileSystemAdapterChecksLoadedStateByBookIdHash() throws IOException {
        String adapter = readFile("src/main/java/com/ldp/reader/ui/adapter/FileSystemAdapter.kt");
        assertTrue(adapter.contains("MD5Utils.strToMd5By16(file.absolutePath)"));
        assertTrue(adapter.contains("getExistingCollBookIds(idsByFile.values)"));
        assertTrue(adapter.contains("mLoadedMap[file]"));
    }

    @Test
    public void fileSystemActivityMarksOnlySavedFilesAsLoaded() throws IOException {
        String activity = readFile("src/main/java/com/ldp/reader/ui/activity/FileSystemActivity.kt");
        assertTrue(activity.contains("private var isSavingSelectedBooks = false"));
        assertTrue(activity.contains("if (isSavingSelectedBooks) return@setOnClickListener"));
        assertTrue(activity.contains("fileActionScope.launch"));
        assertTrue(activity.contains("withContext(Dispatchers.IO)"));
        assertTrue(activity.contains("BookRepository.getInstance()"));
        assertTrue(activity.contains(".saveCollBooks(books)"));
        assertTrue(activity.contains("isSavingSelectedBooks = false"));
        assertTrue(activity.contains("val savedBookIds = collBooks.mapNotNull { it.get_id() }.toSet()"));
        assertTrue(activity.contains("fragment.markFilesLoaded("));
        assertTrue(activity.contains("MD5Utils.strToMd5By16(file.absolutePath) in savedBookIds"));
        assertTrue(activity.contains("if (mCurFragment === fragment)"));
        assertTrue(activity.contains("catch (cancellation: CancellationException)"));
        assertTrue(activity.contains("if (books.isNotEmpty())"));
        assertTrue(activity.contains("collBooks.isEmpty()"));
        assertTrue(activity.contains("所选文件已不存在或无法导入，请重新选择"));
        assertTrue(activity.contains("conversion.skippedCount > 0"));
        assertTrue(activity.contains(
                "其中 ${conversion.skippedCount} 个文件无法读取，未加入书架"
        ));
        assertTrue(!activity.contains("个文件已失效"));
        assertTrue(activity.contains("fragment.removeSelection(conversion.skippedFiles)"));
        assertTrue(activity.contains("LocalBookImportFiles.isImportableFile(file)"));

        String base = readFile("src/main/java/com/ldp/reader/ui/fragment/BaseFileFragment.kt");
        assertTrue(base.contains("mAdapter?.markFilesLoaded(files)"));
        assertTrue(base.contains("mAdapter?.removeSelection(files)"));
        assertTrue(!base.substring(base.indexOf("fun markFilesLoaded"), base.indexOf("fun applyDisplayOptions"))
                .contains("renderFiles()"));
    }

    @Test
    public void topControlsProvideFunctionalSearchFilterAndImportModeSelection() throws IOException {
        String activity = readFile("src/main/java/com/ldp/reader/ui/activity/FileSystemActivity.kt");
        String layout = readFile("src/main/res/layout/activity_file_system.xml");

        assertTrue(layout.contains("file_system_search_input"));
        assertTrue(activity.contains("fileSystemTitle.setOnClickListener"));
        assertTrue(activity.contains("showImportModeDialog()"));
        assertTrue(activity.contains("showFilterDialog()"));
        assertTrue(activity.contains("applyDisplayOptions"));
        assertTrue(!activity.contains("搜索本地文件暂未开放"));
        assertTrue(!activity.contains("当前仅显示可导入文件"));
    }

    @Test
    public void filterAndSearchStateSurviveActivityRecreation() throws IOException {
        String activity = readFile("src/main/java/com/ldp/reader/ui/activity/FileSystemActivity.kt");

        assertTrue(activity.contains("override fun initData(savedInstanceState: Bundle?)"));
        assertTrue(activity.contains("LocalImportUiState.restore("));
        assertTrue(activity.contains("override fun onSaveInstanceState(outState: Bundle)"));
        assertTrue(activity.contains("STATE_IMPORT_TAB"));
        assertTrue(activity.contains("STATE_IMPORT_FILTER"));
        assertTrue(activity.contains("STATE_IMPORT_QUERY"));
        assertTrue(activity.contains("STATE_IMPORT_SEARCH_VISIBLE"));
        assertTrue(activity.contains("fileSystemSearchInput.isSaveEnabled = false"));
        assertTrue(activity.contains("restoredImportUiState"));
    }

    @Test
    public void phoneDirectoryNavigationSurvivesRecreationAndBackUnwindsBeforeExit()
            throws IOException {
        assertNotEquals(0, R.id.file_category_empty);
        String activity = readFile("src/main/java/com/ldp/reader/ui/activity/FileSystemActivity.kt");
        String directory = readFile(
                "src/main/java/com/ldp/reader/ui/fragment/FileCategoryFragment.kt"
        );
        String layout = readFile("src/main/res/layout/fragment_file_category.xml");

        assertTrue(activity.contains("toolbar.setNavigationOnClickListener { handleBackNavigation() }"));
        assertTrue(activity.contains("override fun onBackPressed()"));
        assertTrue(activity.contains("importUiState.searchVisible"));
        assertTrue(activity.contains("mCategoryFragment?.navigateBack() == true"));
        assertTrue(directory.contains("LocalDirectoryNavigationState.restore("));
        assertTrue(directory.contains("STATE_CURRENT_DIRECTORY"));
        assertTrue(directory.contains("STATE_DIRECTORY_HISTORY"));
        assertTrue(directory.contains("STATE_SELECTED_PATHS"));
        assertTrue(directory.contains("override fun onSaveInstanceState(outState: Bundle)"));
        assertTrue(layout.contains("file_category_empty"));
        assertTrue(layout.contains("@string/nb_file_directory_empty"));
    }

    @Test
    public void smartImportIsSortedByRecencyAndKeepsItsIntentionalNoiseFilter() throws IOException {
        String loader = readFile("src/main/java/com/ldp/reader/utils/media/LocalFileLoader.kt");
        String fragment = readFile("src/main/java/com/ldp/reader/ui/fragment/LocalBookFragment.kt");
        String policy = readFile("src/main/java/com/ldp/reader/utils/LocalBookImportFiles.kt");

        assertTrue(loader.contains("MediaStore.Files.FileColumns.DATE_MODIFIED + \" DESC\""));
        assertTrue(fragment.contains("LocalBookImportFiles.isSmartImportCandidate(file)"));
        assertTrue(policy.contains("SMART_IMPORT_MIN_BYTES"));
        assertTrue(policy.contains("contains(\"log\")"));
        assertTrue(policy.contains("fun isImportableFile"));
    }

    @Test
    public void rowsDisambiguateDuplicateNamesAndExposeImportedStateAsText() throws IOException {
        String item = readFile("src/main/res/layout/item_file.xml");
        String holder = readFile("src/main/java/com/ldp/reader/ui/adapter/view/FileHolder.kt");

        assertTrue(item.contains("file_tv_location"));
        assertTrue(holder.contains("duplicateParentLabel"));
        assertTrue(holder.contains("已导入"));
        assertTrue(holder.contains("已加入书架"));
    }

    @Test
    public void storageAccessIsExplainedBeforeOpeningAppSpecificSettings() throws IOException {
        String activity = readFile("src/main/java/com/ldp/reader/ui/activity/FileSystemActivity.kt");

        assertTrue(activity.contains("showStorageAccessDialog()"));
        assertTrue(activity.contains("ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION"));
        assertTrue(activity.contains("Uri.parse(\"package:$packageName\")"));
        assertTrue(activity.contains("使用选择文件"));
        assertTrue(activity.contains("LocalImportTab.PICK_FILE.pageIndex"));
    }

    @Test
    public void tappingAnImportedTextFileOpensItsExistingShelfBook() throws IOException {
        String recent = readFile("src/main/java/com/ldp/reader/ui/fragment/LocalBookFragment.kt");
        String directory = readFile("src/main/java/com/ldp/reader/ui/fragment/FileCategoryFragment.kt");
        String base = readFile("src/main/java/com/ldp/reader/ui/fragment/BaseFileFragment.kt");

        assertTrue(recent.contains("openImportedTextFile(file)"));
        assertTrue(directory.contains("openImportedTextFile(file)"));
        assertTrue(base.contains("ReadActivity.startActivity"));
        assertTrue(base.contains("MD5Utils.strToMd5By16(file.absolutePath)"));
    }

    @Test
    public void selectionActionNeverDeletesTheExternalSourceFile() throws IOException {
        String activity = readFile("src/main/java/com/ldp/reader/ui/activity/FileSystemActivity.kt");
        String base = readFile("src/main/java/com/ldp/reader/ui/fragment/BaseFileFragment.kt");
        String layout = readFile("src/main/res/layout/activity_file_system.xml");

        assertTrue(layout.contains("android:text=\"取消选择\""));
        assertTrue(activity.contains("mCurFragment?.clearSelection()"));
        assertTrue(base.contains("fun clearSelection()"));
        assertTrue(!base.contains("file.delete()"));
        assertTrue(!base.contains("deleteCheckedFiles"));
    }

    @Test
    public void safDefaultKeepsBroadStorageScannersLazyAndPermissionGated() throws IOException {
        String activity = readFile("src/main/java/com/ldp/reader/ui/activity/FileSystemActivity.kt");
        String recent = readFile("src/main/java/com/ldp/reader/ui/fragment/LocalBookFragment.kt");
        String directory = readFile("src/main/java/com/ldp/reader/ui/fragment/FileCategoryFragment.kt");

        assertTrue(activity.contains("mVp.offscreenPageLimit = 1"));
        assertTrue(activity.contains("setScanAccessEnabled("));
        assertTrue(activity.contains("LocalImportEntryPolicy.canScan(importUiState.tab, hasAccess)"));
        assertTrue(recent.contains("if (!isScanAccessEnabled()"));
        assertTrue(recent.contains("startScanIfAllowed()"));
        assertTrue(directory.contains("loadCurrentDirectoryIfAllowed()"));
        assertTrue(directory.contains("if (!isScanAccessEnabled()"));
    }

    @Test
    public void restoredPagerFragmentsBecomeTheActivitysActualInstances() throws IOException {
        String activity = readFile("src/main/java/com/ldp/reader/ui/activity/FileSystemActivity.kt");

        assertTrue(activity.contains("override fun instantiateItem(container: ViewGroup, position: Int)"));
        assertTrue(activity.contains("val restoredOrCreated = super.instantiateItem"));
        assertTrue(activity.contains("bindTabFragment(position, restoredOrCreated)"));
        assertTrue(activity.contains("mLocalFragment = actual"));
        assertTrue(activity.contains("mCategoryFragment = actual"));
        assertTrue(activity.contains("actual.setOnFileCheckedListener(mListener)"));
    }

    @Test
    public void filterRefreshPreservesSelectionAndHiddenCallbacksCannotRewriteCurrentPage() throws IOException {
        String activity = readFile("src/main/java/com/ldp/reader/ui/activity/FileSystemActivity.kt");
        String base = readFile("src/main/java/com/ldp/reader/ui/fragment/BaseFileFragment.kt");
        String adapter = readFile("src/main/java/com/ldp/reader/ui/adapter/FileSystemAdapter.kt");
        String directory = readFile("src/main/java/com/ldp/reader/ui/fragment/FileCategoryFragment.kt");

        assertTrue(base.contains("mListener?.onCategoryChanged(this)"));
        assertTrue(activity.contains("if (source !== mCurFragment) return"));
        assertTrue(adapter.contains("LocalImportSelectionState"));
        assertTrue(adapter.contains("selectionState.isSelected(file)"));
        assertTrue(directory.contains("preserveSelection = false"));
        assertTrue(activity.contains(
            "fileSystemCbSelectedAll!!.isChecked = fragment.isCheckedAll"
        ));
    }

    @Test
    public void permissionLossReturnsToSafAndClearsScannerData() throws IOException {
        String activity = readFile("src/main/java/com/ldp/reader/ui/activity/FileSystemActivity.kt");
        String recent = readFile("src/main/java/com/ldp/reader/ui/fragment/LocalBookFragment.kt");
        String directory = readFile("src/main/java/com/ldp/reader/ui/fragment/FileCategoryFragment.kt");

        assertTrue(activity.contains("lastStoragePermissionState == true && !permissionGranted"));
        assertTrue(activity.contains("switchToSaf(clearScanData = true)"));
        assertTrue(recent.contains("destroyLoader(LoaderCreator.ALL_BOOK_FILE)"));
        assertTrue(recent.contains("replaceSourceFiles(emptyList(), preserveSelection = false)"));
        assertTrue(directory.contains("directoryLoadJob?.cancel()"));
        assertTrue(directory.contains("replaceSourceFiles(emptyList(), preserveSelection = false)"));
    }

    @Test
    public void standardSafTxtSharesTheDirectPathBookIdentity() throws IOException {
        String store = readFile("src/main/java/com/ldp/reader/document/LocalTextImportStore.kt");
        String identity = readFile("src/main/java/com/ldp/reader/document/LocalTextImportIdentity.kt");

        assertTrue(store.contains("LocalTextImportIdentity.directFilePath("));
        assertTrue(store.contains("directPathBookId"));
        assertTrue(store.contains("LocalTextImportIdentity.legacyRelativeTarget(displayName)"));
        assertTrue(store.contains("legacyUnscopedBookId"));
        assertTrue(store.contains("LocalTextImportIdentity.sameFileContent("));
        assertTrue(store.contains("LocalTextImportIdentity.preferredBookId("));
        assertTrue(identity.contains("com.android.externalstorage.documents"));
        assertTrue(identity.contains("isSafeRelativePath"));
    }

    private static String readFile(String path) throws IOException {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }

    private static String openingTagWithId(String xml, String id) {
        int idOffset = xml.indexOf("android:id=\"@+id/" + id + "\"");
        assertTrue("Missing view id: " + id, idOffset >= 0);
        int tagStart = xml.lastIndexOf('<', idOffset);
        int tagEnd = xml.indexOf('>', idOffset);
        assertTrue("Malformed opening tag for: " + id, tagStart >= 0 && tagEnd > idOffset);
        return xml.substring(tagStart, tagEnd + 1);
    }
}
