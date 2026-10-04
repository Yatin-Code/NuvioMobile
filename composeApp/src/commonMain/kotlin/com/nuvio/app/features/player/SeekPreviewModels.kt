package com.nuvio.app.features.player

/**
 * Scrub-seek preview models for the mobile player.
 *
 * Served by OUR registry (seekr-wire-compatible), NOT generated on-device:
 *   GET {base}/v1/sprites?tmdb_id|imdb_id|show_tmdb_id|show_imdb_id[+season+episode]&duration_ms
 *     -> {vtt_url (absolute), version, source_duration_ms, scale,
 *         status (pending|complete), covered_until_ms, ids...}
 *     unknown title -> 404 {"error":...} (callers treat as silent miss)
 *
 * VTT cues map time ranges to absolute sheet URLs + `#xywh=x,y,w,h`
 * (same wire format as seekr VTT / spritegen output). A `pending` version is
 * a partial stripe: servable up to `covered_until_ms`, silent after it.
 */

/** Registry base. Hardcoded for testing only — single const, see [SEEK_PREVIEW_REGISTRY_BASE]. */
const val SEEK_PREVIEW_REGISTRY_BASE = "http://20.244.18.205:8080"

/**
 * Bootstrap registry API key: the `local-dev` row the registry seeds itself
 * with (see registry/migrate.sql). Used whenever no custom key is stored so
 * existing test builds keep working out of the box.
 */
const val SEEK_PREVIEW_DEFAULT_API_KEY = "local-dev-key"

/** Key self-check route (quota-free server-side): GET {base}/v1/keys/validate. */
const val SEEK_PREVIEW_KEYS_VALIDATE_PATH = "/v1/keys/validate"

data class SeekPreviewCue(
    val startMs: Long,
    val imageUrl: String,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
)

/** Registry version states (`status` on /v1/sprites and /v1/titles). */
const val SEEK_PREVIEW_VERSION_PENDING = "pending"
const val SEEK_PREVIEW_VERSION_COMPLETE = "complete"

/**
 * Served version of a title. Coverage-aware since the registry started
 * keeping partial (`pending`) versions: those are servable, but only up to
 * [coveredUntilMs], so a partial sheet must never hand out a stale tail tile
 * past its last cue.
 */
data class SeekPreviewTrack(
    val vttUrl: String,
    val sourceDurationMs: Long,
    val scale: Double = 1.0,
    val cues: List<SeekPreviewCue> = emptyList(),
    /** `pending` (partial, servable up to coveredUntilMs) or `complete`. */
    val status: String = SEEK_PREVIEW_VERSION_COMPLETE,
    /**
     * Source-time end of the served coverage (registry `covered_until_ms`).
     * Null when the registry did not say (older server) -> treat as unbounded,
     * which keeps the pre-coverage behavior.
     */
    val coveredUntilMs: Long? = null,
) {
    /**
     * Nothing left for a contributor to stripe. Coverage decides (both are
     * source time), with `status` as the fallback for a registry that reports
     * coverage at all — a pre-coverage registry sends neither field, which
     * reads as complete.
     */
    val coversWholeSource: Boolean
        get() {
            val covered = coveredUntilMs
                ?: return status != SEEK_PREVIEW_VERSION_PENDING
            return covered >= sourceDurationMs
        }

    /**
     * [coveredUntilMs] on the local playback timeline. `scale` is local
     * duration / registry source duration and [thumbnailFor] maps local ->
     * source with a division, so mapping source -> local multiplies. Null when
     * the registry reported no coverage at all.
     */
    fun coveredUntilOnLocalTimeline(): Long? {
        val covered = coveredUntilMs ?: return null
        return if (scale > 0.0) (covered * scale).toLong() else covered
    }
}

/**
 * Floor semantics (port of sdk/kotlin Peek.kt `thumbnailFor`):
 * last cue at or before [positionMs], corrected by the registry scale
 * (scale = playing duration / source duration). Null = no preview, hide thumb.
 *
 * [coveredUntilMs] defaults to the track's own coverage end and is compared in
 * source time (after the scale correction), so a partial `pending` version
 * stays silent past its last cue instead of showing a stale tail tile. Pass
 * null to lift the bound.
 */
