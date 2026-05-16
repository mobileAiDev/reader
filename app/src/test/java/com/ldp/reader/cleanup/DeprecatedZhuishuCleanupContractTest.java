package com.ldp.reader.cleanup;

import static org.junit.Assert.assertFalse;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

public class DeprecatedZhuishuCleanupContractTest {

    @Test
    public void manifestDoesNotKeepCommentedLegacyDiscoveryEntries() throws Exception {
        String manifest = new String(
                Files.readAllBytes(new File("src/main/AndroidManifest.xml").toPath()),
                StandardCharsets.UTF_8
        );

        String[] retiredActivities = {
                "BookDiscussionActivity",
                "DiscDetailActivity",
                "BillboardActivity",
                "BookSortActivity",
                "BookSortListActivity",
                "BookListActivity",
                "BookListDetailActivity",
                "BillBookActivity",
                "OtherBillBookActivity",
                "DownloadActivity",
                "CommunityActivity",
                "MoreSettingActivity"
        };

        for (String retiredActivity : retiredActivities) {
            assertFalse(
                    "Manifest should not keep commented legacy entry for " + retiredActivity,
                    manifest.contains(retiredActivity)
            );
        }
    }

    @Test
    public void wholeCommentedLegacySourceStubsAreRemoved() {
        String[] retiredSources = {
                "src/main/java/com/ldp/reader/ui/activity/DownloadActivity.java",
                "src/main/java/com/ldp/reader/presenter/BillBookPresenter.java",
                "src/main/java/com/ldp/reader/presenter/BillboardPresenter.java",
                "src/main/java/com/ldp/reader/presenter/BookListDetailPresenter.java",
                "src/main/java/com/ldp/reader/presenter/BookListPresenter.java",
                "src/main/java/com/ldp/reader/presenter/BookSortListPresenter.java",
                "src/main/java/com/ldp/reader/presenter/BookSortPresenter.java",
                "src/main/java/com/ldp/reader/presenter/CommentDetailPresenter.java",
                "src/main/java/com/ldp/reader/presenter/DiscCommentPresenter.java",
                "src/main/java/com/ldp/reader/presenter/DiscHelpsPresenter.java",
                "src/main/java/com/ldp/reader/presenter/DiscReviewPresenter.java",
                "src/main/java/com/ldp/reader/presenter/HelpsDetailPresenter.java",
                "src/main/java/com/ldp/reader/presenter/ReviewDetailPresenter.java",
                "src/main/java/com/ldp/reader/presenter/contract/BillBookContract.java",
                "src/main/java/com/ldp/reader/presenter/contract/BillboardContract.java",
                "src/main/java/com/ldp/reader/presenter/contract/BookListDetailContract.java",
                "src/main/java/com/ldp/reader/presenter/contract/BookListContract.java",
                "src/main/java/com/ldp/reader/presenter/contract/BookSortContract.java",
                "src/main/java/com/ldp/reader/presenter/contract/BookSortListContract.java",
                "src/main/java/com/ldp/reader/presenter/contract/CommentDetailContract.java",
                "src/main/java/com/ldp/reader/presenter/contract/DiscCommentContact.java",
                "src/main/java/com/ldp/reader/presenter/contract/DiscHelpsContract.java",
                "src/main/java/com/ldp/reader/presenter/contract/DiscReviewContract.java",
                "src/main/java/com/ldp/reader/presenter/contract/HelpsDetailContract.java",
                "src/main/java/com/ldp/reader/presenter/contract/ReviewDetailContract.java",
                "src/main/java/com/ldp/reader/ui/adapter/BillboardAdapter.java",
                "src/main/java/com/ldp/reader/ui/base/BaseTabActivity.java"
        };

        assertFilesRemoved("Remove whole-commented legacy source stub: ", retiredSources);
    }

