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

public class ReaderSettingPanelLayoutContractTest {
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";
    private static final String PANEL_SOURCE = "src/main/java/com/ldp/reader/ui/reader/ReadSettingPanel.kt";

    @Test
    public void glassSheetFloatsOverThePageAndDismissesOnOutsideTap() throws Exception {
        Element windowRoot = parseXml("src/main/res/layout/view_read_setting_panel.xml")
                .getDocumentElement();

        assertEquals("FrameLayout", windowRoot.getTagName());
        assertEquals("@+id/read_setting_window_root", androidAttribute(windowRoot, "id"));
        assertEquals("match_parent", androidAttribute(windowRoot, "layout_width"));
        assertEquals("match_parent", androidAttribute(windowRoot, "layout_height"));
        assertEquals("@android:color/transparent", androidAttribute(windowRoot, "background"));
        assertEquals("true", androidAttribute(windowRoot, "clickable"));
        assertEquals("gone", androidAttribute(windowRoot, "visibility"));

        Element sheet = directChildWithId(windowRoot, "@+id/read_setting_ll_menu");
        assertNotNull(sheet);
        assertEquals("com.ldp.reader.ui.reader.ReaderSheetLayout", sheet.getTagName());
        assertEquals("wrap_content", androidAttribute(sheet, "layout_height"));
        assertEquals("bottom", androidAttribute(sheet, "layout_gravity"));
        assertEquals("@dimen/reader_float_margin", androidAttribute(sheet, "layout_marginBottom"));
        assertEquals("true", androidAttribute(sheet, "clickable"));

        String panel = readFile(PANEL_SOURCE);
        assertTrue(panel.contains("binding.readSettingWindowRoot.setOnClickListener { dismiss() }"));
        assertTrue(panel.contains("binding.readSettingLlMenu,\n            atTop = false,"));
        assertTrue(panel.contains("binding.readSettingLlMenu.scrollContent = binding.readSettingScroll"));
        assertTrue(panel.contains("binding.readSettingLlMenu.glass.style = GlassStyle.readerPanel(palette.panel, palette.isDark)"));
    }

    @Test
    public void shortWindowsKeepAllNovelAndLocalTxtSettingsInOneVerticalScrollRange()
            throws Exception {
        Element windowRoot = parseXml("src/main/res/layout/view_read_setting_panel.xml")
                .getDocumentElement();
        Element sheet = directChildWithId(windowRoot, "@+id/read_setting_ll_menu");
        Element scroll = directChildWithId(sheet, "@+id/read_setting_scroll");
        Element content = directChildWithId(scroll, "@+id/read_setting_content");

        assertNotNull(scroll);
        assertEquals("ScrollView", scroll.getTagName());
        assertEquals("none", androidAttribute(scroll, "scrollbars"));
        assertNotNull(content);
        assertEquals("LinearLayout", content.getTagName());
        assertEquals("vertical", androidAttribute(content, "orientation"));

        assertNotNull(descendantWithId(content, "@+id/read_setting_sb_brightness"));
        assertNotNull(descendantWithId(content, "@+id/read_setting_tv_font_minus"));
        assertNotNull(descendantWithId(content, "@+id/read_setting_tv_font_plus"));
        assertNotNull(descendantWithId(content, "@+id/read_setting_rg_page_mode"));
        assertNotNull(descendantWithId(content, "@+id/read_setting_rv_bg"));

        Element encodingRow = descendantWithId(content, "@+id/read_setting_encoding_row");
        assertNotNull(encodingRow);
        assertEquals("56dp", androidAttribute(encodingRow, "layout_height"));
        assertEquals("gone", androidAttribute(encodingRow, "visibility"));
    }

    @Test
    public void hiddenMoreSettingsDoesNotReservePanelHeight() throws Exception {
        Element layout = parseXml("src/main/res/layout/view_read_setting_panel.xml")
                .getDocumentElement();
        Element moreSettings = descendantWithId(layout, "@+id/read_setting_tv_more");

        assertNotNull(moreSettings);
        assertEquals("gone", androidAttribute(moreSettings, "visibility"));

        String panel = readFile(PANEL_SOURCE);
        assertTrue(panel.contains("mTvMore.visibility = View.GONE"));
        assertTrue(!panel.contains("mTvMore.visibility = View.INVISIBLE"));
    }

    private static String readFile(String path) throws Exception {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
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
