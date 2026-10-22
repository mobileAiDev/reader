package com.ldp.reader.ui;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.ldp.reader.R;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class HomeUiResourceContractTest {

    @Test
    public void homeAndLoginLayoutsKeepBoundResourceIds() {
        assertNotEquals(0, R.layout.activity_main);
        assertNotEquals(0, R.layout.fragment_bookshelf);
        assertNotEquals(0, R.layout.fragment_book_store);
        assertNotEquals(0, R.layout.fragment_mine);
        assertNotEquals(0, R.layout.item_coll_book);
        assertNotEquals(0, R.layout.activity_login);
        assertNotEquals(0, R.id.home_toolbar_title);
        assertNotEquals(0, R.id.home_nav_indicator);
        assertNotEquals(0, R.id.home_bottom_nav);
        assertNotEquals(0, R.id.home_nav_bookshelf);
        assertNotEquals(0, R.id.home_nav_bookstore);
        assertNotEquals(0, R.id.home_nav_mine);
        assertNotEquals(0, R.id.home_nav_bookshelf_icon);
        assertNotEquals(0, R.id.home_nav_bookshelf_label);
        assertNotEquals(0, R.id.home_nav_mine_icon);
        assertNotEquals(0, R.id.home_nav_mine_label);
        assertNotEquals(0, R.id.home_shelf_summary);
        assertNotEquals(0, R.id.home_reading_time_chip);
        assertNotEquals(0, R.id.home_bookshelf_tools);
        assertNotEquals(0, R.id.home_bookshelf_filter);
        assertNotEquals(0, R.id.home_bookshelf_edit);
        assertNotEquals(0, R.id.home_bookshelf_edit_bar);
        assertNotEquals(0, R.id.shelf_hero_card);
        assertNotEquals(0, R.id.shelf_hero_cover);
        assertNotEquals(0, R.id.home_bookshelf_select_all);
        assertNotEquals(0, R.id.home_bookshelf_pin_selected);
        assertNotEquals(0, R.id.home_bookshelf_delete_selected);
        assertNotEquals(0, R.id.home_bookshelf_content_frame);
        assertNotEquals(0, R.id.home_bookshelf_filter_empty);
        assertNotEquals(0, R.id.home_bookshelf_filter_empty_title);
        assertNotEquals(0, R.id.home_bookshelf_filter_empty_reset);
        assertNotEquals(0, R.id.book_shelf_empty_title);
        assertNotEquals(0, R.id.book_shelf_empty_import);
        assertNotEquals(0, R.id.book_shelf_empty_search);
        assertNotEquals(0, R.id.book_shelf_rv_content);
        assertNotEquals(0, R.id.coll_book_iv_cover);
        assertNotEquals(0, R.id.coll_book_local_cover);
        assertNotEquals(0, R.id.coll_book_local_cover_title);
        assertNotEquals(0, R.id.coll_book_local_cover_type);
        assertNotEquals(0, R.id.coll_book_tv_name);
        assertNotEquals(0, R.id.coll_book_tv_chapter);
        assertNotEquals(0, R.id.coll_book_tv_lately_update);
        assertNotEquals(0, R.id.coll_book_iv_top);
        assertNotEquals(0, R.id.coll_book_iv_red_rot);
        assertNotEquals(0, R.drawable.bg_bookshelf_local_cover);
        assertNotEquals(0, R.drawable.ic_bookshelf_check_checked_24);
        assertNotEquals(0, R.drawable.ic_bookshelf_check_unchecked_24);
        assertNotEquals(0, R.drawable.selector_bookshelf_edit_check);
        assertNotEquals(0, R.id.iv_login_back);
        assertNotEquals(0, R.id.et_user_phone);
        assertNotEquals(0, R.id.et_sms_code_input);
        assertNotEquals(0, R.id.btn_get_sms_code);
        assertNotEquals(0, R.id.btn_user_login);
        assertNotEquals(0, R.id.btn_direct_login);
        assertNotEquals(0, R.id.btn_user_logout);
        assertNotEquals(0, R.id.mine_profile_card);
        assertNotEquals(0, R.id.mine_profile_avatar);
        assertNotEquals(0, R.id.mine_profile_reading);
        assertNotEquals(0, R.id.mine_login_entry);
        assertNotEquals(0, R.id.mine_reading_entry);
        assertNotEquals(0, R.id.mine_stat_total_value);
        assertNotEquals(0, R.id.mine_stat_week_value);
        assertNotEquals(0, R.id.mine_stat_today_value);
        assertNotEquals(0, R.id.mine_books_entry);
        assertNotEquals(0, R.id.mine_appearance_entry);
        assertNotEquals(0, R.id.mine_settings_entry);
        assertNotEquals(0, R.id.mine_about_entry);
        assertNotEquals(0, R.drawable.bg_mine_avatar);
        assertNotEquals(0, R.layout.activity_reading_stats);
        assertNotEquals(0, R.id.reading_stats_total_value);
        assertNotEquals(0, R.id.reading_stats_today_value);
        assertNotEquals(0, R.id.reading_stats_week_value);
        assertNotEquals(0, R.layout.activity_about);
        assertNotEquals(0, R.id.about_version);
        assertNotEquals(0, R.layout.view_ink_page_header);
        assertNotEquals(0, R.id.ink_header_back);
        assertNotEquals(0, R.id.ink_header_title);
        assertNotEquals(0, R.id.book_store_empty_title);
        assertNotEquals(0, R.menu.menu_home_bottom);
        assertNotEquals(0, R.drawable.bg_home_reading_chip);
        assertNotEquals(0, R.drawable.ic_ink_search);
        assertNotEquals(0, R.drawable.ic_ink_more);
        assertNotEquals(0, R.drawable.selector_ink_tab_shelf);
        assertNotEquals(0, R.drawable.selector_ink_tab_mine);
    }

    @Test
    public void minePageKeepsOperationalActionsInSettings() throws IOException {
        assertNotEquals(0, R.layout.activity_settings);
        assertNotEquals(0, R.id.settings_header);
        assertNotEquals(0, R.id.settings_account_entry);
        assertNotEquals(0, R.id.settings_sync_bookshelf);
        assertNotEquals(0, R.id.settings_appearance_entry);
        assertNotEquals(0, R.id.settings_appearance_value);
        assertNotEquals(0, R.id.settings_local_import);
        assertNotEquals(0, R.id.settings_clear_cache);
        assertNotEquals(0, R.id.settings_cache_summary);
        assertNotEquals(0, R.id.settings_about_entry);

        String mineLayout = readFile("src/main/res/layout/fragment_mine.xml");
        assertTrue(mineLayout.contains("android:text=\"我的书架\""));
        assertTrue(mineLayout.contains("android:text=\"设置\""));
        assertTrue(mineLayout.contains("android:text=\"@string/mine_about_title\""));
        assertTrue(mineLayout.contains("android:text=\"@string/appearance_title\""));
        assertFalse(mineLayout.contains("掌阅会员"));
        assertFalse(mineLayout.contains("充值"));
        assertFalse(mineLayout.contains("同步书架"));
        assertFalse(mineLayout.contains("本机导入"));

        String settingsLayout = readFile("src/main/res/layout/activity_settings.xml");
        assertTrue(settingsLayout.contains("android:text=\"@string/settings_account_title\""));
        assertTrue(settingsLayout.contains("android:text=\"@string/settings_sync_title\""));
        assertTrue(settingsLayout.contains("android:text=\"@string/appearance_title\""));
        assertTrue(settingsLayout.contains("android:text=\"@string/settings_local_import_title\""));
        assertTrue(settingsLayout.contains("android:text=\"@string/settings_clear_cache_title\""));
        assertTrue(settingsLayout.contains("android:text=\"@string/settings_about_title\""));
        assertFalse(settingsLayout.contains("青少年模式"));
        assertFalse(settingsLayout.contains("自动充值服务"));

        String mineFragment = readFile("src/main/java/com/ldp/reader/ui/fragment/MineFragment.kt");
        assertTrue(mineFragment.contains("ReadingStatsActivity::class.java"));
        assertTrue(mineFragment.contains("SettingsActivity::class.java"));
        assertTrue(mineFragment.contains("AboutActivity::class.java"));
        assertTrue(mineFragment.contains("ThemeActivity.start(requireContext())"));
        assertTrue(mineFragment.contains("selectHomeTab(MainActivity.HomeTabKey.BOOKSHELF)"));
        assertFalse(mineFragment.contains("FileSystemActivity"));
        assertFalse(mineFragment.contains("ToastUtils"));

        String settingsActivity = readFile("src/main/java/com/ldp/reader/ui/activity/SettingsActivity.kt");
        assertTrue(settingsActivity.contains("settingsSyncBookshelf"));
        assertTrue(settingsActivity.contains("BookshelfSyncRequest.resultIntent()"));
        assertTrue(settingsActivity.contains("registerForActivityResult(ActivityResultContracts.StartActivityForResult())"));
        assertTrue(settingsActivity.contains("SharedPreUtils.getInstance().getString(\"token\").isEmpty()"));
        assertTrue(settingsActivity.contains("loginSyncLauncher.launch(LoginActivity.syncIntent(this))"));
        assertTrue(settingsActivity.contains("FileSystemActivity::class.java"));
        assertTrue(settingsActivity.contains("CacheUtils.clearAppCache"));
        assertTrue(settingsActivity.contains("AboutActivity::class.java"));
        assertTrue(settingsActivity.contains("ThemeActivity.start(this)"));

        String mainActivity = readFile("src/main/java/com/ldp/reader/ui/activity/MainActivity.kt");
        assertTrue(mainActivity.contains("selectHomeTab(tabKey: HomeTabKey)"));
        assertTrue(mainActivity.contains("isMinePage"));
        assertTrue(mainActivity.contains("toolbar.visibility = if (isMinePage) View.GONE else View.VISIBLE"));
        assertTrue(mainActivity.contains("EdgeToEdge.apply(this)"));
        assertTrue(mainActivity.contains("fun requestBookShelfSync()"));
        assertTrue(mainActivity.contains("bookShelfFragment()?.requestBookShelfSync()"));

        String bookshelfFragment = readFile("src/main/java/com/ldp/reader/ui/fragment/BookShelfFragment.kt");
        assertTrue(bookshelfFragment.contains("fun requestBookShelfSync()"));
        assertTrue(bookshelfFragment.contains("loginSyncLauncher.launch(LoginActivity.syncIntent(requireContext()))"));
        assertFalse(bookshelfFragment.contains("RxBus"));
        assertFalse(bookshelfFragment.contains("ToastUtils.show(\"请登录\")"));
    }

    @Test
    public void bottomNavigationUsesInkAccentSelection() throws IOException {
        String selector = readFile("src/main/res/color/ink_tab_item.xml");
        assertTrue(selector.contains("android:color=\"@color/ink_text_primary\" android:state_selected=\"true\""));
        assertTrue(selector.contains("@color/ink_text_tertiary"));

        String colors = readFile("src/main/res/values/colors.xml");
        String themes = readFile("src/main/res/values/themes_ink.xml");
        String nightThemes = readFile("src/main/res/values-night/themes_ink.xml");
        assertTrue(readFile("src/main/res/color/ink_primary.xml").contains("?attr/inkPrimary"));
        assertTrue(themes.contains("<item name=\"inkPrimary\">"));
        assertTrue(nightThemes.contains("<item name=\"inkPrimary\">"));
        assertTrue(colors.contains("<color name=\"home_primary\">@color/ink_primary</color>"));

        String activityMain = readFile("src/main/res/layout/activity_main.xml");
        assertEquals(2, countOccurrences(activityMain, "app:tint=\"@color/ink_tab_item\""));
        assertEquals(2, countOccurrences(activityMain, "android:textColor=\"@color/ink_tab_item\""));
        assertEquals(4, countOccurrences(activityMain, "android:duplicateParentState=\"true\""));
        assertFalse(activityMain.contains("BottomNavigationView"));
    }

    @Test
    public void homeUsesInkTypographyAndPortraitCovers() throws IOException {
        String toolbar = readFile("src/main/res/layout/toolbar_home.xml");
        assertTrue(toolbar.contains("android:textAppearance=\"@style/TextAppearance.Ink.Display\""));

        String readingChip = readFile("src/main/res/drawable/bg_home_reading_chip.xml");
        assertTrue(readingChip.contains("<inset"));
        assertTrue(readingChip.contains("android:insetTop=\"8dp\""));
        assertTrue(readingChip.contains("android:insetBottom=\"8dp\""));

        String item = readFile("src/main/res/layout/item_coll_book.xml");
        assertTrue(item.contains("com.ldp.reader.ui.widget.CoverFrameLayout"));
        assertFalse(item.contains("android:stateListAnimator"));
        String adapter = readFile("src/main/java/com/ldp/reader/ui/adapter/HomeShelfAdapter.kt");
        assertTrue(adapter.contains("Press.attach(it.itemView)"));
        assertTrue(item.contains("android:textSize=\"14sp\""));
        assertTrue(countOccurrences(item, "android:textSize=\"12sp\"") >= 2);

        String coverFrame = readFile("src/main/java/com/ldp/reader/ui/widget/CoverFrameLayout.kt");
        assertTrue(coverFrame.contains("val height = width * 4 / 3"));
    }

    @Test
    public void bookshelfTopMenuAndTabIconsKeepInkSpacing() throws IOException {
        String fragment = readFile("src/main/java/com/ldp/reader/ui/fragment/BookShelfFragment.kt");
        assertTrue(fragment.contains("addItemDecoration(ReaderBookGridSpacingDecoration(3, dp(14)))"));
        assertTrue(fragment.contains("ReadingStatsActivity::class.java"));
        assertTrue(fragment.contains("homeReadingTimeChip?.setOnClickListener"));

        String activityMain = readFile("src/main/res/layout/activity_main.xml");
        assertFalse(activityMain.contains("home_bottom_nav_shadow"));
        assertTrue(activityMain.contains("<com.ldp.reader.ui.glass.GlassFrameLayout\n        android:id=\"@+id/home_bottom_nav\""));
        assertTrue(activityMain.contains("android:layout_height=\"@dimen/ink_float_bar_height\""));
        assertTrue(activityMain.contains("android:id=\"@+id/home_nav_indicator\""));
        assertEquals(2, countOccurrences(activityMain, "android:layout_width=\"22dp\""));
        assertEquals(2, countOccurrences(activityMain, "android:layout_height=\"22dp\""));

        String mainActivity = readFile("src/main/java/com/ldp/reader/ui/activity/MainActivity.kt");
        assertTrue(mainActivity.contains("applyBottomNavItemState("));
        assertFalse(mainActivity.contains("imageTintList = ColorStateList.valueOf(color)"));
    }

    @Test
    public void mainMenuKeepsCustomOverlayActionContract() throws IOException {
        assertNotEquals(0, R.menu.menu_main);
        assertNotEquals(0, R.id.action_search);
        assertNotEquals(0, R.id.action_more_custom);
        assertNotEquals(0, R.layout.view_home_more_menu);
        assertNotEquals(0, R.id.home_menu_overlay);
        assertNotEquals(0, R.id.home_menu_scrim);
        assertNotEquals(0, R.id.home_menu_popup);
        assertNotEquals(0, R.id.home_menu_import);
        assertNotEquals(0, R.id.home_menu_import_source);
        assertNotEquals(0, R.id.home_menu_sync);
        assertNotEquals(0, R.id.home_menu_account);
        assertNotEquals(0, R.integer.home_more_menu_anim_duration);

        String mainActivity = readFile("src/main/java/com/ldp/reader/ui/activity/MainActivity.kt");
        assertTrue(mainActivity.contains("HomeMoreMenuView"));
        assertFalse(mainActivity.contains("PopupWindow"));
        assertFalse(mainActivity.contains("dimMenuSystemBars"));
        assertTrue(mainActivity.contains("actionMenuItemView(R.id.action_more_custom)"));

        String menuLayout = readFile("src/main/res/layout/view_home_more_menu.xml");
        assertTrue(menuLayout.contains("<com.ldp.reader.ui.glass.GlassLinearLayout\n        android:id=\"@+id/home_menu_popup\""));
        assertTrue(menuLayout.contains("app:glassLook=\"popup\""));
        assertFalse(menuLayout.contains("home_menu_arrow"));
        assertEquals(4, countOccurrences(menuLayout, "android:layout_height=\"48dp\""));
        assertEquals(4, countOccurrences(menuLayout, "android:ellipsize=\"end\""));
        assertEquals(4, countOccurrences(menuLayout, "android:maxLines=\"1\""));
        assertTrue(menuLayout.contains("@drawable/ic_ink_import"));
        assertTrue(menuLayout.contains("@drawable/ic_ink_source"));
        assertTrue(menuLayout.contains("@drawable/ic_ink_sync"));
        assertTrue(menuLayout.contains("@drawable/ic_ink_person"));

        String menu = readFile("src/main/res/menu/menu_main.xml");
        assertTrue(menu.contains("android:icon=\"@drawable/ic_ink_search\""));
        assertTrue(menu.contains("android:icon=\"@drawable/ic_ink_more\""));
    }

    @Test
    public void bookshelfItemKeepsCardOrderAndEditControls() throws IOException {
        String itemLayout = readFile("src/main/res/layout/item_coll_book.xml");
        // The cover frame shapes and edges the cover; the picture is a plain rectangle, so the
        // cloth cover under it can never show at a corner.
        for (String layout : new String[] {"item_coll_book", "item_shelf_hero", "item_home_shelf_media"}) {
            String coverLayout = readFile("src/main/res/layout/" + layout + ".xml");
            assertTrue(layout, coverLayout.contains("com.ldp.reader.ui.widget.CoverFrameLayout"));
            assertFalse(layout, coverLayout.contains("ShapeableImageView"));
            assertFalse(layout, coverLayout.contains("app:strokeWidth"));
        }
        assertTrue(itemLayout.contains("coll_book_local_cover"));
        assertTrue(itemLayout.contains("@drawable/bg_bookshelf_local_cover"));
        assertTrue(itemLayout.contains("android:button=\"@drawable/selector_bookshelf_edit_check\""));
        assertTrue(itemLayout.contains("android:text=\"@string/bookshelf_badge_updated\""));
        assertTrue(itemLayout.contains("android:clickable=\"false\""));
        assertTrue(itemLayout.contains("android:focusable=\"false\""));
        assertTrue(itemLayout.indexOf("coll_book_tv_lately_update") < itemLayout.indexOf("coll_book_tv_chapter"));

        String bookshelfLayout = readFile("src/main/res/layout/fragment_bookshelf.xml");
        assertTrue(bookshelfLayout.contains("android:id=\"@+id/home_bookshelf_select_all\""));
        assertTrue(bookshelfLayout.contains("android:id=\"@+id/home_bookshelf_pin_selected\""));
        assertTrue(bookshelfLayout.contains("android:id=\"@+id/home_bookshelf_delete_selected\""));
        assertTrue(bookshelfLayout.contains("android:text=\"@string/home_bookshelf_remove_zero\""));
        assertTrue(bookshelfLayout.contains("android:textColor=\"@color/ink_danger\""));

        String holder = readFile("src/main/java/com/ldp/reader/ui/adapter/view/CollBookHolder.kt");
        assertTrue(holder.contains("coverTitle(value.title)"));
        assertTrue(holder.contains("fileTypeLabel(value.cover)"));
        assertTrue(holder.contains("progressLabel("));
        assertTrue(holder.contains("recordProvider?.let"));
        assertTrue(holder.contains("mTvChapter.visibility = View.GONE"));
        assertTrue(holder.contains("adapter.isPinned(value)"));
        assertTrue(holder.contains("onLoaded = { loadedCover ->"));
        assertTrue(holder.contains("BookshelfCoverCandidates.promoteLoadedCover(value, loadedCover)"));
        assertTrue(holder.contains("BookRepository.getInstance().saveCollBook(value)"));

        String fragment = readFile("src/main/java/com/ldp/reader/ui/fragment/BookShelfFragment.kt");
        assertTrue(fragment.contains("exitEditModeIfNeeded"));
        assertTrue(fragment.contains("repository.getBookRecords(collBookBeans.map { it._id })"));
        assertTrue(fragment.contains("mCollBookAdapter!!.refreshItems(filteredBooks, mediaItems, recordsByBookId, nullIdRecord)"));

        String adapter = readFile("src/main/java/com/ldp/reader/ui/adapter/HomeShelfAdapter.kt");
        assertTrue(adapter.contains("bookRecordsById: Map<String, BookRecordBean>"));
        assertTrue(adapter.contains("CollBookHolder(this) { book -> bookRecordFor(book) }"));
        assertTrue(adapter.contains("return if (bookId == null) nullIdBookRecord else bookRecordsById[bookId]"));

        String mainActivity = readFile("src/main/java/com/ldp/reader/ui/activity/MainActivity.kt");
        assertTrue(mainActivity.contains("exitBookshelfEditModeIfNeeded"));
    }

    @Test
    public void bookshelfLongPressEntersUnifiedSafeSelectionMode() throws IOException {
        String fragment = readFile("src/main/java/com/ldp/reader/ui/fragment/BookShelfFragment.kt");
        String adapter = readFile("src/main/java/com/ldp/reader/ui/adapter/HomeShelfAdapter.kt");
        String mainActivity = readFile("src/main/java/com/ldp/reader/ui/activity/MainActivity.kt");

        assertTrue(fragment.contains("enterEmptyBookshelfManageMode()"));
        assertTrue(fragment.contains("enterBookshelfManageMode(collBook)"));
        assertTrue(fragment.contains("enterBookshelfManageMode(item)"));
        assertTrue(fragment.contains("mCollBookAdapter?.selectBook(collBook)"));
        assertTrue(fragment.contains("mCollBookAdapter?.selectMediaItem(item)"));
        assertTrue(adapter.contains("fun selectBook(book: CollBookBean?)"));
        assertTrue(adapter.contains("fun selectMediaItem(item: MediaShelfItem?)"));
        assertTrue(adapter.contains("!selectedBookKeys.add(key)"));
        assertTrue(adapter.contains("!selectedMediaIds.add(id)"));
        assertFalse(fragment.contains("private fun openItemDialog"));
        assertFalse(fragment.contains("ArrayAdapter("));
        assertFalse(fragment.contains("private fun deleteBook"));

        assertTrue(fragment.contains("selectedBooks.size == 1 && selectedMediaItems.isEmpty()"));
        assertTrue(fragment.contains("BookshelfPinStore.pin(collBook)"));
        assertTrue(fragment.contains("BookshelfPinStore.unpin(collBook)"));
        assertFalse(fragment.contains("collBook.lastRead = BookshelfPinTimestamp"));
        assertTrue(fragment.contains("refreshShelfDisplay(BookRepository.getInstance().collBooks)"));
        assertTrue(fragment.contains("homeBookshelfPinSelected.isEnabled = canPin"));
        assertTrue(fragment.contains("homeBookshelfDeleteSelected.isEnabled = selectedCount > 0"));
        assertTrue(fragment.contains("setHomeBottomNavigationVisible(!editMode)"));
        assertTrue(mainActivity.contains("navBarSpring?.animateTo(if (visible) 0f else 1f)"));
    }

    @Test
    public void networkReaderPersistsProgressForFinishedFilter() throws IOException {
        String netPageLoader = readFile("src/main/java/com/ldp/reader/widget/page/NetPageLoader.kt");
        assertTrue(netPageLoader.contains("calculateProgressTenths"));
        assertTrue(netPageLoader.contains("BookshelfLocalProgressStore.saveProgressTenths"));
        assertTrue(netPageLoader.contains("mChapterList.size"));
        assertTrue(netPageLoader.contains("getCurrentPagePosition()"));
        assertTrue(netPageLoader.contains("getCurrentPageCount()"));
    }

    @Test
    public void readerStoresProgressWhenUserHitsReadableEnd() throws IOException {
        String pageLoader = readFile("src/main/java/com/ldp/reader/widget/page/PageLoader.kt");
        assertTrue(pageLoader.contains("protected open fun onReadableEndReached()"));
        assertTrue(pageLoader.contains("onReadableEndReached()"));

        String netLoader = readFile("src/main/java/com/ldp/reader/widget/page/NetPageLoader.kt");
        assertTrue(netLoader.contains("override fun onReadableEndReached()"));
        assertTrue(netLoader.contains("saveRecord()"));

        String localLoader = readFile("src/main/java/com/ldp/reader/widget/page/LocalPageLoader.kt");
        assertTrue(localLoader.contains("override fun onReadableEndReached()"));
        assertTrue(localLoader.contains("saveRecord()"));
    }

    @Test
    public void localReaderDoesNotRequestRemoteFolderWhileParsingLocalFile() throws IOException {
        String readActivity = readFile("src/main/java/com/ldp/reader/ui/activity/ReadActivity.kt");

        int localRefresh = readActivity.indexOf("mPageLoader!!.refreshChapterList()");
        int localGuard = readActivity.indexOf("if (mCollBook!!.isLocal())", localRefresh);
        int guardReturn = readActivity.indexOf("return", localGuard);
        int remoteLoad = readActivity.indexOf("viewModel.loadCategory(mBookId, mCollBook!!, persistToShelf = true)", localRefresh);

        assertTrue(localRefresh > 0);
        assertTrue(localGuard > localRefresh);
        assertTrue(guardReturn > localGuard);
        assertTrue(remoteLoad > guardReturn);
    }

    @Test
    public void networkTrialReadDoesNotCreateBookshelfEntry() throws IOException {
        String readViewModel = readFile("src/main/java/com/ldp/reader/ui/activity/ReadViewModel.kt");
        String netLoader = readFile("src/main/java/com/ldp/reader/widget/page/NetPageLoader.kt");

        assertTrue(readViewModel.contains("persistToShelf: Boolean"));
        assertTrue(readViewModel.contains("if (persistToShelf)"));
        assertTrue(netLoader.contains("getCollBook(mCollBook.get_id()) != null"));
    }

    @Test
    public void networkReaderRequestsImmediateNextReadableChapter() throws IOException {
        String loader = readFile("src/main/java/com/ldp/reader/widget/page/NetPageLoader.kt");
        assertTrue(loader.contains("requestStart > requestEnd || requestStart >= mChapterList.size"));
        assertFalse(loader.contains("start + 1 >= mChapterList.size"));
        assertFalse(loader.contains("mChapterList[start + 1]"));
    }

    @Test
    public void remoteFolderSaveReplacesStaleReadableChapterList() throws IOException {
        String repository = readFile("src/main/java/com/ldp/reader/model/local/BookRepository.kt");
        assertTrue(repository.contains("replaceBookChaptersInTx"));
        assertTrue(repository.contains("SourceEngineCachedCatalogMigration.sanitize(beans).chapters"));
        assertTrue(repository.contains("mBookStore.replaceBookChapters(bookId, preparedChapters)"));

        String backendProvider = readFile("src/main/java/com/ldp/reader/source/BackendReaderContentProvider.kt");
        assertTrue(backendProvider.contains("mapIndexed { index, chapter ->"));
        assertTrue(backendProvider.contains("start = index.toLong()"));
        String sourceProvider = readFile("src/main/java/com/ldp/reader/source/SourceEngineReaderContentProvider.kt");
        assertTrue(sourceProvider.contains("mapIndexedNotNull { index, canonicalChapter ->"));
        assertTrue(sourceProvider.contains("start = index.toLong()"));
        String shelfViewModel = readFile("src/main/java/com/ldp/reader/ui/fragment/BookShelfViewModel.kt");
        assertTrue(shelfViewModel.contains("start = bookChapterBeans.size.toLong()"));
        assertTrue(shelfViewModel.contains("chaptersCount = bookChapterBeans.size"));
        assertTrue(shelfViewModel.contains("isReadableFolderStale"));
        assertTrue(shelfViewModel.contains("getBookRecord"));
        String detailViewModel = readFile("src/main/java/com/ldp/reader/ui/activity/BookDetailViewModel.kt");
        assertTrue(detailViewModel.contains("BookContentProviderRouter.getBookFolder"));
        assertTrue(detailViewModel.contains("chaptersCount = bookChapterBeans.size"));
    }

    @Test
    public void loginTriggeredBookshelfSyncKeepsServerShelfContractDirect() throws IOException {
        String presenter = readFile("src/main/java/com/ldp/reader/ui/fragment/BookShelfViewModel.kt");

        assertFalse(presenter.contains("if (bookIdBeans != null)"));
        assertFalse(presenter.contains("if (bookIdBean != null"));
        assertTrue(presenter.contains("if (bookIdBean.bookId != 0)"));
        assertTrue(presenter.contains("if (bookIdList == null || bookIdList.isEmpty())"));
        assertTrue(presenter.contains("updateShelf(ArrayList())"));
        assertTrue(presenter.contains("onlineBookIdsFrom(collBooks)"));
    }

    @Test
    public void bookshelfFilterUsesSingleChoiceSheetAndNeutralEmptyState() throws IOException {
        assertNotEquals(0, R.layout.sheet_bookshelf_filter);
        assertNotEquals(0, R.id.bookshelf_filter_all);
        assertNotEquals(0, R.id.bookshelf_filter_status_3_days);
        assertNotEquals(0, R.id.bookshelf_filter_status_7_days);
        assertNotEquals(0, R.id.bookshelf_filter_progress_unread);
        assertNotEquals(0, R.id.bookshelf_filter_progress_reading);
        assertNotEquals(0, R.id.bookshelf_filter_progress_finished);
        assertNotEquals(0, R.id.bookshelf_filter_local);

        String filterLayout = readFile("src/main/res/layout/sheet_bookshelf_filter.xml");
        assertTrue(filterLayout.contains("android:text=\"全部书籍\""));
        assertTrue(filterLayout.contains("android:text=\"更新状态\""));
        assertTrue(filterLayout.contains("android:text=\"3日内更新\""));
        assertTrue(filterLayout.contains("android:text=\"7日内更新\""));
        assertTrue(filterLayout.contains("android:text=\"阅读进度\""));
        assertTrue(filterLayout.contains("android:text=\"尚未阅读\""));
        assertTrue(filterLayout.contains("android:text=\"正在阅读\""));
        assertTrue(filterLayout.contains("android:text=\"已读完\""));
        assertTrue(filterLayout.contains("android:text=\"本地书\""));
        assertEquals(7, countOccurrences(filterLayout, "style=\"@style/Widget.Ink.Chip.Choice\""));
        assertFalse(filterLayout.contains("在线书籍"));

        String bookshelfLayout = readFile("src/main/res/layout/fragment_bookshelf.xml");
        assertTrue(bookshelfLayout.contains("android:text=\"当前筛选暂无书籍\""));
        assertTrue(bookshelfLayout.contains("android:text=\"查看全部\""));
        assertFalse(bookshelfLayout.contains("home_bookshelf_filter_empty_import"));

        String emptyShelfLayout = readFile("src/main/res/layout/view_empty_book_shelf.xml");
        assertTrue(emptyShelfLayout.contains("android:text=\"@string/bookshelf_empty_title\""));
        assertTrue(emptyShelfLayout.contains("android:text=\"@string/bookshelf_empty_search\""));
        assertTrue(emptyShelfLayout.contains("android:text=\"@string/bookshelf_empty_import\""));

        String sheet = readFile("src/main/java/com/ldp/reader/ui/widget/BookshelfFilterSheet.kt");
        assertTrue(sheet.contains("BottomSheetDialog"));
        assertTrue(sheet.contains("chip.isChecked = key == selected"));

        String fragment = readFile("src/main/java/com/ldp/reader/ui/fragment/BookShelfFragment.kt");
        assertTrue(fragment.contains("BookshelfFilterSheet(requireContext(), currentShelfFilter)"));
        assertTrue(fragment.contains("BookShelfViewModel.filterToolbarLabel"));
        assertTrue(fragment.contains("BookShelfViewModel.shouldShowFilterEmpty"));
        assertTrue(fragment.contains("BookShelfViewModel.filterEmptyTitle"));
        assertTrue(fragment.contains("BookShelfViewModel.filterEmptyResetText"));
        assertTrue(fragment.contains("homeBookshelfFilterEmptyReset"));
        assertTrue(fragment.contains("ViewEmptyBookShelfBinding.bind"));
        assertTrue(fragment.contains("bookShelfEmptyImport"));
        assertTrue(fragment.contains("bookShelfEmptySearch"));
        assertFalse(fragment.contains("ShelfFilter.ONLINE"));
    }

    @Test
    public void searchPageKeepsInkResourceContract() {
        assertNotEquals(0, R.layout.activity_search);
        assertNotEquals(0, R.id.search_toolbar);
        assertNotEquals(0, R.id.search_input_container);
        assertNotEquals(0, R.id.search_hot_panel);
        assertNotEquals(0, R.id.search_hot_title);
        assertNotEquals(0, R.id.search_iv_back);
        assertNotEquals(0, R.id.search_et_input);
        assertNotEquals(0, R.id.search_iv_delete);
        assertNotEquals(0, R.id.search_iv_search);
        assertNotEquals(0, R.id.search_book_tv_refresh_hot);
        assertNotEquals(0, R.id.search_tg_hot);
        assertNotEquals(0, R.drawable.bg_search_input_pill);
        assertNotEquals(0, R.drawable.bg_search_tab_track);
    }

    @Test
    public void bookDetailPageKeepsInkResourceContract() {
        assertNotEquals(0, R.layout.activity_book_detail);
        assertNotEquals(0, R.id.book_detail_top_bar);
        assertNotEquals(0, R.id.book_detail_header);
        assertNotEquals(0, R.id.book_detail_info_panel);
        assertNotEquals(0, R.id.book_detail_brief_panel);
        assertNotEquals(0, R.id.book_detail_brief_toggle);
        assertNotEquals(0, R.id.book_detail_bottom_bar);
        assertNotEquals(0, R.id.book_detail_iv_cover);
        assertNotEquals(0, R.id.book_detail_tv_title);
        assertNotEquals(0, R.id.book_detail_tv_author);
        assertNotEquals(0, R.id.book_detail_tv_last_chapter);
        assertNotEquals(0, R.id.book_detail_tv_brief);
        assertNotEquals(0, R.id.book_list_ll_chase);
        assertNotEquals(0, R.id.book_list_iv_chase);
        assertNotEquals(0, R.id.book_list_tv_chase);
        assertNotEquals(0, R.id.book_detail_tv_read);
        assertNotEquals(0, R.drawable.bg_book_detail_action_secondary);
        assertNotEquals(0, R.drawable.ic_book_detail_back_24);
    }

    private static String readFile(String path) throws IOException {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }

    private static int countOccurrences(String source, String pattern) {
        int count = 0;
        int index = source.indexOf(pattern);
        while (index >= 0) {
            count++;
            index = source.indexOf(pattern, index + pattern.length());
        }
        return count;
    }
}
