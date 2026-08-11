package com.ldp.reader.ui.activity

import com.ldp.reader.document.epub.EpubCatalogEntry

internal object EpubReaderUiPolicy {
    fun readingPercent(scrollY: Int, maxScrollY: Int): Int {
        if (maxScrollY <= 0) return 100
        return ((scrollY.coerceIn(0, maxScrollY).toLong() * 100L) / maxScrollY).toInt()
    }

    fun catalogJumpScript(sectionIndex: Int, targetId: String? = null): String {
        val sectionNumber = sectionIndex + 1
        val resolvedTargetId = targetId ?: "epub-section-$sectionNumber"
        return """
            (function() {
              var target = document.getElementById('$resolvedTargetId');
              if (!target) return false;
              target.scrollIntoView();
              return true;
            })();
        """.trimIndent()
    }

    fun currentSectionScript(): String {
        return """
            (function() {
              var sections = document.querySelectorAll('.epub-section');
              if (!sections.length) return -1;
              var probeY = window.innerHeight * 0.25;
              var current = 0;
              for (var index = 0; index < sections.length; index += 1) {
                if (sections[index].getBoundingClientRect().top <= probeY) current = index;
                else break;
              }
              return current;
            })();
        """.trimIndent()
    }

    fun checkedCatalogEntryIndex(
        catalog: List<EpubCatalogEntry>,
        currentSectionIndex: Int
    ): Int {
        if (currentSectionIndex < 0) return -1
        val exact = catalog.indexOfFirst { it.sectionIndex == currentSectionIndex }
        if (exact >= 0) return exact
        return catalog.indexOfLast { it.sectionIndex < currentSectionIndex }
    }

    fun shouldToggleControls(
        hasReadableDocument: Boolean,
        insideCenter: Boolean,
        targetsLink: Boolean
    ): Boolean {
        return hasReadableDocument && insideCenter && !targetsLink
    }
}
