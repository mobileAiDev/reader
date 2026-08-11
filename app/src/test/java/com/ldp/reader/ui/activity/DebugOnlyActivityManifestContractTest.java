package com.ldp.reader.ui.activity;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

public class DebugOnlyActivityManifestContractTest {
    @Test
    public void mediaProbeIsPrivateInMainAndOnlyExportedByTheDebugOverlay() throws IOException {
        String mainManifest = readFile("src/main/AndroidManifest.xml");
        String debugManifest = readFile("src/debug/AndroidManifest.xml");

        String mainDeclaration = activityDeclaration(mainManifest, ".ui.activity.MediaProbeActivity");
        String debugDeclaration = activityDeclaration(debugManifest, ".ui.activity.MediaProbeActivity");

        assertTrue(mainDeclaration.contains("android:exported=\"false\""));
        assertTrue(debugDeclaration.contains("android:exported=\"true\""));
        assertTrue(debugDeclaration.contains("tools:replace=\"android:exported\""));
    }

    private static String activityDeclaration(String manifest, String activityName) {
        int nameIndex = manifest.indexOf("android:name=\"" + activityName + "\"");
        assertTrue("Missing activity declaration for " + activityName, nameIndex >= 0);
        int start = manifest.lastIndexOf("<activity", nameIndex);
        int end = manifest.indexOf('>', nameIndex);
        assertTrue("Malformed activity declaration for " + activityName, start >= 0 && end > nameIndex);
        return manifest.substring(start, end + 1);
    }

    private static String readFile(String path) throws IOException {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }
}
