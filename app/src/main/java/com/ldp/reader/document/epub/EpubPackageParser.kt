package com.ldp.reader.document.epub

import org.jsoup.Jsoup
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

data class EpubReadLimits(
    val maxEntries: Int = 4_096,
    val maxEntryBytes: Int = 12 * 1024 * 1024,
    val maxTotalBytes: Int = 24 * 1024 * 1024,
    val maxPathBytes: Int = 1_024,
    val maxXmlNodes: Int = 50_000,
    val maxXmlDepth: Int = 128,
    val maxRenderedHtmlChars: Int = 8 * 1024 * 1024,
    val maxResourceBytes: Int = 16 * 1024 * 1024,
    val maxImageDimension: Int = 8_192,
    val maxImagePixels: Long = 16_000_000L
) {
    init {
        require(maxEntries > 0)
        require(maxEntryBytes > 0)
        require(maxTotalBytes > 0)
        require(maxPathBytes > 0)
        require(maxXmlNodes > 0)
        require(maxXmlDepth > 0)
        require(maxRenderedHtmlChars > 0)
        require(maxResourceBytes > 0)
        require(maxImageDimension > 0)
        require(maxImagePixels > 0)
    }
}

enum class EpubParseError {
    INVALID_ARCHIVE,
    CONTAINER_MISSING,
    PACKAGE_MISSING,
    PACKAGE_INVALID,
    SPINE_EMPTY,
    UNSAFE_PATH,
    DUPLICATE_ENTRY,
    TOO_MANY_ENTRIES,
    ENTRY_TOO_LARGE,
    ARCHIVE_TOO_LARGE,
    PATH_TOO_LONG,
    RENDERED_CONTENT_TOO_LARGE,
    RESOURCE_BUDGET_EXCEEDED,
    IMAGE_DIMENSIONS_TOO_LARGE,
    INLINE_DATA_IMAGE_NOT_ALLOWED,
    XML_NODE_LIMIT_EXCEEDED,
    XML_DEPTH_LIMIT_EXCEEDED
}

class EpubParseException(
    val reason: EpubParseError,
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)

data class EpubResource(
    val bytes: ByteArray,
    val mediaType: String
)

data class EpubCatalogEntry(
    val title: String,
    val sectionIndex: Int,
    val targetId: String? = null
)

data class EpubDocument(
    val headHtml: String,
    val bodyHtml: String,
    val spinePaths: List<String>,
    val catalogEntries: List<EpubCatalogEntry>,
    val resources: Map<String, EpubResource>
)

