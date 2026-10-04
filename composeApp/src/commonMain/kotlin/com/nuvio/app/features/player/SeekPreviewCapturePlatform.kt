package com.nuvio.app.features.player

/**
 * Platform side of contributor capture (expect; actuals in androidMain/iosMain).
 *
 * The common planner ([captureTimestampsFor]) decides *when*; this object does
 * the platform work: grabbing a 320x180 JPEG per timestamp, persisting tiles
 * under `cacheDir/capture/<title-hash>/`, stitching persisted tiles into
 * 5x5 sheets, and the permissive [captureAllowed] consent hook.
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

    /**
     * Persisted tiles for a title (timestamp -> JPEG), empty when none.
     * [timestampsMs] narrows the read to the wanted slots; an empty collection
     * means "everything", which is fine for listing but wasteful for a bundle
     * flush (the bucket can hold thousands of tiles).
     */
    suspend fun loadTiles(
        titleHash: String,
        timestampsMs: Collection<Long> = emptyList(),
    ): Map<Long, ByteArray>

    /**
     * Timestamps persisted under [titleHash], ascending, without reading tile
     * bytes. Buckets survive a successful upload (they are the backlog a later
     * session drains), so this listing stays the cheap way to resume.
     */
    suspend fun listTileTimestamps(titleHash: String): List<Long>

    /**
     * Deletes a title's whole capture bucket. The contribute loop does NOT call
     * this on success: banked tiles are the backlog a later session drains, and
     * the platform prunes the bucket on its own. Kept for a manual reset.
     */
    suspend fun clearTitle(titleHash: String)

    /**
     * Stitches [tiles] into 5x5 sheets using [tileBoxForTimestamp] cell math
     * (must match [buildCaptureVtt]), one [SeekPreviewSheetFile] per sheet.
     * Empty when nothing usable could be composed.
     */
    suspend fun composeSheets(tiles: Map<Long, ByteArray>): List<SeekPreviewSheetFile>

    /**
     * Whether the platform would allow a capture right now. There is no
     * unmetered-power gate any more (the contribute toggle is the only consent,
     * and the settings row warns about data + battery), so every current
     * platform reports true. Kept as the contract's hook for a future gate.
     */
    fun captureAllowed(): Boolean
}
