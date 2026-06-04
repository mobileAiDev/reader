package com.ldp.reader.media.legado

import com.ldp.reader.media.MediaRequestParser
import java.util.regex.Pattern

@Suppress("RegExpRedundantEscape")
internal object LegadoMediaHtmlFormatter {
    private val nbspRegex = "(&nbsp;)+".toRegex()
    private val espRegex = "(&ensp;|&emsp;)".toRegex()
    private val noPrintRegex = "(&thinsp;|&zwnj;|&zwj;|\u2009|\u200C|\u200D)".toRegex()
    private val wrapHtmlRegex = "</?(?:div|p|br|hr|h\\d|article|dd|dl)[^>]*>".toRegex()
    private val commentRegex = "<!--[^>]*-->".toRegex()
    private val notImgHtmlRegex = "</?(?!img)[a-zA-Z]+(?=[ >])[^<>]*>".toRegex()
    private val formatImagePattern = Pattern.compile(
        "<img[^>]*\\ssrc\\s*=\\s*['\"]([^'\"{>]*\\{(?:[^{}]|\\{[^}>]+\\})+\\})['\"][^>]*>|" +
            "<img[^>]*\\s(?:data-src|src)\\s*=\\s*['\"]([^'\">]+)['\"][^>]*>|" +
            "<img[^>]*\\sdata-[^=>]*=\\s*['\"]([^'\">]*)['\"][^>]*>",
        Pattern.CASE_INSENSITIVE
    )
    private val indent1Regex = "\\s*\\n+\\s*".toRegex()
    private val indent2Regex = "^[\\n\\s]+".toRegex()
    private val lastRegex = "[\\n\\s]+$".toRegex()

    fun formatKeepImg(html: String?, redirectUrl: String): String {
        html ?: return ""
        val keepImgHtml = format(html, notImgHtmlRegex)
        val matcher = formatImagePattern.matcher(keepImgHtml)
        var appendPos = 0
        val output = StringBuilder()
        while (matcher.find()) {
            var param = ""
            val rawImage = matcher.group(1)?.let { value ->
                val optionIndex = value.indexOfOptionJson()
                if (optionIndex >= 0) {
                    param = "," + value.substring(optionIndex + 1)
                    value.substring(0, optionIndex)
                } else {
                    value
                }
            } ?: matcher.group(2) ?: matcher.group(3).orEmpty()
            output.append(keepImgHtml.substring(appendPos, matcher.start()))
            output.append("<img src=\"")
            output.append(MediaRequestParser.resolveUrl(redirectUrl, rawImage))
            output.append(param)
            output.append("\">")
            appendPos = matcher.end()
        }
        if (appendPos < keepImgHtml.length) {
            output.append(keepImgHtml.substring(appendPos))
        }
        return output.toString()
    }

    private fun format(html: String, otherRegex: Regex): String {
        return html.replace(nbspRegex, " ")
            .replace(espRegex, " ")
            .replace(noPrintRegex, "")
            .replace(wrapHtmlRegex, "\n")
            .replace(commentRegex, "")
            .replace(otherRegex, "")
            .replace(indent1Regex, "\n  ")
            .replace(indent2Regex, "  ")
            .replace(lastRegex, "")
    }

    private fun String.indexOfOptionJson(): Int {
        for (index in indices) {
            if (this[index] != ',') continue
            if (substring(index + 1).trimStart().startsWith("{")) return index
        }
        return -1
    }
}
