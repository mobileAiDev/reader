package com.ldp.reader.ui.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.File;
import java.io.IOException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.xml.sax.SAXException;

public class ReaderCatalogHeaderLayoutContractTest {

    private static final String ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android";

    @Test
    public void wrongChapterToggleAndLiveProgressUseSeparateBoundedRows()
            throws IOException, ParserConfigurationException, SAXException {
        Document layout = readLayout("src/main/res/layout/activity_read.xml");

        Element header = elementById(layout, "@+id/read_ll_catalog_header");
        Element titleToggleRow = elementById(layout, "@+id/read_ll_catalog_title_toggle_row");
        Element title = elementById(layout, "@+id/read_tv_catalog_title");
        Element toggle = elementById(layout, "@+id/read_cb_show_wrong_chapters");
        Element progressRow = elementById(layout, "@+id/read_ll_wrong_analysis_loading");
        Element status = elementById(layout, "@+id/read_tv_wrong_analysis_status");

        assertEquals("vertical", androidAttribute(header, "orientation"));

        assertSame(header, titleToggleRow.getParentNode());
        assertEquals("match_parent", androidAttribute(titleToggleRow, "layout_width"));
        assertEquals("horizontal", androidAttribute(titleToggleRow, "orientation"));
        assertSame(titleToggleRow, title.getParentNode());
        assertSame(titleToggleRow, toggle.getParentNode());
        assertEquals("0dp", androidAttribute(toggle, "layout_width"));
        assertEquals("1", androidAttribute(toggle, "layout_weight"));
        assertEquals("48dp", androidAttribute(toggle, "minHeight"));
        assertFalse("toggle label must be allowed to wrap at large font scales",
                "true".equals(androidAttribute(toggle, "singleLine")));

        assertSame(header, progressRow.getParentNode());
        assertEquals("match_parent", androidAttribute(progressRow, "layout_width"));
        assertEquals("horizontal", androidAttribute(progressRow, "orientation"));
        assertSame(progressRow, status.getParentNode());
        assertEquals("0dp", androidAttribute(status, "layout_width"));
        assertEquals("1", androidAttribute(status, "layout_weight"));
        assertFalse("processed/target text must be allowed to wrap instead of clipping",
                "1".equals(androidAttribute(status, "maxLines")));
        assertFalse("processed/target text must not be ellipsized",
                "end".equals(androidAttribute(status, "ellipsize")));
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
}
