package com.nuvio.app.features.player

/**
 * Scrub-seek preview models for the mobile player.
 *
 * Served by OUR registry (seekr-wire-compatible), NOT generated on-device:
 *   GET {base}/v1/sprites?tmdb_id|imdb_id|show_tmdb_id|show_imdb_id[+season+episode]&duration_ms
 *     -> {vtt_url (absolute), version, source_duration_ms, scale, ids...}
 *     unknown title -> 404 {"error":...} (callers treat as silent miss)
 *
 * VTT cues map time ranges to absolute sheet URLs + `#xywh=x,y,w,h`
 * (same wire format as seekr VTT / spritegen output).
 */

/** Registry base. Hardcoded for testing only — single const, see [SEEK_PREVIEW_REGISTRY_BASE]. */
const val SEEK_PREVIEW_REGISTRY_BASE = "http://20.244.18.205:8080"

data class SeekPreviewCue(
    val startMs: Long,
    val imageUrl: String,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
)

data class SeekPreviewTrack(
    val vttUrl: String,
    val sourceDurationMs: Long,
    val scale: Double = 1.0,
    val cues: List<SeekPreviewCue> = emptyList(),
)

/**
 * Floor semantics (port of sdk/kotlin Peek.kt `thumbnailFor`):
 * last cue at or before [positionMs], corrected by the registry scale
 * (scale = playing duration / source duration). Null = no preview, hide thumb.
 */
fun SeekPreviewTrack.thumbnailFor(positionMs: Long): SeekPreviewCue? {
    val pos = if (scale > 0.0) (positionMs / scale).toLong() else positionMs
    var hit: SeekPreviewCue? = null
    for (c in cues) {
        if (c.startMs <= pos) hit = c else break
    }
    return hit
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
        co.touchlab.kermit.Logger.withTag("SeekPreview")
            .d { "query null: badDuration parent=$parentMetaId video=$videoId type=$contentType" }
        return null
    }
    val cleanMetaImdb = metaImdbId?.takeIf { it.startsWith("tt") && it.length > 2 }
    if (contentType.equals("movie", ignoreCase = true)) {
        val tmdbId = extractSeekTmdbId(parentMetaId) ?: extractSeekTmdbId(videoId)
        val imdbId = extractSeekImdbId(parentMetaId)
            ?: extractSeekImdbId(videoId)
            ?: cleanMetaImdb
        if (tmdbId == null && imdbId == null) {
            co.touchlab.kermit.Logger.withTag("SeekPreview")
                .d { "query null: noIds parent=$parentMetaId video=$videoId metaImdb=$metaImdbId" }
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
