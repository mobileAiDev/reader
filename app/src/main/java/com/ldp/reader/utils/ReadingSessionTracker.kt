package com.ldp.reader.utils

/**
 * Records only the interval during which a reader Activity is in the foreground.
 * `onPause` and `onStop` may both be delivered for one transition; committing clears the
 * active session first so the second callback is deliberately a no-op.
 */
class ReadingSessionTracker(
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val recorder: (String?, Long, Long) -> Unit = { bookId, startMs, endMs ->
        ReadingStatsUtils.recordReading(bookId, startMs, endMs)
    }
) {
    private var activeBookId: String? = null
    private var activeStartMs: Long? = null

    @Synchronized
    fun onResume(bookId: String?) {
        if (activeStartMs != null) return
        activeBookId = bookId
        activeStartMs = nowMillis()
    }

    fun onPause() {
        commit()
    }

    fun onStop() {
        commit()
    }

    @Synchronized
    private fun commit() {
        val startMs = activeStartMs ?: return
        val bookId = activeBookId
        activeStartMs = null
        activeBookId = null
        recorder(bookId, startMs, nowMillis())
    }
}
