package com.ldp.reader.document.epub

object EpubUserMessage {
    fun forParseError(error: EpubParseError): String {
        return when (error) {
            EpubParseError.SPINE_EMPTY -> "文件中没有可阅读内容"
            EpubParseError.TOO_MANY_ENTRIES,
            EpubParseError.ENTRY_TOO_LARGE,
            EpubParseError.ARCHIVE_TOO_LARGE,
            EpubParseError.RENDERED_CONTENT_TOO_LARGE,
            EpubParseError.RESOURCE_BUDGET_EXCEEDED,
            EpubParseError.IMAGE_DIMENSIONS_TOO_LARGE ->
                "文件过大或内容过于复杂，暂时无法打开"
            EpubParseError.INLINE_DATA_IMAGE_NOT_ALLOWED -> "文件包含暂不支持的内容"
            EpubParseError.INVALID_ARCHIVE,
            EpubParseError.CONTAINER_MISSING,
            EpubParseError.PACKAGE_MISSING,
            EpubParseError.PACKAGE_INVALID,
            EpubParseError.UNSAFE_PATH,
            EpubParseError.DUPLICATE_ENTRY,
            EpubParseError.PATH_TOO_LONG,
            EpubParseError.XML_NODE_LIMIT_EXCEEDED,
            EpubParseError.XML_DEPTH_LIMIT_EXCEEDED -> "文件已损坏或内容不完整"
        }
    }
}
