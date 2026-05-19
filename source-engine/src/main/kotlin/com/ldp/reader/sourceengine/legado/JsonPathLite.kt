package com.ldp.reader.sourceengine.legado

import com.google.gson.JsonElement
import com.google.gson.JsonObject

class JsonPathLite {
    fun select(root: JsonElement, path: String): List<JsonElement> {
        val cleanPath = path.trim()
        if (cleanPath == "$") return listOf(root)
        if (!cleanPath.startsWith("$.")) return emptyList()

        var current = listOf(root)
        tokenize(cleanPath.substring(2)).forEach { token ->
            current = current.flatMap { element -> step(element, token) }
            if (current.isEmpty()) return emptyList()
        }
        return current
    }

    fun readString(root: JsonElement, path: String): String {
        return select(root, path).firstOrNull()?.toPlainString().orEmpty()
    }

    private fun step(element: JsonElement, token: PathToken): List<JsonElement> {
        if (element.isJsonArray) {
            if (token.name == null && token.index == null) {
                return element.asJsonArray.toList()
            }
            return element.asJsonArray.flatMap { step(it, token.copy(recursive = false)) }
        }
        if (!element.isJsonObject) return emptyList()

        val obj = element.asJsonObject
        val values = if (token.recursive) {
            recursiveValues(obj, token.name.orEmpty())
        } else {
            token.name?.let { name ->
                obj.get(name)?.let { listOf(it) } ?: emptyList()
            } ?: emptyList()
        }
        if (token.wildcard) {
            return values.flatMap { value ->
                if (value.isJsonArray) value.asJsonArray.toList() else listOf(value)
            }
        }
        token.index ?: return values
        return values.mapNotNull { value ->
            if (!value.isJsonArray) {
                null
            } else {
                val array = value.asJsonArray
                val actualIndex = if (token.index < 0) array.size() + token.index else token.index
                if (actualIndex in 0 until array.size()) array.get(actualIndex) else null
            }
        }
    }

    private fun recursiveValues(obj: JsonObject, name: String): List<JsonElement> {
        val values = ArrayList<JsonElement>()
        obj.entrySet().forEach { (key, value) ->
            if (key == name) values.add(value)
            if (value.isJsonObject) values.addAll(recursiveValues(value.asJsonObject, name))
            if (value.isJsonArray) {
                value.asJsonArray.forEach { item ->
                    if (item.isJsonObject) values.addAll(recursiveValues(item.asJsonObject, name))
                }
            }
        }
        return values
    }

    private fun tokenize(path: String): List<PathToken> {
        val tokens = ArrayList<PathToken>()
        var cursor = 0
        while (cursor < path.length) {
            val recursive = path.startsWith(".", cursor)
            if (recursive) cursor += 1
            val nextDot = path.indexOf('.', cursor).let { if (it < 0) path.length else it }
            val raw = path.substring(cursor, nextDot)
            tokens.add(parseToken(raw, recursive))
            cursor = nextDot + 1
        }
        return tokens.filter { it.name?.isNotBlank() == true || it.index != null || it.wildcard }
    }

    private fun parseToken(raw: String, recursive: Boolean): PathToken {
        val wildcardMatch = Regex("""^([A-Za-z0-9_\-]+)?\[\*]$""").matchEntire(raw)
        if (wildcardMatch != null) {
            return PathToken(
                name = wildcardMatch.groupValues[1].ifBlank { null },
                index = null,
                wildcard = true,
                recursive = recursive
            )
        }
        val indexMatch = Regex("""^([A-Za-z0-9_\-]+)?\[(\-?\d+)]$""").matchEntire(raw)
        if (indexMatch != null) {
            return PathToken(
                name = indexMatch.groupValues[1].ifBlank { null },
                index = indexMatch.groupValues[2].toInt(),
                wildcard = false,
                recursive = recursive
            )
        }
        return PathToken(raw, null, wildcard = false, recursive = recursive)
    }

    private fun JsonElement.toPlainString(): String {
        if (isJsonNull) return ""
        if (isJsonPrimitive) return asJsonPrimitive.asString
        return toString()
    }

    private data class PathToken(
        val name: String?,
        val index: Int?,
        val wildcard: Boolean,
        val recursive: Boolean
    )
}
