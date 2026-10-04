package com.nuvio.app.features.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.nuvio.app.features.addons.AddonHttpClientProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.ByteArrayOutputStream

/**
 * Android tile provider: sheets fetched once per title over the shared app
 * OkHttp client ([AddonHttpClientProvider], same one backing httpGetText),
 * decoded per `#xywh` via [BitmapRegionDecoder] (RGB_565, no full-sheet ARGB
 * bitmap), cropped tiles held in a memory LRU. SILENT null on 404/any error.
 */
actual object SeekPreviewSheetTiles {
    private const val TILE_CACHE_BYTES = 6 * 1024 * 1024
    private const val MAX_SHEET_BYTES = 12 * 1024 * 1024

    private val lock = Any()
    private val tileCache = object : LruCache<String, Bitmap>(TILE_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val sheetBytes = HashMap<String, ByteArray>()

    actual suspend fun tile(sheetUrl: String, x: Int, y: Int, w: Int, h: Int): ImageBitmap? =
        withContext(Dispatchers.IO) {
            runCatching { tileSync(sheetUrl, x, y, w, h)?.asImageBitmap() }.getOrNull()
        }

    actual suspend fun decodeTile(jpeg: ByteArray): ImageBitmap? =
        withContext(Dispatchers.IO) {
            runCatching {
                if (jpeg.isEmpty()) return@runCatching null
                val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return@runCatching null
                bitmap.asImageBitmap()
            }.getOrNull()
        }

    actual fun clear() {
        synchronized(lock) {
            tileCache.evictAll()
            sheetBytes.clear()
        }
    }

    private fun tileSync(sheetUrl: String, x: Int, y: Int, w: Int, h: Int): Bitmap? {
        if (w <= 0 || h <= 0) return null
        val key = "$sheetUrl#$x,$y,$w,$h"
        synchronized(lock) { tileCache.get(key) }?.let { return it }
        val bytes = synchronized(lock) { sheetBytes[sheetUrl] }
            ?: fetchSheet(sheetUrl)?.also { fetched ->
                synchronized(lock) { sheetBytes[sheetUrl] = fetched }
            }
            ?: return null
        val bitmap = decodeRegion(bytes, x, y, w, h) ?: return null
        synchronized(lock) { tileCache.put(key, bitmap) }
        return bitmap
    }

    private fun fetchSheet(url: String): ByteArray? {
        val request = Request.Builder().url(url).header("Accept", "image/*").build()
        AddonHttpClientProvider.get().newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val stream = response.body?.byteStream() ?: return null
            return stream.use {
                val out = ByteArrayOutputStream(64 * 1024)
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val read = it.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_SHEET_BYTES) return null
                    out.write(buffer, 0, read)
                }
                out.toByteArray().takeIf { bytes -> bytes.isNotEmpty() }
            }
        }
    }

    private fun decodeRegion(bytes: ByteArray, x: Int, y: Int, w: Int, h: Int): Bitmap? {
        val decoder = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false) ?: return null
        try {
            val sheetWidth = decoder.width
            val sheetHeight = decoder.height
            val left = x.coerceIn(0, sheetWidth - 1)
            val top = y.coerceIn(0, sheetHeight - 1)
            val right = (x + w).coerceIn(left + 1, sheetWidth)
            val bottom = (y + h).coerceIn(top + 1, sheetHeight)
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            return decoder.decodeRegion(Rect(left, top, right, bottom), options)
        } finally {
            decoder.recycle()
        }
    }
}
