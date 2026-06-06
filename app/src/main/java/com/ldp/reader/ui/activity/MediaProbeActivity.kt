package com.ldp.reader.ui.activity

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.ldp.reader.BuildConfig
import com.ldp.reader.media.MediaChapterItem
import com.ldp.reader.media.MediaPlaybackSignature
import com.ldp.reader.media.MediaSourceAuditResult
import com.ldp.reader.media.MediaSourceRepository
import com.ldp.reader.media.MediaTailProbeResult
import com.ldp.reader.media.ReaderMediaKind
import com.ldp.reader.source.AiBridgeTrace
import kotlin.random.Random

class MediaProbeActivity : Activity() {
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isOpenUiProbeMode()) {
            startOpenUiProbe()
            return
        }
        if (isH5ProbeMode()) {
            startH5Probe()
            return
        }
        if (isSourceAuditMode()) {
            startSourceAudit()
            return
        }
        if (isFlowProbeMode()) {
            startFlowProbe()
            return
        }
        statusView = TextView(this).apply {
            text = "媒体探测中..."
            textSize = 18f
            setPadding(32, 96, 32, 32)
        }
        setContentView(statusView)
        if (!BuildConfig.DEBUG) {
            statusView.text = "仅 debug 包可用"
            return
        }
        val kind = ReaderMediaKind.fromSeedKey(intent.getStringExtra(EXTRA_KIND).orEmpty())
        val queries = intent.getStringExtra(EXTRA_QUERIES)
            .orEmpty()
            .split('|', ',', '，', '\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val maxBooks = readIntExtra(EXTRA_MAX_BOOKS, 2).coerceIn(1, 5)
        val maxSources = readIntExtra(EXTRA_MAX_SOURCES, 6).coerceIn(2, 24)
        if (kind == null || queries.isEmpty()) {
            statusView.text = "参数缺失"
            return
        }
        Thread({
            runProbe(kind, queries, maxBooks, maxSources)
        }, "media-tail-probe").start()
    }

    private fun isH5ProbeMode(): Boolean {
        return intent.getStringExtra(EXTRA_MODE)
            .orEmpty()
            .trim()
            .lowercase()
            .let { it == "h5" || it == "web" || it == "webview" }
    }

    private fun isSourceAuditMode(): Boolean {
        return intent.getStringExtra(EXTRA_MODE)
            .orEmpty()
            .trim()
            .lowercase() == "audit"
    }

    private fun isFlowProbeMode(): Boolean {
        return intent.getStringExtra(EXTRA_MODE)
            .orEmpty()
            .trim()
            .lowercase() == "flow"
    }

    private fun isOpenUiProbeMode(): Boolean {
        return intent.getStringExtra(EXTRA_MODE)
            .orEmpty()
            .trim()
            .lowercase()
            .let { it == "open" || it == "ui" || it == "reader" }
    }

    private fun startOpenUiProbe() {
        statusView = TextView(this).apply {
            text = "媒体 UI 打开中..."
            textSize = 14f
            setPadding(32, 96, 32, 32)
        }
        setContentView(statusView)
        if (!BuildConfig.DEBUG) {
            statusView.text = "仅 debug 包可用"
            return
        }
        val kind = ReaderMediaKind.fromSeedKey(intent.getStringExtra(EXTRA_KIND).orEmpty())
        val chapterRouteId = intent.getStringExtra(EXTRA_CHAPTER_ROUTE_ID)
            .orEmpty()
            .ifBlank { intent.getStringExtra(EXTRA_ROUTE_ID).orEmpty() }
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        if (kind == null || chapterRouteId.isBlank()) {
            statusView.text = "参数缺失"
            return
        }
        AiBridgeTrace.event(
            "media_ui_open",
            "${kind.seedKey}:$chapterRouteId".traceToken(),
            AiBridgeTrace.fields(
                "kind" to kind.seedKey,
                "route" to chapterRouteId,
                "title" to title
            )
        )
        when (kind) {
            ReaderMediaKind.COMIC -> ComicReadActivity.start(this, chapterRouteId, title)
            ReaderMediaKind.AUDIO -> AudioPlayerActivity.start(
                this,
                chapterRouteId,
                title,
                forceStart = readBooleanExtra(EXTRA_FORCE_START, true),
                autoPlay = true
            )
            ReaderMediaKind.NOVEL -> {
                statusView.text = "媒体 UI 不支持小说"
                return
            }
        }
        finish()
    }

    private fun startSourceAudit() {
        statusView = TextView(this).apply {
            text = "媒体源审计中..."
            textSize = 14f
            setPadding(32, 96, 32, 32)
        }
        setContentView(statusView)
        if (!BuildConfig.DEBUG) {
            statusView.text = "仅 debug 包可用"
            return
        }
        val kind = ReaderMediaKind.fromSeedKey(intent.getStringExtra(EXTRA_KIND).orEmpty())
        val query = intent.getStringExtra(EXTRA_QUERY).orEmpty().trim()
        val maxSources = readIntExtra(EXTRA_MAX_SOURCES, 60).coerceIn(1, 240)
        val builtInOnly = readBooleanExtra(EXTRA_BUILT_IN_ONLY, false)
        if (kind == null || query.isBlank()) {
            statusView.text = "参数缺失"
            return
        }
        Thread({
            postStatus("${kind.displayName}源审计中: $query${if (builtInOnly) " (内置)" else ""}")
            val result = MediaSourceRepository.sourceAudit(kind, query, maxSources, builtInOnly)
            traceAuditResult(result)
            postStatus("媒体源审计完成\n${buildAuditSummary(result)}")
        }, "media-source-audit").start()
    }

    private fun buildAuditSummary(result: MediaSourceAuditResult): String {
        val ok = result.rows.count { it.ok }
        val searched = result.rows.count { it.searchCount > 0 }
        val matched = result.rows.count { it.matchingCount > 0 }
        val detailed = result.rows.count { it.detailOk }
        val withChapters = result.rows.count { it.chapterCount > 0 }
        val failures = result.rows
            .filterNot { it.ok }
            .groupingBy { it.error.ifBlank { "unknown" }.substringBefore(":") }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .joinToString(",") { "${it.key}:${it.value}" }
        val rows = result.rows.joinToString("\n") { row ->
            val title = row.selectedTitle.ifBlank { row.firstTitle }.traceToken(28)
            val tail = row.tailTitle.traceToken(28)
            "- ${row.index + 1}. ${row.sourceName.traceToken(18)} s${row.searchCount}/m${row.matchingCount}/c${row.chapterCount}/i${row.itemCount} ${if (row.ok) "可播" else row.error} $title $tail"
        }
        return "${result.query} ${if (result.builtInOnly) "内置" else "全部"}源池${result.sourceCount} 审计${result.auditedCount} 搜到$searched 匹配$matched 详情$detailed 目录$withChapters 可播$ok 用时${result.durationMs}ms\n失败[$failures]\n$rows"
    }

    private fun traceAuditResult(result: MediaSourceAuditResult) {
        val ok = result.rows.count { it.ok }
        val searched = result.rows.count { it.searchCount > 0 }
        val matched = result.rows.count { it.matchingCount > 0 }
        val detailed = result.rows.count { it.detailOk }
        val withChapters = result.rows.count { it.chapterCount > 0 }
        AiBridgeTrace.state(
            "media_source_audit_summary",
            "${result.kind.seedKey}:${result.query}".traceToken(),
            AiBridgeTrace.fields(
                "sources" to result.sourceCount,
                "audited" to result.auditedCount,
                "searched" to searched,
                "matched" to matched,
                "detail" to detailed,
                "chapters" to withChapters,
                "ok" to ok,
                "durationMs" to result.durationMs,
                "builtInOnly" to result.builtInOnly
            )
        )
        result.rows.forEach { row ->
            AiBridgeTrace.state(
                "media_source_audit_row",
                "${result.kind.seedKey}:${result.query}:${row.index}".traceToken(),
                AiBridgeTrace.fields(
                    "source" to row.sourceName,
                    "search" to row.searchCount,
                    "match" to row.matchingCount,
                    "detail" to row.detailOk,
                    "chapters" to row.chapterCount,
                    "items" to row.itemCount,
                    "ok" to row.ok,
                    "error" to row.error.ifBlank { "-" },
                    "title" to row.selectedTitle.ifBlank { row.firstTitle },
                    "bookUrl" to row.selectedUrl,
                    "tocUrl" to row.tocUrl
                )
            )
            AiBridgeTrace.event(
                "media_source_audit_row",
                "${result.kind.seedKey}:${result.query}:${row.index}".traceToken(),
                AiBridgeTrace.fields(
                    "source" to row.sourceName,
                    "search" to row.searchCount,
                    "match" to row.matchingCount,
                    "first" to row.firstTitle,
                    "selected" to row.selectedTitle,
                    "bookUrl" to row.selectedUrl,
                    "tocUrl" to row.tocUrl,
                    "detail" to row.detailOk,
                    "chapters" to row.chapterCount,
                    "tail" to row.tailTitle,
                    "items" to row.itemCount,
                    "offset" to row.offsetFromLatest,
                    "ok" to row.ok,
                    "error" to row.error,
                    "sample" to row.sampleUrl
                )
            )
        }
    }

    private fun startFlowProbe() {
        statusView = TextView(this).apply {
            text = "媒体完整流程探测中..."
            textSize = 14f
            setPadding(32, 96, 32, 32)
        }
        setContentView(statusView)
        if (!BuildConfig.DEBUG) {
            statusView.text = "仅 debug 包可用"
            return
        }
        val kind = ReaderMediaKind.fromSeedKey(intent.getStringExtra(EXTRA_KIND).orEmpty())
        val queries = readQueries()
        val maxBooks = readIntExtra(EXTRA_MAX_BOOKS, 1).coerceIn(1, 10)
        val maxChapters = readIntExtra(EXTRA_MAX_CHAPTERS, 5).coerceIn(3, 8)
        val maxSources = readIntExtra(EXTRA_MAX_SOURCES, 72).coerceIn(1, 240)
        if (kind == null || queries.isEmpty()) {
            statusView.text = "参数缺失"
            return
        }
        Thread({
            runFlowProbe(kind, queries, maxBooks, maxChapters, maxSources)
        }, "media-flow-probe").start()
    }

    private fun runFlowProbe(
        kind: ReaderMediaKind,
        queries: List<String>,
        maxBooks: Int,
        maxChapters: Int,
        maxSources: Int
    ) {
        val startedAt = System.currentTimeMillis()
        val rows = ArrayList<MediaFlowBookProbe>()
        AiBridgeTrace.event(
            "media_flow_probe_started",
            kind.seedKey,
            AiBridgeTrace.fields(
                "queries" to queries.joinToString("+") { it.traceToken() },
                "maxBooks" to maxBooks,
                "maxChapters" to maxChapters,
                "maxSources" to maxSources
            )
        )
        queries.forEachIndexed { queryIndex, query ->
            postStatus("${kind.displayName}完整流程 ${queryIndex + 1}/${queries.size}: $query")
            var searchFailed = false
            val books = runCatching { MediaSourceRepository.flowSearch(kind, query, maxBooks, maxSources) }
                .getOrElse { throwable ->
                    searchFailed = true
                    val row = MediaFlowBookProbe(
                        query = query,
                        title = query,
                        sourceName = "",
                        routeId = "",
                        chapterCount = 0,
                        checkedChapters = "",
                        firstRouteId = "",
                        middleRouteId = "",
                        tailRouteId = "",
                        previousRouteId = "",
                        nextRouteId = "",
                        firstItems = 0,
                        middleItems = 0,
                        tailItems = 0,
                        previousItems = 0,
                        nextItems = 0,
                        ok = false,
                        error = "search_exception:${throwable.javaClass.simpleName}",
                        sampleUrl = ""
                    )
                    rows += row
                    traceFlowRow(kind, rows.lastIndex, row)
                    emptyList()
                }
            if (searchFailed) return@forEachIndexed
            if (books.isEmpty()) {
                val row = MediaFlowBookProbe(
                    query = query,
                    title = query,
                    sourceName = "",
                    routeId = "",
                    chapterCount = 0,
                    checkedChapters = "",
                    firstRouteId = "",
                    middleRouteId = "",
                    tailRouteId = "",
                    previousRouteId = "",
                    nextRouteId = "",
                    firstItems = 0,
                    middleItems = 0,
                    tailItems = 0,
                    previousItems = 0,
                    nextItems = 0,
                    ok = false,
                    error = "search_empty",
                    sampleUrl = ""
                )
                rows += row
                traceFlowRow(kind, rows.lastIndex, row)
                return@forEachIndexed
            }
            books.forEach { book ->
                val row = probeFlowBook(kind, query, book.routeId, book.title, book.sourceName, maxChapters)
                rows += row
                traceFlowRow(kind, rows.lastIndex, row)
            }
        }
        val result = MediaFlowProbeResult(
            kind = kind,
            rows = rows,
            durationMs = System.currentTimeMillis() - startedAt
        )
        traceFlowResult(result)
        postStatus("媒体完整流程探测完成\n${buildFlowSummary(result)}")
    }

    private fun probeFlowBook(
        kind: ReaderMediaKind,
        query: String,
        routeId: String,
        title: String,
        sourceName: String,
        maxChapters: Int
    ): MediaFlowBookProbe {
        return runCatching {
            val detail = MediaSourceRepository.detail(routeId)
                ?: return MediaFlowBookProbe.empty(query, title, sourceName, routeId, "detail_empty")
            val chapters = MediaSourceRepository.chapters(routeId)
            if (chapters.isEmpty()) {
                return MediaFlowBookProbe.empty(query, detail.title, detail.sourceName, routeId, "chapters_empty")
            }
            val selected = selectedFlowIndices(chapters.size, "$query|${detail.title}", maxChapters)
            val probes = selected.associateWith { index ->
                probeFlowChapter(kind, chapters[index])
            }
            val middleIndex = navigationIndex(chapters.size)
            val previousProbe = middleIndex
                ?.let { index -> probeFlowChapter(kind, chapters[index - 1]) }
                ?: MediaFlowChapterProbe.empty()
            val nextProbe = middleIndex
                ?.let { index -> probeFlowChapter(kind, chapters[index + 1]) }
                ?: MediaFlowChapterProbe.empty()
            val firstProbe = probes[0] ?: MediaFlowChapterProbe.empty()
            val middleProbe = probes[chapters.size / 2] ?: middleIndex
                ?.let { index -> probeFlowChapter(kind, chapters[index]) }
                ?: MediaFlowChapterProbe.empty()
            val tailProbe = probes[chapters.lastIndex] ?: MediaFlowChapterProbe.empty()
            val selectedOk = probes.values.all { it.itemCount > 0 }
            val navigationOk = previousProbe.itemCount > 0 && nextProbe.itemCount > 0
            val duplicateAudio = kind == ReaderMediaKind.AUDIO &&
                MediaPlaybackSignature.repeatedAudioUrlAcrossChapters(
                    (probes.values + listOf(previousProbe, nextProbe)).map { probe ->
                        probe.routeId to probe.sampleUrl
                    }
                )
            val error = when {
                chapters.size < 3 -> "chapters_too_few"
                !selectedOk -> "selected_chapter_unreadable"
                !navigationOk -> "sibling_chapter_unreadable"
                duplicateAudio -> "duplicate_audio_signature"
                else -> ""
            }
            MediaFlowBookProbe(
                query = query,
                title = detail.title,
                sourceName = detail.sourceName,
                routeId = routeId,
                chapterCount = chapters.size,
                checkedChapters = probes.values.joinToString("|") { probe ->
                    "${probe.index}:${probe.title.traceToken(18)}:${probe.itemCount}:" +
                        MediaPlaybackSignature.audioUrl(probe.sampleUrl).traceToken(24)
                },
                firstRouteId = firstProbe.routeId,
                middleRouteId = middleProbe.routeId,
                tailRouteId = tailProbe.routeId,
                previousRouteId = previousProbe.routeId,
                nextRouteId = nextProbe.routeId,
                firstItems = firstProbe.itemCount,
                middleItems = middleProbe.itemCount,
                tailItems = tailProbe.itemCount,
                previousItems = previousProbe.itemCount,
                nextItems = nextProbe.itemCount,
                ok = error.isBlank(),
                error = error,
                sampleUrl = listOf(firstProbe.sampleUrl, middleProbe.sampleUrl, tailProbe.sampleUrl)
                    .firstOrNull { it.isNotBlank() }
                    .orEmpty()
            )
        }.getOrElse { throwable ->
            MediaFlowBookProbe.empty(
                query = query,
                title = title,
                sourceName = sourceName,
                routeId = routeId,
                error = "flow_exception:${throwable.javaClass.simpleName}"
            )
        }
    }

    private fun selectedFlowIndices(total: Int, seed: String, maxChapters: Int): List<Int> {
        if (total <= 0) return emptyList()
        val selected = linkedSetOf(0, total / 2, total - 1)
        val random = Random(seed.hashCode())
        while (selected.size < maxChapters && selected.size < total) {
            selected += random.nextInt(total)
        }
        return selected.sorted()
    }

    private fun navigationIndex(total: Int): Int? {
        if (total < 3) return null
        return (total / 2).coerceIn(1, total - 2)
    }

    private fun probeFlowChapter(kind: ReaderMediaKind, chapter: MediaChapterItem): MediaFlowChapterProbe {
        return runCatching {
            when (kind) {
                ReaderMediaKind.COMIC -> {
                    val pages = MediaSourceRepository.comicPages(chapter.routeId)
                    MediaFlowChapterProbe(
                        routeId = chapter.routeId,
                        index = chapter.index,
                        title = chapter.title,
                        itemCount = pages.size,
                        sampleUrl = pages.firstOrNull()?.url.orEmpty()
                    )
                }
                ReaderMediaKind.AUDIO -> {
                    val request = MediaSourceRepository.audioRequest(chapter.routeId)
                    MediaFlowChapterProbe(
                        routeId = chapter.routeId,
                        index = chapter.index,
                        title = chapter.title,
                        itemCount = if (request?.url.isNullOrBlank()) 0 else 1,
                        sampleUrl = request?.url.orEmpty()
                    )
                }
                ReaderMediaKind.NOVEL -> MediaFlowChapterProbe.empty()
            }
        }.getOrElse {
            MediaFlowChapterProbe(
                routeId = chapter.routeId,
                index = chapter.index,
                title = chapter.title,
                itemCount = 0,
                sampleUrl = ""
            )
        }
    }

    private fun buildFlowSummary(result: MediaFlowProbeResult): String {
        val ok = result.rows.count { it.ok }
        val rows = result.rows.joinToString("\n") { row ->
            "- ${row.title.traceToken(22)} / ${row.sourceName.traceToken(16)} c${row.chapterCount} " +
                "f${row.firstItems} m${row.middleItems} t${row.tailItems} p${row.previousItems} n${row.nextItems} " +
                if (row.ok) "通过" else row.error
        }
        return "${result.kind.displayName} $ok/${result.rows.size} 用时${result.durationMs}ms\n$rows"
    }

    private fun traceFlowResult(result: MediaFlowProbeResult) {
        val ok = result.rows.count { it.ok }
        AiBridgeTrace.state(
            "media_flow_summary",
            result.kind.seedKey,
            AiBridgeTrace.fields(
                "books" to result.rows.size,
                "ok" to ok,
                "durationMs" to result.durationMs
            )
        )
        AiBridgeTrace.event(
            "media_flow_summary",
            result.kind.seedKey,
            AiBridgeTrace.fields(
                "books" to result.rows.size,
                "ok" to ok,
                "durationMs" to result.durationMs
            )
        )
    }

    private fun traceFlowRow(kind: ReaderMediaKind, index: Int, row: MediaFlowBookProbe) {
        val value = AiBridgeTrace.fields(
            "query" to row.query,
            "title" to row.title,
            "source" to row.sourceName,
            "route" to row.routeId,
            "chapters" to row.chapterCount,
            "checked" to row.checkedChapters,
            "firstRoute" to row.firstRouteId,
            "middleRoute" to row.middleRouteId,
            "tailRoute" to row.tailRouteId,
            "previousRoute" to row.previousRouteId,
            "nextRoute" to row.nextRouteId,
            "first" to row.firstItems,
            "middle" to row.middleItems,
            "tail" to row.tailItems,
            "previous" to row.previousItems,
            "next" to row.nextItems,
            "ok" to row.ok,
            "error" to row.error.ifBlank { "-" },
            "sample" to row.sampleUrl
        )
        val key = "${kind.seedKey}:${row.query}:$index".traceToken()
        AiBridgeTrace.state("media_flow_row", key, value)
        AiBridgeTrace.event("media_flow_row", key, value)
    }

    private fun readQueries(): List<String> {
        val raw = intent.getStringExtra(EXTRA_QUERIES)
            .orEmpty()
            .ifBlank { intent.getStringExtra(EXTRA_QUERY).orEmpty() }
        return raw
            .split('|', ',', '，', '\n')
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private fun startH5Probe() {
        if (!BuildConfig.DEBUG) {
            statusView = TextView(this).apply {
                text = "仅 debug 包可用"
                textSize = 18f
                setPadding(32, 96, 32, 32)
            }
            setContentView(statusView)
            return
        }
        val initialUrl = intent.getStringExtra(EXTRA_URL).orEmpty()
        val initialJs = intent.getStringExtra(EXTRA_JS).orEmpty().ifBlank { DEFAULT_H5_SNAPSHOT_JS }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 48, 18, 18)
        }
        val title = TextView(this).apply {
            text = "媒体 H5 探测"
            textSize = 18f
        }
        val urlInput = EditText(this).apply {
            hint = "输入源站或章节 URL"
            setSingleLine(true)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setText(initialUrl)
        }
        val jsInput = EditText(this).apply {
            hint = "输入要在当前页面执行的 JS"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            maxLines = 8
            setText(initialJs)
        }
        val output = TextView(this).apply {
            textSize = 12f
            text = "等待加载"
            setPadding(0, 8, 0, 8)
        }
        val outputScroll = ScrollView(this).apply {
            addView(
                output,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        val webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            webChromeClient = WebChromeClient()
        }
        fun traceOutput(stage: String, text: String) {
            val value = text.take(MAX_H5_OUTPUT_CHARS)
            output.text = "$stage\n$value"
            AiBridgeTrace.event(
                "media_h5_probe",
                stage.traceToken(),
                AiBridgeTrace.fields(
                    "url" to webView.url.orEmpty(),
                    "text" to value.traceToken(1_500)
                )
            )
            AiBridgeTrace.state(
                "media_h5_probe",
                stage.traceToken(),
                AiBridgeTrace.fields(
                    "url" to webView.url.orEmpty(),
                    "text" to value.traceToken(1_500)
                )
            )
        }
        fun evaluate(js: String, stage: String = "js") {
            if (js.isBlank()) {
                traceOutput(stage, "JS 为空")
                return
            }
            webView.evaluateJavascript(wrapProbeJs(js), ValueCallback { value ->
                traceOutput(stage, value.orEmpty())
            })
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                traceOutput("page_finished", "url=$url title=${view.title.orEmpty()}")
                if (intent.getStringExtra(EXTRA_JS) != null || readBooleanExtra(EXTRA_AUTO_SNAPSHOT, true)) {
                    evaluate(jsInput.text.toString(), "auto_js")
                }
            }
        }
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        buttonRow.addView(Button(this).apply {
            text = "加载"
            setOnClickListener {
                val url = urlInput.text.toString().trim()
                if (url.isBlank()) {
                    traceOutput("load", "URL 为空")
                } else {
                    traceOutput("load", url)
                    webView.loadUrl(url)
                }
            }
        })
        buttonRow.addView(Button(this).apply {
            text = "执行 JS"
            setOnClickListener { evaluate(jsInput.text.toString(), "manual_js") }
        })
        buttonRow.addView(Button(this).apply {
            text = "快照"
            setOnClickListener { evaluate(DEFAULT_H5_SNAPSHOT_JS, "snapshot") }
        })
        root.addView(title)
        root.addView(urlInput)
        root.addView(buttonRow)
        root.addView(jsInput)
        root.addView(
            outputScroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                resources.displayMetrics.density.let { (180 * it).toInt() }
            )
        )
        root.addView(webView)
        setContentView(root)
        if (initialUrl.isBlank()) {
            webView.loadDataWithBaseURL(
                "https://reader.local/",
                DEFAULT_H5_HOME,
                "text/html",
                "UTF-8",
                null
            )
        } else {
            webView.loadUrl(initialUrl)
        }
    }

    private fun wrapProbeJs(js: String): String {
        return """
            (function(){
              try {
                var __probeResult = (function(){ $js })();
                if (typeof __probeResult === 'undefined') return 'undefined';
                if (typeof __probeResult === 'string') return __probeResult;
                return JSON.stringify(__probeResult);
              } catch (e) {
                return 'ERROR: ' + (e && e.stack ? e.stack : e);
              }
            })();
        """.trimIndent()
    }

    private fun runProbe(
        kind: ReaderMediaKind,
        queries: List<String>,
        maxBooks: Int,
        maxSources: Int
    ) {
        AiBridgeTrace.event(
            "media_tail_probe_started",
            kind.seedKey,
            AiBridgeTrace.fields(
                "queries" to queries.joinToString("+") { it.traceToken() },
                "maxBooks" to maxBooks,
                "maxSources" to maxSources
            )
        )
        val summaries = ArrayList<String>()
        queries.forEachIndexed { index, query ->
            postStatus("${kind.displayName} ${index + 1}/${queries.size}: $query")
            val result = MediaSourceRepository.tailProbe(kind, query, maxBooks, maxSources)
            traceResult(result)
            summaries += buildSummary(result)
        }
        val summaryText = summaries.joinToString("\n")
        AiBridgeTrace.event("media_tail_probe_finished", kind.seedKey, summaryText.traceToken(180))
        postStatus("媒体探测完成\n$summaryText")
    }

    private fun buildSummary(result: MediaTailProbeResult): String {
        val readyBooks = result.books.count { it.usableSources >= 2 }
        val bookLines = result.books.joinToString("\n") { book ->
            val usable = book.sourceProbes
                .filter { it.ok }
                .joinToString(",") { it.sourceName.traceToken(24) }
                .ifBlank { "无" }
            val failed = book.sourceProbes
                .filterNot { it.ok }
                .take(6)
                .joinToString(",") { "${it.sourceName.traceToken(16)}:${it.error}" }
                .ifBlank { "无" }
            "- ${book.title.traceToken(24)} ${book.usableSources}/${book.probedSources} 可用[$usable] 失败[$failed]"
        }
        return "${result.query}: $readyBooks/${result.books.size}\n$bookLines"
    }

    private fun traceResult(result: MediaTailProbeResult) {
        AiBridgeTrace.event(
            "media_tail_probe_query",
            "${result.kind.seedKey}:${result.query}",
            AiBridgeTrace.fields(
                "books" to result.books.size,
                "durationMs" to result.durationMs
            )
        )
        result.books.forEachIndexed { bookIndex, book ->
            AiBridgeTrace.event(
                "media_tail_probe_item",
                "${result.kind.seedKey}:${result.query}:${book.title}".traceToken(),
                AiBridgeTrace.fields(
                    "i" to bookIndex,
                    "title" to book.title,
                    "latest" to book.latest,
                    "displaySrc" to book.displayedSourceCount,
                    "probeSrc" to book.probedSources,
                    "usableSrc" to book.usableSources,
                    "ok" to (book.usableSources >= 2)
                )
            )
            AiBridgeTrace.state(
                "media_tail_probe_item",
                "${result.kind.seedKey}:${result.query}:${book.title}".traceToken(),
                AiBridgeTrace.fields(
                    "displaySrc" to book.displayedSourceCount,
                    "probeSrc" to book.probedSources,
                    "usableSrc" to book.usableSources,
                    "ok" to (book.usableSources >= 2)
                )
            )
            AiBridgeTrace.state(
                "media_tail_probe_sources",
                "${result.kind.seedKey}:${result.query}:${book.title}".traceToken(),
                book.sourceProbes.joinToString(";") { source ->
                    listOf(
                        source.sourceName.traceToken(24),
                        if (source.ok) "ok" else "bad",
                        "c${source.chapterCount}",
                        "i${source.itemCount}",
                        "o${source.offsetFromLatest}",
                        source.error.ifBlank { "-" }.traceToken(32)
                    ).joinToString(":")
                }.traceToken(1_000)
            )
            book.sourceProbes.forEachIndexed { sourceIndex, source ->
                AiBridgeTrace.event(
                    "media_tail_probe_source",
                    "${result.kind.seedKey}:${result.query}:${book.title}".traceToken(),
                    AiBridgeTrace.fields(
                        "i" to bookIndex,
                        "s" to sourceIndex,
                        "source" to source.sourceName,
                        "title" to source.title,
                        "chapters" to source.chapterCount,
                        "tail" to source.tailTitle,
                        "tailOffset" to source.offsetFromLatest,
                        "items" to source.itemCount,
                        "sample" to source.sampleUrl,
                        "ok" to source.ok,
                        "error" to source.error
                    )
                )
            }
        }
    }

    private fun postStatus(text: String) {
        runOnUiThread { statusView.text = text }
    }

    private fun readIntExtra(name: String, defaultValue: Int): Int {
        val stringValue = runCatching { intent.getStringExtra(name) }.getOrNull()
        if (!stringValue.isNullOrBlank()) return stringValue.toIntOrNull() ?: defaultValue
        return runCatching { intent.getIntExtra(name, defaultValue) }.getOrDefault(defaultValue)
    }

    private fun readBooleanExtra(name: String, defaultValue: Boolean): Boolean {
        val stringValue = runCatching { intent.getStringExtra(name) }.getOrNull()
        if (!stringValue.isNullOrBlank()) {
            return stringValue.equals("true", ignoreCase = true) || stringValue == "1"
        }
        return runCatching { intent.getBooleanExtra(name, defaultValue) }.getOrDefault(defaultValue)
    }

    private fun String.traceToken(limit: Int = 80): String {
        return replace(Regex("""[\s=:/\\#]+"""), "_").take(limit)
    }

    private data class MediaFlowProbeResult(
        val kind: ReaderMediaKind,
        val rows: List<MediaFlowBookProbe>,
        val durationMs: Long
    )

    private data class MediaFlowBookProbe(
        val query: String,
        val title: String,
        val sourceName: String,
        val routeId: String,
        val chapterCount: Int,
        val checkedChapters: String,
        val firstRouteId: String,
        val middleRouteId: String,
        val tailRouteId: String,
        val previousRouteId: String,
        val nextRouteId: String,
        val firstItems: Int,
        val middleItems: Int,
        val tailItems: Int,
        val previousItems: Int,
        val nextItems: Int,
        val ok: Boolean,
        val error: String,
        val sampleUrl: String
    ) {
        companion object {
            fun empty(
                query: String,
                title: String,
                sourceName: String,
                routeId: String,
                error: String
            ): MediaFlowBookProbe {
                return MediaFlowBookProbe(
                    query = query,
                    title = title,
                    sourceName = sourceName,
                    routeId = routeId,
                    chapterCount = 0,
                    checkedChapters = "",
                    firstRouteId = "",
                    middleRouteId = "",
                    tailRouteId = "",
                    previousRouteId = "",
                    nextRouteId = "",
                    firstItems = 0,
                    middleItems = 0,
                    tailItems = 0,
                    previousItems = 0,
                    nextItems = 0,
                    ok = false,
                    error = error,
                    sampleUrl = ""
                )
            }
        }
    }

    private data class MediaFlowChapterProbe(
        val routeId: String,
        val index: Int,
        val title: String,
        val itemCount: Int,
        val sampleUrl: String
    ) {
        companion object {
            fun empty(): MediaFlowChapterProbe {
                return MediaFlowChapterProbe(
                    routeId = "",
                    index = -1,
                    title = "",
                    itemCount = 0,
                    sampleUrl = ""
                )
            }
        }
    }

    companion object {
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_MODE = "mode"
        private const val EXTRA_QUERY = "query"
        private const val EXTRA_QUERIES = "queries"
        private const val EXTRA_MAX_BOOKS = "maxBooks"
        private const val EXTRA_MAX_SOURCES = "maxSources"
        private const val EXTRA_MAX_CHAPTERS = "maxChapters"
        private const val EXTRA_BUILT_IN_ONLY = "builtInOnly"
        private const val EXTRA_URL = "url"
        private const val EXTRA_JS = "js"
        private const val EXTRA_AUTO_SNAPSHOT = "autoSnapshot"
        private const val EXTRA_CHAPTER_ROUTE_ID = "chapter_route_id"
        private const val EXTRA_ROUTE_ID = "route_id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_FORCE_START = "force_start"
        private const val MAX_H5_OUTPUT_CHARS = 4_000
        private const val DEFAULT_H5_HOME = """
            <!doctype html>
            <html>
            <head>
              <meta name="viewport" content="width=device-width,initial-scale=1">
              <style>
                body{font-family:sans-serif;padding:16px;line-height:1.5}
                code{word-break:break-all}
              </style>
            </head>
            <body>
              <h3>媒体 H5 探测</h3>
              <p>在上方输入 URL 加载源站，再输入 JS 检查 DOM、音频节点、全局变量或页面脚本结果。</p>
              <p>默认快照会返回 <code>location.href</code>、<code>document.title</code>、音频/视频节点和页面 HTML 片段。</p>
            </body>
            </html>
        """
        private const val DEFAULT_H5_SNAPSHOT_JS = """
            return {
              href: location.href,
              title: document.title,
              readyState: document.readyState,
              audio: Array.from(document.querySelectorAll('audio,source,video')).map(function(e){
                return {
                  tag: e.tagName,
                  src: e.currentSrc || e.src || e.getAttribute('src') || '',
                  type: e.getAttribute('type') || ''
                };
              }),
              scripts: Array.from(document.scripts).slice(0, 20).map(function(s){ return s.src || s.textContent.slice(0, 120); }),
              html: document.documentElement.outerHTML.slice(0, 3000)
            };
        """
    }
}
