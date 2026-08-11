package com.ldp.reader.ui.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

public class ReaderBottomDialogLayoutContractTest {
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    @Test
    public void fullHeightTransparentWindowLetsBottomPanelPaintTheGestureAreaOnce() throws Exception {
        Element windowRoot = parseXml("src/main/res/layout/dialog_read_setting.xml")
                .getDocumentElement();

        assertEquals("FrameLayout", windowRoot.getTagName());
        assertEquals("@+id/read_setting_window_root", androidAttribute(windowRoot, "id"));
        assertEquals("match_parent", androidAttribute(windowRoot, "layout_width"));
        assertEquals("match_parent", androidAttribute(windowRoot, "layout_height"));
        assertEquals("@android:color/transparent", androidAttribute(windowRoot, "background"));
        assertEquals("true", androidAttribute(windowRoot, "clickable"));

        Element panel = directChildWithId(windowRoot, "@+id/read_setting_ll_menu");
        assertNotNull(panel);
        assertEquals("ScrollView", panel.getTagName());
        assertEquals("wrap_content", androidAttribute(panel, "layout_height"));
        assertEquals("bottom", androidAttribute(panel, "layout_gravity"));
        assertEquals("@color/nb.read.menu.bg", androidAttribute(panel, "background"));
        assertEquals("true", androidAttribute(panel, "clickable"));

        String dialog = new String(
                Files.readAllBytes(
                        new File("src/main/java/com/ldp/reader/ui/dialog/ReadSettingDialog.kt")
                                .toPath()),
                StandardCharsets.UTF_8
        );
        assertTrue(dialog.contains("lp.height = WindowManager.LayoutParams.MATCH_PARENT"));
        assertTrue(dialog.contains(
                "MediaUiChrome.prepareReaderBottomDialog(window, binding.readSettingLlMenu)"
        ));
        assertTrue(dialog.contains(
                "binding.readSettingWindowRoot.setOnClickListener { dismiss() }"
        ));
    }

    @Test
    public void shortWindowsKeepAllNovelAndLocalTxtSettingsInOneVerticalScrollRange()
            throws Exception {
        Element windowRoot = parseXml("src/main/res/layout/dialog_read_setting.xml")
                .getDocumentElement();
        Element panel = directChildWithId(windowRoot, "@+id/read_setting_ll_menu");
        Element content = directChildWithId(panel, "@+id/read_setting_content");

        assertNotNull(content);
        assertEquals("LinearLayout", content.getTagName());
        assertEquals("vertical", androidAttribute(content, "orientation"));
        assertEquals("vertical", androidAttribute(panel, "scrollbars"));

        assertNotNull(descendantWithId(content, "@+id/read_setting_sb_brightness"));
        assertNotNull(descendantWithId(content, "@+id/read_setting_tv_font_minus"));
        assertNotNull(descendantWithId(content, "@+id/read_setting_tv_font_plus"));
        assertNotNull(descendantWithId(content, "@+id/read_setting_rg_page_mode"));
        assertNotNull(descendantWithId(content, "@+id/read_setting_rv_bg"));

        Element encodingRow = descendantWithId(
                content,
                "@+id/read_setting_encoding_row"
        );
        assertNotNull(encodingRow);
        assertEquals("52dp", androidAttribute(encodingRow, "layout_height"));
        assertEquals("gone", androidAttribute(encodingRow, "visibility"));
    }

    @Test
    public void hiddenMoreSettingsDoesNotReservePanelHeight() throws Exception {
        Element layout = parseXml("src/main/res/layout/dialog_read_setting.xml")
                .getDocumentElement();
        Element moreSettings = descendantWithId(layout, "@+id/read_setting_tv_more");

        assertNotNull(moreSettings);
        assertEquals("gone", androidAttribute(moreSettings, "visibility"));

        String dialog = new String(
                Files.readAllBytes(
                        new File("src/main/java/com/ldp/reader/ui/dialog/ReadSettingDialog.kt")
                                .toPath()),
                StandardCharsets.UTF_8
        );
        assertTrue(dialog.contains("mTvMore.visibility = View.GONE"));
        assertTrue(!dialog.contains("mTvMore.visibility = View.INVISIBLE"));
    }

    private static String androidAttribute(Element element, String name) {
        return element.getAttributeNS(ANDROID_NS, name);
    }

    private static Element directChildWithId(Element parent, String id) {
        if (parent == null) {
            return null;
        }
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child instanceof Element) {
                Element element = (Element) child;
                if (id.equals(androidAttribute(element, "id"))) {
                    return element;
                }
            }
        }
        return null;
    }

    private static Element descendantWithId(Element parent, String id) {
        if (parent == null) {
            return null;
        }
        if (id.equals(androidAttribute(parent, "id"))) {
            return parent;
        }
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child instanceof Element) {
                Element match = descendantWithId((Element) child, id);
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
    }

    private static org.w3c.dom.Document parseXml(String path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new File(path));
    }

}
