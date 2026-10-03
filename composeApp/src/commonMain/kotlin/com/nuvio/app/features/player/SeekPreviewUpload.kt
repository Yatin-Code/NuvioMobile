package com.nuvio.app.features.player

import com.nuvio.app.features.addons.httpRequestRaw
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.buildJsonObject
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
 * Reuses the shared HTTP plumbing ([httpRequestRaw], the same client family
 * backing [SeekPreviewRepository]) so behavior matches on Android (OkHttp)
 * and iOS (Darwin). Silent failure: returns false on any error, never throws
 * (cancellation excepted), never interrupts playback.
 */

const val SEEK_PREVIEW_CONTRIBUTE_PATH = "/v1/contribute"
const val SEEK_PREVIEW_CONTRIBUTE_UPLOADER = "nuvio-mobile"

/** Refuse to build absurd payloads (a 4h title is ~160 sheets, well below this). */
private const val MAX_CONTRIBUTE_BODY_BYTES = 64 * 1024 * 1024

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
     * Uploads one title's capture. [onProgress] reports (done, totalFiles)
     * during body assembly plus a final (total, total) on response.
     * Returns true on HTTP 2xx, false otherwise — never throws
     * (cancellation excepted).
     */
    suspend fun upload(
        query: SeekPreviewQuery,
        title: String,
        durationMs: Long,
        vttText: String,
        sheets: List<SeekPreviewSheetFile>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Boolean {
        return try {
            if (sheets.isEmpty() || vttText.isBlank() || durationMs <= 0L) return false
            val safeProgress: (Int, Int) -> Unit = { done, total ->
                runCatching { onProgress(done, total) }
            }
            val metaJson = buildContributeMetaJson(query, title, durationMs)
            val boundary = newContributeBoundary()
            val body = buildContributeMultipartBody(metaJson, vttText, sheets, boundary, safeProgress)
            if (body.size > MAX_CONTRIBUTE_BODY_BYTES) return false
            val response = httpRequestRaw(
                method = "POST",
                url = SEEK_PREVIEW_REGISTRY_BASE + SEEK_PREVIEW_CONTRIBUTE_PATH,
                headers = mapOf(
                    "Accept" to "application/json",
                    "Content-Type" to "multipart/form-data; boundary=$boundary",
                ),
                body = "",
                bodyBytes = body,
            )
            safeProgress(2 + sheets.size, 2 + sheets.size)
            response.status in 200..299
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }
}