    @Test
    public void unreachableLegacyDiscoveryUiLayerIsRemoved() {
        String[] retiredSources = {
                "src/main/java/com/ldp/reader/ui/adapter/BillBookAdapter.java",
                "src/main/java/com/ldp/reader/ui/adapter/BookListDetailAdapter.java",
                "src/main/java/com/ldp/reader/ui/adapter/BookSortAdapter.java",
                "src/main/java/com/ldp/reader/ui/adapter/BookSortListAdapter.java",
                "src/main/java/com/ldp/reader/ui/adapter/CommentAdapter.java",
                "src/main/java/com/ldp/reader/ui/adapter/DiscCommentAdapter.java",
                "src/main/java/com/ldp/reader/ui/adapter/DiscHelpsAdapter.java",
                "src/main/java/com/ldp/reader/ui/adapter/DiscReviewAdapter.java",
                "src/main/java/com/ldp/reader/ui/adapter/DownLoadAdapter.java",
                "src/main/java/com/ldp/reader/ui/adapter/GodCommentAdapter.java",
                "src/main/java/com/ldp/reader/ui/adapter/HorizonTagAdapter.java",
                "src/main/java/com/ldp/reader/ui/adapter/SectionAdapter.java",
                "src/main/java/com/ldp/reader/ui/adapter/TagGroupAdapter.java",
                "src/main/java/com/ldp/reader/ui/adapter/view/BillBookHolder.java",
                "src/main/java/com/ldp/reader/ui/adapter/view/BookListInfoHolder.java",
                "src/main/java/com/ldp/reader/ui/adapter/view/BookSortHolder.java",
                "src/main/java/com/ldp/reader/ui/adapter/view/BookSortListHolder.java",
                "src/main/java/com/ldp/reader/ui/adapter/view/CommentHolder.java",
                "src/main/java/com/ldp/reader/ui/adapter/view/DiscCommentHolder.java",
                "src/main/java/com/ldp/reader/ui/adapter/view/DiscHelpsHolder.java",
                "src/main/java/com/ldp/reader/ui/adapter/view/DiscReviewHolder.java",
                "src/main/java/com/ldp/reader/ui/adapter/view/DownloadHolder.java",
                "src/main/java/com/ldp/reader/ui/adapter/view/HorizonTagHolder.java",
                "src/main/java/com/ldp/reader/ui/adapter/view/SectionHolder.java",
                "src/main/java/com/ldp/reader/ui/adapter/view/TagChildHolder.java",
                "src/main/java/com/ldp/reader/ui/adapter/view/TagGroupHolder.java"
        };

        String[] retiredResources = {
                "src/main/res/layout/activity_base_tab.xml",
                "src/main/res/layout/activity_bilboard.xml",
                "src/main/res/layout/activity_book_discussion.xml",
                "src/main/res/layout/activity_book_list.xml",
                "src/main/res/layout/activity_book_sort.xml",
                "src/main/res/layout/activity_book_sort_list.xml",
                "src/main/res/layout/activity_community.xml",
                "src/main/res/layout/activity_discussion_detail.xml",
                "src/main/res/layout/activity_more_setting.xml",
                "src/main/res/layout/activity_refresh_list.xml",
                "src/main/res/layout/fragment_community.xml",
                "src/main/res/layout/fragment_find.xml",
                "src/main/res/layout/fragment_scroll_refresh_list.xml",
                "src/main/res/layout/header_book_list_detail.xml",
                "src/main/res/layout/header_disc_detail.xml",
                "src/main/res/layout/header_disc_review_detail.xml",
                "src/main/res/layout/item_billboard_group.xml",
                "src/main/res/layout/item_billborad_child.xml",
                "src/main/res/layout/item_book_list_info.xml",
                "src/main/res/layout/item_comment.xml",
                "src/main/res/layout/item_disc_comment.xml",
                "src/main/res/layout/item_disc_review.xml",
                "src/main/res/layout/item_download.xml",
                "src/main/res/layout/item_horizon_tag.xml",
                "src/main/res/layout/item_section.xml",
                "src/main/res/layout/item_sort.xml",
                "src/main/res/layout/item_tag_child.xml",
                "src/main/res/layout/item_tag_group.xml",
                "src/main/res/layout/layout_disc_detail.xml",
                "src/main/res/layout/layout_disc_detail_comment.xml"
        };

        assertFilesRemoved("Remove unreachable legacy UI source: ", retiredSources);
        assertFilesRemoved("Remove unreachable legacy UI resource: ", retiredResources);
    }

