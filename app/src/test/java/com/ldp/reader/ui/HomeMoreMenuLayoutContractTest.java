package com.ldp.reader.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

public class HomeMoreMenuLayoutContractTest {

    private static final String ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android";
    private static final List<String> ACTION_IDS = Arrays.asList(
            "@+id/home_menu_import",
            "@+id/home_menu_import_source",
            "@+id/home_menu_sync",
            "@+id/home_menu_account"
    );

    @Test
    public void allFourActionsMeetThe48DpTouchTargetWithoutChangingPopupGeometry()
            throws IOException, ParserConfigurationException, SAXException {
        Document layout = readLayout("src/main/res/layout/view_home_more_menu.xml");
        Element popup = elementById(layout, "@+id/home_menu_popup");
        Element arrow = elementById(layout, "@+id/home_menu_arrow");

        assertEquals("wrap_content", androidAttribute(popup, "layout_width"));
        assertEquals("wrap_content", androidAttribute(popup, "layout_height"));
        assertEquals("vertical", androidAttribute(popup, "orientation"));
        assertEquals("87dp", androidAttribute(popup, "layout_marginTop"));
        assertEquals("15dp", androidAttribute(popup, "layout_marginRight"));
        assertEquals("@drawable/bg_home_more_popup", androidAttribute(popup, "background"));

        int totalActionHeightDp = 0;
        for (String actionId : ACTION_IDS) {
            Element action = elementById(layout, actionId);
            assertSame("all menu actions must remain direct popup rows", popup, action.getParentNode());
            assertEquals("wrap_content", androidAttribute(action, "layout_width"));
            assertEquals("48dp", androidAttribute(action, "layout_height"));
            assertEquals("center_vertical", androidAttribute(action, "gravity"));
            assertEquals("16dp", androidAttribute(action, "paddingLeft"));
            assertEquals("16dp", androidAttribute(action, "paddingRight"));
            assertEquals("?attr/selectableItemBackground", androidAttribute(action, "background"));
            totalActionHeightDp += dimensionDp(androidAttribute(action, "layout_height"));
        }
        assertEquals("four 48dp actions make a bounded 192dp popup body", 192, totalActionHeightDp);

        assertEquals("14dp", androidAttribute(arrow, "layout_width"));
        assertEquals("7dp", androidAttribute(arrow, "layout_height"));
        assertEquals("80dp", androidAttribute(arrow, "layout_marginTop"));
        assertEquals("28dp", androidAttribute(arrow, "layout_marginRight"));
        assertEquals("@drawable/ic_home_more_popup_arrow", androidAttribute(arrow, "src"));

        NodeList images = popup.getElementsByTagName("ImageView");
        NodeList labels = popup.getElementsByTagName("TextView");
        assertEquals(4, images.getLength());
        assertEquals(4, labels.getLength());
        for (int index = 0; index < 4; index++) {
            Element image = (Element) images.item(index);
            Element label = (Element) labels.item(index);
            assertEquals("20dp", androidAttribute(image, "layout_width"));
            assertEquals("20dp", androidAttribute(image, "layout_height"));
            assertEquals("8dp", androidAttribute(label, "layout_marginLeft"));
            assertEquals("16sp", androidAttribute(label, "textSize"));
            assertEquals("1", androidAttribute(label, "maxLines"));
            assertEquals("end", androidAttribute(label, "ellipsize"));
        }
    }

    @Test
    public void backScrimAndPopupKeepTheExpectedDismissBoundary() throws IOException {
        String source = new String(
                Files.readAllBytes(
                        new File("src/main/java/com/ldp/reader/ui/widget/HomeMoreMenuView.kt").toPath()
                ),
                StandardCharsets.UTF_8
        );

        assertTrue(source.contains("binding.homeMenuScrim.setOnClickListener { dismiss() }"));
        assertTrue(source.contains("binding.homeMenuPopup.setOnClickListener { }"));
        assertTrue(source.contains("event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP"));
        assertTrue(source.contains("dismiss()\n            return true"));
        assertTrue(source.contains("(parent as? android.view.ViewGroup)?.removeView(this)"));
        assertTrue(source.contains("onDismiss?.invoke()"));
    }

    private static Document readLayout(String path)
            throws IOException, ParserConfigurationException, SAXException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder().parse(new File(path));
    }

    private static Element elementById(Document document, String id) {
        NodeList allElements = document.getElementsByTagName("*");
        for (int index = 0; index < allElements.getLength(); index++) {
            Node node = allElements.item(index);
            if (node instanceof Element) {
                Element element = (Element) node;
                if (id.equals(androidAttribute(element, "id"))) {
                    return element;
                }
            }
        }
        throw new AssertionError("Missing layout element " + id);
    }

    private static String androidAttribute(Element element, String name) {
        return element.getAttributeNS(ANDROID_NAMESPACE, name);
    }

    private static int dimensionDp(String value) {
        if (!value.endsWith("dp")) {
            throw new AssertionError("Expected dp dimension but was " + value);
        }
        return Integer.parseInt(value.substring(0, value.length() - 2));
    }
}