/** Reads the EPUB package contract without extracting archive paths to disk. */
class EpubPackageParser(
    private val limits: EpubReadLimits = EpubReadLimits()
) {
    fun parse(input: InputStream, checkCancelled: () -> Unit = {}): EpubDocument {
        checkCancelled()
        val entries = readArchive(input, checkCancelled)
        checkCancelled()
        val containerBytes = entries[CONTAINER_PATH]
            ?: throw failure(EpubParseError.CONTAINER_MISSING, "EPUB 缺少 META-INF/container.xml")
        val container = parseXml(containerBytes, CONTAINER_PATH, checkCancelled)
        requireRoot(container, "container", CONTAINER_PATH)
        val rootFile = container.elementsNamed("rootfile").firstOrNull {
            it.attr("media-type") == OPF_MEDIA_TYPE
        } ?: throw failure(EpubParseError.PACKAGE_INVALID, "EPUB container.xml 缺少 OPF rootfile")
        val packagePath = resolvePath("", rootFile.attr("full-path"))
        val packageBytes = entries[packagePath]
            ?: throw failure(EpubParseError.PACKAGE_MISSING, "EPUB 找不到 OPF 文件: $packagePath")
        val packageDocument = parseXml(packageBytes, packagePath, checkCancelled)
        requireRoot(packageDocument, "package", packagePath)
        val packageDirectory = packagePath.substringBeforeLast('/', "")

        val manifestElement = packageDocument.elementsNamed("manifest").firstOrNull()
            ?: throw failure(EpubParseError.PACKAGE_INVALID, "EPUB OPF 缺少 manifest")
        val manifest = LinkedHashMap<String, ManifestItem>()
        manifestElement.elementsNamed("item").forEach { item ->
            checkCancelled()
            val id = item.attr("id")
            val href = item.attr("href")
            val mediaType = item.attr("media-type")
            if (id.isBlank() || href.isBlank() || mediaType.isBlank() || manifest.containsKey(id)) {
                throw failure(EpubParseError.PACKAGE_INVALID, "EPUB manifest 条目无效")
            }
            manifest[id] = ManifestItem(
                path = resolvePath(packageDirectory, href),
                mediaType = mediaType,
                properties = item.attr("properties")
                    .split(Regex("\\s+"))
                    .filterTo(LinkedHashSet()) { it.isNotBlank() }
            )
        }

        val spineElement = packageDocument.elementsNamed("spine").firstOrNull()
            ?: throw failure(EpubParseError.SPINE_EMPTY, "EPUB OPF 缺少 spine")
        val spineItems = spineElement.elementsNamed("itemref").map { itemRef ->
            checkCancelled()
            val idRef = itemRef.attr("idref")
            manifest[idRef]
                ?: throw failure(EpubParseError.PACKAGE_INVALID, "EPUB spine 引用了未知 manifest id: $idRef")
        }.filter { it.mediaType in CONTENT_MEDIA_TYPES }

        if (spineItems.isEmpty()) {
            throw failure(EpubParseError.SPINE_EMPTY, "EPUB spine 没有可阅读正文")
        }

        val headParts = ArrayList<String>(spineItems.size)
        val bodyParts = ArrayList<String>(spineItems.size)
        val spinePaths = ArrayList<String>(spineItems.size)
        var renderedHtmlChars = 0L
        val spineSectionIds = spineItems.mapIndexed { index, item ->
            virtualUrl(item.path) to "epub-section-${index + 1}"
        }.toMap()
        spineItems.forEachIndexed { index, item ->
            checkCancelled()
            val chapterBytes = entries[item.path]
                ?: throw failure(EpubParseError.PACKAGE_MISSING, "EPUB 找不到正文文件: ${item.path}")
            val chapter = parseXml(chapterBytes, item.path, checkCancelled)
            requireRoot(chapter, "html", item.path)
            sanitizeAndResolve(chapter, item.path, checkCancelled)
            installSpineFragmentTargets(chapter, index)
            rewriteSpineNavigation(
                document = chapter,
                chapterPath = item.path,
                sectionIndex = index,
                spineSectionIds = spineSectionIds
            )
            chapter.head()?.children()
                ?.filter { element ->
                    element.normalName() == "style" ||
                        element.normalName() == "link" &&
                        element.attr("rel").split(Regex("\\s+")).any {
                            it.equals("stylesheet", ignoreCase = true)
                        }
                }
                ?.forEach { element ->
                    checkCancelled()
                    val html = element.outerHtml()
                    renderedHtmlChars = reserveRenderedHtml(renderedHtmlChars, html.length)
                    headParts.add(html)
                }
            val body = chapter.body()
                ?: throw failure(EpubParseError.PACKAGE_INVALID, "EPUB 正文缺少 body: ${item.path}")
            val bodyPart =
                "<section id=\"epub-section-${index + 1}\" class=\"epub-section\" " +
                    "data-epub-path=\"${escapeAttribute(item.path)}\">" +
                    body.html() +
                    "</section>"
            renderedHtmlChars = reserveRenderedHtml(renderedHtmlChars, bodyPart.length)
            bodyParts.add(bodyPart)
            spinePaths.add(item.path)
        }

        val resources = LinkedHashMap<String, EpubResource>()
        var resourceBytes = 0L
        manifest.values.asSequence()
            .filterNot { it.mediaType in CONTENT_MEDIA_TYPES }
            .forEach { item ->
                checkCancelled()
                val bytes = entries[item.path]
                    ?: throw failure(EpubParseError.PACKAGE_MISSING, "EPUB 找不到 manifest 资源: ${item.path}")
                resourceBytes += bytes.size.toLong()
                if (resourceBytes > limits.maxResourceBytes) {
                    throw failure(EpubParseError.RESOURCE_BUDGET_EXCEEDED, "EPUB 图片、字体和样式资源超过安全上限")
                }
                validateRasterDimensions(item, bytes)
                resources[item.path] = EpubResource(bytes = bytes, mediaType = item.mediaType)
            }
        checkCancelled()
        val catalogEntries = epubCatalogEntries(
            entries = entries,
            manifest = manifest,
            spineElement = spineElement,
            spineItems = spineItems,
            checkCancelled = checkCancelled
        )
        return EpubDocument(
            headHtml = headParts.joinToString("\n"),
            bodyHtml = bodyParts.joinToString("\n<hr />\n"),
            spinePaths = spinePaths,
            catalogEntries = catalogEntries,
            resources = resources
        )
    }

    private fun epubCatalogEntries(
        entries: Map<String, ByteArray>,
        manifest: Map<String, ManifestItem>,
        spineElement: Element,
        spineItems: List<ManifestItem>,
        checkCancelled: () -> Unit
    ): List<EpubCatalogEntry> {
        val spineIndexByPath = spineItems.mapIndexed { index, item -> item.path to index }.toMap()
        val epub3Navigation = manifest.values.firstOrNull { item ->
            NAV_PROPERTY in item.properties && item.mediaType in CONTENT_MEDIA_TYPES
        }
        if (epub3Navigation != null) {
            val parsed = parseEpub3Catalog(
                entries = entries,
                navigation = epub3Navigation,
                spineIndexByPath = spineIndexByPath,
                checkCancelled = checkCancelled
            )
            if (parsed.isNotEmpty()) return parsed
        }

        val ncxNavigation = manifest[spineElement.attr("toc")]
            ?: manifest.values.firstOrNull { it.mediaType == NCX_MEDIA_TYPE }
        if (ncxNavigation != null) {
            val parsed = parseNcxCatalog(
                entries = entries,
                navigation = ncxNavigation,
                spineIndexByPath = spineIndexByPath,
                checkCancelled = checkCancelled
            )
            if (parsed.isNotEmpty()) return parsed
        }

        return spineItems.mapIndexed { index, item ->
            EpubCatalogEntry(
                title = fallbackCatalogTitle(index, item.path),
                sectionIndex = index
            )
        }
    }

    private fun parseNcxCatalog(
        entries: Map<String, ByteArray>,
        navigation: ManifestItem,
        spineIndexByPath: Map<String, Int>,
        checkCancelled: () -> Unit
    ): List<EpubCatalogEntry> {
        val bytes = entries[navigation.path] ?: return emptyList()
        val navigationDocument = parseXml(bytes, navigation.path, checkCancelled)
        return navigationDocument.elementsNamed("navPoint").mapNotNull { navPoint ->
            checkCancelled()
            val title = navPoint.elementsNamed("navLabel")
                .firstOrNull()
                ?.elementsNamed("text")
                ?.firstOrNull()
                ?.text()
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                .orEmpty()
            val reference = navPoint.elementsNamed("content")
                .firstOrNull()
                ?.attr("src")
                .orEmpty()
            if (title.isBlank()) return@mapNotNull null
            catalogTarget(
                sourcePath = navigation.path,
                reference = reference,
                title = title,
                spineIndexByPath = spineIndexByPath
            )
        }
    }

    private fun parseEpub3Catalog(
        entries: Map<String, ByteArray>,
        navigation: ManifestItem,
        spineIndexByPath: Map<String, Int>,
        checkCancelled: () -> Unit
    ): List<EpubCatalogEntry> {
        val bytes = entries[navigation.path] ?: return emptyList()
        val navigationDocument = parseXml(bytes, navigation.path, checkCancelled)
        val toc = navigationDocument.elementsNamed("nav").firstOrNull { nav ->
            nav.attr("epub:type").split(Regex("\\s+")).any { it.equals("toc", true) } ||
                nav.attr("role").split(Regex("\\s+")).any { it.equals("doc-toc", true) }
        } ?: return emptyList()

        return toc.select("a[href]").mapNotNull { link ->
            checkCancelled()
            val title = link.text().replace(Regex("\\s+"), " ").trim()
            if (title.isBlank()) return@mapNotNull null
            catalogTarget(
                sourcePath = navigation.path,
                reference = link.attr("href"),
                title = title,
                spineIndexByPath = spineIndexByPath
            )
        }
    }

    private fun catalogTarget(
        sourcePath: String,
        reference: String,
        title: String,
        spineIndexByPath: Map<String, Int>
    ): EpubCatalogEntry? {
        if (reference.isBlank() || reference.contains(':')) return null
        val path = resolvePath(sourcePath.substringBeforeLast('/', ""), reference)
        val sectionIndex = spineIndexByPath[path] ?: return null
        return EpubCatalogEntry(
            title = title,
            sectionIndex = sectionIndex,
            targetId = decodedFragment(reference)
                ?.let { spineFragmentTargetId(sectionIndex, it) }
        )
    }

    private fun fallbackCatalogTitle(index: Int, path: String): String {
        val leaf = path.substringAfterLast('/').substringBeforeLast('.', path.substringAfterLast('/'))
        val readable = leaf
            .replace('_', ' ')
            .replace('-', ' ')
            .trim()
            .takeIf { it.isNotBlank() }
            ?: return "第 ${index + 1} 节"
        return "${index + 1}. $readable"
    }

    private fun readArchive(input: InputStream, checkCancelled: () -> Unit): Map<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        var entryCount = 0
        var totalBytes = 0
        try {
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    checkCancelled()
                    val entry = zip.nextEntry ?: break
                    entryCount += 1
                    if (entryCount > limits.maxEntries) {
                        throw failure(EpubParseError.TOO_MANY_ENTRIES, "EPUB 压缩包条目过多")
                    }
                    val path = normalizePath(entry.name)
                    if (!entry.isDirectory) {
                        if (entries.containsKey(path)) {
                            throw failure(EpubParseError.DUPLICATE_ENTRY, "EPUB 含重复路径: $path")
                        }
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var entryBytes = 0
                        while (true) {
                            checkCancelled()
                            val count = zip.read(buffer)
                            if (count < 0) break
                            entryBytes += count
                            if (entryBytes > limits.maxEntryBytes) {
                                throw failure(EpubParseError.ENTRY_TOO_LARGE, "EPUB 条目过大: $path")
                            }
                            totalBytes += count
                            if (totalBytes > limits.maxTotalBytes) {
                                throw failure(EpubParseError.ARCHIVE_TOO_LARGE, "EPUB 解压内容超过安全上限")
                            }
                            output.write(buffer, 0, count)
                        }
                        entries[path] = output.toByteArray()
                    }
                    zip.closeEntry()
                }
            }
        } catch (error: EpubParseException) {
            throw error
        } catch (error: ZipException) {
            throw failure(EpubParseError.INVALID_ARCHIVE, "EPUB 压缩包损坏", error)
        }
        return entries
    }

    private fun parseXml(bytes: ByteArray, path: String, checkCancelled: () -> Unit): Document {
        EpubXmlPreflight(limits).validate(bytes, path, checkCancelled)
        checkCancelled()
        val document = try {
            Jsoup.parse(ByteArrayInputStream(bytes), null, virtualUrl(path), Parser.xmlParser())
        } catch (error: RuntimeException) {
            throw failure(EpubParseError.PACKAGE_INVALID, "EPUB XML 无法解析: $path", error)
        }
        checkCancelled()
        return document
    }

    private fun requireRoot(document: Document, localName: String, path: String) {
        val root = document.children().firstOrNull()
        if (root?.tagName()?.substringAfter(':')?.equals(localName, ignoreCase = true) != true) {
            throw failure(EpubParseError.PACKAGE_INVALID, "EPUB XML 根节点无效: $path")
        }
    }

    private fun sanitizeAndResolve(
        document: Document,
        chapterPath: String,
        checkCancelled: () -> Unit
    ) {
        document.select("script, iframe, object, embed, base, meta[http-equiv=refresh]").remove()
        document.allElements.forEach { element ->
            checkCancelled()
            element.attributes().asList()
                .filter { it.key.startsWith("on", ignoreCase = true) }
                .forEach { element.removeAttr(it.key) }
            rewriteReference(element, "src", chapterPath)
            rewriteReference(element, "href", chapterPath)
            rewriteReference(element, "poster", chapterPath)
            rewriteReference(element, "xlink:href", chapterPath)
            if (element.hasAttr("srcset") && containsInlineDataUrl(element.attr("srcset"))) {
                throw inlineDataImageFailure(chapterPath)
            }
            if (element.hasAttr("style")) {
                element.attr("style", rewriteCssUrls(element.attr("style"), chapterPath))
            }
            if (element.normalName() == "style") {
                val css = element.data().ifEmpty { element.text() }
                element.empty().appendChild(DataNode(rewriteCssUrls(css, chapterPath)))
            }
        }
    }

    private fun rewriteCssUrls(css: String, chapterPath: String): String {
        if (containsInlineDataUrl(css)) throw inlineDataImageFailure(chapterPath)
        return CSS_URL.replace(css) { match ->
            val reference = match.groupValues[2].trim()
            if (reference.isEmpty() || reference.startsWith('#')) {
                match.value
            } else if (containsInlineDataUrl(reference)) {
                throw inlineDataImageFailure(chapterPath)
            } else if (reference.contains(':')) {
                "url('')"
            } else {
                val path = resolvePath(chapterPath.substringBeforeLast('/', ""), reference)
                "url('${virtualUrl(path)}')"
            }
        }
    }

    private fun installSpineFragmentTargets(document: Document, sectionIndex: Int) {
        val installedFragments = HashSet<String>()
        document.select("body [id]").toList().forEach { target ->
            val fragment = target.id()
            if (fragment.isBlank() || !installedFragments.add(fragment)) return@forEach
            target.before(
                "<span id=\"${spineFragmentTargetId(sectionIndex, fragment)}\" " +
                    "class=\"epub-anchor-proxy\" aria-hidden=\"true\"></span>"
            )
        }
    }

    private fun rewriteSpineNavigation(
        document: Document,
        chapterPath: String,
        sectionIndex: Int,
        spineSectionIds: Map<String, String>
    ) {
        val currentSectionId = "epub-section-${sectionIndex + 1}"
        val currentChapterUrl = virtualUrl(chapterPath)
        document.select("a[href]").forEach { link ->
            val href = link.attr("href")
            val targetUrl = href.substringBefore('#')
            val sectionId = if (targetUrl.isBlank()) {
                currentSectionId
            } else {
                spineSectionIds[targetUrl] ?: return@forEach
            }
            val fragment = decodedFragment(href)
            if (fragment == null) {
                link.attr("href", "#$sectionId")
                return@forEach
            }
            val targetSectionIndex = if (targetUrl.isBlank() || targetUrl == currentChapterUrl) {
                sectionIndex
            } else {
                sectionId.removePrefix("epub-section-").toIntOrNull()?.minus(1)
                    ?: return@forEach
            }
            link.attr("href", "#${spineFragmentTargetId(targetSectionIndex, fragment)}")
        }
    }

    private fun decodedFragment(reference: String): String? {
        val encoded = reference.substringAfter('#', "").takeIf { it.isNotBlank() } ?: return null
        return try {
            URLDecoder.decode(encoded.replace("+", "%2B"), Charsets.UTF_8.name())
        } catch (_: IllegalArgumentException) {
            encoded
        }
    }

    private fun spineFragmentTargetId(sectionIndex: Int, fragment: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(fragment.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return "epub-target-${sectionIndex + 1}-$digest"
    }

    private fun rewriteReference(element: Element, attribute: String, chapterPath: String) {
        if (!element.hasAttr(attribute)) return
        val value = element.attr(attribute).trim()
        if (value.isEmpty() || value.startsWith('#')) return
        if (containsInlineDataUrl(value)) {
            throw inlineDataImageFailure(chapterPath)
        }
        if (value.contains(':')) {
            element.removeAttr(attribute)
            return
        }
        val fragment = value.substringAfter('#', "")
        val path = resolvePath(chapterPath.substringBeforeLast('/', ""), value)
        element.attr(attribute, virtualUrl(path) + if (fragment.isBlank()) "" else "#$fragment")
    }

    private fun resolvePath(directory: String, reference: String): String {
        val rawPath = reference.substringBefore('#').substringBefore('?')
        if (rawPath.isBlank() || rawPath.startsWith('/')) {
            throw failure(EpubParseError.UNSAFE_PATH, "EPUB 路径无效: $reference")
        }
        val decoded = try {
            URLDecoder.decode(rawPath.replace("+", "%2B"), Charsets.UTF_8.name())
        } catch (error: IllegalArgumentException) {
            throw failure(EpubParseError.PACKAGE_INVALID, "EPUB 路径编码无效: $reference", error)
        }
        val combined = if (directory.isBlank()) decoded else "$directory/$decoded"
        return normalizePath(combined)
    }

    private fun normalizePath(path: String): String {
        if (path.isBlank() || path.startsWith('/') || '\u0000' in path || '\\' in path) {
            throw failure(EpubParseError.UNSAFE_PATH, "EPUB 含不安全路径: $path")
        }
        if (path.toByteArray(Charsets.UTF_8).size > limits.maxPathBytes) {
            throw failure(EpubParseError.PATH_TOO_LONG, "EPUB 路径名称过长")
        }
        val segments = ArrayDeque<String>()
        path.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> {
                    if (segments.isEmpty()) {
                        throw failure(EpubParseError.UNSAFE_PATH, "EPUB 路径越界: $path")
                    }
                    segments.removeLast()
                }
                else -> segments.addLast(segment)
            }
        }
        if (segments.isEmpty()) {
            throw failure(EpubParseError.UNSAFE_PATH, "EPUB 路径为空")
        }
        val normalized = segments.joinToString("/")
        if (normalized.toByteArray(Charsets.UTF_8).size > limits.maxPathBytes) {
            throw failure(EpubParseError.PATH_TOO_LONG, "EPUB 路径名称过长")
        }
        return normalized
    }

    private fun reserveRenderedHtml(current: Long, addedChars: Int): Long {
        val updated = current + addedChars.toLong()
        if (updated > limits.maxRenderedHtmlChars) {
            throw failure(EpubParseError.RENDERED_CONTENT_TOO_LARGE, "EPUB 正文渲染内容超过安全上限")
        }
        return updated
    }

    private fun validateRasterDimensions(item: ManifestItem, bytes: ByteArray) {
        if (!item.mediaType.startsWith("image/")) return
        val dimensions = encodedRasterDimensions(bytes) ?: return
        val pixels = dimensions.width.toLong() * dimensions.height.toLong()
        if (
            dimensions.width <= 0 ||
            dimensions.height <= 0 ||
            dimensions.width > limits.maxImageDimension ||
            dimensions.height > limits.maxImageDimension ||
            pixels > limits.maxImagePixels
        ) {
            throw failure(
                EpubParseError.IMAGE_DIMENSIONS_TOO_LARGE,
                "EPUB 图片尺寸超过安全上限: ${item.path}"
            )
        }
    }

    private fun encodedRasterDimensions(bytes: ByteArray): RasterDimensions? {
        return pngDimensions(bytes)
            ?: gifDimensions(bytes)
            ?: webpDimensions(bytes)
            ?: jpegDimensions(bytes)
    }

    private fun pngDimensions(bytes: ByteArray): RasterDimensions? {
        if (bytes.size < 24 || !bytes.copyOfRange(0, PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)) {
            return null
        }
        return RasterDimensions(readIntBigEndian(bytes, 16), readIntBigEndian(bytes, 20))
    }

    private fun gifDimensions(bytes: ByteArray): RasterDimensions? {
        if (bytes.size < 10) return null
        val signature = bytes.copyOfRange(0, 6).toString(Charsets.US_ASCII)
        if (signature != "GIF87a" && signature != "GIF89a") return null
        return RasterDimensions(readUnsignedShortLittleEndian(bytes, 6), readUnsignedShortLittleEndian(bytes, 8))
    }

    private fun webpDimensions(bytes: ByteArray): RasterDimensions? {
        if (
            bytes.size < 30 ||
            bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) != "RIFF" ||
            bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) != "WEBP"
        ) {
            return null
        }
        return when (bytes.copyOfRange(12, 16).toString(Charsets.US_ASCII)) {
            "VP8X" -> RasterDimensions(
                1 + readUnsigned24LittleEndian(bytes, 24),
                1 + readUnsigned24LittleEndian(bytes, 27)
            )
            "VP8L" -> {
                if (bytes[20].toInt() and 0xFF != 0x2F) return null
                val b1 = bytes[21].toInt() and 0xFF
                val b2 = bytes[22].toInt() and 0xFF
                val b3 = bytes[23].toInt() and 0xFF
                val b4 = bytes[24].toInt() and 0xFF
                RasterDimensions(
                    1 + b1 + ((b2 and 0x3F) shl 8),
                    1 + ((b2 and 0xC0) shr 6) + (b3 shl 2) + ((b4 and 0x0F) shl 10)
                )
            }
            "VP8 " -> {
                if (
                    bytes[23].toInt() and 0xFF != 0x9D ||
                    bytes[24].toInt() and 0xFF != 0x01 ||
                    bytes[25].toInt() and 0xFF != 0x2A
                ) {
                    return null
                }
                RasterDimensions(
                    readUnsignedShortLittleEndian(bytes, 26) and 0x3FFF,
                    readUnsignedShortLittleEndian(bytes, 28) and 0x3FFF
                )
            }
            else -> null
        }
    }

    private fun jpegDimensions(bytes: ByteArray): RasterDimensions? {
        if (bytes.size < 4 || bytes[0].toInt() and 0xFF != 0xFF || bytes[1].toInt() and 0xFF != 0xD8) {
            return null
        }
        var offset = 2
        while (offset + 3 < bytes.size) {
            while (offset < bytes.size && bytes[offset].toInt() and 0xFF == 0xFF) offset += 1
            if (offset >= bytes.size) return null
            val marker = bytes[offset].toInt() and 0xFF
            offset += 1
            if (marker == 0xD9 || marker == 0xDA) return null
            if (marker == 0x01 || marker in 0xD0..0xD7) continue
            if (offset + 1 >= bytes.size) return null
            val segmentLength = readUnsignedShortBigEndian(bytes, offset)
            if (segmentLength < 2 || offset + segmentLength > bytes.size) return null
            if (marker in JPEG_START_OF_FRAME_MARKERS && segmentLength >= 7) {
                return RasterDimensions(
                    readUnsignedShortBigEndian(bytes, offset + 5),
                    readUnsignedShortBigEndian(bytes, offset + 3)
                )
            }
            offset += segmentLength
        }
        return null
    }

    private fun readIntBigEndian(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    private fun readUnsignedShortBigEndian(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
    }

    private fun readUnsignedShortLittleEndian(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readUnsigned24LittleEndian(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16)
    }

    private fun virtualUrl(path: String): String {
        val encoded = path.split('/').joinToString("/") { segment ->
            URLEncoder.encode(segment, Charsets.UTF_8.name()).replace("+", "%20")
        }
        return BASE_URL + encoded
    }

    private fun Document.elementsNamed(localName: String): List<Element> {
        return allElements.filter { element ->
            element.tagName().substringAfter(':').equals(localName, ignoreCase = true)
        }
    }

    private fun Element.elementsNamed(localName: String): List<Element> {
        return allElements.filter { element ->
            element.tagName().substringAfter(':').equals(localName, ignoreCase = true)
        }
    }

    private fun escapeAttribute(value: String): String {
        return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
    }

    private fun containsInlineDataUrl(value: String): Boolean {
        return INLINE_DATA_URL.containsMatchIn(value.trim())
    }

    private fun inlineDataImageFailure(path: String): EpubParseException {
        return failure(
            EpubParseError.INLINE_DATA_IMAGE_NOT_ALLOWED,
            "EPUB 不支持内联 data 图片: $path"
        )
    }

    private fun failure(reason: EpubParseError, message: String, cause: Throwable? = null): EpubParseException {
        return EpubParseException(reason, message, cause)
    }

    private data class ManifestItem(
        val path: String,
        val mediaType: String,
        val properties: Set<String> = emptySet()
    )

    private data class RasterDimensions(
        val width: Int,
        val height: Int
    )

    companion object {
        const val BASE_URL = "https://epub.local/"

        private const val CONTAINER_PATH = "META-INF/container.xml"
        private const val OPF_MEDIA_TYPE = "application/oebps-package+xml"
        private const val NCX_MEDIA_TYPE = "application/x-dtbncx+xml"
        private const val NAV_PROPERTY = "nav"
        private val CONTENT_MEDIA_TYPES = setOf("application/xhtml+xml", "text/html")
        private val INLINE_DATA_URL = Regex(
            """(?:^|[,\s('"])d[\t\r\n]*a[\t\r\n]*t[\t\r\n]*a[\t\r\n]*:""",
            RegexOption.IGNORE_CASE
        )
        private val CSS_URL = Regex("""url\(\s*(['\"]?)([^'\")]+)\1\s*\)""", RegexOption.IGNORE_CASE)
        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
        private val JPEG_START_OF_FRAME_MARKERS = setOf(
            0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7,
            0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF
        )
    }
}