    @Test
    public void bookDetailDoesNotKeepLegacyZhuishuCommunitySections() throws Exception {
        String[] retiredSources = {
                "src/main/java/com/ldp/reader/ui/adapter/BookListAdapter.java",
                "src/main/java/com/ldp/reader/ui/adapter/HotCommentAdapter.java",
                "src/main/java/com/ldp/reader/ui/adapter/view/BookListHolder.java",
                "src/main/java/com/ldp/reader/ui/adapter/view/HotCommentHolder.java",
                "src/main/java/com/ldp/reader/model/bean/HotCommentBean.java",
                "src/main/java/com/ldp/reader/model/bean/packages/HotCommentPackage.java",
                "src/main/java/com/ldp/reader/model/bean/packages/RecommendBookListPackage.java"
        };

        String[] retiredResources = {
                "src/main/res/layout/item_book_brief.xml",
                "src/main/res/layout/item_hot_comment.xml"
        };

        String detailLayout = readFile("src/main/res/layout/activity_book_detail.xml");
        String detailContract = readFile("src/main/java/com/ldp/reader/presenter/contract/BookDetailContract.java");
        String bookApi = readFile("src/main/java/com/ldp/reader/model/remote/BookApi.java");
        String remoteRepository = readFile("src/main/java/com/ldp/reader/model/remote/RemoteRepository.java");
        String simplifiedChineseStrings = readFile("src/main/res/values/strings.xml");
        String traditionalChineseStrings = readFile("src/main/res/values-zh-rTW/strings.xml");

        String[] retiredDetailTokens = {
                "book_detail_rv_hot_comment",
                "book_detail_tv_more_comment",
                "book_detail_rv_community",
                "book_detail_tv_community",
                "book_detail_tv_posts_count",
                "book_detail_rv_recommend_book_list",
                "book_list_tv_recommend_book_list",
                "追书人数",
                "读者留存率",
                "日更新字数"
        };

        String[] retiredApiTokens = {
                "finishHotComment",
                "finishRecommendBookList",
                "getHotCommnentPackage",
                "getRecommendBookListPackage",
                "getHotComments",
                "getRecommendBookList",
                "HotCommentBean",
                "RecommendBookListPackage"
        };

        String[] retiredStringTokens = {
                "nb.book_detail.hot_comment",
                "nb.book_detail.community",
                "nb.book_detail.posts_count",
                "nb.book_detail.recommend_book_list",
                "热门书评",
                "推薦書單"
        };

        assertFilesRemoved("Remove detail legacy source: ", retiredSources);
        assertFilesRemoved("Remove detail legacy resource: ", retiredResources);
        assertTextAbsent("Remove detail legacy UI token: ", detailLayout, retiredDetailTokens);
        assertTextAbsent("Remove detail legacy contract token: ", detailContract, retiredApiTokens);
        assertTextAbsent("Remove detail legacy API token: ", bookApi, retiredApiTokens);
        assertTextAbsent("Remove detail legacy repository token: ", remoteRepository, retiredApiTokens);
        assertTextAbsent("Remove detail legacy simplified string token: ", simplifiedChineseStrings, retiredStringTokens);
        assertTextAbsent("Remove detail legacy traditional string token: ", traditionalChineseStrings, retiredStringTokens);
    }

    @Test
    public void remoteLayerDoesNotKeepUnreachableLegacyDiscoveryApis() throws Exception {
        String bookApi = readFile("src/main/java/com/ldp/reader/model/remote/BookApi.java");
        String remoteRepository = readFile("src/main/java/com/ldp/reader/model/remote/RemoteRepository.java");

        String[] retiredBookApiTokens = {
                "getBookCommentList",
                "getBookHelpList",
                "getBookReviewList",
                "getCommentDetailPackage",
                "getReviewDetailPacakge",
                "getHelpsDetailPackage",
                "getBestCommentPackage",
                "getCommentPackage",
                "getBookCommentPackage",
                "getBillboardPackage",
                "getBillBookPackage",
                "getBookSortPackage",
                "getBookSubSortPackage",
                "getSortBookPackage",
                "getBookListPackage",
                "getBookTagPackage",
                "getBookListDetailPackage",
                "getBookDetail(",
                "getTagSearchPackage"
        };

        String[] retiredRepositoryTokens = {
                "getBookComment(",
                "getBookHelps(",
                "getBookReviews(",
                "getCommentDetail(",
                "getReviewDetail(",
                "getHelpsDetail(",
                "getBestComments(",
                "getDetailComments(",
                "getDetailBookComments(",
                "getBookSortPackage(",
                "getBookSubSortPackage(",
                "getSortBooks(",
                "getBillboardPackage(",
                "getBillBooks(",
                "getBookLists(",
                "getBookTags(",
                "getBookListDetail(",
                "getBookDetail("
        };

        assertTextAbsent("Remove unreachable legacy BookApi token: ", bookApi, retiredBookApiTokens);
        assertTextAbsent("Remove unreachable legacy RemoteRepository token: ", remoteRepository, retiredRepositoryTokens);
    }

