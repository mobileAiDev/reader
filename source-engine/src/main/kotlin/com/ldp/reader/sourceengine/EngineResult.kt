package com.ldp.reader.sourceengine

sealed class EngineResult<out T> {
    data class Success<T>(val value: T) : EngineResult<T>()
    data class Failure(val failure: EngineFailure) : EngineResult<Nothing>()
}

sealed class EngineFailure {
    data class ParseError(val message: String) : EngineFailure()
    data class ContractViolation(val message: String) : EngineFailure()
    data class NetworkError(val message: String) : EngineFailure()
    data class RuleError(val message: String) : EngineFailure()
}
