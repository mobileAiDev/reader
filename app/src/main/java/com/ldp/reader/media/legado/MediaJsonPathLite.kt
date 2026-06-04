package com.ldp.reader.media.legado

import com.google.gson.JsonElement
import com.google.gson.JsonObject

class MediaJsonPathLite {
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
            if (token.existsField != null) {
                return element.asJsonArray.filter { item -> item.hasNonBlankField(token.existsField) }
            }
            if (token.name == null && token.index == null) {
                return element.asJsonArray.toList()
            }
            return element.asJsonArray.flatMap { step(it, token.copy(recursive = false)) }
        }
        if (!element.isJsonObject) return emptyList()

        val obj = element.asJsonObject
        if (token.wildcard && token.name == null) {
            return obj.entrySet().map { it.value }
        }
        val values = if (token.recursive) {
            recursiveValues(obj, token.name.orEmpty())
        } else {
            token.name?.let { name ->
                obj.get(name)?.let { listOf(it) } ?: emptyList()
            } ?: emptyList()
        }
        if (token.existsField != null) {
            return values.flatMap { value ->
                when {
                    value.isJsonArray -> value.asJsonArray.filter { item ->
                        item.hasNonBlankField(token.existsField)
                    }
                    value.hasNonBlankField(token.existsField) -> listOf(value)
                    else -> emptyList()
                }
            }
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
            val nextDot = nextPathSeparator(path, cursor)
            val raw = path.substring(cursor, nextDot)
            tokens.add(parseToken(raw, recursive))
            cursor = nextDot + 1
        }
        return tokens.filter {
            it.name?.isNotBlank() == true ||
                it.index != null ||
                it.wildcard ||
                it.existsField != null
        }
    }

    private fun parseToken(raw: String, recursive: Boolean): PathToken {
        if (raw == "*") {
            return PathToken(
                name = null,
                index = null,
                wildcard = true,
                recursive = recursive,
                existsField = null
            )
        }
        val existsMatch = Regex("""^([A-Za-z0-9_\-]+)?\[\?\(@\.([A-Za-z0-9_\-]+)\)]$""")
            .matchEntire(raw)
        if (existsMatch != null) {
            return PathToken(
                name = existsMatch.groupValues[1].ifBlank { null },
                index = null,
                wildcard = false,
                recursive = recursive,
                existsField = existsMatch.groupValues[2]
            )
        }
        val wildcardMatch = Regex("""^([A-Za-z0-9_\-]+)?\[\*]$""").matchEntire(raw)
        if (wildcardMatch != null) {
            return PathToken(
                name = wildcardMatch.groupValues[1].ifBlank { null },
                index = null,
                wildcard = true,
                recursive = recursive,
                existsField = null
            )
        }
        val indexMatch = Regex("""^([A-Za-z0-9_\-]+)?\[(\-?\d+)]$""").matchEntire(raw)
        if (indexMatch != null) {
            return PathToken(
                name = indexMatch.groupValues[1].ifBlank { null },
                index = indexMatch.groupValues[2].toInt(),
                wildcard = false,
                recursive = recursive,
                existsField = null
            )
        }
        return PathToken(raw, null, wildcard = false, recursive = recursive, existsField = null)
    }

    private fun nextPathSeparator(path: String, start: Int): Int {
        var bracketDepth = 0
        var cursor = start
        while (cursor < path.length) {
            when (path[cursor]) {
                '[' -> bracketDepth += 1
                ']' -> bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                '.' -> if (bracketDepth == 0) return cursor
            }
            cursor += 1
        }
        return path.length
    }

    private fun JsonElement.toPlainString(): String {
        if (isJsonNull) return ""
        if (isJsonPrimitive) return asJsonPrimitive.asString
        return toString()
    }

    private fun JsonElement.hasNonBlankField(name: String): Boolean {
        if (!isJsonObject) return false
        val value = asJsonObject.get(name) ?: return false
        if (value.isJsonNull) return false
        if (!value.isJsonPrimitive) return true
        return value.asJsonPrimitive.asString.isNotBlank()
    }

    private data class PathToken(
        val name: String?,
        val index: Int?,
        val wildcard: Boolean,
        val recursive: Boolean,
        val existsField: String?
    )
}