    @Test
    public void unusedLegacyPackageBeansAndImportsAreRemoved() throws Exception {
        String[] retiredSources = {
                "src/main/java/com/ldp/reader/model/bean/BookChapterBeanByBiquge.java",
                "src/main/java/com/ldp/reader/model/bean/BookDetailBean.java",
                "src/main/java/com/ldp/reader/model/bean/BookDetailBeanInBiquge.java",
                "src/main/java/com/ldp/reader/model/bean/packages/BillBookPackage.java",
                "src/main/java/com/ldp/reader/model/bean/packages/BookChapterPackageByBiquge.java",
                "src/main/java/com/ldp/reader/model/bean/packages/BookCommentPackage.java",
                "src/main/java/com/ldp/reader/model/bean/packages/BookHelpsPackage.java",
                "src/main/java/com/ldp/reader/model/bean/packages/BookListDetailPackage.java",
                "src/main/java/com/ldp/reader/model/bean/packages/BookListPackage.java",
                "src/main/java/com/ldp/reader/model/bean/packages/BookReviewPackage.java",
                "src/main/java/com/ldp/reader/model/bean/packages/BookSubSortPackage.java",
                "src/main/java/com/ldp/reader/model/bean/packages/BookTagPackage.java",
                "src/main/java/com/ldp/reader/model/bean/packages/CommentDetailPackage.java",
                "src/main/java/com/ldp/reader/model/bean/packages/CommentsPackage.java",
                "src/main/java/com/ldp/reader/model/bean/packages/HelpsDetailPackage.java",
                "src/main/java/com/ldp/reader/model/bean/packages/ReviewDetailPackage.java",
                "src/main/java/com/ldp/reader/model/bean/packages/SearchBookPackage.java",
                "src/main/java/com/ldp/reader/model/bean/packages/SearchBookPackageByBiquge.java",
                "src/main/java/com/ldp/reader/model/bean/packages/SortBookPackage.java",
                "src/main/java/com/ldp/reader/model/bean/packages/TagSearchPackage.java"
        };

        String bookApi = readFile("src/main/java/com/ldp/reader/model/remote/BookApi.java");
        String readPresenter = readFile("src/main/java/com/ldp/reader/presenter/ReadPresenter.java");
        String bookShelfPresenter = readFile("src/main/java/com/ldp/reader/presenter/BookShelfPresenter.java");
        String bookDetailContract = readFile("src/main/java/com/ldp/reader/presenter/contract/BookDetailContract.java");
        String searchContract = readFile("src/main/java/com/ldp/reader/presenter/contract/SearchContract.java");
        String bookRepository = readFile("src/main/java/com/ldp/reader/model/local/BookRepository.java");

        String[] retiredTokens = {
                "SearchBookPackage",
                "getSearchBookPackage",
                "BookChapterPackageByBiquge",
                "BookChapterBeanByBiquge",
                "BookDetailBeanInBiquge",
                "BookDetailBean bean"
        };

        assertFilesRemoved("Remove unused legacy package bean: ", retiredSources);
        assertTextAbsent("Remove legacy BookApi package token: ", bookApi, retiredTokens);
        assertTextAbsent("Remove legacy read presenter import token: ", readPresenter, retiredTokens);
        assertTextAbsent("Remove legacy shelf presenter import token: ", bookShelfPresenter, retiredTokens);
        assertTextAbsent("Remove legacy detail contract comment token: ", bookDetailContract, retiredTokens);
        assertTextAbsent("Remove legacy search contract comment token: ", searchContract, retiredTokens);
        assertTextAbsent("Remove legacy repository comment token: ", bookRepository, retiredTokens);
    }

