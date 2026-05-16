package com.ldp.reader.kotlin;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;

public class KotlinMigrationContractTest {

    @Test
    public void firstDataModelBatchIsKotlin() {
        assertKotlinOnly("src/main/java/com/ldp/reader/event/BookSyncEvent");
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
        assertKotlinOnly("src/main/java/com/ldp/reader/presenter/contract/SearchContract");
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
    public void kotlinMigrationCountsMovedForward() {
        assertTrue(countFiles(new File("src/main/java"), ".kt") >= 85);
        assertTrue(countFiles(new File("src/main/java"), ".java") <= 69);
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
