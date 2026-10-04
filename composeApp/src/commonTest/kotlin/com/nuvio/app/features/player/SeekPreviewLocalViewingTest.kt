package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Local-first viewing: strict precedence + exact-slot bucket lookup.
 *
 * The overlay shows, per scrub position: the registry cue wherever it serves
 * (coverage-bounded, as today), else this device's own banked tile for the
 * exact floored slot, else nothing. A sheet-fetch failure on a served cue
 * stays blank — the pure resolution below never consults the bucket there,
 * and the overlay's fetch branch has no fallback either.
 */
class SeekPreviewLocalViewingTest {
    private fun track(
        cueStartsMs: List<Long>,
        status: String = SEEK_PREVIEW_VERSION_PENDING,
        coveredUntilMs: Long? = null,
    ) = SeekPreviewTrack(
        vttUrl = "http://registry/vtt",
        sourceDurationMs = 3_600_000L,
        cues = cueStartsMs.map { SeekPreviewCue(it, "http://registry/sheet.jpg", 0, 0, 320, 180) },
        status = status,
        coveredUntilMs = coveredUntilMs,
    )

    @Test
    fun registryBeatsLocalOnServedSlots() {
        val served = track(listOf(0L, 10_000L), coveredUntilMs = 30_000L)
        val banked = setOf(0L, 10_000L, 20_000L)
        val source = resolveSeekPreviewSource(served, 5_000L, banked)
        assertIs<SeekPreviewTileSource.Registry>(source)
        assertEquals(0L, source.cue.startMs)
    }

    @Test
    fun silenceWhenNeitherServes() {
        // No registry track and an empty bucket: silent.
        assertIs<SeekPreviewTileSource.None>(
            resolveSeekPreviewSource(null, 5_000L, emptySet()),
        )
        // Registry silent past its coverage and nothing banked: still silent.
        val partial = track(listOf(0L, 10_000L), coveredUntilMs = 20_000L)
        assertIs<SeekPreviewTileSource.None>(
            resolveSeekPreviewSource(partial, 50_000L, emptySet()),
        )
    }

    @Test
    fun slotFlooringWithNoInterpolation() {
        val banked = setOf(0L, 10_000L, 20_000L)
        // Mid-slot positions floor to the slot holding them, never interpolate.
        assertEquals(20_000L, localTileSlotFor(25_000L, banked))
        assertEquals(10_000L, localTileSlotFor(10_001L, banked))
        assertEquals(0L, localTileSlotFor(9_999L, banked))
        val source = resolveSeekPreviewSource(null, 25_000L, banked)
        assertIs<SeekPreviewTileSource.Local>(source)
        assertEquals(20_000L, source.slotTimestampMs)
    }

    @Test
    fun noTailPastLastBanked() {
        val banked = setOf(0L, 10_000L)
        // Past the highest banked slot there is no tile to show, even though
        // floor lookup would otherwise repeat the last one.
        assertNull(localTileSlotFor(95_000L, banked))
        assertNull(localTileSlotFor(20_000L, banked))
        assertIs<SeekPreviewTileSource.None>(
            resolveSeekPreviewSource(null, 95_000L, banked),
        )
        // A hole in the banked set is silent too, not the neighbor's tile.
        assertNull(localTileSlotFor(35_000L, setOf(0L, 10_000L, 50_000L)))
    }

    @Test
    fun localServesWhereRegistryIsSilent() {
        // Partial registry version: served head stays registry, the banked
        // past-coverage tail shows the local tile.
        val partial = track(listOf(0L, 10_000L, 20_000L), coveredUntilMs = 30_000L)
        val banked = setOf(30_000L, 40_000L)
        assertIs<SeekPreviewTileSource.Registry>(
            resolveSeekPreviewSource(partial, 5_000L, banked),
        )
        val tail = resolveSeekPreviewSource(partial, 35_000L, banked)
        assertIs<SeekPreviewTileSource.Local>(tail)
        assertEquals(30_000L, tail.slotTimestampMs)
    }
}