    @Test
    public void legacyLocalCacheLayerIsRemoved() throws Exception {
        String[] retiredSources = {
                "src/main/java/com/ldp/reader/model/local/SaveDbHelper.java",
                "src/main/java/com/ldp/reader/model/local/GetDbHelper.java",
                "src/main/java/com/ldp/reader/model/local/DeleteDbHelper.java",
                "src/main/java/com/ldp/reader/model/bean/AuthorBean.java",
                "src/main/java/com/ldp/reader/model/bean/BillBookBean.java",
                "src/main/java/com/ldp/reader/model/bean/BillboardBean.java",
                "src/main/java/com/ldp/reader/model/bean/BookCommentBean.java",
                "src/main/java/com/ldp/reader/model/bean/BookHelpfulBean.java",
                "src/main/java/com/ldp/reader/model/bean/BookHelpsBean.java",
                "src/main/java/com/ldp/reader/model/bean/BookListBean.java",
                "src/main/java/com/ldp/reader/model/bean/BookListDetailBean.java",
                "src/main/java/com/ldp/reader/model/bean/BookReviewBean.java",
                "src/main/java/com/ldp/reader/model/bean/BookSortBean.java",
                "src/main/java/com/ldp/reader/model/bean/BookSubSortBean.java",
                "src/main/java/com/ldp/reader/model/bean/BookTagBean.java",
                "src/main/java/com/ldp/reader/model/bean/CommentBean.java",
                "src/main/java/com/ldp/reader/model/bean/CommentDetailBean.java",
                "src/main/java/com/ldp/reader/model/bean/DetailBean.java",
                "src/main/java/com/ldp/reader/model/bean/HelpsDetailBean.java",
                "src/main/java/com/ldp/reader/model/bean/ReplyToBean.java",
                "src/main/java/com/ldp/reader/model/bean/ReviewBookBean.java",
                "src/main/java/com/ldp/reader/model/bean/ReviewDetailBean.java",
                "src/main/java/com/ldp/reader/model/bean/packages/BillboardPackage.java",
                "src/main/java/com/ldp/reader/model/bean/packages/BookSortPackage.java",
                "src/main/java/com/ldp/reader/model/gen/AuthorBeanDao.java",
                "src/main/java/com/ldp/reader/model/gen/BookCommentBeanDao.java",
                "src/main/java/com/ldp/reader/model/gen/BookHelpfulBeanDao.java",
                "src/main/java/com/ldp/reader/model/gen/BookHelpsBeanDao.java",
                "src/main/java/com/ldp/reader/model/gen/BookReviewBeanDao.java",
                "src/main/java/com/ldp/reader/model/gen/ReviewBookBeanDao.java",
                "src/main/java/com/ldp/reader/model/local/LocalRepository.java"
        };

        String rxUtils = readFile("src/main/java/com/ldp/reader/utils/RxUtils.java");
        String constants = readFile("src/main/java/com/ldp/reader/utils/Constant.java");

        String[] retiredRxTokens = {
                "CommentBean",
                "DetailBean",
                "toCommentDetail",
                "Function3"
        };

        String[] retiredConstantTokens = {
                "SHARED_SAVE_BOOK_SORT",
                "SHARED_SAVE_BILLBOARD",
                "BOOK_TYPE_COMMENT",
                "BOOK_TYPE_VOTE",
                "BOOK_STATE_NORMAL",
                "BOOK_STATE_DISTILLATE"
        };

        assertFilesRemoved("Remove legacy local-cache source: ", retiredSources);
        assertTextAbsent("Remove legacy comment-detail Rx helper token: ", rxUtils, retiredRxTokens);
        assertTextAbsent("Remove legacy local-cache constants token: ", constants, retiredConstantTokens);
    }

