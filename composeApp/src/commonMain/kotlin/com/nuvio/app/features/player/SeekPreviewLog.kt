package com.nuvio.app.features.player

/**
 * Debug logger for seek previews.
 *
 * Kermit has no writer installed in this app, so Kermit logs never reach
 * logcat. [seekPreviewLog] writes to the platform log (android.util.Log on
 * Android, print on iOS) AND keeps an in-memory ring buffer
 * ([SeekPreviewDebugLogs]) surfaced in Settings → Playback → SEEK PREVIEWS
 * for copy/paste without adb. Temporary triage scaffolding.
 */
internal fun seekPreviewLog(message: String) {
    SeekPreviewDebugLogs.add(message)
    seekPreviewLogPlatform(message)
}

internal expect fun seekPreviewLogPlatform(message: String)

/** Last-200 in-memory seek-preview log lines for the on-screen viewer. */
object SeekPreviewDebugLogs {
    private const val MAX_LINES = 500
    private val lock = Any()
    private val lines = ArrayDeque<String>()
    /** Latest fetch outcome; pinned so per-frame logs can never evict it. */
    private var lastFetch: String = "(no fetch yet)"

    fun add(line: String) {
        synchronized(lock) {
            if (lines.size >= MAX_LINES) lines.removeFirst()
            lines.addLast(line)
        }
    }

    fun noteFetch(line: String) {
        synchronized(lock) { lastFetch = line }
        add(line)
    }

    fun snapshot(): List<String> = synchronized(lock) { lines.toList() }

    fun snapshotForCopy(): String = synchronized(lock) {
        (listOf("FETCH: $lastFetch") + lines).joinToString("\n")
    }

    fun clear() = synchronized(lock) { lines.clear(); lastFetch = "(no fetch yet)" }
}
