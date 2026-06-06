package com.ldp.reader.utils

import android.content.Context
import android.util.Log
import java.io.File

object LegacyXmlPrefsCleaner {
    private const val TAG = "LegacyXmlPrefsCleaner"
    private val mediaPrefNames = listOf(
        "reader_media_shelf",
        "reader_audio_now_playing",
        "reader_audio_progress"
    )

    fun clearMediaPrefs(context: Context) {
        val dir = File(context.applicationInfo.dataDir, "shared_prefs")
        if (!dir.exists() || !dir.isDirectory) return
        mediaPrefNames
            .flatMap { name -> listOf("$name.xml", "$name.xml.bak") }
            .forEach { fileName ->
                val file = File(dir, fileName)
                if (!file.exists()) return@forEach
                val bytes = file.length()
                val deleted = file.delete()
                Log.i(TAG, "delete legacy media xml name=$fileName bytes=$bytes deleted=$deleted")
            }
    }
}
