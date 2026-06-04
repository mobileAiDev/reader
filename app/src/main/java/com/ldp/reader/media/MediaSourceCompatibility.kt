package com.ldp.reader.media

import com.ldp.reader.media.MediaSourceDefinition

object MediaSourceCompatibility {
    fun isCompatible(source: MediaSourceDefinition): Boolean {
        return source.enabled
    }
}
