package com.ldp.reader.ui;

import static org.junit.Assert.assertNotEquals;
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
        assertNotEquals(0, R.drawable.ic_file_system_dropdown_20);
        assertNotEquals(0, R.drawable.ic_file_system_filter_28);
        assertNotEquals(0, R.drawable.bg_file_system_bottom_bar);
        assertNotEquals(0, R.drawable.bg_file_system_add_button);
        assertNotEquals(0, R.drawable.bg_file_system_delete_button);

        String layout = readFile("src/main/res/layout/activity_file_system.xml");
        assertTrue(layout.contains("android:text=\"智能导入\""));
        assertTrue(layout.contains("file_system_selected_count"));
        assertTrue(layout.contains("file_system_filter"));
        assertTrue(layout.contains("file_system_search"));
        assertTrue(layout.contains("android:text=\"加入书架\""));
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
        assertTrue(item.contains("android:layout_height=\"102dp\""));
        assertTrue(item.contains("android:layout_gravity=\"right|center_vertical\""));
        assertTrue(item.indexOf("file_tv_name") < item.indexOf("file_fl_icon"));
        assertTrue(item.contains("@drawable/selector_file_reader_check"));
        assertTrue(item.contains("@drawable/bg_file_row_tag"));
    }

    @Test
    public void fileSystemAdapterChecksLoadedStateByBookIdHash() throws IOException {
        String adapter = readFile("src/main/java/com/ldp/reader/ui/adapter/FileSystemAdapter.java");
        assertTrue(adapter.contains("MD5Utils.strToMd5By16(path)"));
    }

    private static String readFile(String path) throws IOException {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }
}
