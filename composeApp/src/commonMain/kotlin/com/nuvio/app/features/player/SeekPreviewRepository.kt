package com.nuvio.app.features.player

import com.nuvio.app.features.addons.httpGetText
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Remote-registry seek-preview repository (commonMain).
 *
 * Flow per title: registry `/v1/sprites` manifest (JSON) -> VTT text ->
 * [SeekPreviewTrack] with floor lookup. Sheet bytes + tile cropping live in
 * the platform [SeekPreviewSheetTiles] actuals.
 *
 * SILENT null on 404 / any error: seeking always works, just without preview.
 */
object SeekPreviewRepository {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private const val MAX_CACHED_TRACKS = 16

    private val lock = Any()
    private val trackCache = HashMap<String, SeekPreviewTrack?>()

    suspend fun loadTrack(query: SeekPreviewQuery): SeekPreviewTrack? {
        val url = query.registryUrl
        synchronized(lock) {
            if (trackCache.containsKey(url)) return trackCache[url]
        }
        val track = try {
            fetchTrack(query)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        synchronized(lock) {
            if (trackCache.size >= MAX_CACHED_TRACKS) trackCache.clear()
            trackCache[url] = track
        }
        return track
    }

    fun clear() {
        synchronized(lock) { trackCache.clear() }
    }

    private suspend fun fetchTrack(query: SeekPreviewQuery): SeekPreviewTrack? {
        val manifest = httpGetText(query.registryUrl)
        val entry = parseRegistryManifest(manifest) ?: return null
        val vttText = httpGetText(entry.vttUrl)
        val cues = parseSeekPreviewVtt(vttText, entry.vttUrl)
        if (cues.isEmpty()) return null
        return SeekPreviewTrack(
            vttUrl = entry.vttUrl,
            sourceDurationMs = entry.sourceDurationMs,
            scale = entry.scale,
            cues = cues,
        )
    }

    internal data class RegistryEntry(
        val vttUrl: String,
        val sourceDurationMs: Long,
        val scale: Double,
    )

    internal fun parseRegistryManifest(text: String): RegistryEntry? {
        return try {
            val obj = json.parseToJsonElement(text).jsonObject
            val vttUrl = obj["vtt_url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return null
            val sourceDurationMs = obj["source_duration_ms"]?.jsonPrimitive?.longOrNull
                ?: return null
            val scale = obj["scale"]?.jsonPrimitive?.doubleOrNull ?: 1.0
            RegistryEntry(vttUrl = vttUrl, sourceDurationMs = sourceDurationMs, scale = scale)
        } catch (_: Exception) {
            null
        }
    }
}