    @Test
    public void retiredDiscoveryFlagsStringsAndMenuHooksAreRemoved() throws Exception {
        String[] retiredSources = {
                "src/main/java/com/ldp/reader/event/BookSubSortEvent.java",
                "src/main/java/com/ldp/reader/event/SelectorEvent.java",
                "src/main/java/com/ldp/reader/model/bean/SectionBean.java",
                "src/main/java/com/ldp/reader/model/bean/SortBookBean.java",
                "src/main/java/com/ldp/reader/model/bean/TagBookBean.java",
                "src/main/java/com/ldp/reader/model/flag/BookConvert.java",
                "src/main/java/com/ldp/reader/model/flag/BookDistillate.java",
                "src/main/java/com/ldp/reader/model/flag/BookFormat.java",
                "src/main/java/com/ldp/reader/model/flag/BookListType.java",
                "src/main/java/com/ldp/reader/model/flag/BookSelection.java",
                "src/main/java/com/ldp/reader/model/flag/BookSort.java",
                "src/main/java/com/ldp/reader/model/flag/BookSortListType.java",
                "src/main/java/com/ldp/reader/model/flag/BookType.java",
                "src/main/java/com/ldp/reader/model/flag/CommunityType.java",
                "src/main/java/com/ldp/reader/model/flag/FindType.java",
                "src/main/res/color/tag_child.xml",
                "src/main/res/drawable/selector_tag.xml",
                "src/main/res/drawable/selector_tag_child.xml",
                "src/main/res/drawable/shape_frame_tag.xml",
                "src/main/res/drawable-xhdpi/ic_billboard_arrow_down.png",
                "src/main/res/drawable-xhdpi/ic_billboard_arrow_up.png",
                "src/main/res/drawable-xhdpi/ic_book_list_delete.png",
                "src/main/res/drawable-xhdpi/ic_book_review_like.png",
                "src/main/res/drawable-xxhdpi/ic_billboard_collapse.png",
                "src/main/res/drawable-xxhdpi/ic_book_review_like.png",
                "src/main/res/drawable-xxhdpi/ic_section_comment.png",
                "src/main/res/drawable-xxhdpi/ic_section_compose.png",
                "src/main/res/drawable-xxhdpi/ic_section_discuss.png",
                "src/main/res/drawable-xxhdpi/ic_section_girl.png",
                "src/main/res/drawable-xxhdpi/ic_section_help.png",
                "src/main/res/drawable-xxhdpi/ic_section_listen.png",
                "src/main/res/drawable-xxhdpi/ic_section_sort.png",
                "src/main/res/drawable-xxhdpi/ic_section_top.png",
                "src/main/res/drawable-xxhdpi/ic_section_topic.png",
                "src/main/res/drawable-xxhdpi/review_useful_no_nor.png",
                "src/main/res/drawable-xxhdpi/review_useful_no_pre.png",
                "src/main/res/drawable-xxhdpi/review_useful_yes_nor.png",
                "src/main/res/drawable-xxhdpi/review_useful_yes_pre.png"
        };

        String mainActivity = readFile("src/main/java/com/ldp/reader/ui/activity/MainActivity.kt");
        String readActivity = readFile("src/main/java/com/ldp/reader/ui/activity/ReadActivity.kt");
        String readLayout = readFile("src/main/res/layout/activity_read.xml");
        String simplifiedChineseStrings = readFile("src/main/res/values/strings.xml");
        String traditionalChineseStrings = readFile("src/main/res/values-zh-rTW/strings.xml");
        String textStyles = readFile("src/main/res/values/styles_text.xml");
        String constants = readFile("src/main/java/com/ldp/reader/utils/Constant.java");

        String[] retiredMainTokens = {
                "CommunityFragment",
                "FindFragment",
                "communityFragment",
                "discoveryFragment"
        };

        String[] retiredReadTokens = {
                "CommunityActivity",
                "mTvCommunity",
                "read_tv_community",
                "nb.read.community"
        };

        String[] retiredStringTokens = {
                "nb.fragment.title.community",
                "nb.fragment.title.find",
                "nb_fragment_section",
                "nb_fragment_bill_book",
                "nb.fragment.community",
                "nb.fragment.book_list",
                "nb.comment",
                "nb.review",
                "nb.fragment.find",
                "nb.read.community",
                "社区",
                "書評區",
                "排行榜",
                "主题书单"
        };

        String[] retiredStyleTokens = {
                "NB.Theme.TextAppearance.Billboard"
        };

        String[] retiredConstantTokens = {
                "StringDef",
                "BookType",
                "bookType",
                "SHARED_SEX",
                "SHARED_CONVERT_TYPE",
                "SEX_BOY",
                "SEX_GIRL",
                "IMG_BASE_URL",
                "MSG_SELECTOR"
        };

        assertFilesRemoved("Remove retired discovery flag/resource source: ", retiredSources);
        assertTextAbsent("Remove retired main tab hook token: ", mainActivity, retiredMainTokens);
        assertTextAbsent("Remove retired read menu hook token: ", readActivity, retiredReadTokens);
        assertTextAbsent("Remove retired read layout token: ", readLayout, retiredReadTokens);
        assertTextAbsent("Remove retired simplified string token: ", simplifiedChineseStrings, retiredStringTokens);
        assertTextAbsent("Remove retired traditional string token: ", traditionalChineseStrings, retiredStringTokens);
        assertTextAbsent("Remove retired text style token: ", textStyles, retiredStyleTokens);
        assertTextAbsent("Remove retired constant token: ", constants, retiredConstantTokens);
    }

    @Test
    public void unusedRecommendAndChapterListApisAreRemoved() throws Exception {
        String[] retiredSources = {
                "src/main/java/com/ldp/reader/event/RecommendBookEvent.java",
                "src/main/java/com/ldp/reader/model/bean/packages/RecommendBookPackage.java",
                "src/main/java/com/ldp/reader/model/bean/packages/BookChapterPackage.java"
        };

        String bookApi = readFile("src/main/java/com/ldp/reader/model/remote/BookApi.java");
        String remoteRepository = readFile("src/main/java/com/ldp/reader/model/remote/RemoteRepository.java");
        String bookShelfPresenter = readFile("src/main/java/com/ldp/reader/presenter/BookShelfPresenter.java");
        String bookShelfContract = readFile("src/main/java/com/ldp/reader/presenter/contract/BookShelfContract.java");
        String bookShelfFragment = readFile("src/main/java/com/ldp/reader/ui/fragment/BookShelfFragment.kt");

        String[] retiredBookApiTokens = {
                "RecommendBookPackage",
                "BookChapterPackage",
                "getRecommendBookPackage",
                "getBookChapterPackage",
                "/book/recommend",
                "/mix-atoc"
        };

        String[] retiredRepositoryTokens = {
                "RecommendBookPackage",
                "BookChapterPackage",
                "getRecommendBooks",
                "getBookChapters"
        };

        String[] retiredShelfTokens = {
                "RecommendBookEvent",
                "loadRecommendBooks",
                "getRecommendBooks"
        };

        assertFilesRemoved("Remove unused recommendation/chapter-list source: ", retiredSources);
        assertTextAbsent("Remove unused recommendation/chapter-list BookApi token: ", bookApi, retiredBookApiTokens);
        assertTextAbsent("Remove unused recommendation/chapter-list repository token: ", remoteRepository, retiredRepositoryTokens);
        assertTextAbsent("Remove unused recommendation presenter token: ", bookShelfPresenter, retiredShelfTokens);
        assertTextAbsent("Remove unused recommendation contract token: ", bookShelfContract, retiredShelfTokens);
        assertTextAbsent("Remove unused recommendation fragment token: ", bookShelfFragment, retiredShelfTokens);
    }

