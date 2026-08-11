package com.ldp.reader.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

public class ReadSettingDialogContractTest {
    @Test
    public void brightnessMinusPersistsTheSameAppliedValue() throws IOException {
        String dialog = readFile("src/main/java/com/ldp/reader/ui/dialog/ReadSettingDialog.kt");
        int minus = dialog.indexOf("mIvBrightnessMinus.setOnClickListener");
        int plus = dialog.indexOf("mIvBrightnessPlus.setOnClickListener", minus);
        String minusBody = dialog.substring(minus, plus);

        assertTrue(minusBody.contains("BrightnessUtils.setBrightness(mActivity, progress)"));
        assertTrue(minusBody.contains("mSettingManager.brightness = progress"));
    }

    @Test
    public void defaultFontStateAndCanonicalSpSizeArePersisted() throws IOException {
        String dialog = readFile("src/main/java/com/ldp/reader/ui/dialog/ReadSettingDialog.kt");
        String manager = readFile("src/main/java/com/ldp/reader/model/local/ReadSettingManager.kt");

        int listener = dialog.indexOf("mCbFontDefault.setOnCheckedChangeListener");
        int pageMode = dialog.indexOf("mRgPageMode.setOnCheckedChangeListener", listener);
        String listenerBody = dialog.substring(listener, pageMode);
        assertTrue(listenerBody.contains("mSettingManager.isDefaultTextSize = isChecked"));
        assertTrue(listenerBody.contains("ReaderTextSize.DEFAULT_SP"));

        assertTrue(dialog.contains("ReaderTextSize.displaySp("));
        assertTrue(dialog.contains("ReaderTextSize.toStoredPx("));
        assertFalse(dialog.contains("ScreenUtils.dpToPx(DEFAULT_TEXT_SIZE)"));
        assertFalse(dialog.contains("private const val DEFAULT_TEXT_SIZE = 16"));

        assertTrue(manager.contains("ReaderTextSize.defaultStoredPx("));
        assertTrue(manager.contains("if (isDefaultTextSize)"));
    }

    @Test
    public void systemBrightnessReleasesWindowOverrideInsteadOfWritingAnInvalidValue() throws IOException {
        String dialog = readFile("src/main/java/com/ldp/reader/ui/dialog/ReadSettingDialog.kt");
        String brightness = readFile("src/main/java/com/ldp/reader/utils/BrightnessUtils.kt");

        int listener = dialog.indexOf("mCbBrightnessAuto.setOnCheckedChangeListener");
        int fontMinus = dialog.indexOf("mTvFontMinus.setOnClickListener", listener);
        String listenerBody = dialog.substring(listener, fontMinus);
        assertTrue(listenerBody.contains("BrightnessUtils.setDefaultBrightness(mActivity)"));
        assertFalse(listenerBody.contains("BrightnessUtils.getScreenBrightness(mActivity)"));
        assertTrue(brightness.contains("getManualScreenBrightness(activity).coerceIn(0, 255)"));
        assertTrue(brightness.contains("brightness.coerceIn(0, 255)"));
        assertFalse(brightness.contains("nowBrightnessValue * 225.0f"));
    }

    @Test
    public void textEncodingPickerIsVisibleOnlyForLocalTxtAndReparsesOnSelection() throws IOException {
        String dialog = readFile("src/main/java/com/ldp/reader/ui/dialog/ReadSettingDialog.kt");
        String layout = readFile("src/main/res/layout/dialog_read_setting.xml");

        assertTrue(dialog.contains("mPageLoader as? LocalPageLoader"));
        assertTrue(dialog.contains("readSettingEncodingRow.visibility"));
        assertTrue(dialog.contains("AlertDialog.Builder(mActivity)"));
        assertTrue(dialog.contains("localTxtLoader.setTextEncodingPreference(preference)"));
        assertTrue(layout.contains("android:id=\"@+id/read_setting_encoding_row\""));
        assertTrue(layout.contains("android:id=\"@+id/read_setting_encoding_value\""));
        assertTrue(layout.contains("android:visibility=\"gone\""));
    }

    @Test
    public void textEncodingPickerUsesTheAppsLocalizedCancelLabel() throws IOException {
        String dialog = readFile("src/main/java/com/ldp/reader/ui/dialog/ReadSettingDialog.kt");
        int picker = dialog.indexOf("private fun showTextEncodingPicker");
        int encodingLabel = dialog.indexOf("private fun encodingLabel", picker);
        String pickerBody = dialog.substring(picker, encodingLabel);

        assertTrue(pickerBody.contains(".setNegativeButton(R.string.nb_common_cancel, null)"));
        assertFalse(pickerBody.contains("android.R.string.cancel"));

        String simplified = readFile("src/main/res/values/strings.xml");
        String traditional = readFile("src/main/res/values-zh-rTW/strings.xml");
        assertTrue(simplified.contains("<string name=\"nb.common.cancel\">取消</string>"));
        assertTrue(traditional.contains("<string name=\"nb.common.cancel\">取消</string>"));
    }

    private static String readFile(String path) throws IOException {
        return new String(
            Files.readAllBytes(new File(path).toPath()),
            StandardCharsets.UTF_8
        );
    }
}
