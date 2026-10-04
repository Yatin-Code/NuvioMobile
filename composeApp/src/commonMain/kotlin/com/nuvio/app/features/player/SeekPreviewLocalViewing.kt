package com.nuvio.app.features.player

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CancellationException

/**
 * Local-first viewing: display-only direct-tile path (no server/VTT/upload
 * changes).
 *
 * Lookup order per scrub position (strict precedence):
 *   1. Registry cue ([SeekPreviewTrack.thumbnailFor], coverage-bounded) ->
 *      sheet tile, as today. A sheet-fetch failure on a served cue stays
 *      blank: there is deliberately NO local fallback there.
 *   2. Else the local capture bucket slot ([localTileSlotFor]) -> direct
 *      `tile-<ts>.jpg` tile, only for exact banked slots.
 *   3. Else null (silent).
 *
 * The bucket is device-shared and already persists across sessions, so a
 * resumed title shows last session's banked past before any registry read
 * lands. Platform pruning stays the only eviction policy.
 */

/** Where one scrub position's thumbnail comes from (pure, unit-testable). */
internal sealed interface SeekPreviewTileSource {
    /** Registry serves this position: show the sheet cue (never a local tile). */
    data class Registry(val cue: SeekPreviewCue) : SeekPreviewTileSource

    /** Registry is silent here but the bucket holds this exact slot. */
    data class Local(val slotTimestampMs: Long) : SeekPreviewTileSource

    /** Neither serves this position: overlay stays silent. */
    data object None : SeekPreviewTileSource
}

/**
 * Exact banked slot holding [positionMs], or null.
 *
 * [positionMs] floors to its grid slot via [slotStartFor] (no interpolation);
 * the slot must be banked exactly, and never past the highest banked slot, so
 * the uncovered tail stays silent instead of repeating a stale tile.
 */
internal fun localTileSlotFor(
    positionMs: Long,
    bankedSlots: Set<Long>,
    intervalMs: Long = SEEK_PREVIEW_CAPTURE_INTERVAL_MS,
): Long? {
    if (bankedSlots.isEmpty()) return null
    val slot = slotStartFor(positionMs, intervalMs)
    val highest = bankedSlots.maxOrNull() ?: return null
    if (slot > highest) return null
    return if (slot in bankedSlots) slot else null
}

/**
 * One scrub position's source: the registry cue when [SeekPreviewTrack]
 * serves it (coverage-bounded, as today), else the local bucket's exact slot,
 * else [SeekPreviewTileSource.None].
 *
 * The local consult happens ONLY when [thumbnailFor] returns null — a served
 * cue keeps strict precedence even if its sheet fetch later fails.
 */
internal fun resolveSeekPreviewSource(
    track: SeekPreviewTrack?,
    positionMs: Long,
    bankedSlots: Set<Long>,
    intervalMs: Long = SEEK_PREVIEW_CAPTURE_INTERVAL_MS,
): SeekPreviewTileSource {
    val cue = track?.thumbnailFor(positionMs)
    if (cue != null) return SeekPreviewTileSource.Registry(cue)
    val slot = localTileSlotFor(positionMs, bankedSlots, intervalMs)
    return if (slot != null) SeekPreviewTileSource.Local(slot) else SeekPreviewTileSource.None
}

/**
 * Per-session memory cache + disk read for direct bucket tiles.
 *
 * Reads go through the existing platform bucket ([SeekPreviewFrameCapture]
 * `tile-<ts>.jpg` files, decoded via [SeekPreviewSheetTiles.decodeTile]);
 * vanished/unreadable files fail silent (null). Decoded bitmaps are cached in
 * memory for the session; the overlay clears this on title change.
 */
internal object SeekPreviewLocalTiles {
    private const val MAX_CACHED_TILES = 32

    private val lock = Any()
    private val cache = LinkedHashMap<String, ImageBitmap>()

    private fun key(titleHash: String, slotTimestampMs: Long): String =
        "$titleHash:$slotTimestampMs"

    fun get(titleHash: String, slotTimestampMs: Long): ImageBitmap? =
        synchronized(lock) { cache[key(titleHash, slotTimestampMs)] }

    fun put(titleHash: String, slotTimestampMs: Long, tile: ImageBitmap) {
        synchronized(lock) {
            cache[key(titleHash, slotTimestampMs)] = tile
            while (cache.size > MAX_CACHED_TILES) {
                val eldest = cache.keys.firstOrNull() ?: break
                cache.remove(eldest)
            }
        }
    }

    fun clear() {
        synchronized(lock) { cache.clear() }
    }

    /** Direct tile for one banked slot, or null when it vanished/undecodable. */
    suspend fun tileFor(titleHash: String, slotTimestampMs: Long): ImageBitmap? {
        get(titleHash, slotTimestampMs)?.let { return it }
        val jpeg: ByteArray? = try {
            SeekPreviewFrameCapture.loadTiles(titleHash, listOf(slotTimestampMs))[slotTimestampMs]
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (jpeg == null || jpeg.isEmpty()) return null
        val decoded: ImageBitmap? = try {
            SeekPreviewSheetTiles.decodeTile(jpeg)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (decoded != null) put(titleHash, slotTimestampMs, decoded)
        return decoded
    }
}
