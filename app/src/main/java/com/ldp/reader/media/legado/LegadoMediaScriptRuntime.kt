package com.ldp.reader.media.legado

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.ldp.reader.media.legado.MediaHttpFetcher
import com.ldp.reader.media.legado.MediaHttpRequest
import com.ldp.reader.media.legado.MediaLegadoRuleEvaluator
import com.ldp.reader.media.MediaSourceDefinition
import com.ldp.reader.media.MediaSourceChapter
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context as RhinoContext
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeJavaArray
import org.mozilla.javascript.NativeJavaObject
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal class LegadoMediaScriptRuntime(
    private val fetcher: MediaHttpFetcher,
    private val evaluator: MediaLegadoRuleEvaluator = MediaLegadoRuleEvaluator()
) {
    fun evaluate(
        script: String,
        result: Any?,
        baseUrl: String,
        source: MediaSourceDefinition,
        chapter: MediaSourceChapter?,
        variables: MutableMap<String, String>
    ): String {
        return execute(script, result, baseUrl, source, chapter, variables).value
    }

    fun evaluateSearchRequest(
        script: String,
        baseUrl: String,
        source: MediaSourceDefinition,
        keyword: String,
        page: Int,
        variables: MutableMap<String, String>
    ): LegadoMediaScriptOutput {
        return execute(
            script = script,
            result = null,
            baseUrl = baseUrl,
            source = source,
            chapter = null,
            variables = variables,
            keyword = keyword,
            page = page
        )
    }

    private fun execute(
        script: String,
        result: Any?,
        baseUrl: String,
        source: MediaSourceDefinition,
        chapter: MediaSourceChapter?,
        variables: MutableMap<String, String>,
        keyword: String? = null,
        page: Int? = null
    ): LegadoMediaScriptOutput {
        val context = RhinoContext.enter()
        return try {
            context.optimizationLevel = -1
            context.languageVersion = RhinoContext.VERSION_ES6
            val scope = context.initStandardObjects()
            val bridge = LegadoMediaJsBridge(
                fetcher = fetcher,
                evaluator = evaluator,
                baseUrl = baseUrl,
                headers = source.headers,
                variables = variables,
                content = result.toScriptContent()
            )
            val jsBridge = RhinoContext.javaToJS(bridge, scope)
            ScriptableObject.putProperty(scope, "java", jsBridge)
            ScriptableObject.putProperty(scope, READER_JAVA_BRIDGE_ALIAS, jsBridge)
            ScriptableObject.putProperty(scope, "cache", RhinoContext.javaToJS(MediaScriptCache(), scope))
            ScriptableObject.putProperty(scope, "cookie", RhinoContext.javaToJS(MediaScriptCookie(), scope))
            ScriptableObject.putProperty(scope, "source", RhinoContext.javaToJS(MediaScriptSource(source, variables), scope))
            ScriptableObject.putProperty(scope, "book", RhinoContext.javaToJS(MediaScriptBook(chapter), scope))
            ScriptableObject.putProperty(scope, "chapter", RhinoContext.javaToJS(MediaScriptChapter(chapter), scope))
            val scriptResult = result.toScriptValue(context, scope)
            ScriptableObject.putProperty(scope, "result", scriptResult)
            ScriptableObject.putProperty(scope, "src", scriptResult)
            ScriptableObject.putProperty(scope, "baseUrl", baseUrl)
            ScriptableObject.putProperty(scope, "chapterUrl", chapter?.chapterUrl.orEmpty())
            ScriptableObject.putProperty(scope, "bookUrl", chapter?.book?.bookUrl.orEmpty())
            ScriptableObject.putProperty(scope, "title", chapter?.name.orEmpty())
            keyword?.let { ScriptableObject.putProperty(scope, "key", it) }
            page?.let { ScriptableObject.putProperty(scope, "page", it) }
            installLegadoGlobals(context, scope)
            loadJsLib(source.jsLib).forEachIndexed { index, js ->
                context.evaluateString(scope, protectLegadoJavaBridgeReferences(js), "media-legado-jslib-$index", 1, null)
            }
            val output = context.evaluateString(
                scope,
                protectLegadoJavaBridgeReferences(script),
                "media-legado-rule",
                1,
                null
            )
            LegadoMediaScriptOutput(
                value = output.toRuleString(),
                url = scope.propertyString("url"),
                body = scope.propertyString("body"),
                method = scope.propertyString("method"),
                headers = scope.propertyStringMap("headers")
            )
        } finally {
            RhinoContext.exit()
        }
    }

    private fun Any?.toScriptContent(): String {
        return when (this) {
            null, Undefined.instance -> ""
            is MediaScriptRuleNodeList -> toString()
            is MediaLegadoRuleEvaluator.RuleNode -> toRuleText()
            is Element -> outerHtml()
            is JsonElement -> toRuleText()
            else -> toString()
        }
    }

    private fun Any?.toScriptValue(context: RhinoContext, scope: Scriptable): Any {
        return when (this) {
            null, Undefined.instance -> ""
            is MediaScriptRuleNodeList -> {
                val values = nodes.map { node -> node.toScriptValue(context, scope) }.toTypedArray()
                context.newArray(scope, values)
            }
            is MediaLegadoRuleEvaluator.RuleNode -> toScriptValue(context, scope)
            else -> RhinoContext.javaToJS(this, scope)
        }
    }

    private fun MediaLegadoRuleEvaluator.RuleNode.toScriptValue(
        context: RhinoContext,
        scope: Scriptable
    ): Any {
        json?.let { return it.toScriptValue(context, scope) }
        element?.let { return RhinoContext.javaToJS(it, scope) }
        return ""
    }

    private fun JsonElement.toScriptValue(context: RhinoContext, scope: Scriptable): Any {
        return if (isJsonPrimitive) {
            val primitive = asJsonPrimitive
            when {
                primitive.isBoolean -> primitive.asBoolean
                primitive.isNumber -> primitive.asNumber
                else -> primitive.asString
            }
        } else {
            context.evaluateString(scope, toString(), "media-legado-json-result", 1, null)
        }
    }

    private fun installLegadoGlobals(context: RhinoContext, scope: Scriptable) {
        context.evaluateString(
            scope,
            """
                function Reload(url) { return java.ajax(url); }
                function Get(key) { return java.get(key); }
                function Put(key, value) { return java.put(key, value); }
                var SystemPropsUtil = {
                    getProps: function() {
                        return 'Android 13 reader_media_legado Pixel 7 1080 2400 arm64-v8a';
                    }
                };
                function __reader_to_array(value) {
                    if (value == null) return [];
                    var length = Number(value.length) || 0;
                    var out = [];
                    for (var i = 0; i < length; i++) {
                        var byteValue = Number(value[i]);
                        if (byteValue < 0) byteValue += 256;
                        out.push(byteValue & 255);
                    }
                    return out;
                }
                var ZipUtil = {
                    gzip: function(value, charsetName) { return __reader_to_array(${READER_JAVA_BRIDGE_ALIAS}.gzip(value, charsetName)); },
                    unGZip: function(value, charsetName) { return __reader_to_array(${READER_JAVA_BRIDGE_ALIAS}.ungzip(value, charsetName)); },
                    unGzip: function(value, charsetName) { return __reader_to_array(${READER_JAVA_BRIDGE_ALIAS}.ungzip(value, charsetName)); }
                };
                var Base64 = {
                    encode: function(value) { return ${READER_JAVA_BRIDGE_ALIAS}.base64EncodeBytes(value); },
                    decode: function(value) { return __reader_to_array(${READER_JAVA_BRIDGE_ALIAS}.base64DecodeToByteArray(value)); }
                };
                var DigestUtil = {
                    sha512: function(value) { return __reader_to_array(${READER_JAVA_BRIDGE_ALIAS}.sha512(value)); },
                    md5Hex: function(value) { return ${READER_JAVA_BRIDGE_ALIAS}.md5Encode(value); }
                };
                var RandomUtil = {
                    randomBytes: function(size) { return __reader_to_array(${READER_JAVA_BRIDGE_ALIAS}.randomBytes(size)); }
                };
                var StrUtil = {
                    reverse: function(value) { return String(value).split('').reverse().join(''); }
                };
                var __reader_native_eval = eval;
                eval = function(script) {
                    return __reader_native_eval(String(script).replace(
                        /(^|[^A-Za-z0-9_$\.])java\./g,
                        '$1${READER_JAVA_BRIDGE_ALIAS}.'
                    ));
                };
                var cache_api = typeof cache_api === 'undefined' ? '' : cache_api;
            """.trimIndent(),
            "media-legado-globals",
            1,
            null
        )
    }

    private fun loadJsLib(jsLib: String): List<String> {
        if (jsLib.isBlank()) return emptyList()
        return jsLibCache.computeIfAbsent(jsLib) {
            val parsed = runCatching { JsonParser.parseString(jsLib) }.getOrNull()
            if (parsed != null && parsed.isJsonObject) {
                parsed.asJsonObject.entrySet()
                    .mapNotNull { (_, value) -> value.takeIf { it.isJsonPrimitive }?.asString }
                    .filter { it.startsWith("http://") || it.startsWith("https://") }
                    .mapNotNull { url ->
                        runCatching { fetcher.fetch(MediaHttpRequest(url)).body }.getOrNull()
                    }
            } else {
                listOf(jsLib)
            }
        }
    }

    private fun ScriptableObject.propertyString(name: String): String {
        val value = ScriptableObject.getProperty(this, name)
        return if (value == Scriptable.NOT_FOUND || value == Undefined.instance) "" else RhinoContext.toString(value)
    }

    private fun ScriptableObject.propertyStringMap(name: String): Map<String, String> {
        val value = ScriptableObject.getProperty(this, name)
        if (value !is NativeObject) return emptyMap()
        return value.ids
            .mapNotNull { id ->
                val key = id.toString()
                val rawValue = value.get(key, value)
                if (rawValue == Undefined.instance) null else key to RhinoContext.toString(rawValue)
            }
            .toMap()
    }

    private fun Any?.toRuleString(): String {
        return when (this) {
            null, Undefined.instance -> ""
            is MediaScriptRuleNodeList -> toString()
            is MediaLegadoRuleEvaluator.RuleNode -> toRuleText()
            is Element -> outerHtml()
            is JsonElement -> toRuleText()
            is NativeJavaObject -> unwrap().toRuleString()
            is NativeArray -> gson.toJson(toPlainValue())
            is NativeObject -> gson.toJson(toPlainValue())
            is Iterable<*> -> joinToString("\n") { it.toRuleText() }
            else -> RhinoContext.toString(this)
        }
    }

    private fun NativeArray.toPlainValue(): List<Any?> {
        val size = length.toInt()
        return (0 until size).map { index -> jsToPlainValue(get(index, this)) }
    }

    private fun NativeObject.toPlainValue(): Map<String, Any?> {
        return ids.associate { id ->
            val key = id.toString()
            key to jsToPlainValue(get(key, this))
        }
    }

    private fun jsToPlainValue(value: Any?): Any? {
        return when (value) {
            null, Undefined.instance -> null
            is NativeArray -> value.toPlainValue()
            is NativeObject -> value.toPlainValue()
            is NativeJavaObject -> jsToPlainValue(value.unwrap())
            is Element -> value.outerHtml()
            is JsonElement -> value.toPlainValue()
            is MediaLegadoRuleEvaluator.RuleNode -> value.toRuleText()
            is MediaScriptRuleNodeList -> value.nodes.map { it.toRuleText() }
            is Number, is Boolean, is String -> value
            else -> RhinoContext.toString(value)
        }
    }

    private fun Any?.toRuleText(): String {
        return when (this) {
            null, Undefined.instance -> ""
            is MediaLegadoRuleEvaluator.RuleNode -> toRuleText()
            is Element -> outerHtml()
            is JsonElement -> toRuleText()
            is NativeJavaObject -> unwrap().toRuleText()
            else -> RhinoContext.toString(this)
        }
    }

    private fun MediaLegadoRuleEvaluator.RuleNode.toRuleText(): String {
        return json?.toRuleText() ?: element?.outerHtml().orEmpty()
    }

    private fun JsonElement.toRuleText(): String {
        return when {
            isJsonNull -> ""
            isJsonPrimitive -> asJsonPrimitive.asString
            else -> toString()
        }
    }

    private fun JsonElement.toPlainValue(): Any? {
        return when {
            isJsonNull -> null
            isJsonPrimitive -> {
                val primitive = asJsonPrimitive
                when {
                    primitive.isBoolean -> primitive.asBoolean
                    primitive.isNumber -> primitive.asNumber
                    else -> primitive.asString
                }
            }
            isJsonArray -> asJsonArray.map { it.toPlainValue() }
            isJsonObject -> asJsonObject.entrySet().associate { it.key to it.value.toPlainValue() }
            else -> toString()
        }
    }

    private companion object {
        private const val READER_JAVA_BRIDGE_ALIAS = "__reader_java"
        private val JAVA_BRIDGE_REFERENCE = Regex("""(?<![A-Za-z0-9_$.])java\.""")
        private val gson = Gson()
        private val jsLibCache = ConcurrentHashMap<String, List<String>>()

        private fun protectLegadoJavaBridgeReferences(script: String): String {
            return JAVA_BRIDGE_REFERENCE.replace(script, "$READER_JAVA_BRIDGE_ALIAS.")
        }
    }
}