    @Test
    public void unreachableDownloadCacheLayerIsRemoved() throws Exception {
        String[] retiredSources = {
                "src/main/java/com/ldp/reader/service/DownloadService.java",
                "src/main/java/com/ldp/reader/event/DeleteResponseEvent.java",
                "src/main/java/com/ldp/reader/event/DeleteTaskEvent.java",
                "src/main/java/com/ldp/reader/event/DownloadMessage.java",
                "src/main/java/com/ldp/reader/model/bean/DownloadTaskBean.java",
                "src/main/java/com/ldp/reader/model/bean/ChapterInfoBean.java",
                "src/main/java/com/ldp/reader/model/bean/packages/ChapterInfoPackage.java",
                "src/main/java/com/ldp/reader/model/gen/DownloadTaskBeanDao.java",
                "src/main/java/com/ldp/reader/model/local/LocalRepository.java"
        };

        String manifest = readFile("src/main/AndroidManifest.xml");
        String app = readFile("src/main/java/com/ldp/reader/App.java");
        String bookApi = readFile("src/main/java/com/ldp/reader/model/remote/BookApi.java");
        String remoteRepository = readFile("src/main/java/com/ldp/reader/model/remote/RemoteRepository.java");
        String bookRepository = readFile("src/main/java/com/ldp/reader/model/local/BookRepository.java");
        String readPresenter = readFile("src/main/java/com/ldp/reader/presenter/ReadPresenter.java");
        String bookShelfPresenter = readFile("src/main/java/com/ldp/reader/presenter/BookShelfPresenter.java");
        String bookShelfContract = readFile("src/main/java/com/ldp/reader/presenter/contract/BookShelfContract.java");
        String bookShelfFragment = readFile("src/main/java/com/ldp/reader/ui/fragment/BookShelfFragment.kt");
        String simplifiedChineseStrings = readFile("src/main/res/values/strings.xml");
        String traditionalChineseStrings = readFile("src/main/res/values-zh-rTW/strings.xml");
        String colors = readFile("src/main/res/values/colors.xml");

        String[] retiredBookApiTokens = {
                "ChapterInfoPackage",
                "getChapterInfoPackage",
                "chapter2.zhuishushenqi.com"
        };

        String[] retiredRepositoryTokens = {
                "ChapterInfoBean",
                "getChapterInfo("
        };

        String[] retiredDownloadLayerTokens = {
                "DownloadService",
                "DownloadTaskBean",
                "DownloadTaskBeanDao",
                "LocalRepository",
                "DownloadMessage",
                "DeleteTaskEvent",
                "DeleteResponseEvent",
                "createDownloadTask",
                "downloadBook",
                "nb.menu.action.cache",
                "nb.menu.action.download",
                "nb.download.",
                "nb.read.download"
        };

        assertFilesRemoved("Remove unreachable download-cache source: ", retiredSources);
        assertTextAbsent("Remove unreachable download-cache manifest token: ", manifest, retiredDownloadLayerTokens);
        assertTextAbsent("Remove unreachable download-cache app token: ", app, retiredDownloadLayerTokens);
        assertTextAbsent("Remove legacy chapter-content BookApi token: ", bookApi, retiredBookApiTokens);
        assertTextAbsent("Remove legacy chapter-content repository token: ", remoteRepository, retiredRepositoryTokens);
        assertTextAbsent("Remove unreachable download-cache repository token: ", bookRepository, retiredDownloadLayerTokens);
        assertTextAbsent("Remove legacy chapter-content presenter import token: ", readPresenter, retiredRepositoryTokens);
        assertTextAbsent("Remove unreachable download-cache presenter token: ", bookShelfPresenter, retiredDownloadLayerTokens);
        assertTextAbsent("Remove unreachable download-cache contract token: ", bookShelfContract, retiredDownloadLayerTokens);
        assertTextAbsent("Remove unreachable download-cache fragment token: ", bookShelfFragment, retiredDownloadLayerTokens);
        assertTextAbsent("Remove unreachable download-cache simplified string token: ", simplifiedChineseStrings, retiredDownloadLayerTokens);
        assertTextAbsent("Remove unreachable download-cache traditional string token: ", traditionalChineseStrings, retiredDownloadLayerTokens);
        assertTextAbsent("Remove unreachable download-cache color token: ", colors, retiredDownloadLayerTokens);
    }

