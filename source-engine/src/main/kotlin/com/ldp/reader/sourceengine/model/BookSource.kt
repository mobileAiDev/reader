package com.ldp.reader.sourceengine.model

import com.ldp.reader.sourceengine.legado.LegadoRuleSet

data class BookSource @JvmOverloads constructor(
    val sourceName: String,
    val sourceUrl: String,
    val sourceType: Int = BookSourceType.TEXT,
    val sourceGroup: String?,
    val sourceComment: String?,
    val enabled: Boolean,
    val headers: Map<String, String>,
    val searchUrl: String?,
    val ruleSearch: LegadoRuleSet,
    val ruleBookInfo: LegadoRuleSet,
    val ruleToc: LegadoRuleSet,
    val ruleContent: LegadoRuleSet,
    val diagnostics: List<SourceDiagnostic>,
    val jsLib: String = ""
)
