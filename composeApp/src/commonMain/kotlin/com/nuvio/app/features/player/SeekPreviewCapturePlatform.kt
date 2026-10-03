package com.nuvio.app.features.player

/**
 * Platform side of contributor capture (expect; actuals in androidMain/iosMain).
 *
 * The common planner ([captureTimestampsFor]) decides *when*; this object does
 * the platform work: grabbing a 320x180 JPEG per timestamp, persisting tiles
 * under `cacheDir/capture/<title-hash>/`, stitching persisted tiles into
 * 5x5 sheets, and the best-effort wifi/charging gate.
 *
 * SILENT contract: every function returns null/false/empty on any failure and
 * never throws (coroutine cancellation excepted). Capture must never interrupt
 * playback.
 */
expect object SeekPreviewFrameCapture {
    /**
     * Grabs one frame at [positionMs] and returns it as a 320x180 JPEG
     * (quality ~70), or null when unavailable (DRM, network, unsupported
     * container). Runs off the main thread; at most 1 call per 5s per title.
     */
    suspend fun grabFrame(
        sourceUrl: String,
        headers: Map<String, String>,
        positionMs: Long,
    ): ByteArray?

    /** Persists one tile JPEG; false when storage is unavailable. */
    suspend fun saveTile(titleHash: String, timestampMs: Long, jpeg: ByteArray): Boolean

    /** All persisted tiles for a title (timestamp -> JPEG), empty when none. */
    suspend fun loadTiles(titleHash: String): Map<Long, ByteArray>

    /** Deletes a title's whole capture bucket (after a successful upload). */
    suspend fun clearTitle(titleHash: String)

    /**
     * Stitches [tiles] into 5x5 sheets using [tileBoxForTimestamp] cell math
     * (must match [buildCaptureVtt]), one [SeekPreviewSheetFile] per sheet.
     * Empty when nothing usable could be composed.
     */
    suspend fun composeSheets(tiles: Map<Long, ByteArray>): List<SeekPreviewSheetFile>

    /**
     * Best-effort unmetered-power gate: true when on wifi/unmetered or
     * charging. Fail-open true when the state cannot be determined (no
     * context/permission) — the 5s grab throttle still applies.
     */
    fun captureAllowed(): Boolean
}