    @Test
    public void deadZhuishuChapterValidityWritesAreRemoved() throws Exception {
        String bookDetailPresenter = readFile("src/main/java/com/ldp/reader/presenter/BookDetailPresenter.java");
        String bookShelfPresenter = readFile("src/main/java/com/ldp/reader/presenter/BookShelfPresenter.java");
        String readPresenter = readFile("src/main/java/com/ldp/reader/presenter/ReadPresenter.java");

        String[] retiredPresenterTokens = {
                "setValidInZhuishu",
                "validInZhuishu"
        };

        assertTextAbsent("Remove dead Zhuishu chapter-validity write from detail presenter: ",
                bookDetailPresenter, retiredPresenterTokens);
        assertTextAbsent("Remove dead Zhuishu chapter-validity write from shelf presenter: ",
                bookShelfPresenter, retiredPresenterTokens);
        assertTextAbsent("Remove dead Zhuishu chapter-validity write from read presenter: ",
                readPresenter, retiredPresenterTokens);
    }

    @Test
    public void orphanLegacyServiceLayoutsAndDrawablesAreRemoved() {
        String[] retiredSources = {
                "src/main/java/com/ldp/reader/ui/base/BaseService.java",
                "src/main/res/layout/activity_retro.xml",
                "src/main/res/layout/activity_scroll.xml",
                "src/main/res/layout/dialog_sex_choose.xml",
                "src/main/res/layout/novel_update_notification.xml",
                "src/main/res/layout/one_ads.xml",
                "src/main/res/layout/two_ads.xml",
                "src/main/res/drawable/btn_sex_choose_boy.xml",
                "src/main/res/drawable/btn_sex_choose_girl.xml",
                "src/main/res/drawable-xhdpi/ic_notif_post.png",
                "src/main/res/drawable-xhdpi/ic_notif_vote.png",
                "src/main/res/drawable-xhdpi/ic_read_menu_download.png",
                "src/main/res/drawable-xxhdpi/ic_download_complete.png",
                "src/main/res/drawable-xxhdpi/ic_download_error.png",
                "src/main/res/drawable-xxhdpi/ic_download_loading.png",
                "src/main/res/drawable-xxhdpi/ic_download_pause.png",
                "src/main/res/drawable-xxhdpi/ic_download_wait.png",
                "src/main/res/drawable-xxhdpi/ic_menu_download.png",
                "src/main/res/drawable-xxhdpi/ic_sex_logo.png",
                "src/main/res/drawable-xxhdpi/ic_topic_distillate.png",
                "src/main/res/drawable-xxhdpi/ic_topic_hot.png",
                "src/main/res/drawable-xxhdpi/post_item_like.png",
                "src/main/res/drawable-xxhdpi/rating_star_user_nor.png",
                "src/main/res/drawable-xxhdpi/rating_star_user_press.png"
        };

        assertFilesRemoved("Remove orphan legacy service/layout/drawable source: ", retiredSources);
    }

    @Test
    public void unusedDownloadStatusConstantsAndTagStringsAreRemoved() throws Exception {
        String collBookBean = readFile("src/main/java/com/ldp/reader/model/bean/CollBookBean.kt");
        String simplifiedChineseStrings = readFile("src/main/res/values/strings.xml");
        String traditionalChineseStrings = readFile("src/main/res/values-zh-rTW/strings.xml");

        String[] retiredStatusTokens = {
                "STATUS_UNCACHE",
                "STATUS_CACHING",
                "STATUS_CACHED"
        };

        String[] retiredTagStringTokens = {
                "nb.tag.all",
                "nb.tag.sex",
                "nb.tag.boy",
                "nb.tag.girl"
        };

        assertTextAbsent("Remove unused download-status constant: ", collBookBean, retiredStatusTokens);
        assertTextAbsent("Remove unused legacy tag string: ", simplifiedChineseStrings, retiredTagStringTokens);
        assertTextAbsent("Remove unused legacy tag string: ", traditionalChineseStrings, retiredTagStringTokens);
    }

    private void assertFilesRemoved(String messagePrefix, String[] paths) {
        for (String path : paths) {
            assertFalse(messagePrefix + path, new File(path).exists());
        }
    }

    private void assertTextAbsent(String messagePrefix, String text, String[] tokens) {
        for (String token : tokens) {
            assertFalse(messagePrefix + token, text.contains(token));
        }
    }

    private String readFile(String path) throws Exception {
        return new String(
                Files.readAllBytes(new File(path).toPath()),
                StandardCharsets.UTF_8
        );
    }
}
