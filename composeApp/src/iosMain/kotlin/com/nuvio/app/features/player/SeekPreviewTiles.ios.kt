package com.nuvio.app.features.player

import androidx.compose.ui.graphics.ImageBitmap

/**
 * iOS tile provider: silent stub (returns null, overlay stays hidden).
 * Remote-registry tile decoding is Android-only for now.
 *
 * No shared-fetch state is needed here: there is no download to survive
 * recomposition (both entry points return null unconditionally, with no
 * try/catch, so caller cancellation already propagates untouched). If remote
 * sheet decoding ever lands on iOS, its fetch MUST live in an app-scope
 * shared Deferred per URL (as the Android actual does) rather than in the
 * per-cue produceState block, or every scrub frame will cancel it mid-flight.
 */
actual object SeekPreviewSheetTiles {
    actual suspend fun tile(sheetUrl: String, x: Int, y: Int, w: Int, h: Int): ImageBitmap? = null

    actual suspend fun decodeTile(jpeg: ByteArray): ImageBitmap? = null

    actual fun clear() {
    }
}
