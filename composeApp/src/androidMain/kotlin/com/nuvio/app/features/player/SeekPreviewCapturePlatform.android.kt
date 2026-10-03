package com.nuvio.app.features.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Android contributor capture (seekr-TV-fork MmrFrameGrabber pattern).
 *
 * A fresh [MediaMetadataRetriever] per grab (like the fork's one-shot grabber):
 * no long-lived instance to go stale across source switches, and every failure
 * (DRM, expiring credentials, unsupported container) collapses to silent null.
 * Grabs run on [Dispatchers.IO] at most once per 5s per title, so playback is
 * never janked. Frames are center-cropped to 16:9, downscaled to 320x180 and
 * stored as JPEG q70 under `cacheDir/capture/<title-hash>/tile-<ts>.jpg`.
 *
 * Request headers forwarded to MMR come from the player's sanitized playback
 * headers (same map the engine feeds ExoPlayer/Media3), plus a default
 * User-Agent when none is present — MovieBox-class hosts 403/428 grabs without
 * one. Everything here is silent-null/false/empty on any error.
 */
actual object SeekPreviewFrameCapture {
    private const val TILE_JPEG_QUALITY = 70
    private const val SHEET_JPEG_QUALITY = 80
    private const val MAX_TILE_FILE_BYTES = 1024 * 1024
    private const val MAX_STORED_TILES = 1600

    @Volatile
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    actual suspend fun grabFrame(
        sourceUrl: String,
        headers: Map<String, String>,
        positionMs: Long,
    ): ByteArray? = withContext(Dispatchers.IO) {
        runCatching { grabFrameSync(sourceUrl, headers, positionMs) }.getOrNull()
    }

    actual suspend fun saveTile(titleHash: String, timestampMs: Long, jpeg: ByteArray): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { saveTileSync(titleHash, timestampMs, jpeg) }.getOrDefault(false)
        }

    actual suspend fun loadTiles(titleHash: String): Map<Long, ByteArray> =
        withContext(Dispatchers.IO) {
            runCatching { loadTilesSync(titleHash) }.getOrDefault(emptyMap())
        }

    actual suspend fun clearTitle(titleHash: String) {
        withContext(Dispatchers.IO) {
            runCatching { titleDir(titleHash)?.deleteRecursively() }
        }
    }

    actual suspend fun composeSheets(tiles: Map<Long, ByteArray>): List<SeekPreviewSheetFile> =
        withContext(Dispatchers.IO) {
            runCatching { composeSheetsSync(tiles) }.getOrDefault(emptyList())
        }

    actual fun captureAllowed(): Boolean {
        val context = appContext ?: return true
        return runCatching {
            var unmetered = false
            try {
                val connectivity = context.getSystemService(ConnectivityManager::class.java)
                    ?: return@runCatching true
                val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
                if (capabilities != null) {
                    val wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    unmetered = wifi ||
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                }
            } catch (_: SecurityException) {
                // No ACCESS_NETWORK_STATE: fail open, the 5s grab throttle still applies.
                return@runCatching true
            }
            if (unmetered) return@runCatching true
            context.getSystemService(BatteryManager::class.java)?.isCharging == true
        }.getOrDefault(true)
    }

    private fun grabFrameSync(
        sourceUrl: String,
        headers: Map<String, String>,
        positionMs: Long,
    ): ByteArray? {
        if (sourceUrl.isBlank() || positionMs < 0L) return null
        val retriever = MediaMetadataRetriever()
        try {
            val requestHeaders = LinkedHashMap(headers)
            if (requestHeaders.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                requestHeaders["User-Agent"] = PlayerPlaybackNetworking.DEFAULT_USER_AGENT
            }
            try {
                if (requestHeaders.isEmpty()) {
                    retriever.setDataSource(sourceUrl)
                } else {
                    retriever.setDataSource(sourceUrl, requestHeaders)
                }
            } catch (_: Exception) {
                return null
            }
            val timeUs = positionMs.coerceAtLeast(0L) * 1_000L
            val frame = runCatching {
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
            }.getOrNull()
                ?: runCatching {
                    retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }.getOrNull()
                ?: return null
            try {
                return frameToTileJpeg(frame)
            } finally {
                if (!frame.isRecycled) frame.recycle()
            }
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun frameToTileJpeg(frame: Bitmap): ByteArray? {
        val srcW = frame.width
        val srcH = frame.height
        if (srcW <= 0 || srcH <= 0) return null
        val targetAspect = SEEK_PREVIEW_CAPTURE_TILE_WIDTH.toFloat() / SEEK_PREVIEW_CAPTURE_TILE_HEIGHT
        val cropW: Int
        val cropH: Int
        if (srcW.toFloat() / srcH > targetAspect) {
            cropH = srcH
            cropW = (srcH * targetAspect).toInt().coerceIn(1, srcW)
        } else {
            cropW = srcW
            cropH = (srcW / targetAspect).toInt().coerceIn(1, srcH)
        }
        val cropX = ((srcW - cropW) / 2).coerceAtLeast(0)
        val cropY = ((srcH - cropH) / 2).coerceAtLeast(0)
        val cropped = Bitmap.createBitmap(frame, cropX, cropY, cropW, cropH)
        try {
            val scaled = Bitmap.createScaledBitmap(
                cropped,
                SEEK_PREVIEW_CAPTURE_TILE_WIDTH,
                SEEK_PREVIEW_CAPTURE_TILE_HEIGHT,
                true,
            )
            try {
                if (scaled.width <= 0 || scaled.height <= 0) return null
                val out = ByteArrayOutputStream(24 * 1024)
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, TILE_JPEG_QUALITY, out)) return null
                return out.toByteArray().takeIf { it.isNotEmpty() }
            } finally {
                if (scaled !== cropped && !scaled.isRecycled) scaled.recycle()
            }
        } finally {
            if (!cropped.isRecycled) cropped.recycle()
        }
    }

    private fun captureRoot(): File? {
        val context = appContext ?: return null
        return File(context.cacheDir, "capture").also { it.mkdirs() }
    }

    private fun titleDir(titleHash: String): File? {
        val safe = titleHash.filter { it.isLetterOrDigit() }.take(64).ifEmpty { return null }
        val root = captureRoot() ?: return null
        return File(root, safe).also { it.mkdirs() }.takeIf { it.isDirectory }
    }

    private fun saveTileSync(titleHash: String, timestampMs: Long, jpeg: ByteArray): Boolean {
        if (jpeg.isEmpty() || jpeg.size > MAX_TILE_FILE_BYTES || timestampMs < 0L) return false
        val dir = titleDir(titleHash) ?: return false
        File(dir, "tile-$timestampMs.jpg").writeBytes(jpeg)
        pruneOldest(dir)
        return true
    }

    private fun pruneOldest(dir: File) {
        val files = dir.listFiles { file ->
            file.isFile && file.name.startsWith("tile-") && file.name.endsWith(".jpg")
        } ?: return
        if (files.size <= MAX_STORED_TILES) return
        files.sortedByDescending { it.lastModified() }
            .drop(MAX_STORED_TILES)
            .forEach { runCatching { it.delete() } }
    }

    private fun loadTilesSync(titleHash: String): Map<Long, ByteArray> {
        val dir = titleDir(titleHash) ?: return emptyMap()
        val files = dir.listFiles { file ->
            file.isFile && file.name.startsWith("tile-") && file.name.endsWith(".jpg")
        } ?: return emptyMap()
        val out = LinkedHashMap<Long, ByteArray>(files.size)
        for (file in files) {
            val timestampMs = file.name.removePrefix("tile-").removeSuffix(".jpg").toLongOrNull()
                ?: continue
            if (timestampMs < 0L || file.length() <= 0L || file.length() > MAX_TILE_FILE_BYTES) continue
            out[timestampMs] = runCatching { file.readBytes() }.getOrNull()?.takeIf { it.isNotEmpty() }
                ?: continue
        }
        return out.toSortedMap()
    }

    private fun composeSheetsSync(tiles: Map<Long, ByteArray>): List<SeekPreviewSheetFile> {
        if (tiles.isEmpty()) return emptyList()
        val intervalMs = SEEK_PREVIEW_CAPTURE_INTERVAL_MS
        val bySheet = tiles.entries.groupBy { (it.key / intervalMs).toInt() / SEEK_PREVIEW_CAPTURE_TILES_PER_SHEET }
        val out = ArrayList<SeekPreviewSheetFile>(bySheet.size)
        for (sheetIndex in bySheet.keys.sorted()) {
            val sheet = Bitmap.createBitmap(
                SEEK_PREVIEW_CAPTURE_SHEET_COLS * SEEK_PREVIEW_CAPTURE_TILE_WIDTH,
                SEEK_PREVIEW_CAPTURE_SHEET_ROWS * SEEK_PREVIEW_CAPTURE_TILE_HEIGHT,
                Bitmap.Config.RGB_565,
            )
            try {
                sheet.eraseColor(Color.BLACK)
                val canvas = Canvas(sheet)
                for ((timestampMs, bytes) in bySheet.getValue(sheetIndex)) {
                    if (bytes.isEmpty() || bytes.size > MAX_TILE_FILE_BYTES) continue
                    val box = tileBoxForTimestamp(timestampMs, intervalMs)
                    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                    try {
                        val fitted = if (decoded.width != box.w || decoded.height != box.h) {
                            Bitmap.createScaledBitmap(decoded, box.w, box.h, true)
                        } else {
                            decoded
                        }
                        try {
                            canvas.drawBitmap(fitted, box.x.toFloat(), box.y.toFloat(), null)
                        } finally {
                            if (fitted !== decoded && !fitted.isRecycled) fitted.recycle()
                        }
                    } finally {
                        if (!decoded.isRecycled) decoded.recycle()
                    }
                }
                val bytesOut = ByteArrayOutputStream(256 * 1024)
                if (!sheet.compress(Bitmap.CompressFormat.JPEG, SHEET_JPEG_QUALITY, bytesOut)) continue
                val jpeg = bytesOut.toByteArray()
                if (jpeg.isNotEmpty()) out.add(SeekPreviewSheetFile(sheetCaptureFileName(sheetIndex), jpeg))
            } finally {
                if (!sheet.isRecycled) sheet.recycle()
            }
        }
        return out
    }
}
