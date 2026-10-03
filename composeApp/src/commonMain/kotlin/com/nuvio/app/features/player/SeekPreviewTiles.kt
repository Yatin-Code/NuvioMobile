package com.nuvio.app.features.player

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Platform tile provider for seek-preview sprite sheets.
 *
 * Sheets are downloaded once per title and held in a memory LRU; tiles are
 * cropped per `#xywh` on demand. SILENT null on any miss/error.
 */
expect object SeekPreviewSheetTiles {
    suspend fun tile(sheetUrl: String, x: Int, y: Int, w: Int, h: Int): ImageBitmap?

    fun clear()
}
