package com.nuvio.app.features.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.math.abs
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.milliseconds

/**
 * Contributor orchestration (commonMain).
 *
 * Runs only when ALL of these hold:
 *  - seek previews are enabled ([PlayerSettingsUiState.seekPreviewEnabled]),
 *  - the contribute toggle is on ([PlayerSettingsUiState.seekPreviewContributeEnabled],
 *    default OFF — uploading costs data),
 *  - the served registry version leaves something to stripe (see below),
 *  - the platform power gate allows it ([SeekPreviewFrameCapture.captureAllowed],
 *    best-effort wifi/charging; fail-open with the 5s throttle).
 *
 * One registry read at start ([SeekPreviewRepository.loadTrack]) is both probe
 * and coverage plan: its `covered_until_ms` (plus the cue slots of a partial
 * `pending` version) becomes the plan anchor, so this device stripes the TAIL
 * instead of re-grabbing a head somebody else already has. A full miss still
 * plans from 0, exactly like the pre-bundle behavior, and a `complete` version
 * means there is nothing left to do.
 *
 * The loop is NOT terminal: it follows the coverage frontier at most 1 frame
 * per 5s during active playback, and every
 * [SEEK_PREVIEW_CONTRIBUTE_BUNDLE_TILES] banked tiles (48 slots ~ 8 minutes)
 * a bundle is composed, written as a positional VTT and POSTed to
 * /v1/contribute. The registry merges same-duration cues (first writer wins),
 * so after every bundle the track is re-read (self-unlock: this device can
 * scrub its own range) and capturing continues with the next chunk. Tiles are
 * deliberately left on disk after a successful upload (no clearTitle) — they
 * are the backlog a later session drains, and pruning is the platform's job.
 *
 * Silent on every error; never interrupts playback.
 */
object SeekPreviewContribute {
    /** Titles remembered across sessions (maps below); oldest evicted past this. */
    private const val MAX_TRACKED_TITLES = 16

    /** In-flight bundles; far below the cap in practice, but never unbounded. */
    private const val MAX_IN_FLIGHT_BUNDLES = 8

    private val lock = Any()

    /** Bundle keys with an upload in flight — the old uploadedHashes, repurposed. */
    private val inFlightBundles = LinkedHashMap<String, Boolean>()

    /** titleHash -> highest slot timestamp the registry is known to hold. */
    private val coveredThroughMs = LinkedHashMap<String, Long>()

    /** titleHash -> registry coverage end on the local timeline (fallback anchor). */
    private val coverageLedger = LinkedHashMap<String, Long>()

    /** Per-bundle in-flight guard: true when this caller owns the bundle. */
    fun beginBundle(bundleKey: String): Boolean = synchronized(lock) {
        pruneLocked(inFlightBundles, MAX_IN_FLIGHT_BUNDLES)
        if (inFlightBundles.containsKey(bundleKey)) return false
        inFlightBundles[bundleKey] = true
        true
    }

    fun endBundle(bundleKey: String) = synchronized(lock) { inFlightBundles.remove(bundleKey) }

    /** Highest slot timestamp believed covered for [titleHash]; -1 when unknown. */
    fun coveredThrough(titleHash: String): Long =
        synchronized(lock) { coveredThroughMs[titleHash] ?: -1L }

    fun noteCovered(titleHash: String, throughMs: Long) = synchronized(lock) {
        pruneLocked(coveredThroughMs, MAX_TRACKED_TITLES)
        if (throughMs > (coveredThroughMs[titleHash] ?: -1L)) coveredThroughMs[titleHash] = throughMs
    }

    /** Local-timeline coverage end remembered for [titleHash]; -1 when unknown. */
    fun coverageEnd(titleHash: String): Long =
        synchronized(lock) { coverageLedger[titleHash] ?: -1L }

    fun noteCoverageEnd(titleHash: String, coverageEndMs: Long) = synchronized(lock) {
        pruneLocked(coverageLedger, MAX_TRACKED_TITLES)
        if (coverageEndMs > (coverageLedger[titleHash] ?: -1L)) coverageLedger[titleHash] = coverageEndMs
    }

