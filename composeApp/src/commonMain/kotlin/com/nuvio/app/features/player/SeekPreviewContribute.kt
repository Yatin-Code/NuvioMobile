package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/**
 * Contributor orchestration (commonMain).
 *
 * Runs only when ALL of these hold:
 *  - seek previews are enabled ([PlayerSettingsUiState.seekPreviewEnabled]),
 *  - the contribute toggle is on ([PlayerSettingsUiState.seekPreviewContributeEnabled],
 *    default OFF — uploading costs data),
 *  - the registry lookup missed ([SeekPreviewRepository.loadTrack] returned
 *    null; 404 vs other errors is indistinguishable through the repository's
 *    silent-null contract, so any miss is treated as "no previews" — grabs
 *    fail silently offline anyway),
 *  - the platform power gate allows it ([SeekPreviewFrameCapture.captureAllowed],
 *    best-effort wifi/charging; fail-open with the 5s throttle).
 *
 * Captures at most 1 frame per 5s during active playback, resumes from
 * already-persisted tiles, and auto-uploads on title completion or once
 * [SEEK_PREVIEW_CONTRIBUTE_TILE_THRESHOLD] tiles are banked. Silent on every
 * error; never interrupts playback.
 */
object SeekPreviewContribute {
    private val lock = Any()
    private val uploadedHashes = HashSet<String>()

    fun isUploaded(query: SeekPreviewQuery): Boolean =
        synchronized(lock) { uploadedHashes.contains(seekPreviewTitleHash(query)) }

    fun markUploaded(query: SeekPreviewQuery) {
        synchronized(lock) { uploadedHashes.add(seekPreviewTitleHash(query)) }
    }
}

@Composable
internal fun PlayerScreenRuntime.BindSeekPreviewContributeEffects() {
    val contributeEnabled = playerSettingsUiState.seekPreviewContributeEnabled
    val previewEnabled = playerSettingsUiState.seekPreviewEnabled
    val playbackKey = activePlaybackKey
    LaunchedEffect(playbackKey, contributeEnabled, previewEnabled) {
        if (!previewEnabled || !contributeEnabled) return@LaunchedEffect
        runSeekPreviewContribute()
    }
}

private suspend fun PlayerScreenRuntime.runSeekPreviewContribute() {
    // Duration is unknown until the engine reports it; wait briefly.
    var durationMs = playbackSnapshot.durationMs
    var waits = 0
    while (durationMs <= 0L && waits < 45) {
        delay(2_000L)
        durationMs = playbackSnapshot.durationMs
        waits++
    }
    if (durationMs <= 0L) return

    val metaImdbId = (metaUiState.meta ?: playerMeta)
        ?.takeIf { it.id == parentMetaId }
        ?.imdbId
    val query = buildSeekPreviewQuery(
        parentMetaId = parentMetaId,
        contentType = contentType ?: parentMetaType,
        videoId = activeVideoId,
        seasonNumber = activeSeasonNumber,
        episodeNumber = activeEpisodeNumber,
        metaImdbId = metaImdbId,
        durationMs = durationMs,
    ) ?: return
    if (SeekPreviewContribute.isUploaded(query)) return

    // Registry-miss probe. Timeout-guarded; any error reads as a miss (the
    // capture loop below degrades to silent no-op when offline).
    val hasRemote: Boolean? = try {
        withTimeout(20_000L) { SeekPreviewRepository.loadTrack(query) != null }
    } catch (timeout: TimeoutCancellationException) {
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
    if (hasRemote == true) return

    val sourceUrl = activeSourceUrl
    if (sourceUrl.isBlank()) return
    val headers = activeSourceHeaders
    val titleHash = seekPreviewTitleHash(query)

    // Resume from already-persisted tiles (survives effect restarts).
    val captured = LinkedHashSet<Long>()
    try {
        captured.addAll(SeekPreviewFrameCapture.loadTiles(titleHash).keys)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
    }
    // Positions that failed this session: skip them so one bad timestamp
    // cannot pin the loop (each grab failure is already silent).
    val skipped = HashSet<Long>()

    var plan = captureTimestampsFor(durationMs, alreadyCaptured = captured + skipped)
    while (plan.isNotEmpty()) {
        if (errorMessage != null) return
        if (playbackSnapshot.isEnded) break
        if (!SeekPreviewFrameCapture.captureAllowed()) {
            delay(SEEK_PREVIEW_CAPTURE_POLICY_RECHECK_MS)
            continue
        }
        if (!playbackSnapshot.isPlaying || playbackSnapshot.isLoading) {
            delay(SEEK_PREVIEW_CAPTURE_IDLE_RECHECK_MS)
            continue
        }
        val timestampMs = plan.first()
        val jpeg: ByteArray? = try {
            SeekPreviewFrameCapture.grabFrame(sourceUrl, headers, timestampMs)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (jpeg != null && jpeg.isNotEmpty()) {
            val saved: Boolean = try {
                SeekPreviewFrameCapture.saveTile(titleHash, timestampMs, jpeg)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (saved) captured.add(timestampMs) else skipped.add(timestampMs)
        } else {
            skipped.add(timestampMs)
        }
        if (captured.size >= SEEK_PREVIEW_CONTRIBUTE_TILE_THRESHOLD) break
        plan = captureTimestampsFor(durationMs, alreadyCaptured = captured + skipped)
        delay(SEEK_PREVIEW_CAPTURE_GRAB_THROTTLE_MS)
    }

    if (!playbackSnapshot.isEnded && captured.size < SEEK_PREVIEW_CONTRIBUTE_TILE_THRESHOLD) {
        // Partial capture and the title is still playing: the effect stays
        // alive until playback ends (or the title changes, which cancels it).
        while (!playbackSnapshot.isEnded) {
            if (errorMessage != null) return
            delay(10_000L)
        }
    }
    if (captured.size < SEEK_PREVIEW_CONTRIBUTE_MIN_TILES) return

    val tiles: Map<Long, ByteArray> = try {
        SeekPreviewFrameCapture.loadTiles(titleHash)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        emptyMap()
    }
    if (tiles.size < SEEK_PREVIEW_CONTRIBUTE_MIN_TILES) return
    val sheets: List<SeekPreviewSheetFile> = try {
        SeekPreviewFrameCapture.composeSheets(tiles)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        emptyList()
    }
    if (sheets.isEmpty()) return
    val vtt = buildCaptureVtt(tiles.keys, durationMs)
    val uploaded = SeekPreviewUpload.upload(
        query = query,
        title = title,
        durationMs = durationMs,
        vttText = vtt,
        sheets = sheets,
    )
    if (uploaded) {
        SeekPreviewContribute.markUploaded(query)
        try {
            SeekPreviewFrameCapture.clearTitle(titleHash)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
        }
    }
}
