package com.nuvio.app.features.player

/**
 * iOS contributor capture: silent stub (no on-device frame grabs yet).
 * [captureAllowed] reports false so the common contribute effect parks on the
 * power gate without doing any work; every other entry is a null/false/empty
 * no-op, so the bundle loop never reaches a platform that cannot serve it.
 */
actual object SeekPreviewFrameCapture {
    actual suspend fun grabFrame(
        sourceUrl: String,
        headers: Map<String, String>,
        positionMs: Long,
    ): ByteArray? = null

    actual suspend fun saveTile(titleHash: String, timestampMs: Long, jpeg: ByteArray): Boolean = false

    actual suspend fun loadTiles(
        titleHash: String,
        timestampsMs: Collection<Long>,
    ): Map<Long, ByteArray> = emptyMap()

    actual suspend fun listTileTimestamps(titleHash: String): List<Long> = emptyList()

    actual suspend fun clearTitle(titleHash: String) {
    }

    actual suspend fun composeSheets(tiles: Map<Long, ByteArray>): List<SeekPreviewSheetFile> = emptyList()

    actual fun captureAllowed(): Boolean = false
}
