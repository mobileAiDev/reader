package com.ldp.reader.ui.activity;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public class StartupPermissionFlowContractTest {

    @Test
    public void coldStartDoesNotRequestNotificationOrStorageAccess() throws IOException {
        String splash = readFile(
            "src/main/java/com/ldp/reader/ui/activity/SplashActivity.kt"
        );

        assertFalse(splash.contains("PermissionUtils.permission"));
        assertFalse(splash.contains("Manifest.permission.POST_NOTIFICATIONS"));
        assertFalse(splash.contains("Manifest.permission.MANAGE_EXTERNAL_STORAGE"));
        assertTrue(splash.indexOf("requestWindowFeature(Window.FEATURE_NO_TITLE)")
            < splash.indexOf("super.onCreate(savedInstanceState)"));
        assertTrue(splash.indexOf("super.onCreate(savedInstanceState)")
            < splash.indexOf("skipToMain()"));
    }

    @Test
    public void permissionFreeLocalImportStartsWithSafAndExplainsScanningOnDemand()
        throws IOException {
        String activity = readFile(
            "src/main/java/com/ldp/reader/ui/activity/FileSystemActivity.kt"
        );
        String safPicker = readFile(
            "src/main/java/com/ldp/reader/ui/fragment/DocumentImportFragment.kt"
        );

        assertTrue(activity.contains("val hasStorageAccess = isPermissionGranted()"));
        assertTrue(activity.contains(
            "LocalImportEntryPolicy.initialTab(hasStorageAccess)"
        ));
        assertTrue(activity.contains(
            "LocalImportEntryPolicy.requiresBroadStorageAccess(selectedTab)"
        ));
        assertTrue(activity.contains("showStorageAccessDialog()"));
        assertTrue(safPicker.contains("ActivityResultContracts.OpenDocument()"));
    }

    private static String readFile(String path) throws IOException {
        return new String(
            Files.readAllBytes(new File(path).toPath()),
            StandardCharsets.UTF_8
        );
    }
}
