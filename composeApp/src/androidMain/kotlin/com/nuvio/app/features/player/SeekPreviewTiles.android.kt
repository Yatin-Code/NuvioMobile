package com.nuvio.app.features.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.nuvio.app.features.addons.AddonHttpClientProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
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
    // In-flight sheet downloads, one shared Deferred per URL. The overlay's
    // produceState is keyed per cue, so every scrub frame restarts its block
    // and cancels the awaiting child — but the download itself lives in
    // [fetchScope], so a restarted composition re-awaits the SAME Deferred
    // instead of cancelling and restarting the fetch (which previously meant
    // no tile fetch ever survived long enough to complete while scrubbing).
    // Awaiting children are still cancellable independently; cancelling one
    // of them never cancels the shared fetch. Entries remove themselves on
    // completion; [clear] cancels whatever is still running.
    private val fetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlightSheets = HashMap<String, Deferred<ByteArray?>>()

    actual suspend fun tile(sheetUrl: String, x: Int, y: Int, w: Int, h: Int): ImageBitmap? {
        if (w <= 0 || h <= 0) return null
        try {
            return withContext(Dispatchers.IO) {
                tileShared(sheetUrl, x, y, w, h)?.asImageBitmap()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
    }

    actual suspend fun decodeTile(jpeg: ByteArray): ImageBitmap? {
        try {
            return withContext(Dispatchers.IO) {
                if (jpeg.isEmpty()) return@withContext null
                val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return@withContext null
                bitmap.asImageBitmap()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
    }

    actual fun clear() {
        val pending: List<Deferred<ByteArray?>>
        synchronized(lock) {
            tileCache.evictAll()
            sheetBytes.clear()
            pending = inFlightSheets.values.toList()
            inFlightSheets.clear()
        }
        // Cancel outside the lock: the fetchers only touch [lock] briefly,
        // and invokeOnCompletion re-checks identity so a post-clear completion
        // can never resurrect a stale entry.
        pending.forEach { it.cancel() }
    }

    private suspend fun tileShared(sheetUrl: String, x: Int, y: Int, w: Int, h: Int): Bitmap? {
        val key = "$sheetUrl#$x,$y,$w,$h"
        synchronized(lock) { tileCache.get(key) }?.let { return it }
        val bytes = sheetBytesOrFetch(sheetUrl) ?: return null
        val bitmap = try {
            decodeRegion(bytes, x, y, w, h)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return null
        synchronized(lock) { tileCache.put(key, bitmap) }
        return bitmap
    }

    /**
     * Sheet bytes for one URL: memory cache hit, else join the single shared
     * in-flight download for that URL. SILENT null when the fetch fails.
     */
    private suspend fun sheetBytesOrFetch(sheetUrl: String): ByteArray? {
        synchronized(lock) { sheetBytes[sheetUrl] }?.let { return it }
        val deferred: Deferred<ByteArray?> = synchronized(lock) {
            inFlightSheets[sheetUrl]?.let { return@synchronized it }
            val created = fetchScope.async {
                val fetched: ByteArray? = try {
                    fetchSheet(sheetUrl)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                // Don't repopulate after a clear() (title change / refresh):
                // a cancelled fetch's late result is discarded with it.
                if (fetched != null && coroutineContext.isActive) {
                    synchronized(lock) { sheetBytes[sheetUrl] = fetched }
                }
                fetched
            }
            inFlightSheets[sheetUrl] = created
            created.invokeOnCompletion {
                synchronized(lock) {
                    if (inFlightSheets[sheetUrl] === created) inFlightSheets.remove(sheetUrl)
                }
            }
            created
        }
        return try {
            deferred.await()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
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
