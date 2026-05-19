package com.ldp.reader.sourceengine.model

import com.ldp.reader.sourceengine.EngineFailure

data class SourceImportFailure(
    val index: Int,
    val failure: EngineFailure
)
