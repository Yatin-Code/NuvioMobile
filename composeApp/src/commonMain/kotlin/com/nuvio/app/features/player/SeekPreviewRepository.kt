package com.nuvio.app.features.player

import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
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

    /**
     * Stored registry API key, falling back to the bootstrap default when
     * nothing custom is saved. Read late (not cached) so a key saved in
     * Settings applies to the next lookup without an app restart.
     */
    fun currentSeekPreviewApiKey(): String {
        PlayerSettingsRepository.ensureLoaded()
        return PlayerSettingsRepository.uiState.value.seekPreviewApiKey
            .ifBlank { SEEK_PREVIEW_DEFAULT_API_KEY }
    }

    /**
     * Settings-dialog self-check: GET {base}/v1/keys/validate with the pasted
     * key. True only on 200 + {valid:true}. Silent false on any error — the
     * dialog (not playback) is the one place allowed to surface key errors.
     */
    suspend fun validateApiKey(apiKey: String): Boolean {
        val key = apiKey.trim()
        if (key.isEmpty()) return false
        return try {
            val response = httpRequestRaw(
                method = "GET",
                url = SEEK_PREVIEW_REGISTRY_BASE + SEEK_PREVIEW_KEYS_VALIDATE_PATH,
                headers = mapOf(
                    "Accept" to "application/json",
                    "X-API-Key" to key,
                ),
                body = "",
            )
            if (response.status != 200) return false
            try {
                json.parseToJsonElement(response.body).jsonObject["valid"]
                    ?.jsonPrimitive?.booleanOrNull == true
            } catch (_: Exception) {
                false
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun fetchTrack(query: SeekPreviewQuery): SeekPreviewTrack? {
        // Registry manifests are keyed (bootstrap default when nothing custom
        // is stored); VTT/sheet bytes stay keyless (CDN/blob or local /s/ —
        // the server keeps those routes open).
        val manifest: String = try {
            httpGetTextWithHeaders(
                query.registryUrl,
                mapOf("X-API-Key" to currentSeekPreviewApiKey()),
            )
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
