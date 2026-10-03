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
 * [SeekPreviewTrack] with floor lookup + coverage bound. Sheet bytes + tile
 * cropping live in the platform [SeekPreviewSheetTiles] actuals.
 *
 * SILENT null on 404 / any error: seeking always works, just without preview.
 * [loadTrack] caches per URL; contributors pass `forceReload = true` to pick
 * up their own freshly promoted (merged) version.
 */
object SeekPreviewRepository {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private const val MAX_CACHED_TRACKS = 16

    private val lock = Any()
    private val trackCache = HashMap<String, SeekPreviewTrack?>()

    suspend fun loadTrack(query: SeekPreviewQuery, forceReload: Boolean = false): SeekPreviewTrack? {
        val url = query.registryUrl
        seekPreviewLog("loadTrack url=$url forceReload=$forceReload")
        if (!forceReload) {
            synchronized(lock) {
                if (trackCache.containsKey(url)) {
                    seekPreviewLog("loadTrack cache hit url=$url track=${trackCache[url] != null}")
                    return trackCache[url]
                }
            }
        }
        val track = try {
            fetchTrack(query)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            val msg = "loadTrack fetch failed url=$url err=${e.message}"
            SeekPreviewDebugLogs.noteFetch(msg)
            seekPreviewLog(msg)
            null
        }
        val doneMsg = "loadTrack result url=$url track=${track != null} cues=${track?.cues?.size} " +
            "status=${track?.status} covered=${track?.coveredUntilMs}"
        SeekPreviewDebugLogs.noteFetch(doneMsg)
        seekPreviewLog(doneMsg)
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
        val manifest: String = try {
            httpGetText(query.registryUrl)
        } catch (e: Exception) {
            seekPreviewLog("fetchTrack manifest GET failed url=${query.registryUrl} err=${e.message}")
            return null
        }
        val entry = parseRegistryManifest(manifest)
        if (entry == null) {
            seekPreviewLog("fetchTrack manifest parse failed url=${query.registryUrl} body=${manifest.take(200)}")
            return null
        }
        val vttText: String = try {
            httpGetText(entry.vttUrl)
        } catch (e: Exception) {
            seekPreviewLog("fetchTrack VTT GET failed vtt=${entry.vttUrl} err=${e.message}")
            return null
        }
        val cues = parseSeekPreviewVtt(vttText, entry.vttUrl)
        seekPreviewLog(
            "fetchTrack vtt=${entry.vttUrl} cues=${cues.size} srcDur=${entry.sourceDurationMs} " +
                "scale=${entry.scale} status=${entry.status} covered=${entry.coveredUntilMs}",
        )
        if (cues.isEmpty()) return null
        return SeekPreviewTrack(
            vttUrl = entry.vttUrl,
            sourceDurationMs = entry.sourceDurationMs,
            scale = entry.scale,
            cues = cues,
            status = entry.status,
            coveredUntilMs = entry.coveredUntilMs,
        )
    }

    internal data class RegistryEntry(
        val vttUrl: String,
        val sourceDurationMs: Long,
        val scale: Double,
        /** `pending` (partial, servable up to coveredUntilMs) or `complete`. */
        val status: String,
        /** Source-time coverage end; null when the registry did not report one. */
        val coveredUntilMs: Long?,
    )

    internal fun parseRegistryManifest(text: String): RegistryEntry? {
        return try {
            val obj = json.parseToJsonElement(text).jsonObject
            val vttUrl = obj["vtt_url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return null
            val sourceDurationMs = obj["source_duration_ms"]?.jsonPrimitive?.longOrNull
                ?: return null
            val scale = obj["scale"]?.jsonPrimitive?.doubleOrNull ?: 1.0
            // Coverage fields are additive: a registry that predates them omits
            // both, which reads as a fully covered (complete) version.
            val status = obj["status"]?.jsonPrimitive?.contentOrNull
                ?.trim()
                ?.lowercase()
                ?.takeIf { it == SEEK_PREVIEW_VERSION_PENDING }
                ?: SEEK_PREVIEW_VERSION_COMPLETE
            val coveredUntilMs = obj["covered_until_ms"]?.jsonPrimitive?.longOrNull
            RegistryEntry(
                vttUrl = vttUrl,
                sourceDurationMs = sourceDurationMs,
                scale = scale,
                status = status,
                coveredUntilMs = coveredUntilMs,
            )
        } catch (_: Exception) {
            null
        }
    }
}
