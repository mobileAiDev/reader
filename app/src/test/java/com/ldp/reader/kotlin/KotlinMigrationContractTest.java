package com.ldp.reader.kotlin;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;

public class KotlinMigrationContractTest {

    @Test
    public void firstDataModelBatchIsKotlin() {
        assertKotlinOnly("src/main/java/com/ldp/reader/model/bean/BaseBean");
        assertKotlinOnly("src/main/java/com/ldp/reader/model/bean/BookIdBean");
        assertKotlinOnly("src/main/java/com/ldp/reader/model/bean/ChapterBean");
        assertKotlinOnly("src/main/java/com/ldp/reader/model/bean/ContentBean");
        assertKotlinOnly("src/main/java/com/ldp/reader/model/bean/packages/HotWordPackage");
        assertKotlinOnly("src/main/java/com/ldp/reader/model/bean/packages/KeyWordPackage");
        assertKotlinOnly("src/main/java/com/ldp/reader/model/local/Void");
    }

    @Test
    public void loginAndShelfSyncModelBatchIsKotlin() {
        assertKotlinOnly("src/main/java/com/ldp/reader/model/bean/DirectLoginResultBean");
        assertKotlinOnly("src/main/java/com/ldp/reader/model/bean/LoginResultBean");
        assertKotlinOnly("src/main/java/com/ldp/reader/model/bean/SmsLoginBean");
        assertKotlinOnly("src/main/java/com/ldp/reader/model/bean/SyncBookShelfBean");
        assertKotlinOnly("src/main/java/com/ldp/reader/model/bean/DirectSycBookShelfBean");
    }

    @Test
    public void storageModelBatchIsKotlin() {
        assertKotlinOnly("src/main/java/com/ldp/reader/model/bean/BookRecordBean");
        assertKotlinOnly("src/main/java/com/ldp/reader/model/bean/BookChapterBean");
    }

    @Test
    public void remoteBookModelBatchIsKotlin() {
        assertKotlinOnly("src/main/java/com/ldp/reader/model/bean/BookSearchResult");
        assertKotlinOnly("src/main/java/com/ldp/reader/model/bean/BookDetailBeanInOwn");
    }

    @Test
    public void collBookModelIsKotlin() {
        assertKotlinOnly("src/main/java/com/ldp/reader/model/bean/CollBookBean");
    }

    @Test
    public void foundationUtilityAndAdapterBatchIsKotlin() {
        assertKotlinOnly("src/main/java/com/ldp/reader/widget/page/PageMode");
        assertKotlinOnly("src/main/java/com/ldp/reader/widget/page/PageStyle");
        assertKotlinOnly("src/main/java/com/ldp/reader/widget/page/TxtPage");
        assertKotlinOnly("src/main/java/com/ldp/reader/widget/tab/TabItem");
        assertKotlinOnly("src/main/java/com/ldp/reader/widget/tab/TabView");
        assertKotlinOnly("src/main/java/com/ldp/reader/utils/ToastUtils");
        assertKotlinOnly("src/main/java/com/ldp/reader/utils/IOUtils");
        assertKotlinOnly("src/main/java/com/ldp/reader/utils/Charset");
        assertKotlinOnly("src/main/java/com/ldp/reader/utils/media/LoaderCreator");
        assertKotlinOnly("src/main/java/com/ldp/reader/ui/base/BaseContract");
        assertKotlinOnly("src/main/java/com/ldp/reader/ui/base/adapter/IViewHolder");
        assertKotlinOnly("src/main/java/com/ldp/reader/ui/base/adapter/BaseViewHolder");
        assertKotlinOnly("src/main/java/com/ldp/reader/ui/base/adapter/ViewHolderImpl");
        assertKotlinOnly("src/main/java/com/ldp/reader/ui/adapter/KeyWordAdapter");
        assertKotlinOnly("src/main/java/com/ldp/reader/ui/adapter/SearchBookAdapter");
    }

    @Test
    public void apiAndPresenterContractBatchIsKotlin() {
        assertKotlinOnly("src/main/java/com/ldp/reader/model/remote/BookApi");
        assertKotlinOnly("src/main/java/com/ldp/reader/model/remote/BookApiOwn");
        assertKotlinOnly("src/main/java/com/ldp/reader/presenter/contract/BookShelfContract");
        assertKotlinOnly("src/main/java/com/ldp/reader/presenter/contract/BookDetailContract");
        assertKotlinOnly("src/main/java/com/ldp/reader/presenter/contract/LoginContract");
        assertKotlinOnly("src/main/java/com/ldp/reader/presenter/contract/ReadContract");
    }

