package com.ldp.reader.document.epub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.jsoup.Jsoup
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubPackageParserTest {
    private val parser = EpubPackageParser()

    @Test
    fun `EPUB3 navigation exposes user facing titles and spine targets`() {
        val epub = epub(
            "META-INF/container.xml" to container("OPS/package.opf"),
            "OPS/package.opf" to opf(
                manifest = """
                    <item id="one" href="Text/0001.xhtml" media-type="application/xhtml+xml"/>
                    <item id="two" href="Text/0002.xhtml" media-type="application/xhtml+xml"/>
                    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                """,
                spine = "<itemref idref=\"one\"/><itemref idref=\"two\"/>"
            ),
            "OPS/Text/0001.xhtml" to xhtml("<h1 id=\"opening\">第一章</h1>"),
            "OPS/Text/0002.xhtml" to xhtml("<h1 id=\"turn\">第二章</h1>"),
            "OPS/nav.xhtml" to """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                  <head/><body><nav epub:type="toc"><ol>
                    <li><a href="Text/0001.xhtml#opening">第一章 开端</a></li>
                    <li><a href="Text/0002.xhtml#turn">第二章 转折</a></li>
                  </ol></nav></body>
                </html>
            """.trimIndent()
        )

        val document = parser.parse(ByteArrayInputStream(epub))

        assertEquals(
            listOf(
                "第一章 开端" to 0,
                "第二章 转折" to 1
            ),
            document.catalogEntries.map { it.title to it.sectionIndex }
        )
        assertTrue(document.catalogEntries.all { !it.targetId.isNullOrBlank() })
        assertTrue(document.catalogEntries.all { document.bodyHtml.contains("id=\"${it.targetId}\"") })
    }

    @Test
    fun `EPUB2 NCX exposes user facing titles and spine targets`() {
        val epub = epub(
            "META-INF/container.xml" to container("OEBPS/content.opf"),
            "OEBPS/content.opf" to """
                <?xml version="1.0" encoding="UTF-8"?>
                <package version="2.0" xmlns="http://www.idpf.org/2007/opf">
                  <manifest>
                    <item id="chapter-a" href="Text/a.xhtml" media-type="application/xhtml+xml"/>
                    <item id="chapter-b" href="Text/b.xhtml" media-type="application/xhtml+xml"/>
                    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                  </manifest>
                  <spine toc="ncx"><itemref idref="chapter-a"/><itemref idref="chapter-b"/></spine>
                </package>
            """.trimIndent(),
            "OEBPS/Text/a.xhtml" to xhtml("<h1 id=\"a-start\">甲</h1>"),
            "OEBPS/Text/b.xhtml" to xhtml("<h1 id=\"b-start\">乙</h1>"),
            "OEBPS/toc.ncx" to """
                <?xml version="1.0" encoding="UTF-8"?>
                <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/">
                  <navMap>
                    <navPoint id="a"><navLabel><text>甲章 真标题</text></navLabel><content src="Text/a.xhtml#a-start"/></navPoint>
                    <navPoint id="b"><navLabel><text>乙章 真标题</text></navLabel><content src="Text/b.xhtml#b-start"/></navPoint>
                  </navMap>
                </ncx>
            """.trimIndent()
        )

        val document = parser.parse(ByteArrayInputStream(epub))

        assertEquals(
            listOf(
                "甲章 真标题" to 0,
                "乙章 真标题" to 1
            ),
            document.catalogEntries.map { it.title to it.sectionIndex }
        )
        assertTrue(document.catalogEntries.all { !it.targetId.isNullOrBlank() })
        assertTrue(document.catalogEntries.all { document.bodyHtml.contains("id=\"${it.targetId}\"") })
    }

    @Test
    fun `uses container OPF and spine order instead of ZIP entry order`() {
        val epub = epub(
            "OPS/Text/one.xhtml" to xhtml("ONE"),
            "OPS/Text/two.xhtml" to xhtml("TWO"),
            "OPS/nav.xhtml" to xhtml("NAVIGATION"),
            "OPS/package.opf" to opf(
                manifest = """
                    <item id="one" href="Text/one.xhtml" media-type="application/xhtml+xml"/>
                    <item id="two" href="Text/two.xhtml" media-type="application/xhtml+xml"/>
                    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                """,
                spine = "<itemref idref=\"two\"/><itemref idref=\"one\"/>"
            ),
            "META-INF/container.xml" to container("OPS/package.opf")
        )

        val document = parser.parse(ByteArrayInputStream(epub))

        assertTrue(document.bodyHtml.indexOf("TWO") < document.bodyHtml.indexOf("ONE"))
        assertFalse(document.bodyHtml.contains("NAVIGATION"))
        assertEquals(listOf("OPS/Text/two.xhtml", "OPS/Text/one.xhtml"), document.spinePaths)
        assertEquals(listOf("1. two", "2. one"), document.catalogEntries.map { it.title })
        assertFalse(document.resources.containsKey("OPS/Text/one.xhtml"))
        assertFalse(document.resources.containsKey("OPS/Text/two.xhtml"))
    }

    @Test
    fun `resolves chapter relative resources against each chapter path`() {
        val image = byteArrayOf(1, 2, 3, 4)
        val epub = epub(
            "META-INF/container.xml" to container("OPS/package.opf"),
            "OPS/package.opf" to opf(
                manifest = """
                    <item id="chapter" href="Text/chapter.xhtml" media-type="application/xhtml+xml"/>
                    <item id="css" href="Styles/main.css" media-type="text/css"/>
                    <item id="image" href="Images/cover space.png" media-type="image/png"/>
                """,
                spine = "<itemref idref=\"chapter\"/>"
            ),
            "OPS/Text/chapter.xhtml" to """
                <?xml version="1.0" encoding="UTF-8"?>
                <html xmlns="http://www.w3.org/1999/xhtml">
                  <head><link rel="stylesheet" href="../Styles/main.css"/><style>.cover{background:url('../Images/cover space.png')}</style></head>
                  <body onclick="danger()"><script>danger()</script><img src="../Images/cover space.png"/><p style="background:url('../Images/cover space.png')">正文</p></body>
                </html>
            """.trimIndent(),
            "OPS/Styles/main.css" to "body { background-image: url('../Images/cover space.png'); }",
            "OPS/Images/cover space.png" to image
        )

        val document = parser.parse(ByteArrayInputStream(epub))

        assertTrue(document.headHtml.contains("https://epub.local/OPS/Styles/main.css"))
        assertTrue(
            document.headHtml,
            document.headHtml.contains(".cover{background:url('https://epub.local/OPS/Images/cover%20space.png')}")
        )
        assertTrue(document.bodyHtml.contains("https://epub.local/OPS/Images/cover%20space.png"))
        assertTrue(document.bodyHtml.contains("background:url('https://epub.local/OPS/Images/cover%20space.png')"))
        assertFalse(document.bodyHtml.contains("<script"))
        assertFalse(document.bodyHtml.contains("onclick"))
        assertTrue(image.contentEquals(document.resources.getValue("OPS/Images/cover space.png").bytes))
        assertEquals("image/png", document.resources.getValue("OPS/Images/cover space.png").mediaType)
    }

    @Test
    fun `cross chapter links preserve their exact fragment in the combined document`() {
        val epub = epub(
            "META-INF/container.xml" to container("OPS/package.opf"),
            "OPS/package.opf" to opf(
                manifest = """
                    <item id="one" href="Text/one.xhtml" media-type="application/xhtml+xml"/>
                    <item id="two" href="Text/two.xhtml" media-type="application/xhtml+xml"/>
                """,
                spine = "<itemref idref=\"one\"/><itemref idref=\"two\"/>"
            ),
            "OPS/Text/one.xhtml" to """
                <html xmlns="http://www.w3.org/1999/xhtml"><head/><body><a href="two.xhtml#target%20one">下一章</a></body></html>
            """.trimIndent(),
            "OPS/Text/two.xhtml" to """
                <html xmlns="http://www.w3.org/1999/xhtml"><head/><body><p id="target one">第二章</p></body></html>
            """.trimIndent()
        )

        val document = parser.parse(ByteArrayInputStream(epub))

        val combined = Jsoup.parse(document.bodyHtml)
        val linkTargetId = combined.selectFirst("a")?.attr("href")?.removePrefix("#")
        val targetProxy = linkTargetId?.let(combined::getElementById)
        assertNotNull(targetProxy)
        assertEquals("target one", targetProxy?.nextElementSibling()?.id())
        assertTrue(document.bodyHtml.contains("id=\"epub-section-2\""))
        assertFalse(document.bodyHtml.contains("href=\"https://epub.local/OPS/Text/two.xhtml"))
    }

    @Test
    fun `rejects archive entries that escape the EPUB root`() {
        val epub = epub(
            "META-INF/container.xml" to container("OPS/package.opf"),
            "../escape.xhtml" to xhtml("escape")
        )

        val error = expectParseFailure(epub)

        assertEquals(EpubParseError.UNSAFE_PATH, error.reason)
    }

    @Test
    fun `rejects manifest references that escape the EPUB root`() {
        val epub = epub(
            "META-INF/container.xml" to container("OPS/package.opf"),
            "OPS/package.opf" to opf(
                manifest = "<item id=\"chapter\" href=\"../../escape.xhtml\" media-type=\"application/xhtml+xml\"/>",
                spine = "<itemref idref=\"chapter\"/>"
            )
        )

        val error = expectParseFailure(epub)

        assertEquals(EpubParseError.UNSAFE_PATH, error.reason)
    }

    @Test
    fun `reports an empty or non EPUB archive explicitly`() {
        val error = expectParseFailure(epub("notes.txt" to "not an epub"))

        assertEquals(EpubParseError.CONTAINER_MISSING, error.reason)
    }

    @Test
    fun `rejects malformed package metadata instead of scanning HTML entries`() {
        val epub = epub(
            "META-INF/container.xml" to "<not-container/>",
            "chapter.xhtml" to xhtml("must not be used as a fallback")
        )

        val error = expectParseFailure(epub)

        assertEquals(EpubParseError.PACKAGE_INVALID, error.reason)
    }

    @Test
    fun `bounds a decompressed ZIP entry`() {
        val limitedParser = EpubPackageParser(
            EpubReadLimits(maxEntries = 20, maxEntryBytes = 512, maxTotalBytes = 4096)
        )
        val epub = epub(
            "META-INF/container.xml" to container("OPS/package.opf"),
            "OPS/package.opf" to opf(
                manifest = "<item id=\"chapter\" href=\"Text/chapter.xhtml\" media-type=\"application/xhtml+xml\"/>",
                spine = "<itemref idref=\"chapter\"/>"
            ),
            "OPS/Text/chapter.xhtml" to xhtml("A".repeat(2_000))
        )

        val error = try {
            limitedParser.parse(ByteArrayInputStream(epub))
            throw AssertionError("Expected EpubParseException")
        } catch (error: EpubParseException) {
            error
        }

        assertEquals(EpubParseError.ENTRY_TOO_LARGE, error.reason)
    }

    @Test
    fun `bounds archive entry count before parsing package metadata`() {
        val limitedParser = EpubPackageParser(
            EpubReadLimits(maxEntries = 2, maxEntryBytes = 4096, maxTotalBytes = 8192)
        )
        val epub = epub(
            "mimetype" to "application/epub+zip",
            "META-INF/container.xml" to container("OPS/package.opf"),
            "OPS/package.opf" to opf("", "")
        )

        val error = try {
            limitedParser.parse(ByteArrayInputStream(epub))
            throw AssertionError("Expected EpubParseException")
        } catch (error: EpubParseException) {
            error
        }

        assertEquals(EpubParseError.TOO_MANY_ENTRIES, error.reason)
    }

    @Test
    fun `bounds total decompressed archive bytes`() {
        val limitedParser = EpubPackageParser(
            EpubReadLimits(maxEntries = 20, maxEntryBytes = 4_096, maxTotalBytes = 700)
        )
        val epub = epub(
            "META-INF/container.xml" to container("OPS/package.opf"),
            "OPS/package.opf" to opf(
                manifest = "<item id=\"chapter\" href=\"Text/chapter.xhtml\" media-type=\"application/xhtml+xml\"/>",
                spine = "<itemref idref=\"chapter\"/>"
            ),
            "OPS/Text/chapter.xhtml" to xhtml("A".repeat(1_000))
        )

        val error = try {
            limitedParser.parse(ByteArrayInputStream(epub))
            throw AssertionError("Expected EpubParseException")
        } catch (error: EpubParseException) {
            error
        }

        assertEquals(EpubParseError.ARCHIVE_TOO_LARGE, error.reason)
    }

    @Test
    fun `rejects duplicate canonical archive paths`() {
        val epub = epub(
            "META-INF/container.xml" to container("OPS/package.opf"),
            "OPS/package.opf" to opf("", ""),
            "OPS/./package.opf" to opf("", "")
        )

        val error = expectParseFailure(epub)

        assertEquals(EpubParseError.DUPLICATE_ENTRY, error.reason)
    }

    @Test
    fun `decodes percent encoded OPF references and emits encoded virtual URLs`() {
        val epub = epub(
            "META-INF/container.xml" to container("OPS/package.opf"),
            "OPS/package.opf" to opf(
                manifest = """
                    <item id="chapter" href="Text/chapter%20one.xhtml" media-type="application/xhtml+xml"/>
                    <item id="image" href="Text/image%20one.png" media-type="image/png"/>
                """,
                spine = "<itemref idref=\"chapter\"/>"
            ),
            "OPS/Text/chapter one.xhtml" to """
                <html xmlns="http://www.w3.org/1999/xhtml"><head/><body><img src="image%20one.png"/></body></html>
            """.trimIndent(),
            "OPS/Text/image one.png" to byteArrayOf(1)
        )

        val document = parser.parse(ByteArrayInputStream(epub))

        assertEquals(listOf("OPS/Text/chapter one.xhtml"), document.spinePaths)
        assertTrue(document.bodyHtml.contains("https://epub.local/OPS/Text/image%20one.png"))
    }

    @Test
    fun `rejects a spine that has no readable content document`() {
        val epub = epub(
            "META-INF/container.xml" to container("OPS/package.opf"),
            "OPS/package.opf" to opf(
                manifest = "<item id=\"image\" href=\"cover.jpg\" media-type=\"image/jpeg\"/>",
                spine = "<itemref idref=\"image\"/>"
            ),
            "OPS/cover.jpg" to byteArrayOf(1, 2, 3)
        )

        val error = expectParseFailure(epub)

        assertEquals(EpubParseError.SPINE_EMPTY, error.reason)
    }

    @Test
    fun `cooperatively propagates parser cancellation`() {
        val epub = validEpub(xhtml("cancel me"))
        var checks = 0

        val error = try {
            parser.parse(ByteArrayInputStream(epub)) {
                checks += 1
                if (checks >= 4) throw CancellationException("cancelled")
            }
            throw AssertionError("Expected CancellationException")
        } catch (error: CancellationException) {
            error
        }

        assertEquals("cancelled", error.message)
        assertTrue(checks >= 4)
    }

    @Test
    fun `rejects path names beyond the configured UTF-8 byte limit`() {
        val limitedParser = EpubPackageParser(EpubReadLimits(maxPathBytes = 64))
        val epub = epub("目录/" + "章".repeat(30) + ".xhtml" to xhtml("too long"))

        val error = try {
            limitedParser.parse(ByteArrayInputStream(epub))
            throw AssertionError("Expected EpubParseException")
        } catch (error: EpubParseException) {
            error
        }

        assertEquals(EpubParseError.PATH_TOO_LONG, error.reason)
    }

    @Test
    fun `bounds rendered HTML independently from decompressed bytes`() {
        val limitedParser = EpubPackageParser(EpubReadLimits(maxRenderedHtmlChars = 256))

        val error = try {
            limitedParser.parse(ByteArrayInputStream(validEpub(xhtml("A".repeat(1_000)))))
            throw AssertionError("Expected EpubParseException")
        } catch (error: EpubParseException) {
            error
        }

        assertEquals(error.message, EpubParseError.RENDERED_CONTENT_TOO_LARGE, error.reason)
    }

    @Test
    fun `bounds retained non-content resource bytes`() {
        val limitedParser = EpubPackageParser(EpubReadLimits(maxResourceBytes = 256))
        val epub = epub(
            "META-INF/container.xml" to container("OPS/package.opf"),
            "OPS/package.opf" to opf(
                manifest = """
                    <item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/>
                    <item id="style" href="large.css" media-type="text/css"/>
                """,
                spine = "<itemref idref=\"chapter\"/>"
            ),
            "OPS/chapter.xhtml" to xhtml("chapter"),
            "OPS/large.css" to "A".repeat(1_000)
        )

        val error = try {
            limitedParser.parse(ByteArrayInputStream(epub))
            throw AssertionError("Expected EpubParseException")
        } catch (error: EpubParseException) {
            error
        }

        assertEquals(error.message, EpubParseError.RESOURCE_BUDGET_EXCEEDED, error.reason)
    }

    @Test
    fun `rejects a tiny encoded image with an excessive canvas`() {
        val limitedParser = EpubPackageParser(EpubReadLimits(maxImageDimension = 8_192))
        val epub = epub(
            "META-INF/container.xml" to container("OPS/package.opf"),
            "OPS/package.opf" to opf(
                manifest = """
                    <item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/>
                    <item id="image" href="huge.png" media-type="image/png"/>
                """,
                spine = "<itemref idref=\"chapter\"/>"
            ),
            "OPS/chapter.xhtml" to xhtml("<img src=\"huge.png\"/>"),
            "OPS/huge.png" to pngHeader(width = 20_000, height = 20_000)
        )

        val error = try {
            limitedParser.parse(ByteArrayInputStream(epub))
            throw AssertionError("Expected EpubParseException")
        } catch (error: EpubParseException) {
            error
        }

        assertEquals(error.message, EpubParseError.IMAGE_DIMENSIONS_TOO_LARGE, error.reason)
    }

    @Test
    fun `rejects an oversized inline data image instead of passing it to WebView`() {
        val encodedImage = Base64.getEncoder().encodeToString(pngHeader(width = 20_000, height = 20_000))
        val epub = validEpub(xhtml("<img src=\"data:image/png;base64,$encodedImage\"/>"))

        val error = expectParseFailure(epub)

        assertEquals(EpubParseError.INLINE_DATA_IMAGE_NOT_ALLOWED, error.reason)
    }

    @Test
    fun `rejects malformed inline data images`() {
        val epub = validEpub(xhtml("<img src=\"data:image/png;base64,%%%not-base64%%%\"/>"))

        val error = expectParseFailure(epub)

        assertEquals(EpubParseError.INLINE_DATA_IMAGE_NOT_ALLOWED, error.reason)
    }

    @Test
    fun `rejects inline data images in CSS URLs`() {
        val epub = validEpub(
            xhtml("<span style=\"background-image:url(DATA:image/png;base64,AAAA)\">cover</span>")
        )

        val error = expectParseFailure(epub)

        assertEquals(EpubParseError.INLINE_DATA_IMAGE_NOT_ALLOWED, error.reason)
    }

    @Test
    fun `rejects entity decoded inline data images in srcset`() {
        val epub = validEpub(
            xhtml("<picture><source srcset=\"d&#x61;ta:image/png;base64,AAAA 1x\"/></picture>")
        )

        val error = expectParseFailure(epub)

        assertEquals(EpubParseError.INLINE_DATA_IMAGE_NOT_ALLOWED, error.reason)
    }

    @Test
    fun `rejects control character obfuscated inline data images in srcset`() {
        val epub = validEpub(
            xhtml("<picture><source srcset=\"d&#10;a&#9;ta:image/png;base64,AAAA 1x\"/></picture>")
        )

        val error = expectParseFailure(epub)

        assertEquals(EpubParseError.INLINE_DATA_IMAGE_NOT_ALLOWED, error.reason)
    }

    @Test
    fun `rejects inline data images in CSS image set strings`() {
        val epub = validEpub(
            xhtml("<span style=\"background-image:image-set('data:image/png;base64,AAAA' 1x)\">cover</span>")
        )

        val error = expectParseFailure(epub)

        assertEquals(EpubParseError.INLINE_DATA_IMAGE_NOT_ALLOWED, error.reason)
    }

    @Test
    fun `rejects XHTML with too many nodes before rendering`() {
        val limitedParser = EpubPackageParser(EpubReadLimits(maxXmlNodes = 32))
        val epub = validEpub(xhtml("<span/>".repeat(40)))

        val error = expectParseFailure(limitedParser, epub)

        assertEquals(EpubParseError.XML_NODE_LIMIT_EXCEEDED, error.reason)
    }

    @Test
    fun `rejects excessively deep XHTML before rendering`() {
        val limitedParser = EpubPackageParser(EpubReadLimits(maxXmlDepth = 8))
        val nested = "<div>".repeat(12) + "deep" + "</div>".repeat(12)
        val epub = validEpub(xhtml(nested))

        val error = expectParseFailure(limitedParser, epub)

        assertEquals(EpubParseError.XML_DEPTH_LIMIT_EXCEEDED, error.reason)
    }

    @Test
    fun `preflight cancellation interrupts a long text scan`() {
        val preflight = EpubXmlPreflight(EpubReadLimits(maxXmlNodes = 10, maxXmlDepth = 10))
        val xml = "<root><body>${"x".repeat(20_000)}</body></root>".toByteArray(Charsets.UTF_8)
        var checks = 0

        val error = try {
            preflight.validate(xml, "chapter.xhtml") {
                checks += 1
                if (checks >= 5) throw CancellationException("preflight cancelled")
            }
            throw AssertionError("Expected CancellationException")
        } catch (error: CancellationException) {
            error
        }

        assertEquals("preflight cancelled", error.message)
        assertEquals(5, checks)
    }

    @Test
    fun `preflight ignores markup shaped content in declarations comments and CDATA`() {
        val preflight = EpubXmlPreflight(EpubReadLimits(maxXmlNodes = 2, maxXmlDepth = 1))
        val xml = """
            <!DOCTYPE root [<!ELEMENT root ANY>]>
            <root><!-- <fake><nested> --><![CDATA[<fake><nested>]]><leaf/></root>
        """.trimIndent().toByteArray(Charsets.UTF_8)

        preflight.validate(xml, "chapter.xhtml")
    }

    @Test
    fun `preflight rejects mismatched closing tags instead of undercounting depth`() {
        val preflight = EpubXmlPreflight(EpubReadLimits(maxXmlNodes = 10, maxXmlDepth = 3))
        val xml = "<root><one><two></wrong><three></also-wrong></root>".toByteArray(Charsets.UTF_8)

        val error = try {
            preflight.validate(xml, "chapter.xhtml")
            throw AssertionError("Expected EpubParseException")
        } catch (error: EpubParseException) {
            error
        }

        assertEquals(EpubParseError.PACKAGE_INVALID, error.reason)
    }

    @Test
    fun `preflight does not treat malformed spaced slash as a self closing tag`() {
        val preflight = EpubXmlPreflight(EpubReadLimits(maxXmlNodes = 2, maxXmlDepth = 2))

        val error = try {
            preflight.validate("<root/ >".toByteArray(Charsets.UTF_8), "chapter.xhtml")
            throw AssertionError("Expected EpubParseException")
        } catch (error: EpubParseException) {
            error
        }

        assertEquals(EpubParseError.PACKAGE_INVALID, error.reason)
    }

    private fun expectParseFailure(bytes: ByteArray): EpubParseException {
        return expectParseFailure(parser, bytes)
    }

    private fun expectParseFailure(parser: EpubPackageParser, bytes: ByteArray): EpubParseException {
        return try {
            parser.parse(ByteArrayInputStream(bytes))
            throw AssertionError("Expected EpubParseException")
        } catch (error: EpubParseException) {
            error
        }
    }

    private fun epub(vararg entries: Pair<String, Any>): ByteArray {
        return ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (name, value) ->
                    zip.putNextEntry(ZipEntry(name))
                    val bytes = when (value) {
                        is ByteArray -> value
                        else -> value.toString().toByteArray(Charsets.UTF_8)
                    }
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            output.toByteArray()
        }
    }

    private fun validEpub(chapter: String): ByteArray {
        return epub(
            "META-INF/container.xml" to container("OPS/package.opf"),
            "OPS/package.opf" to opf(
                manifest = "<item id=\"chapter\" href=\"chapter.xhtml\" media-type=\"application/xhtml+xml\"/>",
                spine = "<itemref idref=\"chapter\"/>"
            ),
            "OPS/chapter.xhtml" to chapter
        )
    }

    private fun pngHeader(width: Int, height: Int): ByteArray {
        return byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0, 0, 0, 13, 0x49, 0x48, 0x44, 0x52,
            (width ushr 24).toByte(), (width ushr 16).toByte(), (width ushr 8).toByte(), width.toByte(),
            (height ushr 24).toByte(), (height ushr 16).toByte(), (height ushr 8).toByte(), height.toByte()
        )
    }

    private fun container(opfPath: String): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles><rootfile full-path="$opfPath" media-type="application/oebps-package+xml"/></rootfiles>
        </container>
    """.trimIndent()

    private fun opf(manifest: String, spine: String): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <package version="3.0" xmlns="http://www.idpf.org/2007/opf">
          <manifest>$manifest</manifest>
          <spine>$spine</spine>
        </package>
    """.trimIndent()

    private fun xhtml(text: String): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <html xmlns="http://www.w3.org/1999/xhtml"><head/><body><p>$text</p></body></html>
    """.trimIndent()
}
