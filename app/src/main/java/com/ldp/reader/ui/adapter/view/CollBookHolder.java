package com.ldp.reader.ui.adapter.view;

import android.view.View;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.ldp.reader.R;
import com.ldp.reader.databinding.ItemCollBookBinding;
import com.ldp.reader.model.bean.BookRecordBean;
import com.ldp.reader.model.bean.CollBookBean;
import com.ldp.reader.model.local.BookRepository;
import com.ldp.reader.ui.adapter.CollBookAdapter;
import com.ldp.reader.ui.base.adapter.ViewHolderImpl;
import com.ldp.reader.ui.home.BookshelfLocalProgressStore;
import com.ldp.reader.utils.StringUtils;
import com.ldp.reader.widget.page.PageLoader;

import java.util.Locale;

/**
 * Created by ldp on 17-5-8.
 * CollectionBookView
 */

public class CollBookHolder extends ViewHolderImpl<CollBookBean>{

    private static final String TAG = "CollBookView";
    private static final String DEFAULT_LOCAL_TITLE = "本地书";
    private static final String DEFAULT_LOCAL_TYPE = "TXT";
    private static final String START_READING = "开始阅读";
    private ImageView mIvCover;
    private View mLocalCover;
    private TextView mLocalCoverTitle;
    private TextView mLocalCoverType;
    private TextView mTvName;
    private TextView mTvChapter;
    private TextView mTvTime;
    private CheckBox mCbSelected;
    private ImageView mIvRedDot;
    private ImageView mIvTop;
    private final CollBookAdapter adapter;

    public CollBookHolder(CollBookAdapter adapter) {
        this.adapter = adapter;
    }

    @Override
    public void initView() {
        ItemCollBookBinding binding = ItemCollBookBinding.bind(getItemView());
        mIvCover = binding.collBookIvCover;
        mLocalCover = binding.collBookLocalCover;
        mLocalCoverTitle = binding.collBookLocalCoverTitle;
        mLocalCoverType = binding.collBookLocalCoverType;
        mTvName = binding.collBookTvName;
        mTvChapter = binding.collBookTvChapter;
        mTvTime = binding.collBookTvLatelyUpdate;
        mCbSelected = binding.collBookCbSelected;
        mIvRedDot = binding.collBookIvRedRot;
        mIvTop = binding.collBookIvTop;
    }

    @Override
    public void onBind(CollBookBean value, int pos) {
        if (value.isLocal()){
            mIvCover.setVisibility(View.GONE);
            mLocalCover.setVisibility(View.VISIBLE);
            mLocalCoverTitle.setText(coverTitle(value.getTitle()));
            mLocalCoverType.setText(fileTypeLabel(value.getCover()));
        }
        else {
            mIvCover.setVisibility(View.VISIBLE);
            mLocalCover.setVisibility(View.GONE);
            //书的图片
            Glide.with(getContext())
                    .load(value.getCover())
                    .placeholder(R.drawable.ic_book_loading)
                    .error(R.drawable.ic_book_loading)
                    .centerCrop()
                    .into(mIvCover);
        }
        //书名
        mTvName.setText(value.getTitle());
        if (!value.isLocal()){
            //时间
            mTvTime.setText(StringUtils.formatBookUpdateTime(value.getUpdated()));
            mTvTime.setVisibility(View.VISIBLE);
            mTvChapter.setVisibility(View.VISIBLE);
        }
        else {
            mTvTime.setText(progressLabel(
                    value,
                    findBookRecord(value),
                    BookshelfLocalProgressStore.getProgressTenths(value.get_id())
            ));
            mTvTime.setVisibility(View.VISIBLE);
            mTvChapter.setVisibility(View.GONE);
        }
        //章节
        mTvChapter.setText(value.getLastChapter());
        mCbSelected.setVisibility(adapter.isEditMode() ? View.VISIBLE : View.GONE);
        mCbSelected.setChecked(adapter.isSelected(value));
        //我的想法是，在Collection中加一个字段，当追更的时候设置为true。当点击的时候设置为false。
        //当更新的时候，最新数据跟旧数据进行比较，如果更新的话，设置为true。
        if (value.isUpdate()){
            mIvRedDot.setVisibility(value.isLocal() ? View.GONE : View.VISIBLE);
        }
        else {
            mIvRedDot.setVisibility(View.GONE);
        }
    }

    static String coverTitle(String title) {
        if (title == null) {
            return DEFAULT_LOCAL_TITLE;
        }
        String cleanTitle = title.trim();
        return cleanTitle.isEmpty() ? DEFAULT_LOCAL_TITLE : cleanTitle;
    }

    static String fileTypeLabel(String path) {
        String extension = DEFAULT_LOCAL_TYPE;
        if (path != null) {
            int dotIndex = path.lastIndexOf('.');
            if (dotIndex >= 0 && dotIndex + 1 < path.length()) {
                extension = path.substring(dotIndex + 1);
            }
        }
        return "-" + extension.toUpperCase(Locale.CHINA) + "-";
    }

    static String progressLabel(CollBookBean book, BookRecordBean record) {
        return progressLabel(book, record, -1);
    }

    static String progressLabel(CollBookBean book, BookRecordBean record, int storedProgressTenths) {
        if (book == null) {
            return "未读";
        }
        if (storedProgressTenths >= 0) {
            return "已读" + formatPercent(storedProgressTenths);
        }
        if (record != null && (record.getChapter() > 0 || record.getPagePos() > 0)) {
            return "已读" + formatPercent(fallbackProgressTenths(book, record));
        }
        String lastChapter = book.getLastChapter();
        if (lastChapter == null || lastChapter.trim().isEmpty()
                || START_READING.equals(lastChapter.trim())) {
            return "未读";
        }
        return "已读0.1%";
    }

    private static int fallbackProgressTenths(CollBookBean book, BookRecordBean record) {
        int chapterCount = Math.max(0, book.getChaptersCount());
        if (chapterCount > 1) {
            return PageLoader.calculateProgressTenths(chapterCount, record.getChapter(), record.getPagePos(), 0);
        }
        return 1;
    }

    private static String formatPercent(int tenths) {
        int safeTenths = Math.max(1, Math.min(tenths, 999));
        int whole = safeTenths / 10;
        int decimal = safeTenths % 10;
        if (decimal == 0) {
            return whole + "%";
        }
        return whole + "." + decimal + "%";
    }

    private BookRecordBean findBookRecord(CollBookBean value) {
        try {
            return BookRepository.getInstance().getBookRecord(value.get_id());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    @Override
    protected int getItemLayoutId() {
        return R.layout.item_coll_book;
    }
}
