package com.ldp.reader.ui.activity;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

public class MainActivityFragmentRestoreContractTest {
    @Test
    public void restoredPagerFragmentsReplaceFreshUnattachedPlaceholders() throws IOException {
        String activity = readFile("src/main/java/com/ldp/reader/ui/activity/MainActivity.kt");

        assertTrue(activity.contains("override fun instantiateItem(container: ViewGroup, position: Int): Any"));
        assertTrue(activity.contains("val restoredOrCreated = super.instantiateItem(container, position)"));
        assertTrue(activity.contains("mFragmentList[position] = restoredOrCreated"));
        assertTrue(activity.contains("supportFragmentManager.findFragmentByTag(fragmentPagerTag(pagerId, index))"));
        assertTrue(activity.contains("mFragmentList.getOrNull(index)?.takeIf { it.isAdded }"));
        assertTrue(activity.contains("activeFragmentAt(currentIndex)"));
    }

    @Test
    public void bookshelfRestoresOnlyTheSafeFilterAndExitsDestructiveSelection() throws IOException {
        String shelf = readFile("src/main/java/com/ldp/reader/ui/fragment/BookShelfFragment.kt");

        assertTrue(shelf.contains("savedInstanceState?.getString(STATE_SHELF_FILTER)"));
        assertTrue(shelf.contains("outState.putString(STATE_SHELF_FILTER, currentShelfFilter.name)"));
        assertTrue(shelf.contains("Edit mode and its selection intentionally exit on recreation."));
        assertTrue(shelf.contains("Restoring a partial"));
    }

    @Test
    public void pagerTagMatchesFragmentPagerAdapterStableTagFormat() {
        assertTrue(MainActivity.fragmentPagerTag(2131362001, 0)
            .equals("android:switcher:2131362001:0"));
        assertTrue(MainActivity.fragmentPagerTag(2131362001, 1)
            .equals("android:switcher:2131362001:1"));
    }

    private static String readFile(String path) throws IOException {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }
}
