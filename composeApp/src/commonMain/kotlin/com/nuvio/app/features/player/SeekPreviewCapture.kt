package com.nuvio.app.features.player

/**
 * Contributor-side capture planning (commonMain, pure logic, no platform APIs).
 *
 * When the registry has no previews for a title (lookup 404 -> overlay stays
 * silent by design), the app can capture its own thumbnails during playback and
 * later upload them via [SeekPreviewUpload]. This file holds everything that
 * needs no platform: the capture schedule, the sprite-sheet layout math, the
 * positional VTT writer (same wire format the registry serves), and the
 * per-title disk-bucket hash.
 *
 * Platform supplies the actual pixels: a 320x180 JPEG per timestamp
 * ([SeekPreviewFrameCapture.grabFrame]), sheet stitching, and file storage.
 */

/** Capture one thumbnail every 10s, matching the registry/spritegen interval. */
const val SEEK_PREVIEW_CAPTURE_INTERVAL_MS = 10_000L

const val SEEK_PREVIEW_CAPTURE_TILE_WIDTH = 320
const val SEEK_PREVIEW_CAPTURE_TILE_HEIGHT = 180

/** Sheets are 5x5 grids (1600x900), same shape as spritegen output. */
const val SEEK_PREVIEW_CAPTURE_SHEET_COLS = 5
const val SEEK_PREVIEW_CAPTURE_SHEET_ROWS = 5
const val SEEK_PREVIEW_CAPTURE_TILES_PER_SHEET =
    SEEK_PREVIEW_CAPTURE_SHEET_COLS * SEEK_PREVIEW_CAPTURE_SHEET_ROWS

/** VTT file name inside the contribute payload. */
const val SEEK_PREVIEW_CAPTURE_VTT_NAME = "thumbnails-capture.vtt"

/** Auto-upload once this many tiles are banked, without waiting for completion. */
const val SEEK_PREVIEW_CONTRIBUTE_TILE_THRESHOLD = 50

/** Below this many tiles an upload is not worth the bytes; keep capturing. */
const val SEEK_PREVIEW_CONTRIBUTE_MIN_TILES = 5

/** Max 1 frame grab per 5s during playback (never jank playback). */
const val SEEK_PREVIEW_CAPTURE_GRAB_THROTTLE_MS = 5_000L

/** Recheck the wifi/charging gate this often while it reports not-allowed. */
const val SEEK_PREVIEW_CAPTURE_POLICY_RECHECK_MS = 30_000L

/** Idle (paused/buffering) recheck cadence while waiting to resume grabbing. */
const val SEEK_PREVIEW_CAPTURE_IDLE_RECHECK_MS = 5_000L

fun sheetCaptureFileName(sheetIndex: Int): String = "sheet-c-$sheetIndex.jpg"

data class SeekPreviewSheetTileBox(
    val sheetFileName: String,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
)

/** A finished sheet ready to be attached to the contribute multipart body. */
data class SeekPreviewSheetFile(
    val fileName: String,
    val jpeg: ByteArray,
)

/**
 * Deterministic sheet cell for [timestampMs]: slot index = timestamp/interval,
 * sheet = slot / 25, cell = slot % 25. The VTT writer and the platform sheet
 * stitcher both use this, so cues always point at the right cell.
 */
fun tileBoxForTimestamp(
    timestampMs: Long,
    intervalMs: Long = SEEK_PREVIEW_CAPTURE_INTERVAL_MS,
): SeekPreviewSheetTileBox {
    val safeInterval = intervalMs.takeIf { it > 0L } ?: SEEK_PREVIEW_CAPTURE_INTERVAL_MS
    val slot = (timestampMs.coerceAtLeast(0L) / safeInterval).toInt()
    val sheetIndex = slot / SEEK_PREVIEW_CAPTURE_TILES_PER_SHEET
    val pos = slot % SEEK_PREVIEW_CAPTURE_TILES_PER_SHEET
    return SeekPreviewSheetTileBox(
        sheetFileName = sheetCaptureFileName(sheetIndex),
        x = (pos % SEEK_PREVIEW_CAPTURE_SHEET_COLS) * SEEK_PREVIEW_CAPTURE_TILE_WIDTH,
        y = (pos / SEEK_PREVIEW_CAPTURE_SHEET_COLS) * SEEK_PREVIEW_CAPTURE_TILE_HEIGHT,
        w = SEEK_PREVIEW_CAPTURE_TILE_WIDTH,
        h = SEEK_PREVIEW_CAPTURE_TILE_HEIGHT,
    )
}