internal data class LegadoMediaScriptOutput(
    val value: String,
    val url: String,
    val body: String,
    val method: String,
    val headers: Map<String, String>
)

internal data class MediaScriptRuleNodeList(
    val nodes: List<MediaLegadoRuleEvaluator.RuleNode>
) {
    override fun toString(): String {
        return nodes.joinToString("\n") { node ->
            node.json?.toString() ?: node.element?.outerHtml().orEmpty()
        }
    }
}

class LegadoMediaJsBridge(
    private val fetcher: MediaHttpFetcher,
    private val evaluator: MediaLegadoRuleEvaluator,
    private val baseUrl: String,
    private val headers: Map<String, String>,
    private val variables: MutableMap<String, String>,
    private var content: String
) {
    fun ajax(url: Any?): String {
        val rawUrl = when (url) {
            is NativeArray -> RhinoContext.toString(url.get(0, url))
            else -> RhinoContext.toString(url)
        }
        if (rawUrl.isBlank()) return ""
        val request = LegadoMediaUrlRequest.parse(rawUrl, baseUrl, headers)
        val response = if (request.webView) {
            LegadoMediaWebViewFetcher.fetch(request) ?: fetcher.fetch(request.toMediaHttpRequest())
        } else {
            fetcher.fetch(request.toMediaHttpRequest())
        }
        return response.body
    }

    fun ajaxAll(urls: Any?): List<MediaScriptResponse> {
        return urls.toScriptSequence()
            .map { fetchResponse(it, null) }
    }

    fun importScript(path: Any?): String {
        val rawPath = RhinoContext.toString(path).trim()
        if (rawPath.isBlank()) return ""
        val script = MediaScriptImportCache.load(rawPath) {
            when {
                rawPath.startsWith("http://") || rawPath.startsWith("https://") -> {
                    fetcher.fetch(MediaHttpRequest(rawPath)).body
                }
                else -> MediaScriptCacheStore.get(rawPath).orEmpty()
            }
        }
        if (script.isBlank()) error("$rawPath script content is blank")
        return script
    }

    fun connect(url: Any?): MediaScriptResponse {
        return fetchResponse(url, null)
    }

    fun connect(url: Any?, header: Any?): MediaScriptResponse {
        return fetchResponse(url, header)
    }

    fun get(url: Any?, header: Any?): MediaScriptResponse {
        return fetchResponse(url, header)
    }

    fun head(url: Any?): MediaScriptResponse {
        return fetchResponse(url, null, "HEAD", null)
    }

    fun head(url: Any?, header: Any?): MediaScriptResponse {
        return fetchResponse(url, header, "HEAD", null)
    }

    fun post(url: Any?, body: Any?): MediaScriptResponse {
        return fetchResponse(url, null, "POST", RhinoContext.toString(body))
    }

    fun post(url: Any?, body: Any?, header: Any?): MediaScriptResponse {
        return fetchResponse(url, header, "POST", RhinoContext.toString(body))
    }

    private fun fetchResponse(
        url: Any?,
        header: Any?,
        requestMethod: String? = null,
        requestBody: String? = null
    ): MediaScriptResponse {
        val rawUrl = RhinoContext.toString(url)
        if (rawUrl.isBlank()) return MediaScriptResponse("", "")
        val request = LegadoMediaUrlRequest.parse(
            rawValue = rawUrl,
            baseUrl = baseUrl,
            defaultHeaders = headers + headersFromAny(header)
        ).let { parsed ->
            parsed.copy(
                method = requestMethod ?: parsed.method,
                body = requestBody ?: parsed.body
            )
        }
        val response = fetcher.fetch(request.toMediaHttpRequest())
        return MediaScriptResponse(response.finalUrl, response.body, response.headers, response.statusCode)
    }

    fun put(key: Any?, value: Any?): String {
        val name = RhinoContext.toString(key)
        val text = RhinoContext.toString(value)
        if (name.isNotBlank()) variables[name] = text
        return text
    }

    fun get(key: Any?): String {
        return variables[RhinoContext.toString(key)].orEmpty()
    }

    fun setContent(value: Any?): String {
        content = RhinoContext.toString(value)
        return content
    }

    fun getCookie(tag: Any?): String {
        return MediaScriptCookieStore.getCookie(RhinoContext.toString(tag))
    }

    fun getCookie(tag: Any?, key: Any?): String {
        return MediaScriptCookieStore.getCookieValue(
            tag = RhinoContext.toString(tag),
            key = RhinoContext.toString(key)
        )
    }

    fun removeCookie(tag: Any?): String {
        MediaScriptCookieStore.removeCookie(RhinoContext.toString(tag))
        return ""
    }

    fun getString(rule: Any?): String {
        val key = RhinoContext.toString(rule)
        variables[key]?.let { return it }
        val node = contentRootNode() ?: return ""
        return evaluator.string(key, node)
    }

    fun getString(rule: Any?, target: Any?): String {
        val key = RhinoContext.toString(rule)
        if (key.isBlank()) return targetNodes(target).joinToString("\n") { it.toRuleText() }
        return targetNodes(target)
            .map { evaluator.string(key, it).trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
    }

    fun getStringList(rule: Any?): List<String> {
        val key = RhinoContext.toString(rule)
        if (key.isBlank()) return emptyList()
        val context = contentContext() ?: return emptyList()
        val nodes = evaluateListRules(key, context)
        if (nodes.isNotEmpty()) {
            return nodes.mapNotNull { node ->
                node.json?.toPlainString() ?: node.element?.text()
            }.map { it.trim() }.filter { it.isNotBlank() }
        }
        return evaluator.string(key, contentRootNode() ?: return emptyList())
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
    }

    fun getStringList(rule: Any?, target: Any?): List<String> {
        val key = RhinoContext.toString(rule)
        if (key.isBlank()) return emptyList()
        val nodes = targetNodes(target)
        return nodes.flatMap { node ->
            val context = contextFromNode(node) ?: return@flatMap emptyList()
            val selected = evaluateListRules(key, context)
            if (selected.isNotEmpty()) {
                selected.mapNotNull { it.json?.toPlainString() ?: it.element?.text() }
            } else {
                evaluator.string(key, node)
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .toList()
            }
        }
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    fun getElements(rule: Any?): List<Element> {
        val key = RhinoContext.toString(rule)
        if (key.isBlank()) return emptyList()
        val context = contentContext() ?: return emptyList()
        return evaluateListRules(key, context).mapNotNull { it.element }
    }

    fun getElements(rule: Any?, target: Any?): List<Element> {
        val key = RhinoContext.toString(rule)
        if (key.isBlank()) return targetNodes(target).mapNotNull { it.element }
        return targetNodes(target)
            .flatMap { node ->
                val context = contextFromNode(node) ?: return@flatMap emptyList()
                evaluateListRules(key, context)
            }
            .mapNotNull { it.element }
    }

    fun getElement(rule: Any?): Elements {
        return Elements(getElements(rule))
    }

    fun getElement(rule: Any?, target: Any?): Elements {
        return Elements(getElements(rule, target))
    }

    fun log(value: Any?): String {
        return RhinoContext.toString(value)
    }

    fun longToast(value: Any?): String {
        return RhinoContext.toString(value)
    }

    fun toast(value: Any?): String {
        return RhinoContext.toString(value)
    }

    fun t2s(value: Any?): String {
        return RhinoContext.toString(value)
    }

    fun s2t(value: Any?): String {
        return RhinoContext.toString(value)
    }

    fun md5Encode(value: Any?): String {
        return md5(RhinoContext.toString(value))
    }

    fun md5Encode16(value: Any?): String {
        return md5(RhinoContext.toString(value)).substring(8, 24)
    }

    fun base64Encode(value: Any?): String {
        return Base64.getEncoder().encodeToString(RhinoContext.toString(value).toByteArray(Charsets.UTF_8))
    }

    fun base64EncodeBytes(value: Any?): String {
        return Base64.getEncoder().encodeToString(mediaBytesFromAny(value))
    }

    fun base64Decode(value: Any?): String {
        return String(Base64.getDecoder().decode(RhinoContext.toString(value)), Charsets.UTF_8)
    }

    fun base64DecodeToByteArray(value: Any?): ByteArray {
        return Base64.getDecoder().decode(RhinoContext.toString(value))
    }

    fun sha512(value: Any?): ByteArray {
        return MessageDigest.getInstance("SHA-512").digest(mediaBytesFromAny(value))
    }

    fun randomBytes(size: Any?): ByteArray {
        val count = RhinoContext.toNumber(size).takeIf { !it.isNaN() }?.toInt()?.coerceAtLeast(0) ?: 0
        val bytes = ByteArray(count)
        SecureRandom().nextBytes(bytes)
        return bytes
    }

    fun gzip(value: Any?, charsetName: Any?): ByteArray {
        val bytes = mediaBytesFromAny(value, RhinoContext.toString(charsetName).ifBlank { "UTF-8" })
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(bytes) }
        return output.toByteArray()
    }

    fun ungzip(value: Any?, charsetName: Any?): ByteArray {
        val input = ByteArrayInputStream(mediaBytesFromAny(value, RhinoContext.toString(charsetName).ifBlank { "UTF-8" }))
        return GZIPInputStream(input).use { it.readBytes() }
    }

    fun createSymmetricCrypto(transformation: Any?, key: Any?, iv: Any?): MediaSymmetricCrypto {
        return MediaSymmetricCrypto(
            transformation = normalizeTransformation(RhinoContext.toString(transformation)),
            key = mediaBytesFromAny(key),
            iv = mediaBytesFromAny(iv)
        )
    }

    fun aesBase64DecodeToString(value: Any?, key: Any?, transformation: Any?, iv: Any?): String {
        return createSymmetricCrypto(transformation, key, iv).decryptStr(value)
    }

    fun aesBase64DecodeToByteArray(value: Any?, key: Any?, transformation: Any?, iv: Any?): ByteArray {
        return createSymmetricCrypto(transformation, key, iv).decrypt(value)
    }

    fun aesDecodeToString(value: Any?, key: Any?, transformation: Any?, iv: Any?): String {
        return createSymmetricCrypto(transformation, key, iv).decryptStr(value)
    }

    fun aesEncodeToBase64String(value: Any?, key: Any?, transformation: Any?, iv: Any?): String {
        return createSymmetricCrypto(transformation, key, iv).encryptBase64(value)
    }

    fun hexDecodeToString(value: Any?): String {
        val hex = RhinoContext.toString(value).filter { !it.isWhitespace() }
        if (hex.length % 2 != 0) return ""
        return runCatching {
            hex.chunked(2)
                .map { it.toInt(16).toByte() }
                .toByteArray()
                .toString(Charsets.UTF_8)
        }.getOrDefault("")
    }

    fun urlDecode(value: Any?): String {
        return URLDecoder.decode(RhinoContext.toString(value), "UTF-8")
    }

    fun encodeURI(value: Any?): String {
        return encodeURIComponent(value)
            .replace("%2F", "/")
            .replace("%3A", ":")
            .replace("%3F", "?")
            .replace("%26", "&")
            .replace("%3D", "=")
    }

    fun encodeURIComponent(value: Any?): String {
        return URLEncoder.encode(RhinoContext.toString(value), "UTF-8")
    }

    fun timeFormat(value: Any?): String {
        val timestamp = RhinoContext.toNumber(value).takeIf { !it.isNaN() }?.toLong() ?: return ""
        val millis = if (timestamp < 10_000_000_000L) timestamp * 1000L else timestamp
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(millis))
    }

    fun timeFormatUTC(value: Any?, pattern: Any?, offsetHours: Any?): String {
        val timestamp = RhinoContext.toNumber(value).takeIf { !it.isNaN() }?.toLong() ?: return ""
        val millis = if (timestamp < 10_000_000_000L) timestamp * 1000L else timestamp
        val format = RhinoContext.toString(pattern).ifBlank { "yyyy-MM-dd HH:mm:ss" }
        val offset = RhinoContext.toNumber(offsetHours).takeIf { !it.isNaN() }?.toInt() ?: 0
        return SimpleDateFormat(format, Locale.CHINA).apply {
            timeZone = TimeZone.getTimeZone("GMT${if (offset >= 0) "+" else ""}$offset")
        }.format(Date(millis))
    }

    fun androidId(): String = DEVICE_ID

    fun deviceID(): String = DEVICE_ID

    fun randomUUID(): String = UUID.randomUUID().toString()

    fun getWebViewUA(): String = WEB_VIEW_UA

    fun hexEncodeToString(value: Any?): String {
        return RhinoContext.toString(value)
            .toByteArray(Charsets.UTF_8)
            .joinToString("") { byte -> "%02x".format(byte) }
    }

    fun startBrowser(url: Any?, title: Any?): String {
        error("browser action unsupported for media source")
    }

    fun startBrowserAwait(url: Any?, title: Any?): String {
        error("browser action unsupported for media source")
    }

    fun startBrowserAwait(url: Any?, title: Any?, waitLogin: Any?): String {
        error("browser action unsupported for media source")
    }

    fun webViewGetOverrideUrl(html: Any?, url: Any?, js: Any?, title: Any?): String {
        error("browser verification unsupported for media source")
    }

    fun webView(html: Any?, url: Any?, js: Any?): String {
        return runWebView(html, url, js)
    }

    fun webView(html: Any?, url: Any?, js: Any?, title: Any?): String {
        return runWebView(html, url, js)
    }

    private fun runWebView(html: Any?, url: Any?, js: Any?): String {
        val htmlText = RhinoContext.toString(html ?: "")
        val rawUrl = RhinoContext.toString(url ?: "").trim()
        val javaScript = RhinoContext.toString(js ?: "")
        if (htmlText.isBlank() && rawUrl.isBlank()) return ""
        MediaInlineWebViewEvaluator.evaluate(htmlText, javaScript)?.let { return it }
        val request = rawUrl
            .takeIf { it.isNotBlank() }
            ?.let { LegadoMediaUrlRequest.parse(it, baseUrl, headers) }
        return LegadoMediaWebViewFetcher.evaluate(htmlText, request, javaScript)
            ?: error("webView unsupported for media source")
    }

    private fun contentContext(): MediaLegadoRuleEvaluator.BodyContext? {
        if (content.isBlank()) return null
        return runCatching { evaluator.parseBody(content, baseUrl) }.getOrNull()?.also {
            it.variables.putAll(variables)
        }
    }

    private fun contentRootNode(): MediaLegadoRuleEvaluator.RuleNode? {
        val context = contentContext() ?: return null
        return MediaLegadoRuleEvaluator.RuleNode(
            json = context.json,
            element = context.document,
            baseUrl = context.baseUrl,
            variables = context.variables
        )
    }

    private fun targetNodes(value: Any?): List<MediaLegadoRuleEvaluator.RuleNode> {
        return when (val unwrapped = value.unwrapScriptValue()) {
            null, Undefined.instance -> contentRootNode()?.let { listOf(it) }.orEmpty()
            is MediaScriptRuleNodeList -> unwrapped.nodes
            is MediaLegadoRuleEvaluator.RuleNode -> listOf(unwrapped.withVariables())
            is Element -> listOf(MediaLegadoRuleEvaluator.RuleNode(null, unwrapped, baseUrl, variables))
            is Elements -> unwrapped.map { MediaLegadoRuleEvaluator.RuleNode(null, it, baseUrl, variables) }
            is JsonElement -> listOf(MediaLegadoRuleEvaluator.RuleNode(unwrapped, null, baseUrl, variables))
            is NativeArray -> {
                val size = unwrapped.length.toInt()
                (0 until size).flatMap { index -> targetNodes(unwrapped.get(index, unwrapped)) }
            }
            is Iterable<*> -> unwrapped.flatMap { targetNodes(it) }
            else -> {
                val text = RhinoContext.toString(unwrapped)
                if (text.isBlank()) return emptyList()
                val context = runCatching { evaluator.parseBody(text, baseUrl) }.getOrNull() ?: return emptyList()
                context.variables.putAll(variables)
                listOf(
                    MediaLegadoRuleEvaluator.RuleNode(
                        json = context.json,
                        element = context.document,
                        baseUrl = context.baseUrl,
                        variables = context.variables
                    )
                )
            }
        }
    }

    private fun contextFromNode(node: MediaLegadoRuleEvaluator.RuleNode): MediaLegadoRuleEvaluator.BodyContext? {
        val context = when {
            node.json != null -> evaluator.parseBody(node.json.toString(), node.baseUrl)
            node.element != null -> evaluator.parseBody(node.element.outerHtml(), node.baseUrl)
            else -> return null
        }
        context.variables.putAll(node.variables)
        return context
    }

    private fun Any?.unwrapScriptValue(): Any? {
        return when (this) {
            is NativeJavaObject -> unwrap().unwrapScriptValue()
            else -> this
        }
    }

    private fun MediaLegadoRuleEvaluator.RuleNode.withVariables(): MediaLegadoRuleEvaluator.RuleNode {
        variables.putAll(this@LegadoMediaJsBridge.variables)
        return this
    }

    private fun MediaLegadoRuleEvaluator.RuleNode.toRuleText(): String {
        return json?.toPlainString() ?: element?.outerHtml().orEmpty()
    }

    private fun evaluateListRules(
        rule: String,
        context: MediaLegadoRuleEvaluator.BodyContext
    ): List<MediaLegadoRuleEvaluator.RuleNode> {
        return rule.split("&&")
            .flatMap { part -> evaluator.list(part.trim(), context) }
    }

    private fun headersFromAny(value: Any?): Map<String, String> {
        if (value == null || value == Undefined.instance) return emptyMap()
        if (value is NativeObject) {
            return value.ids.associate { id ->
                val key = id.toString()
                key to RhinoContext.toString(value.get(key, value))
            }
        }
        val text = RhinoContext.toString(value).trim()
        if (text.isBlank()) return emptyMap()
        val parsed = runCatching { JsonParser.parseString(text) }.getOrNull()
        if (parsed?.isJsonObject == true) {
            return parsed.asJsonObject.entrySet()
                .filter { it.value.isJsonPrimitive }
                .associate { it.key to it.value.asString }
        }
        return emptyMap()
    }

    private fun Any?.toScriptSequence(): List<Any?> {
        return when (val unwrapped = unwrapScriptValue()) {
            null, Undefined.instance -> emptyList()
            is NativeArray -> (0 until unwrapped.length.toInt()).map { index -> unwrapped.get(index, unwrapped) }
            is Iterable<*> -> unwrapped.toList()
            else -> RhinoContext.toString(unwrapped)
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toList()
        }
    }

    private fun JsonElement.toPlainString(): String {
        if (isJsonNull) return ""
        if (isJsonPrimitive) return asJsonPrimitive.asString
        return toString()
    }

    private fun md5(value: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun normalizeTransformation(value: String): String {
        return value.ifBlank { "AES/CBC/PKCS5Padding" }
            .replace("PKCS7Padding", "PKCS5Padding", ignoreCase = true)
    }

    private companion object {
        private const val DEVICE_ID = "reader_media_legado"
        private const val WEB_VIEW_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
    }
}

private fun mediaBytesFromAny(value: Any?, stringCharsetName: String = "UTF-8"): ByteArray {
    return when (value) {
        null, Undefined.instance -> ByteArray(0)
        is ByteArray -> value
        is NativeArray -> {
            val size = value.length.toInt()
            ByteArray(size) { index -> RhinoContext.toNumber(value.get(index, value)).toInt().toByte() }
        }
        is NativeJavaArray -> mediaBytesFromUnwrapped(value.unwrap(), stringCharsetName)
        is NativeJavaObject -> mediaBytesFromUnwrapped(value.unwrap(), stringCharsetName)
        else -> RhinoContext.toString(value).toByteArray(charsetFromName(stringCharsetName))
    }
}

private fun mediaBytesFromUnwrapped(value: Any?, stringCharsetName: String): ByteArray {
    return when (value) {
        null -> ByteArray(0)
        is ByteArray -> value
        is IntArray -> ByteArray(value.size) { index -> value[index].toByte() }
        is ShortArray -> ByteArray(value.size) { index -> value[index].toByte() }
        is LongArray -> ByteArray(value.size) { index -> value[index].toByte() }
        is Array<*> -> ByteArray(value.size) { index -> RhinoContext.toNumber(value[index]).toInt().toByte() }
        else -> RhinoContext.toString(value).toByteArray(charsetFromName(stringCharsetName))
    }
}

private fun mediaJsArrayFromBytes(bytes: ByteArray): NativeArray {
    val values = arrayOfNulls<Any>(bytes.size)
    bytes.forEachIndexed { index, byte ->
        values[index] = byte.toInt() and 0xff
    }
    return NativeArray(values)
}

private fun charsetFromName(name: String): java.nio.charset.Charset {
    return runCatching { charset(name.ifBlank { "UTF-8" }) }.getOrDefault(Charsets.UTF_8)
}

class MediaSymmetricCrypto(
    private val transformation: String,
    private val key: ByteArray,
    private val iv: ByteArray
) {
    fun decrypt(value: Any?): ByteArray {
        if (key.isEmpty()) return ByteArray(0)
        val input = when (value) {
            is ByteArray, is NativeArray, is NativeJavaArray, is NativeJavaObject -> mediaBytesFromAny(value)
            else -> runCatching { Base64.getDecoder().decode(RhinoContext.toString(value).replace("\n", "")) }
                .getOrElse { RhinoContext.toString(value).toByteArray(Charsets.UTF_8) }
        }
        return transform(Cipher.DECRYPT_MODE, input)
    }

    fun decryptStr(value: Any?): String {
        return String(decrypt(value), Charsets.UTF_8)
    }

    fun encrypt(value: Any?): NativeArray {
        return mediaJsArrayFromBytes(encryptBytes(value))
    }

    private fun encryptBytes(value: Any?): ByteArray {
        if (key.isEmpty()) return ByteArray(0)
        return transform(Cipher.ENCRYPT_MODE, mediaBytesFromAny(value))
    }

    fun encryptBase64(value: Any?): String {
        return Base64.getEncoder().encodeToString(encryptBytes(value))
    }

    private fun transform(mode: Int, input: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(transformation)
        val secretKey = SecretKeySpec(key, transformation.substringBefore("/"))
        if (iv.isNotEmpty()) {
            cipher.init(mode, secretKey, IvParameterSpec(iv))
        } else {
            cipher.init(mode, secretKey)
        }
        return cipher.doFinal(input)
    }
}

private object MediaInlineWebViewEvaluator {
    private val scriptTag = Regex("""(?is)<script\b[^>]*>(.*?)</script>""")

    fun evaluate(html: String, javaScript: String): String? {
        if (html.isBlank()) return null
        val scripts = scriptTag.findAll(html)
            .map { it.groupValues[1] }
            .filter { it.isNotBlank() }
            .toList()
        if (scripts.isEmpty()) return null
        val context = RhinoContext.enter()
        return try {
            context.optimizationLevel = -1
            context.languageVersion = RhinoContext.VERSION_ES6
            val scope = context.initStandardObjects()
            installBrowserGlobals(scope)
            scripts.forEachIndexed { index, script ->
                context.evaluateString(scope, script, "media-inline-webview-script-$index", 1, null)
            }
            if (javaScript.isBlank()) {
                html
            } else {
                RhinoContext.toString(
                    context.evaluateString(scope, javaScript, "media-inline-webview-result", 1, null)
                )
            }
        } catch (_: Throwable) {
            null
        } finally {
            RhinoContext.exit()
        }
    }

    private fun installBrowserGlobals(scope: Scriptable) {
        ScriptableObject.putProperty(scope, "window", scope)
        ScriptableObject.putProperty(scope, "btoa", Base64Function(encode = true))
        ScriptableObject.putProperty(scope, "atob", Base64Function(encode = false))
    }

    private class Base64Function(
        private val encode: Boolean
    ) : BaseFunction() {
        override fun call(
            cx: RhinoContext?,
            scope: Scriptable?,
            thisObj: Scriptable?,
            args: Array<out Any?>?
        ): Any {
            val value = RhinoContext.toString(args?.firstOrNull() ?: "")
            return if (encode) {
                Base64.getEncoder().encodeToString(value.toByteArray(Charsets.ISO_8859_1))
            } else {
                String(Base64.getDecoder().decode(value), Charsets.ISO_8859_1)
            }
        }
    }
}

class MediaScriptResponse(
    private val responseUrl: String,
    private val responseBody: String,
    private val responseHeaders: Map<String, String> = emptyMap(),
    private val responseCode: Int = 200
) {
    fun body(): String = responseBody
    fun url(): String = responseUrl
    fun code(): Int = responseCode
    fun header(name: Any?): String = responseHeaders.headerValue(RhinoContext.toString(name))
    fun headers(): MediaScriptHeaders = MediaScriptHeaders(responseHeaders)
    fun headers(name: Any?): String = header(name)
    fun cookies(): NativeObject = responseHeaders.cookieObject()
    override fun toString(): String = responseBody
}

class MediaScriptHeaders(
    private val values: Map<String, String>
) {
    val location: String get() = get("location")

    fun get(name: Any?): String = values.headerValue(RhinoContext.toString(name))
    fun header(name: Any?): String = get(name)
    override fun toString(): String = values.toString()
}

private fun Map<String, String>.headerValue(name: String): String {
    if (name.isBlank()) return ""
    return this[name]
        ?: this[name.lowercase(Locale.ROOT)]
        ?: entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        ?: ""
}

private fun Map<String, String>.cookieObject(): NativeObject {
    val rawCookie = headerValue("set-cookie")
    val cookies = NativeObject()
    rawCookie.cookiePairs().forEach { (name, value) ->
        ScriptableObject.putProperty(cookies, name, value)
    }
    ScriptableObject.defineProperty(
        cookies,
        "toString",
        object : BaseFunction() {
            override fun call(
                cx: RhinoContext?,
                scope: Scriptable?,
                thisObj: Scriptable?,
                args: Array<out Any?>?
            ): Any = rawCookie
        },
        ScriptableObject.DONTENUM
    )
    return cookies
}

private fun String.cookiePairs(): Map<String, String> {
    if (isBlank()) return emptyMap()
    val pairs = linkedMapOf<String, String>()
    split(Regex(""",\s*(?=[^;,=\s]+=[^;,]*)"""))
        .map { it.substringBefore(";").trim() }
        .forEach { pair ->
            val name = pair.substringBefore("=", "").trim()
            val value = pair.substringAfter("=", "").trim()
            if (name.isNotBlank()) pairs[name] = value
        }
    return pairs
}

class MediaScriptCache {
    fun get(key: Any?): String? {
        return MediaScriptCacheStore.get(RhinoContext.toString(key))
    }

    fun put(key: Any?, value: Any?): String {
        return put(key, value, 0)
    }

    fun put(key: Any?, value: Any?, saveTime: Any?): String {
        val name = RhinoContext.toString(key)
        val text = RhinoContext.toString(value)
        if (name.isNotBlank()) {
            MediaScriptCacheStore.put(name, text, RhinoContext.toNumber(saveTime).takeIf { !it.isNaN() }?.toInt() ?: 0)
        }
        return text
    }

    fun delete(key: Any?): String {
        MediaScriptCacheStore.delete(RhinoContext.toString(key))
        return ""
    }

    fun remove(key: Any?): String = delete(key)

    fun getFile(key: Any?): String? = get(key)

    fun putFile(key: Any?, value: Any?): String = put(key, value)

    fun putFile(key: Any?, value: Any?, saveTime: Any?): String = put(key, value, saveTime)

    fun deleteFile(key: Any?): String = delete(key)
}

class MediaScriptCookie {
    fun getCookie(tag: Any?): String {
        return MediaScriptCookieStore.getCookie(RhinoContext.toString(tag))
    }

    fun getCookie(tag: Any?, key: Any?): String {
        return MediaScriptCookieStore.getCookieValue(
            tag = RhinoContext.toString(tag),
            key = RhinoContext.toString(key)
        )
    }

    fun setCookie(tag: Any?, value: Any?): String {
        val cookie = RhinoContext.toString(value)
        MediaScriptCookieStore.setCookie(RhinoContext.toString(tag), cookie)
        return cookie
    }

    fun replaceCookie(tag: Any?, value: Any?): String = setCookie(tag, value)

    fun removeCookie(tag: Any?): String {
        MediaScriptCookieStore.removeCookie(RhinoContext.toString(tag))
        return ""
    }

    fun removeCookie(tag: Any?, key: Any?): String {
        MediaScriptCookieStore.removeCookieValue(
            tag = RhinoContext.toString(tag),
            key = RhinoContext.toString(key)
        )
        return ""
    }
}

private object MediaScriptCacheStore {
    private val values = ConcurrentHashMap<String, Entry>()

    fun put(key: String, value: String, saveTimeSeconds: Int = 0) {
        val deadline = if (saveTimeSeconds == 0) 0L else System.currentTimeMillis() + saveTimeSeconds * 1000L
        values[key] = Entry(value, deadline)
    }

    fun get(key: String): String? {
        val entry = values[key] ?: return null
        if (entry.deadlineMillis != 0L && entry.deadlineMillis <= System.currentTimeMillis()) {
            values.remove(key)
            return null
        }
        return entry.value
    }

    fun delete(key: String) {
        values.remove(key)
    }

    private data class Entry(
        val value: String,
        val deadlineMillis: Long
    )
}

private object MediaScriptImportCache {
    private val values = ConcurrentHashMap<String, String>()

    fun load(path: String, fetch: () -> String): String {
        return values.computeIfAbsent(path) { fetch() }
    }
}

private object MediaScriptCookieStore {
    private val cookies = ConcurrentHashMap<String, String>()

    fun setCookie(tag: String, cookie: String) {
        if (tag.isNotBlank()) cookies[tag] = cookie
    }

    fun getCookie(tag: String): String = cookies[tag].orEmpty()

    fun getCookieValue(tag: String, key: String): String {
        if (key.isBlank()) return getCookie(tag)
        return getCookie(tag)
            .split(";")
            .map { it.trim() }
            .firstOrNull { it.substringBefore("=").equals(key, ignoreCase = true) }
            ?.substringAfter("=", "")
            .orEmpty()
    }

    fun removeCookie(tag: String) {
        cookies.remove(tag)
    }

    fun removeCookieValue(tag: String, key: String) {
        if (key.isBlank()) return
        val kept = getCookie(tag)
            .split(";")
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.substringBefore("=").equals(key, ignoreCase = true) }
        if (kept.isEmpty()) {
            removeCookie(tag)
        } else {
            cookies[tag] = kept.joinToString("; ")
        }
    }
}

