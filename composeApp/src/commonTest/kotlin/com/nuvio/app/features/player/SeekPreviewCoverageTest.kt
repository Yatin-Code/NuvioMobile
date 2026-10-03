package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coverage-aware planning (BUNDLES_TODO items 5 + 7).
 *
 * The registry serves partial `pending` versions, so both the contributor plan
 * and the overlay lookup have to reason about `covered_until_ms`: plan from
 * there, never re-grab a served slot, and stay silent past coverage.
 */
class SeekPreviewCoverageTest {
    private fun track(
        cueStartsMs: List<Long>,
        status: String = SEEK_PREVIEW_VERSION_PENDING,
        coveredUntilMs: Long? = null,
        sourceDurationMs: Long = 3_600_000L,
        scale: Double = 1.0,
    ) = SeekPreviewTrack(
        vttUrl = "http://registry/vtt",
        sourceDurationMs = sourceDurationMs,
        scale = scale,
        cues = cueStartsMs.map { SeekPreviewCue(it, "http://registry/sheet.jpg", 0, 0, 320, 180) },
        status = status,
        coveredUntilMs = coveredUntilMs,
    )

    @Test
    fun thumbnailForIsSilentPastCoverage() {
        val partial = track(listOf(0L, 10_000L, 20_000L), coveredUntilMs = 30_000L)
        // Inside coverage: floor lookup still returns the last cue.
        assertEquals(20_000L, partial.thumbnailFor(25_000L)?.startMs)
        // One ms past the last cue end: null, never a stale tail tile.
        assertNull(partial.thumbnailFor(30_001L))
    }

    @Test
    fun thumbnailForKeepsLegacyBehaviorWithoutCoverage() {
        val legacy = track(listOf(0L, 10_000L), coveredUntilMs = null)
        // A registry predating covered_until_ms must not silence anything.
        assertEquals(10_000L, legacy.thumbnailFor(3_600_000L)?.startMs)
    }

    @Test
    fun coverageBoundIsComparedInSourceTime() {
        // scale = 2: local 60_000 is source 30_000, the coverage end.
        val scaled = track(listOf(0L, 10_000L, 20_000L), coveredUntilMs = 30_000L, scale = 2.0)
        assertEquals(20_000L, scaled.thumbnailFor(59_000L)?.startMs)
        assertNull(scaled.thumbnailFor(61_000L))
    }

    @Test
    fun coverageAnchorPlansFromCoverageEndNotFromZero() {
        val partial = track(listOf(0L, 10_000L, 20_000L), coveredUntilMs = 30_000L)
        assertEquals(30_000L, partial.coverageAnchorMs(60_000L))
        assertEquals(0L, track(listOf(0L)).coverageAnchorMs(60_000L))
    }

    @Test
    fun planFromAnchorSkipsServedSlots() {
        val covered = setOf(0L, 10_000L, 20_000L)
        val plan = captureTimestampsFor(
            durationMs = 60_000L,
            alreadyCaptured = covered,
            startFromMs = 30_000L,
            limit = 2,
        )
        assertEquals(listOf(30_000L, 40_000L), plan)
    }

    @Test
    fun planStaysInTimeOrderFromAnchor() {
        val plan = captureTimestampsFor(durationMs = 60_000L, startFromMs = 30_000L, limit = 3)
        assertEquals(listOf(30_000L, 40_000L, 50_000L), plan)
    }

    @Test
    fun planWithoutServerVersionStartsAtZero() {
        val plan = captureTimestampsFor(durationMs = 30_000L, limit = 2)
        assertEquals(listOf(0L, 10_000L), plan)
    }

    @Test
    fun coveredSlotsComeFromRealCuesNotAssumedContiguity() {
        val sparse = track(listOf(0L, 40_000L))
        assertEquals(setOf(0L, 40_000L), sparse.coveredSlotTimestamps(60_000L))
    }

    @Test
    fun completeVersionNeedsNoContribution() {
        assertTrue(track(listOf(0L, 10_000L), status = SEEK_PREVIEW_VERSION_COMPLETE).coversWholeSource)
        assertTrue(track(emptyList(), coveredUntilMs = 3_600_000L).coversWholeSource)
        assertTrue(!track(listOf(0L), coveredUntilMs = 30_000L).coversWholeSource)
    }

    @Test
    fun bundleSendsTheLongestGridAdjacentRun() {
        // The registry infers the interval from the median cue step and
        // rejects anything outside {5,10,30}s, so a holey bundle would be
        // refused; grab failures leave holes, so only the clean run ships.
        val withHole = listOf(0L, 10_000L, 20_000L, 50_000L, 60_000L, 70_000L)
        assertEquals(
            listOf(50_000L, 60_000L, 70_000L),
            longestContiguousSlotRun(withHole),
        )
        assertEquals(listOf(0L, 10_000L, 20_000L), longestContiguousSlotRun(listOf(20_000L, 0L, 10_000L)))
        assertEquals(emptyList(), longestContiguousSlotRun(emptyList()))
        assertEquals(listOf(0L, 10_000L), longestContiguousSlotRun(listOf(-1L, 0L, 10_000L)))
    }

    @Test
    fun uploadResponseDrivesTheBundleOutcome() {
        // /v1/contribute runs verify+promote inline, so the response is the
        // verdict: merged progress, a full-overlap duplicate, and a conflict.
        val merged = parseContributeResponse(
            """{"contribution_id":7,"state":"promoted","merged":true,"added_slots":48,""" +
                """"kept_slots":96,"status":"pending","covered_until_ms":960000}""",
            200,
        )
        assertTrue(merged.accepted)
        assertTrue(merged.promoted)
        assertTrue(merged.merged)
        assertEquals(48, merged.addedSlots)
        assertEquals(96, merged.keptSlots)
        assertEquals(960_000L, merged.coveredUntilMs)
        assertTrue(!merged.refused)

        val dupe = parseContributeResponse("""{"state":"rejected","duplicate":true}""", 200)
        assertTrue(!dupe.accepted)
        assertTrue(dupe.refused)

        val conflict = parseContributeResponse(
            """{"state":"rejected","conflict":"interval_mismatch"}""",
            200,
        )
        assertTrue(conflict.refused)
        assertEquals("interval_mismatch", conflict.reason)

        // A transport failure must NOT be terminal: those tiles stay pending.
        val failed = parseContributeResponse("""{"error":"store failed"}""", 500)
        assertTrue(!failed.ok)
        assertTrue(!failed.refused)
        assertTrue(!failed.accepted)
    }

    @Test
    fun capturedBundleVttIsTimeOrderedAndPositional() {
        // Playhead-following grab order (nearest-to-playhead first) must not
        // leak into the wire format: the VTT is time-ordered and absolute.
        val vtt = buildCaptureVtt(listOf(40_000L, 0L, 20_000L, 10_000L), durationMs = 60_000L)
        val starts = vtt.lineSequence()
            .filter { it.contains("-->") }
            .map { parseSeekVttTimestamp(it.substringBefore("-->")) }
            .toList()
        assertEquals(listOf(0L, 10_000L, 20_000L, 40_000L), starts)
        // Absolute slot math (slot 4 -> sheet 0, cell 4), not bundle-relative.
        assertTrue(vtt.contains("sheet-c-0.jpg#xywh=1280,0,320,180"))
    }
}
