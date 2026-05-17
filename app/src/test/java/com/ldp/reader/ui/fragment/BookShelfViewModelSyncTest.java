package com.ldp.reader.ui.fragment;

import static org.junit.Assert.assertEquals;

import com.ldp.reader.model.bean.CollBookBean;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class BookShelfViewModelSyncTest {

    @Test
    public void onlineBookIdsExcludeLocalBooksAndInvalidIds() {
        List<String> ids = BookShelfViewModel.onlineBookIdsFrom(Arrays.asList(
                book("1001", false),
                book("-2029479232", false),
                book("C:/local/book.txt", true),
                book("path-md5-local", true),
                book("not-a-server-id", false),
                book("1002", false)
        ));

        assertEquals(Arrays.asList("1001", "-2029479232", "1002"), ids);
    }

    @Test
    public void mergedSyncIdsKeepServerIdsAndOnlyOnlineLocalShelfIds() {
        List<String> ids = BookShelfViewModel.mergeServerAndLocalOnlineIds(
                Arrays.asList(3001L, -3003L, 3002L),
                Arrays.asList(
                        book("3002", false),
                        book("4001", false),
                        book("D:/books/local.txt", true),
                        book("local-md5", true)
                )
        );

        assertEquals(Arrays.asList("3001", "-3003", "3002", "4001"), ids);
    }

    private static CollBookBean book(String id, boolean local) {
        CollBookBean book = new CollBookBean();
        book.set_id(id);
        book.setLocal(local);
        return book;
    }
}