class MediaScriptSource(
    source: MediaSourceDefinition,
    private val variables: MutableMap<String, String>
) {
    val bookSourceUrl: String = source.sourceUrl
    val bookSourceName: String = source.sourceName
    val bookSourceComment: String = source.sourceComment.orEmpty()
    val sourceUrl: String = source.sourceUrl
    val sourceName: String = source.sourceName
    val key: String = source.sourceUrl
    val loginUrl: String = source.loginUrl

    fun get(key: String): String {
        return when (key) {
            "bookSourceUrl", "sourceUrl" -> sourceUrl
            "bookSourceName", "sourceName" -> sourceName
            "bookSourceComment", "sourceComment" -> bookSourceComment
            "loginUrl" -> loginUrl
            "key" -> this.key
            else -> MediaScriptCacheStore.get("v_${this.key}_$key").orEmpty()
        }
    }

    fun getVariable(): String {
        return MediaScriptCacheStore.get("sourceVariable_$key")
            ?: variables["source.variable"].orEmpty()
    }

    fun getVariable(key: String): String {
        return MediaScriptCacheStore.get("v_${this.key}_$key")
            ?: variables["source.$key"]
            ?: get(key)
    }

    fun setVariable(variable: String?): String {
        val text = variable.orEmpty()
        variables["source.variable"] = text
        if (variable == null) {
            MediaScriptCacheStore.delete("sourceVariable_$key")
        } else {
            MediaScriptCacheStore.put("sourceVariable_$key", text)
        }
        return text
    }

    fun setVariable(key: String?, variable: String?): String {
        val name = key.orEmpty()
        val text = variable.orEmpty()
        if (name.isNotBlank()) {
            variables["source.$name"] = text
            if (variable == null) {
                MediaScriptCacheStore.delete("v_${this.key}_$name")
            } else {
                MediaScriptCacheStore.put("v_${this.key}_$name", text)
            }
        }
        return text
    }

    fun putVariable(value: String): String = setVariable(value)

    fun put(key: String, value: String): String {
        MediaScriptCacheStore.put("v_${this.key}_$key", value)
        return value
    }

    fun getLoginHeader(): String? = MediaScriptCacheStore.get("loginHeader_$key")

    fun putLoginHeader(header: String): String {
        MediaScriptCacheStore.put("loginHeader_$key", header)
        val cookie = runCatching {
            val parsed = JsonParser.parseString(header)
            parsed.asJsonObject.get("Cookie")?.asString ?: parsed.asJsonObject.get("cookie")?.asString
        }.getOrNull()
        cookie?.let { MediaScriptCookieStore.setCookie(key, it) }
        return header
    }

    fun removeLoginHeader(): String {
        MediaScriptCacheStore.delete("loginHeader_$key")
        MediaScriptCookieStore.removeCookie(key)
        return ""
    }

    fun getLoginInfoMap(): Map<String, String> = emptyMap()

    fun refreshJSLib(): String = ""

    fun refreshExplore(): String = ""
}

class MediaScriptBook(private val chapter: MediaSourceChapter?) {
    val name: String = chapter?.book?.name.orEmpty()
    val bookUrl: String = chapter?.book?.bookUrl.orEmpty()

    fun getVariable(key: String): String {
        return chapter?.book?.let { book ->
            when (key) {
                "name", "bookName" -> book.name
                "bookUrl" -> book.bookUrl
                else -> ""
            }
        }.orEmpty()
    }
}

class MediaScriptChapter(private val chapter: MediaSourceChapter?) {
    val title: String = chapter?.name.orEmpty()
    val url: String = chapter?.chapterUrl.orEmpty()

    fun getVariable(key: String): String {
        return chapter?.let {
            when (key) {
                "title" -> it.name
                "url", "chapterUrl" -> it.chapterUrl
                else -> it.runtimeVariables[key].orEmpty()
            }
        }.orEmpty()
    }
}
