package com.ldp.reader.media

sealed class MediaEngineResult<out T> {
    data class Success<T>(val value: T) : MediaEngineResult<T>()
    data class Failure(val failure: MediaEngineFailure) : MediaEngineResult<Nothing>()
}

sealed class MediaEngineFailure {
    data class ParseError(val message: String) : MediaEngineFailure()
    data class ContractViolation(val message: String) : MediaEngineFailure()
    data class NetworkError(val message: String) : MediaEngineFailure()
    data class RuleError(val message: String) : MediaEngineFailure()
}

object MediaSourceType {
    const val TEXT = 0
    const val AUDIO = 1
    const val COMIC = 2
    const val FILE = 3
}

data class MediaLegadoRuleSet(
    val groupName: String,
    val rules: Map<String, String>
) {
    val isEmpty: Boolean
        get() = rules.isEmpty()
}

data class MediaSourceDefinition @JvmOverloads constructor(
    val sourceName: String,
    val sourceUrl: String,
    val sourceType: Int = MediaSourceType.TEXT,
    val sourceGroup: String?,
    val sourceComment: String?,
    val enabled: Boolean,
    val headers: Map<String, String>,
    val searchUrl: String?,
    val ruleSearch: MediaLegadoRuleSet,
    val ruleBookInfo: MediaLegadoRuleSet,
    val ruleToc: MediaLegadoRuleSet,
    val ruleContent: MediaLegadoRuleSet,
    val diagnostics: List<MediaSourceDiagnostic>,
    val jsLib: String = "",
    val loginUrl: String = ""
)

data class MediaSourceBook(
    val source: MediaSourceDefinition,
    val name: String,
    val author: String,
    val bookUrl: String,
    val coverUrl: String,
    val intro: String,
    val kind: String,
    val lastChapter: String
)

data class MediaSourceBookDetail(
    val book: MediaSourceBook,
    val name: String,
    val author: String,
    val coverUrl: String,
    val intro: String,
    val kind: String,
    val lastChapter: String,
    val tocUrl: String,
    val runtimeVariables: Map<String, String> = emptyMap()
)

data class MediaSourceChapter(
    val source: MediaSourceDefinition,
    val book: MediaSourceBook,
    val index: Int,
    val name: String,
    val chapterUrl: String,
    val runtimeVariables: Map<String, String> = emptyMap()
)

data class MediaSourceSearchReport(
    val books: List<MediaSourceBook>,
    val attempts: List<MediaSourceSearchAttempt>
)

data class MediaSourceSearchAttempt(
    val sourceName: String,
    val success: Boolean,
    val resultCount: Int,
    val message: String
)

data class MediaSourceImportReport(
    val sources: List<MediaSourceDefinition>,
    val rejectedSources: List<MediaSourceImportFailure>
) {
    val diagnosticCount: Int
        get() = sources.sumOf { it.diagnostics.size }
}

data class MediaSourceImportFailure(
    val index: Int,
    val failure: MediaEngineFailure
)

data class MediaSourceDiagnostic(
    val severity: MediaDiagnosticSeverity,
    val code: String,
    val path: String,
    val message: String
)

enum class MediaDiagnosticSeverity {
    INFO,
    WARNING,
    ERROR
}

data class MediaImportSummary(
    val acceptedCount: Int,
    val importedCount: Int,
    val duplicateCount: Int,
    val rejectedCount: Int,
    val audioCount: Int,
    val comicCount: Int
)
