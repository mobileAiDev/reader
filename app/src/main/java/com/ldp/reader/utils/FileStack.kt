package com.ldp.reader.utils

import java.io.File

/**
 * Created by ldp on 17-5-28.
 */
class FileStack {
    private var node: Node? = null
    private var count = 0

    fun push(fileSnapshot: FileSnapshot?) {
        if (fileSnapshot == null) return
        val fileNode = Node()
        fileNode.fileSnapshot = fileSnapshot
        fileNode.next = node
        node = fileNode
        ++count
    }

    fun pop(): FileSnapshot? {
        val fileNode = node ?: return null
        val fileSnapshot = fileNode.fileSnapshot
        node = fileNode.next
        --count
        return fileSnapshot
    }

    fun getSize(): Int {
        return count
    }

    private class Node {
        var fileSnapshot: FileSnapshot? = null
        var next: Node? = null
    }

    class FileSnapshot {
        @JvmField
        var filePath: String? = null

        @JvmField
        var files: MutableList<File>? = null

        @JvmField
        var scrollOffset = 0
    }
}
