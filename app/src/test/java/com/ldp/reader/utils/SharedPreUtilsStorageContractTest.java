package com.ldp.reader.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

public class SharedPreUtilsStorageContractTest {

    @Test
    public void sharedPreUtilsUsesMmkvOnly() throws IOException {
        String sharedPreUtils = readFile("src/main/java/com/ldp/reader/utils/SharedPreUtils.java");

        assertTrue(sharedPreUtils.contains("import com.tencent.mmkv.MMKV;"));
        assertTrue(sharedPreUtils.contains("MMKV.mmkvWithID(SHARED_NAME)"));
        assertTrue(sharedPreUtils.contains("mmkv.decodeString(key,\"\""));
        assertTrue(sharedPreUtils.contains("mmkv.decodeInt(key, def)"));
        assertTrue(sharedPreUtils.contains("mmkv.decodeLong(key, def)"));
        assertTrue(sharedPreUtils.contains("mmkv.decodeBool(key, def)"));
        assertTrue(sharedPreUtils.contains("mmkv.encode(key,value)"));
        assertTrue(sharedPreUtils.contains("mmkv.encode(key, value)"));

        assertFalse(sharedPreUtils.contains("SharedPreferences"));
        assertFalse(sharedPreUtils.contains("getSharedPreferences"));
        assertFalse(sharedPreUtils.contains("MODE_MULTI_PROCESS"));
        assertFalse(sharedPreUtils.contains(".commit()"));
        assertFalse(sharedPreUtils.contains(".apply()"));
    }

    @Test
    public void appInitializesMmkvBeforeStorageUse() throws IOException {
        String app = readFile("src/main/java/com/ldp/reader/App.java");

        assertTrue(app.contains("import com.tencent.mmkv.MMKV;"));
        assertTrue(app.contains("MMKV.initialize(this);"));
    }

    @Test
    public void gradleKeepsMmkvDependency() throws IOException {
        String buildGradle = readFile("build.gradle");

        assertTrue(buildGradle.contains("implementation 'com.tencent:mmkv-static:2.4.0'"));
    }

    private static String readFile(String path) throws IOException {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }
}