    @Test
    public void thinUtilityAndAdapterBatchIsKotlin() {
        assertKotlinOnly("src/main/java/com/ldp/reader/utils/Constant");
        assertKotlinOnly("src/main/java/com/ldp/reader/ui/home/BookshelfLocalProgressStore");
        assertKotlinOnly("src/main/java/com/ldp/reader/utils/PermissionsChecker");
        assertKotlinOnly("src/main/java/com/ldp/reader/ui/adapter/view/KeyWordHolder");
        assertKotlinOnly("src/main/java/com/ldp/reader/ui/adapter/view/PageStyleHolder");
        assertKotlinOnly("src/main/java/com/ldp/reader/ui/adapter/CategoryAdapter");
        assertKotlinOnly("src/main/java/com/ldp/reader/ui/adapter/PageStyleAdapter");
        assertKotlinOnly("src/main/java/com/ldp/reader/utils/RxUtils");
        assertKotlinOnly("src/main/java/com/ldp/reader/utils/MD5Utils");
        assertKotlinOnly("src/main/java/com/ldp/reader/utils/FileStack");
        assertKotlinOnly("src/main/java/com/ldp/reader/utils/NetworkUtils");
    }

    @Test
    public void remainingListAdapterBatchIsKotlin() {
        assertKotlinOnly("src/main/java/com/ldp/reader/ui/adapter/view/CategoryHolder");
        assertKotlinOnly("src/main/java/com/ldp/reader/ui/adapter/view/SearchBookHolder");
        assertKotlinOnly("src/main/java/com/ldp/reader/ui/adapter/view/FileHolder");
        assertKotlinOnly("src/main/java/com/ldp/reader/ui/adapter/view/CollBookHolder");
        assertKotlinOnly("src/main/java/com/ldp/reader/ui/adapter/CollBookAdapter");
        assertKotlinOnly("src/main/java/com/ldp/reader/ui/adapter/FileSystemAdapter");
    }

    @Test
    public void baseAdapterAndLoadMoreBatchIsKotlin() {
        assertKotlinOnly("src/main/java/com/ldp/reader/ui/base/adapter/BaseListAdapter");
        assertKotlinOnly("src/main/java/com/ldp/reader/ui/base/adapter/GroupAdapter");
        assertKotlinOnly("src/main/java/com/ldp/reader/ui/base/BaseAdapter");
        assertKotlinOnly("src/main/java/com/ldp/reader/ui/base/EasyAdapter");
        assertKotlinOnly("src/main/java/com/ldp/reader/widget/adapter/LoadMoreDelegate");
        assertKotlinOnly("src/main/java/com/ldp/reader/widget/adapter/LoadMoreView");
        assertKotlinOnly("src/main/java/com/ldp/reader/widget/adapter/WholeAdapter");
    }

    @Test
    public void objectBoxLayerBatchIsKotlin() {
        assertKotlinOnly("src/main/java/com/ldp/reader/model/objectbox/ObjectBoxBookChapterEntity");
        assertKotlinOnly("src/main/java/com/ldp/reader/model/objectbox/ObjectBoxBookRecordEntity");
        assertKotlinOnly("src/main/java/com/ldp/reader/model/objectbox/ObjectBoxCollBookEntity");
        assertKotlinOnly("src/main/java/com/ldp/reader/model/objectbox/ObjectBoxBookRecordStore");
        assertKotlinOnly("src/main/java/com/ldp/reader/model/objectbox/ObjectBoxBookStore");
        assertKotlinOnly("src/main/java/com/ldp/reader/model/objectbox/ObjectBoxDbHelper");
    }

    @Test
    public void appStorageAndEventBusBatchIsKotlin() {
        assertKotlinOnly("src/main/java/com/ldp/reader/App");
        assertKotlinOnly("src/main/java/com/ldp/reader/utils/SharedPreUtils");
    }

    @Test
    public void rxBusAndBookSyncEventAreRemoved() {
        assertFalse(new File("src/main/java/com/ldp/reader/RxBus.kt").exists());
        assertFalse(new File("src/main/java/com/ldp/reader/event/BookSyncEvent.kt").exists());
        assertKotlinOnly("src/main/java/com/ldp/reader/ui/home/BookshelfSyncRequest");
    }