/**
 * Next timestamps to capture: one per [intervalMs] slot in time order,
 * skipping anything already in [alreadyCaptured]. Returns at most [limit].
 */
fun captureTimestampsFor(
    durationMs: Long,
    intervalMs: Long = SEEK_PREVIEW_CAPTURE_INTERVAL_MS,
    alreadyCaptured: Set<Long> = emptySet(),
    limit: Int = Int.MAX_VALUE,
): List<Long> {
    if (durationMs <= 0L || intervalMs <= 0L || limit <= 0) return emptyList()
    // Guard against bogus durations blowing up the schedule.
    val slotCount = ((durationMs + intervalMs - 1L) / intervalMs).toInt().coerceAtMost(4_000)
    val out = ArrayList<Long>(minOf(slotCount, limit.coerceAtMost(1_024)))
    var slot = 0
    while (slot < slotCount && out.size < limit) {
        val ts = slot * intervalMs
        if (ts < durationMs && ts !in alreadyCaptured) out.add(ts)
        slot++
    }
    return out
}

/** Zero-padded HH:MM:SS.mmm, matching what spritegen writes (and parses). */
fun formatSeekCaptureTimestamp(timeMs: Long): String {
    val total = timeMs.coerceAtLeast(0L)
    val hours = total / 3_600_000L
    val minutes = (total % 3_600_000L) / 60_000L
    val seconds = (total % 60_000L) / 1_000L
    val millis = total % 1_000L
    return buildString {
        if (hours < 10L) append('0')
        append(hours).append(':')
        if (minutes < 10L) append('0')
        append(minutes).append(':')
        if (seconds < 10L) append('0')
        append(seconds).append('.')
        if (millis < 100L) append('0')
        if (millis < 10L) append('0')
        append(millis)
    }
}

/**
 * Positional VTT for captured tiles: cue per captured slot, start/end derived
 * from the slot timestamp (end clamped to [durationMs]), payload
 * `sheet-c-N.jpg#xywh=x,y,w,h` — the same wire format the registry serves, so
 * the server can ingest it without conversion. Input order does not matter;
 * output is time-ordered.
 */
fun buildCaptureVtt(
    capturedTimestampsMs: Collection<Long>,
    durationMs: Long,
    intervalMs: Long = SEEK_PREVIEW_CAPTURE_INTERVAL_MS,
): String {
    val safeInterval = intervalMs.takeIf { it > 0L } ?: SEEK_PREVIEW_CAPTURE_INTERVAL_MS
    val slots = capturedTimestampsMs
        .filter { it >= 0L && (durationMs <= 0L || it < durationMs) }
        .toSortedSet()
    return buildString {
        append("WEBVTT\n")
        for (ts in slots) {
            val end = if (durationMs > 0L) minOf(ts + safeInterval, durationMs) else ts + safeInterval
            val box = tileBoxForTimestamp(ts, safeInterval)
            append('\n')
            append(formatSeekCaptureTimestamp(ts))
            append(" --> ")
            append(formatSeekCaptureTimestamp(end))
            append('\n')
            append(box.sheetFileName)
            append("#xywh=")
            append(box.x).append(',').append(box.y).append(',').append(box.w).append(',').append(box.h)
            append('\n')
        }
    }
}

/**
 * Stable per-title disk-bucket hash (FNV-1a hex of the registry URL, which
 * already encodes ids + duration). Hex only, safe as a directory name:
 * `cacheDir/capture/<hash>/`.
 */
fun seekPreviewTitleHash(query: SeekPreviewQuery): String {
    var hash = 0xCBF29CE484222325uL.toLong() // FNV-1a 64 offset basis
    for (byte in query.registryUrl.encodeToByteArray()) {
        hash = hash xor (byte.toLong() and 0xFFL)
        hash *= 109_951_162_8211L // FNV-1a 64 prime
    }
    return hash.toULong().toString(radix = 16).padStart(16, '0')
}
