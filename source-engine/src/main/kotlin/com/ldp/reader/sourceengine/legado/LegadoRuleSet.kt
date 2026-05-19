package com.ldp.reader.sourceengine.legado

data class LegadoRuleSet(
    val groupName: String,
    val rules: Map<String, String>
) {
    val isEmpty: Boolean
        get() = rules.isEmpty()
}
