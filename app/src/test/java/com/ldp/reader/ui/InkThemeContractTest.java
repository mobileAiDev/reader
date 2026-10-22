package com.ldp.reader.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.Test;

/**
 * The theme extension point: a theme is a style in values and values-night that defines every
 * attribute of attrs_ink_theme.xml, registered once in InkThemes.
 */
public class InkThemeContractTest {
    private static final String ATTRS = "src/main/res/values/attrs_ink_theme.xml";
    private static final String DAY = "src/main/res/values/themes_ink.xml";
    private static final String NIGHT = "src/main/res/values-night/themes_ink.xml";
    private static final String REGISTRY = "src/main/java/com/ldp/reader/ui/theme/InkThemes.kt";

    @Test
    public void everyThemeDefinesEveryAttributeInLightAndDark() throws IOException {
        Set<String> attrs = declaredAttrs();
        assertTrue(attrs.size() > 30);
        Map<String, Set<String>> day = themes(readFile(DAY));
        Map<String, Set<String>> night = themes(readFile(NIGHT));

        assertEquals(day.keySet(), night.keySet());
        for (Map.Entry<String, Set<String>> theme : day.entrySet()) {
            assertEquals("light " + theme.getKey(), attrs, theme.getValue());
            assertEquals("dark " + theme.getKey(), attrs, night.get(theme.getKey()));
        }
    }

    @Test
    public void everyRegisteredThemeHasItsStyleAndEveryStyleIsRegistered() throws IOException {
        String registry = readFile(REGISTRY);
        Set<String> styles = themes(readFile(DAY)).keySet();
        Matcher matcher = Pattern.compile("R\\.style\\.InkTheme_([A-Za-z]+)").matcher(registry);
        Set<String> registered = new LinkedHashSet<>();
        while (matcher.find()) {
            registered.add("InkTheme." + matcher.group(1));
        }
        assertEquals(styles, registered);
        assertTrue(registry.contains("val all = listOf(INK_WASH, MODERN, BOOKSHELF)"));
        assertTrue(registry.contains("activity.theme.applyStyle(theme.overlay, true)"));
    }

    @Test
    public void themedColoursAreResolvedFromTheThemeNotFixedValues() throws IOException {
        for (String attr : declaredColourAttrs()) {
            String token = tokenFor(attr);
            String selector = readFile("src/main/res/color/" + token + ".xml");
            assertTrue(token, selector.contains("android:color=\"?attr/" + attr + "\""));
            assertFalse(token, readFile("src/main/res/values/colors.xml").contains("<color name=\"" + token + "\">"));
            assertFalse(token, readFile("src/main/res/values-night/colors.xml").contains("<color name=\"" + token + "\">"));
        }
    }

    @Test
    public void drawableSlotsNeverPointAtAThemedColourSelector() throws IOException {
        // Before Android 10 a colour selector cannot be loaded as a drawable: use ?attr instead.
        List<String> themedTokens = new ArrayList<>();
        for (String attr : declaredColourAttrs()) {
            themedTokens.add(tokenFor(attr));
        }
        Pattern drawableSlot = Pattern.compile(
                "(android:background|android:drawable|android:windowBackground|android:divider|android:src)\"?\\s*[=>]\\s*\"?@color/(ink_[a-z_]+)");
        try (Stream<Path> files = Files.walk(new File("src/main/res").toPath())) {
            for (Path path : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".xml"))::iterator) {
                Matcher matcher = drawableSlot.matcher(readFile(path.toString()));
                while (matcher.find()) {
                    assertFalse(path + " " + matcher.group(), themedTokens.contains(matcher.group(2)));
                }
            }
        }
    }

    @Test
    public void customDrawablesResolveThemeColoursWhenTheThemeArrives() throws IOException {
        // Resources inflate XML drawables without a theme and apply it afterwards.
        String squircle = readFile("src/main/java/com/ldp/reader/ui/motion/SquircleDrawable.kt");
        assertTrue(squircle.contains("override fun canApplyTheme()"));
        assertTrue(squircle.contains("override fun applyTheme(t: Resources.Theme)"));
        assertTrue(squircle.contains("t.resources.getColorStateList(fillColorRes, t)"));
    }

    @Test
    public void uiCodeLoadsColoursAndDrawablesThroughATheme() throws IOException {
        // Theme-less loads (Resources.getColor, ResourcesCompat with a null theme) show magenta.
        try (Stream<Path> files = Files.walk(new File("src/main/java/com/ldp/reader/ui").toPath())) {
            for (Path path : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".kt"))::iterator) {
                String source = readFile(path.toString());
                assertFalse(path.toString(), source.contains("resources.getColor("));
                assertFalse(path.toString(), source.contains("ResourcesCompat.getDrawable("));
            }
        }
    }

    private static Set<String> declaredAttrs() throws IOException {
        Matcher matcher = Pattern.compile("<attr name=\"(ink[A-Za-z]+)\" format=").matcher(readFile(ATTRS));
        Set<String> attrs = new LinkedHashSet<>();
        while (matcher.find()) {
            attrs.add(matcher.group(1));
        }
        return attrs;
    }

    private static Set<String> declaredColourAttrs() throws IOException {
        Matcher matcher = Pattern.compile("<attr name=\"(ink[A-Za-z]+)\" format=\"color\"").matcher(readFile(ATTRS));
        Set<String> attrs = new LinkedHashSet<>();
        while (matcher.find()) {
            attrs.add(matcher.group(1));
        }
        return attrs;
    }

    private static Map<String, Set<String>> themes(String xml) {
        Map<String, Set<String>> themes = new LinkedHashMap<>();
        Matcher style = Pattern.compile("<style name=\"(InkTheme\\.[A-Za-z]+)\" parent=\"\">(.*?)</style>", Pattern.DOTALL)
                .matcher(xml);
        while (style.find()) {
            Matcher item = Pattern.compile("<item name=\"(ink[A-Za-z]+)\">").matcher(style.group(2));
            Set<String> names = new LinkedHashSet<>();
            while (item.find()) {
                names.add(item.group(1));
            }
            themes.put(style.group(1), names);
        }
        return themes;
    }

    /** inkSurfaceRaised -> ink_surface_raised */
    private static String tokenFor(String attr) {
        return attr.replaceAll("([A-Z])", "_$1").toLowerCase();
    }

    private static String readFile(String path) throws IOException {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }
}
