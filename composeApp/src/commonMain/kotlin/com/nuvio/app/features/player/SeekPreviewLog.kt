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
    private const val MAX_LINES = 200
    private val lock = Any()
    private val lines = ArrayDeque<String>()

    fun add(line: String) {
        synchronized(lock) {
            if (lines.size >= MAX_LINES) lines.removeFirst()
            lines.addLast(line)
        }
    }

    fun snapshot(): List<String> = synchronized(lock) { lines.toList() }

    fun clear() = synchronized(lock) { lines.clear() }
}