fun SeekPreviewTrack.thumbnailFor(
    positionMs: Long,
    coveredUntilMs: Long? = this.coveredUntilMs,
): SeekPreviewCue? {
    val pos = if (scale > 0.0) (positionMs / scale).toLong() else positionMs
    if (coveredUntilMs != null && pos > coveredUntilMs) return null
    var hit: SeekPreviewCue? = null
    for (c in cues) {
        if (c.startMs <= pos) hit = c else break
    }
    return hit
}

/**
 * Where a contributor should start striping this title: the served coverage
 * end brought back to the local timeline and floored to a grid slot. 0 when
 * the registry has no version (or no coverage data), so a full miss still
 * plans from the head of the title exactly like before.
 */
fun SeekPreviewTrack.coverageAnchorMs(
    durationMs: Long,
    intervalMs: Long = SEEK_PREVIEW_CAPTURE_INTERVAL_MS,
): Long {
    val covered = coveredUntilOnLocalTimeline() ?: return 0L
    val ceiling = if (durationMs > 0L) durationMs else covered
    return slotStartFor(covered.coerceIn(0L, ceiling), intervalMs)
}

/**
 * Grid slots the served version already covers: cue starts mapped onto the
 * local timeline (source time scaled by [SeekPreviewTrack.scale]) and floored
 * to a slot. A contributor skips these, so two devices rarely upload the same
 * slot and a near-duplicate version (drifted duration) does not re-stripe the
 * head it already holds.
 */
fun SeekPreviewTrack.coveredSlotTimestamps(
    durationMs: Long,
    intervalMs: Long = SEEK_PREVIEW_CAPTURE_INTERVAL_MS,
): Set<Long> {
    val safeInterval = intervalMs.takeIf { it > 0L } ?: SEEK_PREVIEW_CAPTURE_INTERVAL_MS
    val out = HashSet<Long>()
    for (cue in cues) {
        val localMs = if (scale > 0.0) (cue.startMs * scale).toLong() else cue.startMs
        if (localMs < 0L) continue
        if (durationMs > 0L && localMs >= durationMs) continue
        out.add(slotStartFor(localMs, safeInterval))
    }
    return out
}

sealed interface SeekPreviewQuery {
    val durationMs: Long
    val registryUrl: String
}

data class MovieSeekPreviewQuery(
    val tmdbId: Int?,
    val imdbId: String?,
    override val durationMs: Long,
) : SeekPreviewQuery {
    init {
        require(tmdbId != null || imdbId != null) { "movie query needs tmdb_id or imdb_id" }
    }

    override val registryUrl: String
        get() = buildString {
            append(SEEK_PREVIEW_REGISTRY_BASE).append("/v1/sprites?")
            if (tmdbId != null) append("tmdb_id=").append(tmdbId) else append("imdb_id=").append(imdbId)
            append("&duration_ms=").append(durationMs)
        }
}

data class EpisodeSeekPreviewQuery(
    val showTmdbId: Int?,
    val showImdbId: String?,
    val season: Int,
    val episode: Int,
    override val durationMs: Long,
) : SeekPreviewQuery {
    init {
        require(showTmdbId != null || showImdbId != null) { "episode query needs show_tmdb_id or show_imdb_id" }
    }

    override val registryUrl: String
        get() = buildString {
            append(SEEK_PREVIEW_REGISTRY_BASE).append("/v1/sprites?")
            if (showTmdbId != null) append("show_tmdb_id=").append(showTmdbId) else append("show_imdb_id=").append(showImdbId)
            append("&season=").append(season)
            append("&episode=").append(episode)
            append("&duration_ms=").append(durationMs)
        }
}

/** Params threaded from the player runtime into the scrub UI. Commit still only fires onScrubFinished. */
data class SeekPreviewParams(
    val query: SeekPreviewQuery?,
    val enabled: Boolean,
    val isScrubbing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
)

internal fun extractSeekImdbId(value: String?): String? =
    value
        ?.trim()
        ?.split(':', '/', '?', '&')
        ?.firstOrNull { part -> part.startsWith("tt", ignoreCase = true) }
        ?.takeIf { it.length > 2 }

internal fun extractSeekTmdbId(value: String?): Int? {
    val trimmed = value?.trim().orEmpty()
    if (trimmed.isBlank()) return null
    return trimmed
        .takeIf { it.startsWith("tmdb:", ignoreCase = true) }
        ?.substringAfter(':')
        ?.substringBefore(':')
        ?.substringBefore('/')
        ?.toIntOrNull()
}

