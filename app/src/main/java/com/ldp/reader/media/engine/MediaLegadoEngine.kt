package com.ldp.reader.media.engine

import com.ldp.reader.media.MediaEngineResult
import com.ldp.reader.media.legado.MediaHttpFetcher
import com.ldp.reader.media.legado.LegadoMediaRuleRuntime
import com.ldp.reader.media.MediaSourceDefinition
import com.ldp.reader.media.MediaSourceBook
import com.ldp.reader.media.MediaSourceBookDetail
import com.ldp.reader.media.MediaSourceChapter
import com.ldp.reader.media.MediaSourceSearchReport

internal class MediaLegadoEngine(fetcher: MediaHttpFetcher) {
    private val mediaRuntime = LegadoMediaRuleRuntime(fetcher = fetcher)

    fun search(source: MediaSourceDefinition, keyword: String): MediaEngineResult<MediaSourceSearchReport> {
        return mediaRuntime.search(source, keyword)
    }

    fun detail(book: MediaSourceBook): MediaEngineResult<MediaSourceBookDetail> {
        return mediaRuntime.detail(book)
    }

    fun chapters(detail: MediaSourceBookDetail): MediaEngineResult<List<MediaSourceChapter>> {
        return mediaRuntime.chapters(detail)
    }

    fun rawContent(chapter: MediaSourceChapter): MediaEngineResult<String> {
        return mediaRuntime.rawContent(chapter)
    }
}
