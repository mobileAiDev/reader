package com.ldp.reader.model.local;

import com.ldp.reader.model.bean.DownloadTaskBean;
import com.ldp.reader.model.gen.DaoSession;

import java.util.List;

/**
 * Stores local download task metadata.
 */
public class LocalRepository {
    private static volatile LocalRepository sInstance;
    private final DaoSession mSession;

    private LocalRepository() {
        mSession = DaoDbHelper.getInstance().getSession();
    }

    public static LocalRepository getInstance() {
        if (sInstance == null) {
            synchronized (LocalRepository.class) {
                if (sInstance == null) {
                    sInstance = new LocalRepository();
                }
            }
        }
        return sInstance;
    }

    public void saveDownloadTask(DownloadTaskBean bean) {
        BookRepository.getInstance()
                .saveBookChaptersWithAsync(bean.getBookChapters());
        mSession.getDownloadTaskBeanDao()
                .insertOrReplace(bean);
    }

    public List<DownloadTaskBean> getDownloadTaskList() {
        return mSession.getDownloadTaskBeanDao()
                .loadAll();
    }
}
