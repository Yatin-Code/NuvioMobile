package com.nuvio.app.features.player

import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.random.Random

/**
 * Contributor uploader (commonMain) against the registry:
 *
 *   POST {base}/v1/contribute  (multipart/form-data)
 *     meta:  metadata.json (application/json) — media_type, title,
 *            tmdb_id/imdb_id or show_tmdb_id/show_imdb_id + season + episode,
 *            duration_ms, interval_ms, uploader
 *     vtt:   thumbnails-capture.vtt (text/vtt, positional cues)
 *     sheet: sheet-c-N.jpg (image/jpeg), one part per sheet
 *
 * The registry closes the loop inline (verify + promote in the same request),
 * so one POST per bundle is all it takes and the response says whether these
 * cues are now servable — see [SeekPreviewUploadResult].
 *
 * Reuses the shared HTTP plumbing ([httpRequestRaw], the same client family
 * backing [SeekPreviewRepository]) so behavior matches on Android (OkHttp)
 * and iOS (Darwin). Silent failure: returns a not-ok result on any error,
 * never throws (cancellation excepted), never interrupts playback.
 */

const val SEEK_PREVIEW_CONTRIBUTE_PATH = "/v1/contribute"
const val SEEK_PREVIEW_CONTRIBUTE_UPLOADER = "nuvio-mobile"

/**
 * Registry contribution states. `/v1/contribute` runs verify + promote inline,
 * so a 2xx answer is normally [SEEK_PREVIEW_CONTRIBUTE_STATE_PROMOTED] — either
 * as a new version or merged into the same-duration one. Only
 * [SEEK_PREVIEW_CONTRIBUTE_STATE_REJECTED] means the payload was dropped.
 */
const val SEEK_PREVIEW_CONTRIBUTE_STATE_PROMOTED = "promoted"
const val SEEK_PREVIEW_CONTRIBUTE_STATE_REJECTED = "rejected"

/** Refuse to build absurd payloads (a 4h title is ~160 sheets, well below this). */
private const val MAX_CONTRIBUTE_BODY_BYTES = 64 * 1024 * 1024

private val contributeJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * Outcome of one bundle POST.
 *
 * `/v1/contribute` runs verify + promote inline, so [state] is the FINAL
 * state: `promoted` = these cues are servable now (a new version, or merged by
 * slot into the same-duration one — existing tiles win, so bytes a player has
 * cached never change under it), `rejected` = refused with nothing to gain
 * (`duplicate`: full overlap; or unmergeable: `interval_mismatch`,
 * `tile_size_mismatch`, `merge_failed:*`), `quarantine`/`verified` = stored,
 * not yet servable.
 *
 * [versionStatus]/[coveredUntilMs] describe the resulting version;
 * [merged]/[addedSlots]/[keptSlots] are set when a merge happened.
 */
data class SeekPreviewUploadResult(
    val ok: Boolean,
    val httpStatus: Int = 0,
    val state: String? = null,
    val versionStatus: String? = null,
    val coveredUntilMs: Long? = null,
    val contributionId: Long? = null,
    val merged: Boolean = false,
    val addedSlots: Int? = null,
    val keptSlots: Int? = null,
    val duplicate: Boolean = false,
    val reason: String? = null,
) {
    /**
     * The POST landed (2xx) and the registry did not refuse it. A body with no
     * `state` at all still counts: the post-upload registry re-read, not the
     * response shape, decides how far coverage actually moved.
     */
    val accepted: Boolean get() = ok && state != SEEK_PREVIEW_CONTRIBUTE_STATE_REJECTED

    /** These cues are servable now. */
    val promoted: Boolean get() = ok && state == SEEK_PREVIEW_CONTRIBUTE_STATE_PROMOTED

    /**
     * The registry gave up on these slots for good (nothing new to add, or a
     * conflict). Re-sending the same bundle cannot change the answer, so the
     * session stops instead of retrying on every backoff window.
     */
    val refused: Boolean
        get() = ok && (state == SEEK_PREVIEW_CONTRIBUTE_STATE_REJECTED || duplicate)
}

