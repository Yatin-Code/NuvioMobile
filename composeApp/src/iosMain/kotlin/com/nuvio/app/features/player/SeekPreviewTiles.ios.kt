package com.nuvio.app.features.player

import androidx.compose.ui.graphics.ImageBitmap

/**
 * iOS tile provider: silent stub (returns null, overlay stays hidden).
 * Remote-registry tile decoding is Android-only for now.
 */
actual object SeekPreviewSheetTiles {
    actual suspend fun tile(sheetUrl: String, x: Int, y: Int, w: Int, h: Int): ImageBitmap? = null

    actual fun clear() {
    }
}