    @Test
    public void staticUtilityObjectBatchIsKotlin() {
        assertKotlinOnly("src/main/java/com/ldp/reader/utils/CacheUtils");
        assertKotlinOnly("src/main/java/com/ldp/reader/utils/BrightnessUtils");
        assertKotlinOnly("src/main/java/com/ldp/reader/utils/SimilarityCharacterUtils");
        assertKotlinOnly("src/main/java/com/ldp/reader/utils/media/MediaStoreHelper");
    }

    @Test
    public void localFileFragmentBatchIsKotlin() {
        assertKotlinOnly("src/main/java/com/ldp/reader/ui/fragment/BaseFileFragment");
        assertKotlinOnly("src/main/java/com/ldp/reader/ui/fragment/FileCategoryFragment");
        assertKotlinOnly("src/main/java/com/ldp/reader/ui/fragment/LocalBookFragment");
    }

    @Test
    public void smallWidgetUtilityBatchIsKotlin() {
        assertKotlinOnly("src/main/java/com/ldp/reader/widget/BookTextView");
        assertKotlinOnly("src/main/java/com/ldp/reader/widget/ReboundScrollView");
        assertKotlinOnly("src/main/java/com/ldp/reader/widget/CustomTextView");
        assertKotlinOnly("src/main/java/com/ldp/reader/widget/CustomExpandableListView");
        assertKotlinOnly("src/main/java/com/ldp/reader/widget/transform/CircleTransform");
        assertKotlinOnly("src/main/java/com/ldp/reader/widget/itemdecoration/DividerItemDecoration");
        assertKotlinOnly("src/main/java/com/ldp/reader/widget/itemdecoration/DividerGridItemDecoration");
        assertKotlinOnly("src/main/java/com/ldp/reader/widget/animation/BezierEvaluator");
    }

    @Test
    public void textAndScreenUtilityBatchIsKotlin() {
        assertKotlinOnly("src/main/java/com/ldp/reader/utils/LogUtils");
        assertKotlinOnly("src/main/java/com/ldp/reader/utils/SystemBarUtils");
        assertKotlinOnly("src/main/java/com/ldp/reader/utils/ScreenUtils");
        assertKotlinOnly("src/main/java/com/ldp/reader/utils/ReadingStatsUtils");
        assertKotlinOnly("src/main/java/com/ldp/reader/utils/StringUtils");
    }

    @Test
    public void tabSelectorAndChapterWidgetBatchIsKotlin() {
        assertKotlinOnly("src/main/java/com/ldp/reader/widget/EasyRatingBar");
        assertKotlinOnly("src/main/java/com/ldp/reader/widget/SelectorView");
        assertKotlinOnly("src/main/java/com/ldp/reader/widget/tab/TabTextView");
        assertKotlinOnly("src/main/java/com/ldp/reader/widget/tab/TabViewGroup");
        assertKotlinOnly("src/main/java/com/ldp/reader/widget/tab/ScrollTab");
        assertKotlinOnly("src/main/java/com/ldp/reader/widget/page/TxtChapter");
    }

    @Test
    public void refreshWidgetBatchIsKotlin() {
        assertKotlinOnly("src/main/java/com/ldp/reader/widget/RefreshLayout");
        assertKotlinOnly("src/main/java/com/ldp/reader/widget/ScrollRefreshLayout");
        assertKotlinOnly("src/main/java/com/ldp/reader/widget/refresh/RefreshLayout");
        assertKotlinOnly("src/main/java/com/ldp/reader/widget/refresh/RefreshRecyclerView");
        assertKotlinOnly("src/main/java/com/ldp/reader/widget/refresh/ScrollRefreshLayout");
        assertKotlinOnly("src/main/java/com/ldp/reader/widget/refresh/ScrollRefreshRecyclerView");
    }

    @Test
    public void remoteInfrastructureBatchIsKotlin() {
        assertKotlinOnly("src/main/java/com/ldp/reader/model/remote/RemoteHelper");
        assertKotlinOnly("src/main/java/com/ldp/reader/model/remote/RemoteRepository");
        assertKotlinOnly("src/main/java/com/ldp/reader/utils/LenientGsonConverterFactory");
    }

    @Test
    public void localSettingsAndMediaLoaderBatchIsKotlin() {
        assertKotlinOnly("src/main/java/com/ldp/reader/model/local/ReadSettingManager");
        assertKotlinOnly("src/main/java/com/ldp/reader/utils/media/LocalFileLoader");
    }

