package com.ldp.reader.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

public class HomeRefreshAndSettingsAccountContractTest {
    @Test
    public void everyShelfRefreshUsesTheWholeRepositoryNotFilteredVisibleItems() throws IOException {
        String fragment = readFile("src/main/java/com/ldp/reader/ui/fragment/BookShelfFragment.kt");
        int listener = fragment.indexOf("setOnRefreshListener");
        int nextListener = fragment.indexOf("mCollBookAdapter!!.onBookClick", listener);
        String pullToRefresh = fragment.substring(listener, nextListener);
        int finishRefresh = fragment.indexOf("private fun finishRefresh");
        int finishUpdate = fragment.indexOf("private fun finishUpdate", finishRefresh);
        String initialRefresh = fragment.substring(finishRefresh, finishUpdate);

        assertTrue(pullToRefresh.contains("viewModel.updateCollBooks(BookRepository.getInstance().collBooks)"));
        assertFalse(pullToRefresh.contains("mCollBookAdapter!!.bookItems"));
        assertTrue(initialRefresh.contains("viewModel.updateCollBooks(BookRepository.getInstance().collBooks)"));
        assertFalse(initialRefresh.contains("mCollBookAdapter!!.bookItems"));
    }

    @Test
    public void settingsAccountLoginReturnsThroughTheSyncAwareLauncher() throws IOException {
        String settings = readFile("src/main/java/com/ldp/reader/ui/activity/SettingsActivity.kt");
        int account = settings.indexOf("binding.settingsAccountEntry.setOnClickListener");
        int sync = settings.indexOf("binding.settingsSyncBookshelf.setOnClickListener", account);
        String body = settings.substring(account, sync);

        assertTrue(body.contains("loginSyncLauncher.launch(LoginActivity.syncIntent(this))"));
        assertFalse(body.contains("startActivity("));
        assertTrue(settings.contains("BookshelfSyncRequest.isRequested(result.data)"));
        assertTrue(settings.contains("requestBookShelfSyncAndFinish()"));
    }

    private static String readFile(String path) throws IOException {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }
}
