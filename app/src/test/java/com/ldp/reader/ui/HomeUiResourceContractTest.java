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
        assertNotEquals(0, R.id.home_bottom_nav_shadow);
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
        assertNotEquals(0, R.id.home_bookshelf_tool_divider);
        assertNotEquals(0, R.id.home_bookshelf_edit);
        assertNotEquals(0, R.id.home_bookshelf_edit_bar);
        assertNotEquals(0, R.id.home_bookshelf_select_all);
        assertNotEquals(0, R.id.home_bookshelf_delete_selected);
        assertNotEquals(0, R.id.home_section_title);
        assertNotEquals(0, R.id.home_bookshelf_content_frame);
        assertNotEquals(0, R.id.home_bookshelf_filter_empty);
        assertNotEquals(0, R.id.home_bookshelf_filter_empty_title);
        assertNotEquals(0, R.id.home_bookshelf_filter_empty_reset);
        assertNotEquals(0, R.id.home_bookshelf_filter_empty_import);
        assertNotEquals(0, R.id.book_shelf_empty_title);
        assertNotEquals(0, R.id.book_shelf_empty_import);
        assertNotEquals(0, R.id.book_shelf_rv_content);
        assertNotEquals(0, R.id.coll_book_iv_cover);
        assertNotEquals(0, R.id.coll_book_local_cover);
        assertNotEquals(0, R.id.coll_book_local_cover_title);
        assertNotEquals(0, R.id.coll_book_local_cover_type);
        assertNotEquals(0, R.id.coll_book_tv_name);
        assertNotEquals(0, R.id.coll_book_tv_chapter);
        assertNotEquals(0, R.id.coll_book_tv_lately_update);
        assertNotEquals(0, R.drawable.bg_bookshelf_local_cover);
        assertNotEquals(0, R.drawable.ic_bookshelf_local_fold_40);
        assertNotEquals(0, R.drawable.ic_bookshelf_check_checked_24);
        assertNotEquals(0, R.drawable.ic_bookshelf_check_unchecked_24);
        assertNotEquals(0, R.drawable.selector_bookshelf_edit_check);
        assertNotEquals(0, R.drawable.ic_bookshelf_select_all_24);
        assertNotEquals(0, R.drawable.ic_bookshelf_delete_24);
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
        assertNotEquals(0, R.id.mine_reading_summary);
        assertNotEquals(0, R.id.mine_books_entry);
        assertNotEquals(0, R.id.mine_settings_entry);
        assertNotEquals(0, R.id.mine_about_entry);
        assertNotEquals(0, R.drawable.bg_mine_avatar);
        assertNotEquals(0, R.drawable.ic_mine_avatar_default);
        assertNotEquals(0, R.layout.activity_reading_stats);
        assertNotEquals(0, R.id.reading_stats_back);
        assertNotEquals(0, R.id.reading_stats_total_value);
        assertNotEquals(0, R.id.reading_stats_today_value);
        assertNotEquals(0, R.id.reading_stats_week_value);
        assertNotEquals(0, R.layout.activity_about);
        assertNotEquals(0, R.id.about_back);
        assertNotEquals(0, R.id.about_version);
        assertNotEquals(0, R.id.book_store_empty_title);
        assertNotEquals(0, R.menu.menu_home_bottom);
        assertNotEquals(0, R.drawable.bg_home_reading_chip);
        assertNotEquals(0, R.drawable.ic_home_search_24);
        assertNotEquals(0, R.drawable.ic_home_import_24);
        assertNotEquals(0, R.drawable.ic_home_sync_24);
        assertNotEquals(0, R.drawable.bg_home_bottom_nav_shadow);
        assertNotEquals(0, R.drawable.ic_home_tab_bookshelf_24);
        assertNotEquals(0, R.drawable.ic_home_tab_bookstore_24);
        assertNotEquals(0, R.drawable.ic_home_tab_mine_24);
    }

    @Test
    public void minePageMovesOperationalActionsIntoSettings() throws IOException {
        assertNotEquals(0, R.layout.activity_settings);
        assertNotEquals(0, R.id.settings_back);
        assertNotEquals(0, R.id.settings_title);
        assertNotEquals(0, R.id.settings_account_entry);
        assertNotEquals(0, R.id.settings_sync_bookshelf);
        assertNotEquals(0, R.id.settings_local_import);
        assertNotEquals(0, R.id.settings_clear_cache);
        assertNotEquals(0, R.id.settings_cache_summary);
        assertNotEquals(0, R.id.settings_about_entry);
        assertNotEquals(0, R.drawable.ic_settings_back_32);
        assertNotEquals(0, R.drawable.bg_settings_section_gap);

        String mineLayout = readFile("src/main/res/layout/fragment_mine.xml");
        assertTrue(mineLayout.contains("android:text=\"阅读时长\""));
        assertTrue(mineLayout.contains("android:text=\"我的书籍\""));
        assertTrue(mineLayout.contains("android:text=\"设置\""));
        assertTrue(mineLayout.contains("android:text=\"关于本应用\""));
        assertFalse(mineLayout.contains("掌阅会员"));
        assertFalse(mineLayout.contains("我的账户"));
        assertFalse(mineLayout.contains("充值"));
        assertFalse(mineLayout.contains("我的书单"));
        assertFalse(mineLayout.contains("笔记评论"));
        assertFalse(mineLayout.contains("客服与反馈"));
        assertFalse(mineLayout.contains("证照信息"));
        assertFalse(mineLayout.contains("扫一扫"));
        assertFalse(mineLayout.contains("消息"));
        assertFalse(mineLayout.contains("同步书架"));
        assertFalse(mineLayout.contains("本机导入"));

        String settingsLayout = readFile("src/main/res/layout/activity_settings.xml");
        assertTrue(settingsLayout.contains("android:text=\"设置\""));
        assertTrue(settingsLayout.contains("android:text=\"账号与安全\""));
        assertTrue(settingsLayout.contains("android:text=\"书架备份与同步\""));
        assertTrue(settingsLayout.contains("android:text=\"本机书籍导入\""));
        assertTrue(settingsLayout.contains("android:text=\"清除缓存\""));
        assertTrue(settingsLayout.contains("android:text=\"关于本应用\""));
        assertFalse(settingsLayout.contains("隐私设置"));
        assertFalse(settingsLayout.contains("阅读设置"));
        assertFalse(settingsLayout.contains("消息通知"));
        assertFalse(settingsLayout.contains("夜间模式"));
        assertFalse(settingsLayout.contains("青少年模式"));
        assertFalse(settingsLayout.contains("个人阅读偏好"));
        assertFalse(settingsLayout.contains("书架推书"));
        assertFalse(settingsLayout.contains("自动充值服务"));
        assertFalse(settingsLayout.contains("检查更新"));

        String mineFragment = readFile("src/main/java/com/ldp/reader/ui/fragment/MineFragment.kt");
        assertTrue(mineFragment.contains("ReadingStatsActivity::class.java"));
        assertTrue(mineFragment.contains("SettingsActivity::class.java"));
        assertTrue(mineFragment.contains("AboutActivity::class.java"));
        assertTrue(mineFragment.contains("selectHomeTab(MainActivity.HomeTabKey.BOOKSHELF)"));
        assertFalse(mineFragment.contains("BookSyncEvent"));
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
        assertFalse(settingsActivity.contains("settingsShelfPushSwitch"));

        String mainActivity = readFile("src/main/java/com/ldp/reader/ui/activity/MainActivity.kt");
        assertTrue(mainActivity.contains("selectHomeTab(tabKey: HomeTabKey)"));
        assertTrue(mainActivity.contains("isMinePage"));
        assertTrue(mainActivity.contains("toolbar.visibility = if (isMinePage) View.GONE else View.VISIBLE"));
        assertTrue(mainActivity.contains("applyHomeStatusBarFlags()"));
        assertTrue(mainActivity.contains("fun requestBookShelfSync()"));
        assertTrue(mainActivity.contains("bookShelfFragment().requestBookShelfSync()"));

        String bookshelfFragment = readFile("src/main/java/com/ldp/reader/ui/fragment/BookShelfFragment.kt");
        assertTrue(bookshelfFragment.contains("fun requestBookShelfSync()"));
        assertTrue(bookshelfFragment.contains("loginSyncLauncher.launch(LoginActivity.syncIntent(requireContext()))"));
        assertFalse(bookshelfFragment.contains("RxBus"));
        assertFalse(bookshelfFragment.contains("BookSyncEvent"));
        assertFalse(bookshelfFragment.contains("ToastUtils.show(\"请登录\")"));
    }

    @Test
    public void bottomNavigationUsesReaderBlueSelectionColor() throws IOException {
        String selector = readFile("src/main/res/color/home_bottom_nav_item.xml");

        assertTrue(selector.contains("@color/home_primary"));
        String colors = readFile("src/main/res/values/colors.xml");
        assertTrue(colors.contains("<color name=\"home_primary\">#208BFF</color>"));
        assertFalse(colors.contains("<color name=\"home_primary\">#B05A3C</color>"));
        assertFalse(selector.contains("@color/home_accent"));
    }

    @Test
    public void homeAndMineTypographyUseReaderLikeDensityAndFonts() throws IOException {
        String toolbar = readFile("src/main/res/layout/toolbar_home.xml");
        assertTrue(toolbar.contains("android:layout_marginLeft=\"24dp\""));
        assertTrue(toolbar.contains("android:textSize=\"25sp\""));
        assertTrue(toolbar.contains("android:fontFamily=\"sans-serif-medium\""));

        String bookshelf = readFile("src/main/res/layout/fragment_bookshelf.xml");
        assertTrue(bookshelf.contains("android:paddingLeft=\"20dp\""));
        assertTrue(bookshelf.contains("android:layout_height=\"40dp\""));
        assertTrue(bookshelf.contains("android:layout_height=\"32dp\""));
        assertTrue(bookshelf.contains("android:textSize=\"14sp\""));
        assertTrue(bookshelf.contains("android:fontFamily=\"sans-serif\""));
        assertTrue(bookshelf.contains("android:visibility=\"gone\""));
        assertTrue(bookshelf.contains("android:includeFontPadding=\"false\""));
        assertFalse(bookshelf.contains("android:paddingBottom=\"1dp\""));

        String readingChip = readFile("src/main/res/drawable/bg_home_reading_chip.xml");
        assertTrue(readingChip.contains("android:top=\"0dp\""));
        assertTrue(readingChip.contains("android:bottom=\"0dp\""));
        assertFalse(readingChip.contains("android:top=\"8dp\""));
        assertFalse(readingChip.contains("android:bottom=\"8dp\""));

        String item = readFile("src/main/res/layout/item_coll_book.xml");
        assertTrue(item.contains("android:textSize=\"16sp\""));
        assertTrue(countOccurrences(item, "android:textSize=\"14sp\"") >= 2);
        assertTrue(item.contains("android:fontFamily=\"sans-serif\""));

        String mine = readFile("src/main/res/layout/fragment_mine.xml");
        assertTrue(mine.contains("android:background=\"@color/mine_bg\""));
        assertTrue(mine.contains("android:paddingTop=\"72dp\""));
        assertTrue(mine.contains("android:background=\"@drawable/ic_mine_avatar_default\""));
        assertTrue(mine.contains("android:layout_width=\"58dp\""));
        assertTrue(mine.contains("android:textColor=\"@color/mine_text_primary\""));
        assertTrue(mine.contains("android:textColor=\"@color/mine_text_secondary\""));
        assertTrue(mine.contains("android:textSize=\"21sp\""));
        assertTrue(mine.contains("android:textSize=\"18sp\""));
        assertTrue(mine.contains("android:fontFamily=\"sans-serif-medium\""));

        String colors = readFile("src/main/res/values/colors.xml");
        assertTrue(colors.contains("<color name=\"mine_bg\">#F7F8FA</color>"));
        assertTrue(colors.contains("<color name=\"mine_text_primary\">#171D29</color>"));
        assertTrue(colors.contains("<color name=\"mine_text_secondary\">#ADB2BC</color>"));
    }

    @Test
    public void bookshelfTopMenuAndTabIconsKeepReaderSpacing() throws IOException {
        String fragment = readFile("src/main/java/com/ldp/reader/ui/fragment/BookShelfFragment.kt");
        assertTrue(fragment.contains("ReaderBookGridSpacingDecoration"));
        assertTrue(fragment.contains("addItemDecoration(ReaderBookGridSpacingDecoration(3, dp(16)))"));
        assertTrue(fragment.contains("ReadingStatsActivity::class.java"));
        assertTrue(fragment.contains("homeReadingTimeChip?.setOnClickListener"));

        String activityMain = readFile("src/main/res/layout/activity_main.xml");
        assertTrue(activityMain.contains("android:id=\"@+id/home_bottom_nav_shadow\""));
        assertTrue(activityMain.contains("android:layout_height=\"3dp\""));
        assertTrue(activityMain.contains("android:background=\"@drawable/bg_home_bottom_nav_shadow\""));
        assertTrue(activityMain.contains("android:id=\"@+id/home_bottom_nav\"\n        android:layout_width=\"match_parent\"\n        android:layout_height=\"56dp\""));
        assertTrue(activityMain.contains("android:id=\"@+id/home_nav_bookshelf\""));
        assertTrue(activityMain.contains("android:id=\"@+id/home_nav_mine\""));
        assertEquals(2, countOccurrences(activityMain, "android:layout_width=\"24dp\""));
        assertEquals(2, countOccurrences(activityMain, "android:layout_height=\"24dp\""));
        assertEquals(2, countOccurrences(activityMain, "android:layout_marginTop=\"3dp\""));
        assertEquals(2, countOccurrences(activityMain, "android:textSize=\"13sp\""));
        assertEquals(4, countOccurrences(activityMain, "android:duplicateParentState=\"true\""));
        assertFalse(activityMain.contains("BottomNavigationView"));
        assertFalse(activityMain.contains("app:itemIconSize"));

        String bookshelfLayout = readFile("src/main/res/layout/fragment_bookshelf.xml");
        assertTrue(bookshelfLayout.contains("android:id=\"@+id/home_reading_time_chip\""));
        assertTrue(bookshelfLayout.contains("android:clickable=\"true\""));
        assertTrue(bookshelfLayout.contains("android:focusable=\"true\""));
        assertTrue(bookshelfLayout.contains("android:foreground=\"?attr/selectableItemBackgroundBorderless\""));

        String bottomShadow = readFile("src/main/res/drawable/bg_home_bottom_nav_shadow.xml");
        assertTrue(bottomShadow.contains("<layer-list"));
        assertTrue(bottomShadow.contains("android:startColor=\"#08000000\""));
        assertTrue(bottomShadow.contains("android:centerColor=\"#03000000\""));
        assertTrue(bottomShadow.contains("android:endColor=\"#00000000\""));
        assertTrue(bottomShadow.contains("android:color=\"#0A000000\""));

        String mainActivity = readFile("src/main/java/com/ldp/reader/ui/activity/MainActivity.kt");
        assertTrue(mainActivity.contains("applyBottomNavItemState("));
        assertTrue(mainActivity.contains("imageTintList = ColorStateList.valueOf(color)"));

        String searchIcon = readFile("src/main/res/drawable/ic_home_search_24.xml");
        assertTrue(searchIcon.contains("android:width=\"24dp\""));
        assertTrue(searchIcon.contains("android:height=\"24dp\""));
        assertTrue(searchIcon.contains("android:strokeColor=\"@color/home_text_primary\""));
        assertTrue(searchIcon.contains("android:strokeLineCap=\"round\""));

        String moreIcon = readFile("src/main/res/drawable/ic_home_more_horizontal_24.xml");
        assertTrue(moreIcon.contains("android:width=\"24dp\""));
        assertTrue(moreIcon.contains("android:height=\"24dp\""));

        String bookshelfIcon = readFile("src/main/res/drawable/ic_home_tab_bookshelf_24.xml");
        String mineIcon = readFile("src/main/res/drawable/ic_home_tab_mine_24.xml");
        assertTrue(bookshelfIcon.contains("android:width=\"24dp\""));
        assertTrue(mineIcon.contains("android:width=\"24dp\""));
        assertTrue(bookshelfIcon.contains("M4.2,3"));
        assertTrue(bookshelfIcon.contains("21.4"));
        assertTrue(mineIcon.contains("M12,1.6"));
        assertTrue(mineIcon.contains("22.8"));
        assertFalse(mineIcon.contains("M9.35,9.3H9.42"));
        assertFalse(mineIcon.contains("14.58,9.3H14.65"));
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
        assertNotEquals(0, R.id.home_menu_arrow);
        assertNotEquals(0, R.id.home_menu_import);
        assertNotEquals(0, R.id.home_menu_sync);
        assertNotEquals(0, R.id.home_menu_account);
        assertNotEquals(0, R.drawable.ic_home_more_horizontal_24);
        assertNotEquals(0, R.drawable.bg_home_more_popup);
        assertNotEquals(0, R.drawable.ic_home_more_popup_arrow);
        assertNotEquals(0, R.drawable.ic_home_menu_import_24);
        assertNotEquals(0, R.drawable.ic_home_menu_sync_24);
        assertNotEquals(0, R.drawable.ic_home_menu_account_24);
        assertNotEquals(0, R.integer.home_more_menu_anim_duration);

        String mainActivity = readFile("src/main/java/com/ldp/reader/ui/activity/MainActivity.kt");
        assertTrue(mainActivity.contains("HomeMoreMenuView"));
        assertTrue(mainActivity.contains("window.statusBarColor = Color.TRANSPARENT"));
        assertFalse(mainActivity.contains("PopupWindow"));
        assertFalse(mainActivity.contains("Color.parseColor(\"#66000000\")"));
        assertTrue(mainActivity.contains("actionMenuItemView(R.id.action_more_custom)"));

        String menuLayout = readFile("src/main/res/layout/view_home_more_menu.xml");
        assertTrue(menuLayout.contains("android:layout_width=\"14dp\""));
        assertTrue(menuLayout.contains("android:layout_height=\"7dp\""));
        assertTrue(menuLayout.contains("android:id=\"@+id/home_menu_popup\"\n        android:layout_width=\"wrap_content\""));
        assertFalse(menuLayout.contains("android:layout_width=\"178dp\""));
        assertEquals(3, countOccurrences(menuLayout, "android:layout_height=\"44dp\""));
        assertEquals(3, countOccurrences(menuLayout, "android:layout_width=\"20dp\""));
        assertEquals(3, countOccurrences(menuLayout, "android:layout_height=\"20dp\""));
        assertEquals(3, countOccurrences(menuLayout, "android:layout_marginLeft=\"8dp\""));
        assertEquals(3, countOccurrences(menuLayout, "android:paddingLeft=\"16dp\""));
        assertEquals(3, countOccurrences(menuLayout, "android:paddingRight=\"16dp\""));
        assertEquals(2, countOccurrences(menuLayout, "android:maxWidth=\"76dp\""));
        assertEquals(1, countOccurrences(menuLayout, "android:maxWidth=\"104dp\""));
        assertEquals(1, countOccurrences(menuLayout, "android:maxEms=\"11\""));
        assertEquals(3, countOccurrences(menuLayout, "android:ellipsize=\"end\""));
        assertEquals(3, countOccurrences(menuLayout, "android:maxLines=\"1\""));
        assertEquals(3, countOccurrences(menuLayout, "android:textSize=\"16sp\""));
        assertTrue(menuLayout.contains("@drawable/ic_home_menu_import_24"));
        assertTrue(menuLayout.contains("@drawable/ic_home_menu_sync_24"));
        assertTrue(menuLayout.contains("@drawable/ic_home_menu_account_24"));
        assertTrue(menuLayout.contains("android:tint=\"@color/home_menu_item\""));
        assertTrue(menuLayout.contains("android:textColor=\"@color/home_menu_item\""));
        assertTrue(countOccurrences(menuLayout, "android:tint=\"@color/home_menu_item\"") >= 3);
        assertTrue(countOccurrences(menuLayout, "android:textColor=\"@color/home_menu_item\"") >= 3);

        String menu = readFile("src/main/res/menu/menu_main.xml");
        assertTrue(menu.contains("android:icon=\"@drawable/ic_home_search_24\""));

        String menuView = readFile("src/main/java/com/ldp/reader/ui/widget/HomeMoreMenuView.kt");
        assertTrue(menuView.contains("ViewGroup.LayoutParams.WRAP_CONTENT"));
        assertTrue(menuView.contains("measurePopupWidth"));
        assertTrue(menuView.contains("val arrowWidth = dp(14)"));
        assertTrue(menuView.contains("popupRight = (anchorCenterX + dp(18))"));
        assertFalse(menuView.contains("val popupWidth = dp(178)"));
    }

    @Test
    public void bookshelfItemKeepsReaderCardOrderAndEditControls() throws IOException {
        String itemLayout = readFile("src/main/res/layout/item_coll_book.xml");
        assertTrue(itemLayout.contains("ShapeableImageView"));
        assertTrue(itemLayout.contains("shapeAppearanceOverlay=\"@style/ShapeAppearance.Reader.BookCover\""));
        assertTrue(itemLayout.contains("android:layout_height=\"132dp\""));
        assertTrue(itemLayout.contains("coll_book_local_cover"));
        assertTrue(itemLayout.contains("@drawable/bg_bookshelf_local_cover"));
        assertTrue(itemLayout.contains("@drawable/ic_bookshelf_local_fold_40"));
        assertTrue(itemLayout.contains("android:button=\"@drawable/selector_bookshelf_edit_check\""));
        assertTrue(itemLayout.contains("android:clickable=\"false\""));
        assertTrue(itemLayout.contains("android:focusable=\"false\""));
        assertTrue(itemLayout.indexOf("coll_book_tv_lately_update") < itemLayout.indexOf("coll_book_tv_chapter"));

        String bookshelfLayout = readFile("src/main/res/layout/fragment_bookshelf.xml");
        assertTrue(bookshelfLayout.contains("android:drawableTop=\"@drawable/ic_bookshelf_select_all_24\""));
        assertTrue(bookshelfLayout.contains("android:drawableTop=\"@drawable/ic_bookshelf_delete_24\""));
        assertTrue(bookshelfLayout.contains("android:drawablePadding=\"4dp\""));

        String holder = readFile("src/main/java/com/ldp/reader/ui/adapter/view/CollBookHolder.kt");
        assertTrue(holder.contains("coverTitle(value.title)"));
        assertTrue(holder.contains("fileTypeLabel(value.cover)"));
        assertTrue(holder.contains("progressLabel("));
        assertTrue(holder.contains("mTvChapter.visibility = View.GONE"));
        assertFalse(holder.contains("BookshelfLocalBookUi"));
        assertFalse(holder.contains("String.format(\"  %s\""));
        assertFalse(holder.contains("R.drawable.ic_local_file"));

        String fragment = readFile("src/main/java/com/ldp/reader/ui/fragment/BookShelfFragment.kt");
        assertTrue(fragment.contains("exitEditModeIfNeeded"));

        String mainActivity = readFile("src/main/java/com/ldp/reader/ui/activity/MainActivity.kt");
        assertTrue(mainActivity.contains("exitBookshelfEditModeIfNeeded"));
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
        assertTrue(repository.contains("mBookStore.replaceBookChapters(bookId, beans)"));

        String readPresenter = readFile("src/main/java/com/ldp/reader/presenter/ReadPresenter.kt");
        assertTrue(readPresenter.contains("start = bookChapterBeans.size.toLong()"));
        String shelfPresenter = readFile("src/main/java/com/ldp/reader/presenter/BookShelfPresenter.kt");
        assertTrue(shelfPresenter.contains("start = bookChapterBeans.size.toLong()"));
        assertTrue(shelfPresenter.contains("chaptersCount = bookChapterBeans.size"));
        assertTrue(shelfPresenter.contains("isReadableFolderStale"));
        assertTrue(shelfPresenter.contains("getBookRecord"));
        String detailPresenter = readFile("src/main/java/com/ldp/reader/presenter/BookDetailPresenter.kt");
        assertTrue(detailPresenter.contains("start = bookChapterBeans.size.toLong()"));
        assertTrue(detailPresenter.contains("chaptersCount = bookChapterBeans.size"));
    }

    @Test
    public void loginTriggeredBookshelfSyncToleratesEmptyOrPartialServerShelf() throws IOException {
        String presenter = readFile("src/main/java/com/ldp/reader/presenter/BookShelfPresenter.kt");

        assertTrue(presenter.contains("if (bookIdBeans != null)"));
        assertTrue(presenter.contains("if (bookIdBean != null && bookIdBean.bookId != 0)"));
        assertTrue(presenter.contains("if (bookIdList == null || bookIdList.isEmpty())"));
        assertTrue(presenter.contains("updateShelf(ArrayList())"));
        assertTrue(presenter.contains("onlineBookIdsFrom(collBooks)"));
    }

    @Test
    public void bookshelfFilterUsesReaderStyleOverlayAndNeutralEmptyState() throws IOException {
        assertNotEquals(0, R.layout.view_bookshelf_filter_menu);
        assertNotEquals(0, R.id.bookshelf_filter_overlay);
        assertNotEquals(0, R.id.bookshelf_filter_scrim);
        assertNotEquals(0, R.id.bookshelf_filter_popup);
        assertNotEquals(0, R.id.bookshelf_filter_arrow);
        assertNotEquals(0, R.id.bookshelf_filter_all);
        assertNotEquals(0, R.id.bookshelf_filter_status_section);
        assertNotEquals(0, R.id.bookshelf_filter_status_3_days);
        assertNotEquals(0, R.id.bookshelf_filter_status_7_days);
        assertNotEquals(0, R.id.bookshelf_filter_progress_section);
        assertNotEquals(0, R.id.bookshelf_filter_progress_unread);
        assertNotEquals(0, R.id.bookshelf_filter_progress_reading);
        assertNotEquals(0, R.id.bookshelf_filter_progress_finished);
        assertNotEquals(0, R.id.bookshelf_filter_local_section);
        assertNotEquals(0, R.id.bookshelf_filter_local);
        assertNotEquals(0, R.drawable.bg_bookshelf_filter_popup);
        assertNotEquals(0, R.drawable.ic_bookshelf_filter_arrow);
        assertNotEquals(0, R.drawable.bg_bookshelf_filter_option);
        assertNotEquals(0, R.drawable.bg_bookshelf_filter_option_selected);
        assertNotEquals(0, R.drawable.bg_bookshelf_filter_empty_primary);
        assertNotEquals(0, R.drawable.bg_bookshelf_filter_empty_secondary);

        String filterLayout = readFile("src/main/res/layout/view_bookshelf_filter_menu.xml");
        assertTrue(filterLayout.contains("android:text=\"全部书籍\""));
        assertTrue(filterLayout.contains("android:text=\"更新状态\""));
        assertTrue(filterLayout.contains("android:text=\"3日内更新\""));
        assertTrue(filterLayout.contains("android:text=\"7日内更新\""));
        assertTrue(filterLayout.contains("android:text=\"阅读进度\""));
        assertTrue(filterLayout.contains("android:text=\"尚未阅读\""));
        assertTrue(filterLayout.contains("android:text=\"正在阅读\""));
        assertTrue(filterLayout.contains("android:text=\"已读完\""));
        assertTrue(filterLayout.contains("android:text=\"本地文件\""));
        assertTrue(filterLayout.contains("android:text=\"本地书\""));
        assertEquals(0, countOccurrences(filterLayout, "android:text=\"全部\""));
        assertFalse(filterLayout.contains("在线书籍"));
        assertFalse(filterLayout.contains("购买/折扣"));

        String bookshelfLayout = readFile("src/main/res/layout/fragment_bookshelf.xml");
        assertTrue(bookshelfLayout.contains("android:text=\"重拾阅读习惯，从添加一本书开始\""));
        assertTrue(bookshelfLayout.contains("android:text=\"全部书籍\""));
        assertTrue(bookshelfLayout.contains("android:text=\"去导入\""));
        assertTrue(bookshelfLayout.indexOf("home_bookshelf_filter_empty_reset")
                < bookshelfLayout.indexOf("home_bookshelf_filter_empty_import"));

        String emptyShelfLayout = readFile("src/main/res/layout/view_empty_book_shelf.xml");
        assertTrue(emptyShelfLayout.contains("android:text=\"重拾阅读习惯，从添加一本书开始\""));
        assertTrue(emptyShelfLayout.contains("android:text=\"去导入\""));
        assertFalse(emptyShelfLayout.contains("android:text=\"全部书籍\""));
        assertFalse(emptyShelfLayout.contains("添加你喜爱的小说"));
        assertFalse(emptyShelfLayout.contains("android:text=\"去添加\""));
        assertFalse(emptyShelfLayout.contains("book_shelf_tv_add"));

        String fragment = readFile("src/main/java/com/ldp/reader/ui/fragment/BookShelfFragment.kt");
        assertTrue(fragment.contains("BookshelfFilterMenuView"));
        assertTrue(fragment.contains("BookShelfPresenter.filterToolbarLabel"));
        assertTrue(fragment.contains("BookShelfPresenter.shouldShowFilterEmpty"));
        assertTrue(fragment.contains("BookShelfPresenter.filterEmptyTitle"));
        assertTrue(fragment.contains("BookShelfPresenter.filterEmptyResetText"));
        assertTrue(fragment.contains("BookShelfPresenter.emptyImportText"));
        assertTrue(fragment.contains("homeBookshelfFilterEmptyReset"));
        assertTrue(fragment.contains("ViewEmptyBookShelfBinding.bind"));
        assertTrue(fragment.contains("bookShelfEmptyImport"));
        assertFalse(fragment.contains("AlertDialog.Builder(requireContext())\n            .setItems"));
        assertFalse(fragment.contains("ShelfFilter.ONLINE"));
    }

    @Test
    public void searchPageKeepsReaderStyleResourceContract() {
        assertNotEquals(0, R.layout.activity_search);
        assertNotEquals(0, R.id.search_toolbar);
        assertNotEquals(0, R.id.search_input_container);
        assertNotEquals(0, R.id.search_assistant_entry);
        assertNotEquals(0, R.id.search_hot_panel);
        assertNotEquals(0, R.id.search_hot_title);
        assertNotEquals(0, R.id.search_iv_back);
        assertNotEquals(0, R.id.search_et_input);
        assertNotEquals(0, R.id.search_iv_delete);
        assertNotEquals(0, R.id.search_iv_search);
        assertNotEquals(0, R.id.search_book_tv_refresh_hot);
        assertNotEquals(0, R.id.search_tg_hot);
        assertNotEquals(0, R.drawable.bg_search_input_pill);
        assertNotEquals(0, R.drawable.bg_search_assistant_entry);
        assertNotEquals(0, R.drawable.bg_search_panel);
    }

    @Test
    public void bookDetailPageKeepsReaderStyleResourceContract() {
        assertNotEquals(0, R.layout.activity_book_detail);
        assertNotEquals(0, R.id.book_detail_top_bar);
        assertNotEquals(0, R.id.book_detail_header);
        assertNotEquals(0, R.id.book_detail_info_panel);
        assertNotEquals(0, R.id.book_detail_brief_panel);
        assertNotEquals(0, R.id.book_detail_bottom_bar);
        assertNotEquals(0, R.id.book_detail_iv_cover);
        assertNotEquals(0, R.id.book_detail_tv_title);
        assertNotEquals(0, R.id.book_detail_tv_author);
        assertNotEquals(0, R.id.book_detail_tv_last_chapter);
        assertNotEquals(0, R.id.book_detail_tv_brief);
        assertNotEquals(0, R.id.book_list_ll_chase);
        assertNotEquals(0, R.id.book_list_av_chase);
        assertNotEquals(0, R.id.book_list_tv_chase);
        assertNotEquals(0, R.id.book_detail_tv_read);
        assertNotEquals(0, R.drawable.bg_book_detail_cover);
        assertNotEquals(0, R.drawable.bg_book_detail_action_primary);
        assertNotEquals(0, R.drawable.bg_book_detail_action_secondary);
        assertNotEquals(0, R.drawable.ic_book_detail_back_24);
        assertNotEquals(0, R.drawable.ic_book_detail_more_24);
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