    /**
     * Drops the eldest entries past [cap]. All bookkeeping here is IN-MEMORY
     * only: a fresh process re-reads the registry and simply re-uploads once,
     * which the merge (first writer wins) makes idempotent.
     */
    private fun <K, V> pruneLocked(map: MutableMap<K, V>, cap: Int) {
        while (map.size > cap) {
            val eldest = map.keys.firstOrNull() ?: return
            map.remove(eldest)
        }
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

/**
 * One contribute session: the capture loop, the bundle flush, and the coverage
 * bookkeeping they share. Plain holder (no platform APIs) so the loop stays
 * readable; every field is either derived from disk or advanced by the loop.
 */
private class SeekPreviewContribution(
    val query: SeekPreviewQuery,
    val durationMs: Long,
    val title: String,
    val titleHash: String,
) {
    /** Grid timestamps persisted under the title bucket, in time order. */
    val banked: MutableSet<Long> = sortedSetOf()

    /** Slots the registry serves: never re-grabbed, never re-uploaded. */
    val coveredSlots: MutableSet<Long> = HashSet()

    /** Slots that failed this session, so one bad timestamp cannot pin the plan. */
    val skipped: MutableSet<Long> = HashSet()

    /**
     * Slots POSTed in this session that got a terminal registry answer
     * (promoted, merged, or refused). They stay banked on disk as backlog, but
     * are never re-sent: the registry already holds them or said no.
     */
    val attempted: MutableSet<Long> = HashSet()

    /** Highest banked timestamp the registry is known to hold (-1 = none yet). */
    var flushedThroughMs: Long = -1L

    /** Coverage end on the local timeline, from the registry or the local ledger. */
    var coverageEndMs: Long = -1L

    /**
     * Nothing left to stripe: the registry reports full coverage, or it has
     * refused too many bundles for this session to be worth continuing.
     */
    var done: Boolean = false

    /** Bundles POSTed so far (for the end-of-session log line). */
    var bundles: Int = 0

    /** Bundles the registry refused; caps a session that is going nowhere. */
    var refusals: Int = 0

    fun noteRefusal() {
        refusals++
    }

    private var grabs: Int = 0
    private var failures: Int = 0

    /** Everything the plan must not re-grab. */
    fun occupiedSlots(): Set<Long> {
        val out = HashSet<Long>(banked.size + skipped.size + coveredSlots.size)
        out.addAll(coveredSlots)
        out.addAll(banked)
        out.addAll(skipped)
        return out
    }

    /** Banked tiles still owed to the registry, in time order. */
    fun pendingTiles(): List<Long> =
        banked.filter { it > flushedThroughMs && it !in coveredSlots && it !in attempted }

    /** Highest banked slot, or -1 when nothing is banked yet. */
    fun highestBankedMs(): Long = banked.maxOrNull() ?: -1L

    /** Plan anchor: the coverage frontier floored to a grid slot. */
    fun anchorMs(): Long = slotStartFor(maxOf(coverageEndMs, flushedThroughMs + 1L))

    /**
     * Next grid slot to grab, playhead-following inside the plan window.
     *
     * The window is always the uncovered tail starting at the coverage
     * frontier (so nothing served is re-grabbed and no slot is skipped), and
     * within it the slot nearest the live playhead wins — a viewer who joined
     * mid-title and scrubbed forward stripes around where they are instead of
     * the head. Ties go to the earlier slot.
     *
     * Reordering only affects WHEN a slot is grabbed: [buildCaptureVtt] and
     * [SeekPreviewFrameCapture.composeSheets] both key off the absolute slot
     * index, so the emitted VTT stays time-ordered and the sheets stay stable.
     */
    fun nextTimestampMs(positionMs: Long = 0L): Long? {
        val window = captureTimestampsFor(
            durationMs = durationMs,
            alreadyCaptured = occupiedSlots(),
            limit = SEEK_PREVIEW_CONTRIBUTE_BUNDLE_TILES,
            startFromMs = anchorMs(),
        )
        if (window.isEmpty()) return null
        val pos = positionMs.coerceIn(0L, maxOf(durationMs, 0L))
        return window.minByOrNull { abs(it - pos) }
    }

    /**
     * Heartbeat: one line every [SEEK_PREVIEW_CONTRIBUTE_LOG_EVERY] grabs, so
     * the on-screen viewer shows progress without flooding its ring buffer.
     */
    fun noteGrabSaved(timestampMs: Long) {
        banked.add(timestampMs)
        grabs++
        if (grabs % SEEK_PREVIEW_CONTRIBUTE_LOG_EVERY != 0) return
        seekPreviewLog(
            "contrib grab#$grabs ts=$timestampMs banked=${banked.size} " +
                "pending=${pendingTiles().size} anchor=${anchorMs()} coverage=$coverageEndMs",
        )
    }

    /** First few failures verbatim, then every 20th: a broken host is one line. */
    fun noteGrabFailed(timestampMs: Long, why: String) {
        failures++
        if (failures > 3 && failures % 20 != 0) return
        seekPreviewLog("contrib grab failed #$failures ts=$timestampMs why=$why")
    }

    /**
     * Folds a registry answer into the session: the coverage frontier (which
     * anchors the plan) plus the exact covered slots of the version we just
     * read. Slots are marked from real cues, never assumed contiguous, so a
     * hole in a hand-merged version stays grabbable.
     */
    fun noteRegistryCoverage(track: SeekPreviewTrack?, coverageEndLocalMs: Long) {
        // Clamp to our own timeline so a bogus registry value cannot pin the
        // plan past the end of this title.
        val ceiling = if (durationMs > 0L) durationMs else coverageEndLocalMs.coerceAtLeast(0L)
        val end = coverageEndLocalMs.coerceIn(0L, ceiling)
        coverageEndMs = maxOf(coverageEndMs, end)
        flushedThroughMs = maxOf(flushedThroughMs, end - SEEK_PREVIEW_CAPTURE_INTERVAL_MS)
        if (track != null) {
            coveredSlots.addAll(track.coveredSlotTimestamps(durationMs))
            if (track.coversWholeSource) done = true
        }
        SeekPreviewContribute.noteCovered(titleHash, flushedThroughMs)
        SeekPreviewContribute.noteCoverageEnd(titleHash, end)
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

    // Probe + coverage anchor in one read. Any error reads as "no coverage"
    // (the capture loop below degrades to silent no-op when offline).
    val served = readSeekPreviewTrack(query, forceReload = false)
    if (served != null && served.coversWholeSource) {
        seekPreviewLog(
            "contrib skip: registry covers ${served.sourceDurationMs}ms " +
                "(status=${served.status} covered=${served.coveredUntilMs})",
        )
        return
    }

    val sourceUrl = activeSourceUrl
    if (sourceUrl.isBlank()) return
    val headers = activeSourceHeaders
    val contribution = SeekPreviewContribution(
        query = query,
        durationMs = durationMs,
        title = title,
        titleHash = seekPreviewTitleHash(query),
    )

    // Banked tiles from an earlier session are the upload backlog; which of them
    // are still owed is the in-memory watermark, never "already uploaded".
    try {
        contribution.banked.addAll(SeekPreviewFrameCapture.listTileTimestamps(contribution.titleHash))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
    }
    contribution.flushedThroughMs = SeekPreviewContribute.coveredThrough(contribution.titleHash)
    if (served != null) {
        // Covered slots (sparse-safe) plus the contiguous coverage frontier.
        contribution.coveredSlots.addAll(served.coveredSlotTimestamps(durationMs))
        contribution.noteRegistryCoverage(served, served.coverageAnchorMs(durationMs))
    } else {
        // Offline / registry unreadable: fall back to the local coverage map.
        contribution.coverageEndMs = maxOf(
            SeekPreviewContribute.coverageEnd(contribution.titleHash),
            contribution.coverageEndMs,
        )
    }
    seekPreviewLog(
        "contrib start anchor=${contribution.anchorMs()} coverage=${contribution.coverageEndMs} " +
            "status=${served?.status ?: "none"} covered=${served?.coveredUntilMs} " +
            "cues=${served?.cues?.size} banked=${contribution.banked.size} " +
            "flushedThrough=${contribution.flushedThroughMs} dur=$durationMs",
    )

    var backoffStartedAt: TimeSource.Monotonic.TimeMark? = null
    var loggedGate = false

    while (!playbackSnapshot.isEnded && errorMessage == null && !contribution.done) {
        val pending = contribution.pendingTiles()
        val backoffOver = backoffStartedAt == null ||
            TimeSource.Monotonic.markNow().elapsedNow() >=
                SEEK_PREVIEW_CONTRIBUTE_FLUSH_BACKOFF_MS.milliseconds
        if (pending.size >= SEEK_PREVIEW_CONTRIBUTE_BUNDLE_TILES && backoffOver) {
            // Bundle boundary: flush what is banked, then keep capturing the
            // next chunk from the coverage the registry now reports.
            val accepted = flushSeekPreviewBundle(contribution)
            backoffStartedAt = if (accepted) null else TimeSource.Monotonic.markNow()
            // Fragmented pending sets can stay above the floor after a partial
            // flush; never let that turn into a POST loop.
            delay(SEEK_PREVIEW_CAPTURE_GRAB_THROTTLE_MS)
            continue
        }
        val powerAllowed = SeekPreviewFrameCapture.captureAllowed()
        // The live playhead picks the slot inside the plan window, so a viewer
        // who joined mid-title stripes around where they are watching.
        val nextMs = contribution.nextTimestampMs(playbackSnapshot.positionMs)
        if (nextMs != null && powerAllowed && playbackSnapshot.isPlaying && !playbackSnapshot.isLoading) {
            if (loggedGate) {
                seekPreviewLog("contrib ungated: capturing resumes")
                loggedGate = false
            }
            grabSeekPreviewTile(contribution, nextMs, sourceUrl, headers)
            delay(SEEK_PREVIEW_CAPTURE_GRAB_THROTTLE_MS)
            continue
        }
        if (!powerAllowed) {
            if (!loggedGate) {
                seekPreviewLog("contrib gated: waiting for unmetered power")
                loggedGate = true
            }
            delay(SEEK_PREVIEW_CAPTURE_POLICY_RECHECK_MS)
        } else if (nextMs == null) {
            // Nothing left to stripe until playback ends or a bundle lands.
            delay(SEEK_PREVIEW_CAPTURE_POLICY_RECHECK_MS)
        } else {
            delay(SEEK_PREVIEW_CAPTURE_IDLE_RECHECK_MS)
        }
    }

    if (errorMessage != null) {
        seekPreviewLog("contrib stop: playback error; banked tiles stay for the next run")
        return
    }
    if (contribution.done) {
        seekPreviewLog(
            "contrib stop: ${contribution.bundles} bundles, ${contribution.refusals} refused, " +
                "nothing further to contribute",
        )
        return
    }
    val pending = contribution.pendingTiles()
    if (pending.isEmpty()) {
        seekPreviewLog("contrib end: nothing pending (bundles=${contribution.bundles})")
        return
    }
    if (backoffStartedAt != null) {
        seekPreviewLog("contrib end: flush still in backoff, ${pending.size} tiles stay on disk")
        return
    }
    if (pending.size < SEEK_PREVIEW_CONTRIBUTE_MIN_TILES) {
        seekPreviewLog(
            "contrib end: ${pending.size} pending < $SEEK_PREVIEW_CONTRIBUTE_MIN_TILES, " +
                "keeping them on disk",
        )
        return
    }
    // Playback finished: one last chunk so the title is striped up to the end.
    seekPreviewLog("contrib end: final bundle of ${pending.size} tiles")
    flushSeekPreviewBundle(contribution)
}

/** One grab step for [timestampMs]: pull the frame and bank it on disk. */
private suspend fun grabSeekPreviewTile(
    contribution: SeekPreviewContribution,
    timestampMs: Long,
    sourceUrl: String,
    headers: Map<String, String>,
) {
    val jpeg: ByteArray? = try {
        SeekPreviewFrameCapture.grabFrame(sourceUrl, headers, timestampMs)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
    if (jpeg == null || jpeg.isEmpty()) {
        contribution.skipped.add(timestampMs)
        contribution.noteGrabFailed(timestampMs, "no_frame")
        return
    }
    val saved: Boolean = try {
        SeekPreviewFrameCapture.saveTile(contribution.titleHash, timestampMs, jpeg)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
    if (!saved) {
        contribution.skipped.add(timestampMs)
        contribution.noteGrabFailed(timestampMs, "save_failed")
        return
    }
    contribution.noteGrabSaved(timestampMs)
}

/**
 * One bundle: compose the pending tiles into sheets, POST them, then fold the
 * registry's answer back in. Returns true when the registry accepted the
 * bundle (caller resets the flush backoff).
 */
private suspend fun flushSeekPreviewBundle(
    contribution: SeekPreviewContribution,
): Boolean {
    val pending = contribution.pendingTiles()
    if (pending.size < SEEK_PREVIEW_CONTRIBUTE_MIN_TILES) return false

    val bundleKey = "${contribution.titleHash}:${pending.first()}-${pending.last()}"
    if (!SeekPreviewContribute.beginBundle(bundleKey)) {
        seekPreviewLog("contrib bundle skipped: already in flight $bundleKey")
        return false
    }
    try {
        val tiles: Map<Long, ByteArray> = try {
            // Narrowed to the bundle: the bucket keeps every past tile, so an
            // unrestricted read would pull thousands of JPEGs off disk.
            SeekPreviewFrameCapture.loadTiles(contribution.titleHash, pending)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyMap()
        }
        // Contiguity is computed over what is actually READABLE, so a tile that
        // vanished from disk cannot punch a hole into the bundle: the registry
        // infers the interval from the median cue step and rejects a holey run.
        val readable = pending.filter { tiles[it]?.isNotEmpty() == true }
        val run = longestContiguousSlotRun(readable)
        if (run.size < SEEK_PREVIEW_CONTRIBUTE_MIN_TILES) {
            seekPreviewLog(
                "contrib bundle skipped: longest readable run is ${run.size} of ${pending.size} " +
                    "pending tiles (floor $SEEK_PREVIEW_CONTRIBUTE_MIN_TILES)",
            )
            return false
        }
        val bundleTiles = LinkedHashMap<Long, ByteArray>(run.size)
        for (timestampMs in run) {
            val jpeg = tiles[timestampMs]
            if (jpeg != null && jpeg.isNotEmpty()) bundleTiles[timestampMs] = jpeg
        }
        if (run.size != pending.size || bundleTiles.size != pending.size) {
            seekPreviewLog(
                "contrib bundle: ${bundleTiles.size} of ${pending.size} pending tiles are " +
                    "grid-adjacent and on disk; the rest wait for the next bundle",
            )
        }
        val sheets: List<SeekPreviewSheetFile> = try {
            SeekPreviewFrameCapture.composeSheets(bundleTiles)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
        if (sheets.isEmpty()) {
            seekPreviewLog("contrib bundle failed: composeSheets returned nothing")
            return false
        }
        val vtt = buildCaptureVtt(bundleTiles.keys, contribution.durationMs)
        contribution.bundles++
        seekPreviewLog(
            "contrib bundle #${contribution.bundles} tiles=${bundleTiles.size} " +
                "range=${bundleTiles.keys.first()}..${bundleTiles.keys.last()} sheets=${sheets.size} " +
                "vttBytes=${vtt.length}",
        )

        val result: SeekPreviewUploadResult = try {
            withTimeout(SEEK_PREVIEW_CONTRIBUTE_FLUSH_TIMEOUT_MS) {
                SeekPreviewUpload.upload(
                    query = contribution.query,
                    title = contribution.title,
                    durationMs = contribution.durationMs,
                    vttText = vtt,
                    sheets = sheets,
                )
            }
        } catch (timeout: TimeoutCancellationException) {
            seekPreviewLog("contrib bundle #${contribution.bundles} timed out")
            SeekPreviewUploadResult(ok = false, reason = "timeout")
        }
        seekPreviewLog(
            "contrib bundle #${contribution.bundles} posted http=${result.httpStatus} " +
                "state=${result.state} merged=${result.merged} added=${result.addedSlots} " +
                "kept=${result.keptSlots} version=${result.versionStatus} " +
                "covered=${result.coveredUntilMs} dup=${result.duplicate} reason=${result.reason}",
        )
        if (result.ok) {
            // The registry gave a terminal verdict: promoted, merged, or
            // refused with nothing to gain. Never re-send these tiles, just
            // move the plan on. A transport failure leaves them pending.
            contribution.attempted.addAll(bundleTiles.keys)
        }
        if (result.refused) {
            return handleRefusedBundle(contribution, result)
        }
        if (!result.accepted) return false

        // Self-unlock: re-read the registry (the upload merged our cues into
        // the version) so the scrub overlay can show what we just contributed.
        refreshAfterBundle(contribution, result)
        seekPreviewLog(
            "contrib bundle #${contribution.bundles} flushed through=${contribution.flushedThroughMs} " +
                "coverage=${contribution.coverageEndMs} pending=${contribution.pendingTiles().size} " +
                "done=${contribution.done}",
        )
        return true
    } finally {
        SeekPreviewContribute.endBundle(bundleKey)
    }
}

/**
 * Folds the registry's post-upload state into the session and republishes the
 * refreshed version for the overlay (self-unlock: this device can scrub the
 * range it just contributed). Falls back to the bundle's own reach when the
 * re-read misses but the upload was promoted, so the plan moves on instead of
 * re-sending. Silent on every failure.
 */
private suspend fun refreshAfterBundle(
    contribution: SeekPreviewContribution,
    result: SeekPreviewUploadResult,
) {
    val refreshed = readSeekPreviewTrack(contribution.query, forceReload = true)
    val coverageLocal = refreshed?.coveredUntilOnLocalTimeline()
    if (coverageLocal != null) {
        contribution.noteRegistryCoverage(refreshed, coverageLocal)
    } else if (result.promoted) {
        // Promoted but the re-read missed (offline right after): trust the
        // bundle's own reach so the plan moves on instead of re-uploading.
        contribution.noteRegistryCoverage(
            track = null,
            coverageEndLocalMs = contribution.highestBankedMs() + SEEK_PREVIEW_CAPTURE_INTERVAL_MS,
        )
    }
    if (refreshed != null) SeekPreviewTrackBus.publish(contribution.query, refreshed)
    seekPreviewLog(
        "contrib bundle #${contribution.bundles} refresh track=${refreshed != null} " +
            "cues=${refreshed?.cues?.size} trackCovered=${refreshed?.coveredUntilMs} " +
            "status=${refreshed?.status} coverage=${contribution.coverageEndMs} " +
            "flushedThrough=${contribution.flushedThroughMs}",
    )
}

/**
 * A refused bundle says something about CONTENT (nothing new to add, a
 * different grid, a different tile size, a file error), not about timing, so
 * backoff cannot help. Re-read anyway — a `duplicate` means the registry
 * already holds these slots, and its coverage tells the plan where to resume —
 * then stop after [SEEK_PREVIEW_CONTRIBUTE_MAX_REFUSALS], since the registry is
 * not going to start accepting content it just refused. Returns true so the
 * caller does not enter backoff.
 */
private suspend fun handleRefusedBundle(
    contribution: SeekPreviewContribution,
    result: SeekPreviewUploadResult,
): Boolean {
    contribution.noteRefusal()
    seekPreviewLog(
        "contrib bundle #${contribution.bundles} refused " +
            "(state=${result.state} dup=${result.duplicate} reason=${result.reason}); " +
            "refusals=${contribution.refusals}/$SEEK_PREVIEW_CONTRIBUTE_MAX_REFUSALS",
    )
    refreshAfterBundle(contribution, result)
    if (contribution.refusals >= SEEK_PREVIEW_CONTRIBUTE_MAX_REFUSALS) {
        contribution.done = true
        seekPreviewLog("contrib stop: registry keeps refusing; ${contribution.refusals} refusals")
    }
    return true
}

/** Timeout-guarded registry read; any failure is a silent null (never throws). */
private suspend fun readSeekPreviewTrack(query: SeekPreviewQuery, forceReload: Boolean): SeekPreviewTrack? {
    return try {
        withTimeout(SEEK_PREVIEW_CONTRIBUTE_REFRESH_TIMEOUT_MS) {
            SeekPreviewRepository.loadTrack(query, forceReload = forceReload)
        }
    } catch (timeout: TimeoutCancellationException) {
        seekPreviewLog("contrib lookup timeout url=${query.registryUrl}")
        null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        seekPreviewLog("contrib lookup failed url=${query.registryUrl} err=${error.message}")
        null
    }
}
