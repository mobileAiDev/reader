package com.ldp.reader.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

public class ReadSettingPanelContractTest {
    @Test
    public void brightnessMinusPersistsTheSameAppliedValue() throws IOException {
        String panel = readFile("src/main/java/com/ldp/reader/ui/reader/ReadSettingPanel.kt");
        assertTrue(panel.contains("mIvBrightnessMinus.setOnClickListener { stepBrightness(-BRIGHTNESS_STEP) }"));
        int step = panel.indexOf("private fun stepBrightness(delta: Int)");
        int stepEnd = panel.indexOf("private fun stepFont", step);
        String stepBody = panel.substring(step, stepEnd);

        assertTrue(stepBody.contains("BrightnessUtils.setBrightness(mActivity, progress)"));
        assertTrue(stepBody.contains("mSettingManager.brightness = progress"));
    }

    @Test
    public void defaultFontStateAndCanonicalSpSizeArePersisted() throws IOException {
        String panel = readFile("src/main/java/com/ldp/reader/ui/reader/ReadSettingPanel.kt");
        String manager = readFile("src/main/java/com/ldp/reader/model/local/ReadSettingManager.kt");

        int listener = panel.indexOf("mCbFontDefault.setOnCheckedChangeListener");
        int pageMode = panel.indexOf("mRgPageMode.setOnCheckedChangeListener", listener);
        String listenerBody = panel.substring(listener, pageMode);
        assertTrue(listenerBody.contains("mSettingManager.isDefaultTextSize = isChecked"));
        assertTrue(listenerBody.contains("ReaderTextSize.DEFAULT_SP"));

        assertTrue(panel.contains("ReaderTextSize.displaySp("));
        assertTrue(panel.contains("ReaderTextSize.toStoredPx("));
        assertFalse(panel.contains("ScreenUtils.dpToPx(DEFAULT_TEXT_SIZE)"));
        assertFalse(panel.contains("private const val DEFAULT_TEXT_SIZE = 16"));

        assertTrue(manager.contains("ReaderTextSize.defaultStoredPx("));
        assertTrue(manager.contains("if (isDefaultTextSize)"));
    }

    @Test
    public void systemBrightnessReleasesWindowOverrideInsteadOfWritingAnInvalidValue() throws IOException {
        String panel = readFile("src/main/java/com/ldp/reader/ui/reader/ReadSettingPanel.kt");
        String brightness = readFile("src/main/java/com/ldp/reader/utils/BrightnessUtils.kt");

        int listener = panel.indexOf("mCbBrightnessAuto.setOnCheckedChangeListener");
        int fontMinus = panel.indexOf("mTvFontMinus.setOnClickListener", listener);
        String listenerBody = panel.substring(listener, fontMinus);
        assertTrue(listenerBody.contains("BrightnessUtils.setDefaultBrightness(mActivity)"));
        assertFalse(listenerBody.contains("BrightnessUtils.getScreenBrightness(mActivity)"));
        assertTrue(brightness.contains("getManualScreenBrightness(activity).coerceIn(0, 255)"));
        assertTrue(brightness.contains("brightness.coerceIn(0, 255)"));
        assertFalse(brightness.contains("nowBrightnessValue * 225.0f"));
    }

    @Test
    public void textEncodingPickerIsVisibleOnlyForLocalTxtAndReparsesOnSelection() throws IOException {
        String panel = readFile("src/main/java/com/ldp/reader/ui/reader/ReadSettingPanel.kt");
        String layout = readFile("src/main/res/layout/view_read_setting_panel.xml");

        assertTrue(panel.contains("mPageLoader as? LocalPageLoader"));
        assertTrue(panel.contains("readSettingEncodingRow.visibility"));
        assertTrue(panel.contains("MaterialAlertDialogBuilder(mActivity)"));
        assertTrue(panel.contains("localTxtLoader.setTextEncodingPreference(preference)"));
        assertTrue(layout.contains("android:id=\"@+id/read_setting_encoding_row\""));
        assertTrue(layout.contains("android:id=\"@+id/read_setting_encoding_value\""));
        assertTrue(layout.contains("android:visibility=\"gone\""));
    }

    @Test
    public void textEncodingPickerUsesTheAppsLocalizedCancelLabel() throws IOException {
        String panel = readFile("src/main/java/com/ldp/reader/ui/reader/ReadSettingPanel.kt");
        int picker = panel.indexOf("private fun showTextEncodingPicker");
        int encodingLabel = panel.indexOf("private fun encodingLabel", picker);
        String pickerBody = panel.substring(picker, encodingLabel);

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