    @Test
    public void presenterBaseAndReadSettingDialogBatchIsKotlin() {
        assertKotlinOnly("src/main/java/com/ldp/reader/ui/base/RxPresenter");
        assertKotlinOnly("src/main/java/com/ldp/reader/ui/dialog/ReadSettingDialog");
    }

    @Test
    public void fileUtilsBatchIsKotlin() {
        assertKotlinOnly("src/main/java/com/ldp/reader/utils/FileUtils");
    }

    @Test
    public void bookManagerBatchIsKotlin() {
        assertKotlinOnly("src/main/java/com/ldp/reader/utils/BookManager");
    }

    @Test
    public void searchAndLoginPresentersBatchIsKotlin() {
        assertKotlinOnly("src/main/java/com/ldp/reader/presenter/LoginPresenter");
    }

    @Test
    public void searchFeatureIsMvvm() {
        assertFalse(new File("src/main/java/com/ldp/reader/presenter/SearchPresenter.kt").exists());
        assertFalse(new File("src/main/java/com/ldp/reader/presenter/contract/SearchContract.kt").exists());
        assertKotlinOnly("src/main/java/com/ldp/reader/ui/activity/SearchViewModel");
    }

    @Test
    public void readAndDetailPresentersBatchIsKotlin() {
        assertKotlinOnly("src/main/java/com/ldp/reader/presenter/ReadPresenter");
        assertKotlinOnly("src/main/java/com/ldp/reader/presenter/BookDetailPresenter");
    }

    @Test
    public void bookShelfPresenterBatchIsKotlin() {
        assertKotlinOnly("src/main/java/com/ldp/reader/presenter/BookShelfPresenter");
    }

    @Test
    public void bookRepositoryBatchIsKotlin() {
        assertKotlinOnly("src/main/java/com/ldp/reader/model/local/BookRepository");
    }

    @Test
    public void pageAnimationBatchIsKotlin() {
        assertKotlinOnly("src/main/java/com/ldp/reader/widget/animation/AnimationProvider");
        assertKotlinOnly("src/main/java/com/ldp/reader/widget/animation/PageAnimation");
        assertKotlinOnly("src/main/java/com/ldp/reader/widget/animation/HorizonPageAnim");
        assertKotlinOnly("src/main/java/com/ldp/reader/widget/animation/CoverPageAnim");
        assertKotlinOnly("src/main/java/com/ldp/reader/widget/animation/SlidePageAnim");
        assertKotlinOnly("src/main/java/com/ldp/reader/widget/animation/NonePageAnim");
        assertKotlinOnly("src/main/java/com/ldp/reader/widget/animation/ScrollPageAnim");
        assertKotlinOnly("src/main/java/com/ldp/reader/widget/animation/SimulationPageAnim");
    }

    @Test
    public void pageViewBatchIsKotlin() {
        assertKotlinOnly("src/main/java/com/ldp/reader/widget/page/PageView");
    }

    @Test
    public void unusedEncryptUtilsJavaIsRemoved() {
        assertFalse(new File("src/main/java/com/ldp/reader/utils/EncryptUtils.java").exists());
    }

    @Test
    public void netPageLoaderBatchIsKotlin() {
        assertKotlinOnly("src/main/java/com/ldp/reader/widget/page/NetPageLoader");
    }

    @Test
    public void localPageLoaderBatchIsKotlin() {
        assertKotlinOnly("src/main/java/com/ldp/reader/widget/page/LocalPageLoader");
    }

    @Test
    public void pageLoaderBaseBatchIsKotlin() {
        assertKotlinOnly("src/main/java/com/ldp/reader/widget/page/PageLoader");
    }

    @Test
    public void kotlinMigrationCountsMovedForward() {
        assertTrue(countFiles(new File("src/main/java"), ".kt") >= 151);
        assertTrue(countFiles(new File("src/main/java"), ".java") <= 0);
    }

    private static void assertKotlinOnly(String pathWithoutExtension) {
        assertTrue(new File(pathWithoutExtension + ".kt").exists());
        assertFalse(new File(pathWithoutExtension + ".java").exists());
    }

    private static int countFiles(File root, String extension) {
        File[] files = root.listFiles();
        if (files == null) {
            return 0;
        }
        int count = 0;
        for (File file : files) {
            if (file.isDirectory()) {
                count += countFiles(file, extension);
            } else if (file.getName().endsWith(extension)) {
                count++;
            }
        }
        return count;
    }
}