/** Parses the registry's inline verify+promote response; tolerant of any shape. */
fun parseContributeResponse(body: String, httpStatus: Int): SeekPreviewUploadResult {
    val ok = httpStatus in 200..299
    return try {
        val obj = contributeJson.parseToJsonElement(body).jsonObject
        SeekPreviewUploadResult(
            ok = ok,
            httpStatus = httpStatus,
            state = obj["state"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
                ?.takeIf { it.isNotEmpty() },
            versionStatus = obj["status"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
                ?.takeIf { it.isNotEmpty() },
            coveredUntilMs = obj["covered_until_ms"]?.jsonPrimitive?.longOrNull,
            contributionId = obj["contribution_id"]?.jsonPrimitive?.longOrNull,
            merged = obj["merged"]?.jsonPrimitive?.booleanOrNull == true,
            addedSlots = obj["added_slots"]?.jsonPrimitive?.intOrNull,
            keptSlots = obj["kept_slots"]?.jsonPrimitive?.intOrNull,
            duplicate = obj["duplicate"]?.jsonPrimitive?.booleanOrNull == true,
            // Verification failures report `verify_reason`, a refused merge
            // `reason`; a non-2xx body is usually just {"error": ...}.
            reason = obj["reason"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: obj["verify_reason"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: obj["conflict"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: obj["error"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: if (ok) null else "http_$httpStatus",
        )
    } catch (_: Exception) {
        SeekPreviewUploadResult(ok = ok, httpStatus = httpStatus, reason = "unparsable_body")
    }
}

fun buildContributeMetaJson(
    query: SeekPreviewQuery,
    title: String,
    durationMs: Long,
    uploader: String = SEEK_PREVIEW_CONTRIBUTE_UPLOADER,
): String =
    buildJsonObject {
        when (query) {
            is MovieSeekPreviewQuery -> {
                put("media_type", "movie")
                query.tmdbId?.let { put("tmdb_id", it) }
                query.imdbId?.let { put("imdb_id", it) }
            }
            is EpisodeSeekPreviewQuery -> {
                put("media_type", "episode")
                query.showTmdbId?.let { put("show_tmdb_id", it) }
                query.showImdbId?.let { put("show_imdb_id", it) }
                put("season", query.season)
                put("episode", query.episode)
            }
        }
        put("title", title.ifBlank { "Unknown" })
        put("duration_ms", durationMs)
        put("interval_ms", SEEK_PREVIEW_CAPTURE_INTERVAL_MS)
        put("uploader", uploader.ifBlank { SEEK_PREVIEW_CONTRIBUTE_UPLOADER })
    }.toString()

fun newContributeBoundary(): String =
    buildString {
        append("nuvio-seek-")
        append(kotlin.math.abs(Random.nextLong()).toString(radix = 16))
        append('-')
        repeat(8) { append("0123456789abcdef"[Random.nextInt(16)]) }
    }

/**
 * Pure multipart body builder (byte-exact, CRLF). [onFileAppended] fires
 * per attached file (meta, vtt, then each sheet) so callers get per-file
 * progress; the actual transport is a single POST.
 */
fun buildContributeMultipartBody(
    metaJson: String,
    vttText: String,
    sheets: List<SeekPreviewSheetFile>,
    boundary: String,
    onFileAppended: (done: Int, total: Int) -> Unit = { _, _ -> },
): ByteArray {
    val total = 2 + sheets.size
    var done = 0
    val chunks = ArrayList<ByteArray>(total * 2 + 1)
    fun partHeader(name: String, fileName: String, contentType: String) {
        chunks.add(
            "--$boundary\r\nContent-Disposition: form-data; name=\"$name\"; filename=\"$fileName\"\r\nContent-Type: $contentType\r\n\r\n"
                .encodeToByteArray(),
        )
    }
    partHeader("meta", "metadata.json", "application/json")
    chunks.add(metaJson.encodeToByteArray())
    chunks.add("\r\n".encodeToByteArray())
    onFileAppended(++done, total)
    partHeader("vtt", SEEK_PREVIEW_CAPTURE_VTT_NAME, "text/vtt")
    chunks.add(vttText.encodeToByteArray())
    chunks.add("\r\n".encodeToByteArray())
    onFileAppended(++done, total)
    for (sheet in sheets) {
        partHeader("sheet", sheet.fileName, "image/jpeg")
        chunks.add(sheet.jpeg)
        chunks.add("\r\n".encodeToByteArray())
        onFileAppended(++done, total)
    }
    chunks.add("--$boundary--\r\n".encodeToByteArray())
    var size = 0
    for (chunk in chunks) size += chunk.size
    val out = ByteArray(size)
    var pos = 0
    for (chunk in chunks) {
        chunk.copyInto(out, pos)
        pos += chunk.size
    }
    return out
}

object SeekPreviewUpload {
    /**
     * Uploads one bundle of captured tiles. [onProgress] reports
     * (done, totalFiles) during body assembly plus a final (total, total) on
     * response. Returns the registry's verdict; a not-ok result on any error
     * instead of a throw (cancellation excepted).
     */
    suspend fun upload(
        query: SeekPreviewQuery,
        title: String,
        durationMs: Long,
        vttText: String,
        sheets: List<SeekPreviewSheetFile>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): SeekPreviewUploadResult {
        return try {
            if (sheets.isEmpty() || vttText.isBlank() || durationMs <= 0L) {
                return SeekPreviewUploadResult(ok = false, reason = "empty_payload")
            }
            val safeProgress: (Int, Int) -> Unit = { done, total ->
                runCatching { onProgress(done, total) }
            }
            val metaJson = buildContributeMetaJson(query, title, durationMs)
            val boundary = newContributeBoundary()
            val body = buildContributeMultipartBody(metaJson, vttText, sheets, boundary, safeProgress)
            if (body.size > MAX_CONTRIBUTE_BODY_BYTES) {
                return SeekPreviewUploadResult(ok = false, reason = "payload_too_large")
            }
            val response = httpRequestRaw(
                method = "POST",
                url = SEEK_PREVIEW_REGISTRY_BASE + SEEK_PREVIEW_CONTRIBUTE_PATH,
                headers = mapOf(
                    "Accept" to "application/json",
                    "Content-Type" to "multipart/form-data; boundary=$boundary",
                    // Registry contributions are keyed; blank falls back to
                    // the bootstrap key so test builds upload out of the box.
                    "X-API-Key" to SeekPreviewRepository.currentSeekPreviewApiKey(),
                ),
                body = "",
                bodyBytes = body,
            )
            safeProgress(2 + sheets.size, 2 + sheets.size)
            parseContributeResponse(response.body, response.status)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            SeekPreviewUploadResult(ok = false, reason = "error:${error.message}")
        }
    }
}