/**
 * Builds the registry query from ids the player screen already holds
 * (parentMetaId / activeVideoId / meta.imdbId + season/episode numbers —
 * same sources as the skip-intro plumbing). Returns null when no
 * TMDB/IMDB id is available: overlay stays silent.
 */
internal fun buildSeekPreviewQuery(
    parentMetaId: String,
    contentType: String?,
    videoId: String?,
    seasonNumber: Int?,
    episodeNumber: Int?,
    metaImdbId: String?,
    durationMs: Long,
): SeekPreviewQuery? {
    if (durationMs <= 0L) {
        seekPreviewLog("query null: badDuration parent=$parentMetaId video=$videoId type=$contentType")
        return null
    }
    val cleanMetaImdb = metaImdbId?.takeIf { it.startsWith("tt") && it.length > 2 }
    if (contentType.equals("movie", ignoreCase = true)) {
        val tmdbId = extractSeekTmdbId(parentMetaId) ?: extractSeekTmdbId(videoId)
        val imdbId = extractSeekImdbId(parentMetaId)
            ?: extractSeekImdbId(videoId)
            ?: cleanMetaImdb
        if (tmdbId == null && imdbId == null) {
            seekPreviewLog("query null: noIds parent=$parentMetaId video=$videoId metaImdb=$metaImdbId")
            return null
        }
        return MovieSeekPreviewQuery(tmdbId = tmdbId, imdbId = imdbId, durationMs = durationMs)
    }
    val season = seasonNumber ?: return null
    val episode = episodeNumber ?: return null
    val showTmdbId = extractSeekTmdbId(parentMetaId)
    val showImdbId = extractSeekImdbId(parentMetaId) ?: cleanMetaImdb
    if (showTmdbId == null && showImdbId == null) return null
    return EpisodeSeekPreviewQuery(
        showTmdbId = showTmdbId,
        showImdbId = showImdbId,
        season = season,
        episode = episode,
        durationMs = durationMs,
    )
}

/** Parses "HH:MM:SS.mmm" (spritegen always writes zero-padded hours); tolerates "MM:SS.mmm". */
internal fun parseSeekVttTimestamp(raw: String): Long? {
    val parts = raw.trim().split(':')
    if (parts.size !in 2..3) return null
    return try {
        val secParts = parts.last().split('.')
        val seconds = secParts[0].toLong()
        val millis = secParts.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLong() ?: 0L
        val minutes = parts[parts.size - 2].toLong()
        val hours = if (parts.size == 3) parts[0].toLong() else 0L
        if (minutes !in 0..59 || seconds !in 0..59 || millis !in 0..999) return null
        ((hours * 3600L + minutes * 60L + seconds) * 1000L) + millis
    } catch (_: Exception) {
        null
    }
}

internal fun parseSeekCuePayload(payload: String, vttBase: String): Triple<String, IntArray, Boolean> {
    val hash = payload.indexOf("#xywh=")
    if (hash < 0) return Triple(payload, intArrayOf(), false)
    val rawUrl = payload.substring(0, hash).trim()
    val url = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) rawUrl else "$vttBase/$rawUrl"
    val box = payload.substring(hash + "#xywh=".length).split(',').mapNotNull { it.trim().toIntOrNull() }.toIntArray()
    return Triple(url, box, box.size == 4)
}

/**
 * Parses registry WebVTT text into time-ordered cues. Cues without a valid
 * `#xywh` payload are skipped (silent miss for that slot).
 */
internal fun parseSeekPreviewVtt(vttText: String, vttUrl: String): List<SeekPreviewCue> {
    val vttBase = vttUrl.substringBeforeLast('/').trimEnd('/')
    val cues = ArrayList<SeekPreviewCue>()
    var pendingStartMs: Long? = null
    for (rawLine in vttText.lineSequence()) {
        val line = rawLine.trim()
        if (line.isEmpty()) {
            pendingStartMs = null
            continue
        }
        if ("-->" in line) {
            pendingStartMs = parseSeekVttTimestamp(line.substringBefore("-->"))
            continue
        }
        if (line.startsWith("WEBVTT") || line.startsWith("NOTE")) continue
        val startMs = pendingStartMs ?: continue
        pendingStartMs = null
        val (url, box, valid) = parseSeekCuePayload(line, vttBase)
        if (!valid) continue
        cues.add(SeekPreviewCue(startMs = startMs, imageUrl = url, x = box[0], y = box[1], w = box[2], h = box[3]))
    }
    cues.sortBy { it.startMs }
    return cues
}
